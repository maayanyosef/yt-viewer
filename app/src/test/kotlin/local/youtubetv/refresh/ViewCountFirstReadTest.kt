package local.youtubetv.refresh

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.Availability
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.testVideo
import local.youtubetv.walk.FakeYouTubeApi
import local.youtubetv.walk.normalVideo
import local.youtubetv.youtube.VideoDetails
import local.youtubetv.youtube.YouTubeApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIRST = "PLfirstSourceForTheViewCountFirstRead"
private const val SECOND = "PLsecondSourceForTheViewCountBackfil"
private const val NOT_WALKED = "PLaddedButNeverWalkedForTheFirstRead"
private val WALKED_AT: Instant = Instant.parse("2026-10-01T12:00:00Z")
private val NOW: Instant = Instant.parse("2026-10-03T18:00:00Z")

/**
 * The one-time view count first read of build ticket 59, against the network and storage interfaces.
 *
 * Every rule here runs on the JVM with no device and no unit of quota.
 */
class ViewCountFirstReadTest {

    private val log = mutableListOf<String>()

    private fun firstRead(store: InMemorySourceStore, api: FakeYouTubeApi) =
        ViewCountFirstRead(api = api, store = store, log = { log += it }, now = { NOW })

    /** Saves a walked Source holding [count] videos with no view count, as every row before version 11. */
    private fun InMemorySourceStore.walkedSource(sourceId: String, count: Int, walked: Boolean = true): List<StoredVideo> {
        saveSource(StoredSource(sourceId = sourceId, title = sourceId, lastWalkedAt = if (walked) WALKED_AT else null))
        val videos = (1..count).map { testVideo("$sourceId-$it", day = it, sourceId = sourceId) }
        replaceVideos(sourceId, videos)
        return videos
    }

    /** A fake that knows every video in [videos], each with a count of its own number times ten. */
    private fun apiFor(vararg videos: List<StoredVideo>): FakeYouTubeApi = FakeYouTubeApi(
        playlists = emptyMap(),
        videos = videos.toList().flatten().associate { video ->
            video.videoId to normalVideo(video.videoId, viewCount = video.videoId.substringAfterLast('-').toLong() * 10)
        },
    )

    @Test
    fun `it reads every stored video of every walked Source, fifty ids a unit`() {
        val store = InMemorySourceStore()
        val first = store.walkedSource(FIRST, 120)
        val second = store.walkedSource(SECOND, 3)
        val api = apiFor(first, second)

        val run = runBlocking { firstRead(store, api).runIfOwed() }

        // 120 videos are three calls, and 3 videos are one.
        assertEquals(4, api.videoCalls)
        assertEquals(listOf(50, 50, 20, 3), api.videoBatchSizes)
        assertEquals(4, run.unitsSpent)
        assertEquals(0, api.playlistItemCalls)
        assertTrue(run.ran)
        assertTrue(run.finished)
        assertEquals(listOf(FIRST, SECOND), run.writtenSourceIds)
        assertEquals(1_200L, store.allVideos(FIRST).single { it.videoId == "$FIRST-120" }.viewCount)
        assertTrue(store.allVideos(FIRST).all { it.viewCount != null })
        assertTrue(store.allVideos(SECOND).all { it.viewCount != null })
    }

    @Test
    fun `it runs once, and a second launch asks YouTube for nothing`() {
        val store = InMemorySourceStore()
        val first = store.walkedSource(FIRST, 3)
        runBlocking { firstRead(store, apiFor(first)).runIfOwed() }
        assertEquals(NOW.toString(), store.meta(META_VIEW_COUNTS_FIRST_READ))

        log.clear()
        val again = apiFor(first)
        val run = runBlocking { firstRead(store, again).runIfOwed() }

        assertFalse(run.ran)
        assertEquals(0, again.videoCalls)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `it writes only the counts, and no other field of any row moves`() {
        val store = InMemorySourceStore()
        val first = store.walkedSource(FIRST, 4).map { it.copy(newlyListed = it.videoId.endsWith("-4")) }
        store.replaceVideos(FIRST, first)
        val before = store.allVideos(FIRST)

        runBlocking { firstRead(store, apiFor(first)).runIfOwed() }

        assertEquals(before.map { it.copy(viewCount = null) }, store.allVideos(FIRST).map { it.copy(viewCount = null) })
    }

    @Test
    fun `a video with no count is stored as no count, and a video missing from the reply is left alone`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(sourceId = FIRST, title = FIRST, lastWalkedAt = WALKED_AT))
        store.replaceVideos(
            FIRST,
            listOf(
                testVideo("counted", day = 3, sourceId = FIRST),
                testVideo("premiere", day = 2, sourceId = FIRST, liveBroadcastContent = "upcoming"),
                testVideo("private", day = null, sourceId = FIRST, availability = Availability.UNAVAILABLE),
            ),
        )
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            videos = mapOf(
                "counted" to normalVideo("counted", viewCount = 116_945L),
                "premiere" to normalVideo("premiere", liveBroadcastContent = "upcoming", viewCount = null),
            ),
        )

        val run = runBlocking { firstRead(store, api).runIfOwed() }

        val counts = store.allVideos(FIRST).associate { it.videoId to it.viewCount }
        assertEquals(mapOf("counted" to 116_945L, "premiere" to null, "private" to null), counts)
        assertTrue(run.finished)
        assertTrue(log.toString(), log.contains(viewCountFirstReadSourceLine(FIRST, asked = 3, counted = 1)))
    }

    @Test
    fun `a Source that has never been walked is not read`() {
        val store = InMemorySourceStore()
        val first = store.walkedSource(FIRST, 2)
        store.walkedSource(NOT_WALKED, 2, walked = false)
        val api = apiFor(first)

        val run = runBlocking { firstRead(store, api).runIfOwed() }

        assertEquals(1, api.videoCalls)
        assertEquals(listOf(FIRST), run.writtenSourceIds)
        assertTrue(run.finished)
    }

    @Test
    fun `a quota refusal stops it with no flag, so the next launch starts again`() {
        val store = InMemorySourceStore()
        store.walkedSource(FIRST, 3)
        val api = object : FakeYouTubeApi(playlists = emptyMap()) {
            override suspend fun videos(videoIds: List<String>): List<VideoDetails> =
                throw YouTubeApiException(YouTubeApiException.Reason.QUOTA_EXCEEDED, "403 quotaExceeded")
        }

        val run = runBlocking { firstRead(store, api).runIfOwed() }

        assertTrue(run.stoppedByQuota)
        assertFalse(run.finished)
        assertNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
        assertTrue(firstRead(store, apiFor()).isOwed())
    }

    @Test
    fun `a lost network stops it with no flag`() {
        val store = InMemorySourceStore()
        store.walkedSource(FIRST, 3)
        val api = object : FakeYouTubeApi(playlists = emptyMap()) {
            override suspend fun videos(videoIds: List<String>): List<VideoDetails> =
                throw YouTubeApiException(YouTubeApiException.Reason.NETWORK, "no route")
        }

        val run = runBlocking { firstRead(store, api).runIfOwed() }

        assertTrue(run.offline)
        assertNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
    }

    @Test
    fun `any other failure skips that Source, reads the next one, and still finishes`() {
        // Found by `/code-review`: a failure that repeats would otherwise spend the whole read
        // again on every launch, for ever. The failed Source gets its counts from the next
        // weekly full walk.
        val store = InMemorySourceStore()
        val first = store.walkedSource(FIRST, 2)
        val second = store.walkedSource(SECOND, 2)
        val api = object : FakeYouTubeApi(
            playlists = emptyMap(),
            videos = (first + second).associate { it.videoId to normalVideo(it.videoId, viewCount = 1L) },
        ) {
            override suspend fun videos(videoIds: List<String>): List<VideoDetails> {
                if (videoIds.first().startsWith(FIRST)) {
                    throw YouTubeApiException(YouTubeApiException.Reason.HTTP, "500")
                }
                return super.videos(videoIds)
            }
        }
        val written = mutableListOf<String>()

        val run = runBlocking { firstRead(store, api).runIfOwed { written += it } }

        assertEquals(listOf(SECOND), written)
        assertTrue(run.finished)
        assertNotNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
        assertTrue(store.allVideos(SECOND).all { it.viewCount == 1L })
        assertTrue(store.allVideos(FIRST).all { it.viewCount == null })
        assertTrue(log.toString(), log.contains(viewCountFirstReadFinishedLine(1, 1, sourceFailed = true)))
    }

    @Test
    fun `a run stopped part way goes on from where it stopped, and pays nothing twice`() {
        val store = InMemorySourceStore()
        val first = store.walkedSource(FIRST, 60)
        val second = store.walkedSource(SECOND, 3)
        // The quota runs out on the second Source.
        val refusing = object : FakeYouTubeApi(
            playlists = emptyMap(),
            videos = first.associate { it.videoId to normalVideo(it.videoId, viewCount = 9L) },
        ) {
            override suspend fun videos(videoIds: List<String>): List<VideoDetails> {
                if (videoIds.first().startsWith(SECOND)) {
                    throw YouTubeApiException(YouTubeApiException.Reason.QUOTA_EXCEEDED, "403 quotaExceeded")
                }
                return super.videos(videoIds)
            }
        }
        val stopped = runBlocking { firstRead(store, refusing).runIfOwed() }
        assertTrue(stopped.stoppedByQuota)
        assertEquals(listOf(FIRST), stopped.writtenSourceIds)
        assertNull(store.meta(META_VIEW_COUNTS_FIRST_READ))

        val next = apiFor(first, second)
        val run = runBlocking { firstRead(store, next).runIfOwed() }

        assertEquals("only the Source not yet read is asked about", listOf(3), next.videoBatchSizes)
        assertEquals(listOf(SECOND), run.writtenSourceIds)
        assertTrue(run.finished)
        assertTrue(store.allVideos(FIRST).all { it.viewCount == 9L })
    }

    @Test
    fun `marking it done means it never asks YouTube`() {
        // What a weekly full walk that every Source came through does.
        val store = InMemorySourceStore()
        val first = store.walkedSource(FIRST, 3)
        val reader = firstRead(store, apiFor(first))
        reader.markDone()
        assertFalse(reader.isOwed())
        val api = apiFor(first)
        val run = runBlocking { firstRead(store, api).runIfOwed() }
        assertFalse(run.ran)
        assertEquals(0, api.videoCalls)
    }

    @Test
    fun `each Source is handed on as soon as its counts are written`() {
        val store = InMemorySourceStore()
        val first = store.walkedSource(FIRST, 1)
        val second = store.walkedSource(SECOND, 1)
        val seen = mutableListOf<Pair<String, Long?>>()
        runBlocking {
            firstRead(store, apiFor(first, second)).runIfOwed { sourceId ->
                // Already in storage when the screens hear about it.
                seen += sourceId to store.allVideos(sourceId).single().viewCount
            }
        }
        assertEquals(listOf(FIRST to 10L, SECOND to 10L), seen)
        assertNotNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
    }
}
