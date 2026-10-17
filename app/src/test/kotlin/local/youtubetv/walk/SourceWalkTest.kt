package local.youtubetv.walk

import java.time.Instant
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.Availability
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.isListed
import local.youtubetv.data.isUpcoming
import local.youtubetv.data.wasLive
import local.youtubetv.youtube.PlaylistItemRef
import local.youtubetv.youtube.VideoDetails
import local.youtubetv.youtube.YouTubeApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A channel Source: its uploads playlist, which has a `UUSH` companion. */
private const val UPLOADS = "UUAuUUnT6oDeKwE6v1NGQxug"
private const val UPLOADS_SHORTS = "UUSHAuUUnT6oDeKwE6v1NGQxug"

/** A curated playlist, which has no `UUSH` companion. */
private const val CURATED = "PLrAXtmRdnEQy6nuLMfO6uJx0w0vLgQ1dw"

private val BASE_DATE: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** Video `n` is published `n` days after [BASE_DATE], so a higher number is newer. */
private fun dayOf(n: Int): Instant = BASE_DATE.plusSeconds(n.toLong() * 86_400)

private fun ref(videoId: String, day: Int?): PlaylistItemRef =
    PlaylistItemRef(videoId, day?.let { dayOf(it) })

class SourceWalkTest {

    private fun walk(
        playlistId: String = UPLOADS,
        playlists: Map<String, List<PlaylistItemRef>>,
        videos: Map<String, VideoDetails> = emptyMap(),
        missingPlaylists: Set<String> = setOf(UPLOADS_SHORTS),
        failingPlaylists: Map<String, YouTubeApiException.Reason> = emptyMap(),
        onProgress: (Int, Int?) -> Unit = { _, _ -> },
    ): Pair<WalkResult, FakeYouTubeApi> {
        val api = FakeYouTubeApi(playlists, videos, missingPlaylists, failingPlaylists)
        val result = runBlocking { SourceWalk(api).walk(playlistId, onProgress) }
        return result to api
    }

    // --- Paging and ordering ------------------------------------------------------------

    @Test
    fun `walks every page of the playlist, not only the first`() {
        val refs = (1..137).map { ref("v$it", it) }
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
        )
        assertEquals(137, result.videos.size)
    }

    @Test
    fun `orders by videoPublishedAt, newest first, whatever order the playlist returned`() {
        // The playlist hands them back in its own order, which is the owner's choice.
        val refs = listOf(ref("middle", 5), ref("oldest", 1), ref("newest", 9))
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
        )
        assertEquals(listOf("newest", "middle", "oldest"), result.videos.map { it.videoId })
    }

    @Test
    fun `reports progress as pages arrive, so the screen can show a loading line`() {
        val refs = (1..120).map { ref("v$it", it) }
        val seen = mutableListOf<Pair<Int, Int?>>()
        walk(
            playlists = mapOf(UPLOADS to refs),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
            onProgress = { loaded, total -> seen += loaded to total },
        )
        assertEquals(listOf(50 to 120, 100 to 120, 120 to 120), seen)
    }

    // --- The call count -----------------------------------------------------------------

    @Test
    fun `a walk of N videos costs two calls per fifty, plus the UUSH pages`() {
        val n = 137
        val refs = (1..n).map { ref("v$it", it) }
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs, UPLOADS_SHORTS to listOf(ref("v7", 7))),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
            missingPlaylists = emptySet(),
        )
        val pagesOfN = ceil(n / 50.0).toInt()
        assertEquals(pagesOfN, result.apiCalls.playlistPages)
        assertEquals(pagesOfN, result.apiCalls.videoPages)
        assertEquals(1, result.apiCalls.shortsPages)
        assertEquals(2 * pagesOfN + 1, result.apiCalls.total)
    }

    @Test
    fun `a curated playlist costs exactly two calls per fifty and reads no UUSH`() {
        val n = 100
        val refs = (1..n).map { ref("v$it", it) }
        val (result, _) = walk(
            playlistId = CURATED,
            playlists = mapOf(CURATED to refs),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
            missingPlaylists = emptySet(),
        )
        assertEquals(0, result.apiCalls.shortsPages)
        assertEquals(2 * (n / 50), result.apiCalls.total)
    }

    @Test
    fun `never asks videos_list for more than fifty ids at a time`() {
        val refs = (1..137).map { ref("v$it", it) }
        val (_, api) = walk(
            playlists = mapOf(UPLOADS to refs),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
        )
        assertEquals(listOf(50, 50, 37), api.videoBatchSizes)
    }

    // --- Shorts -------------------------------------------------------------------------

    @Test
    fun `a Short is classified and is not listed, so it is out of the list and the count`() {
        val refs = listOf(ref("normal", 2), ref("short", 1))
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs, UPLOADS_SHORTS to listOf(ref("short", 1))),
            videos = mapOf("normal" to normalVideo("normal"), "short" to shortVideo("short")),
            missingPlaylists = emptySet(),
        )
        assertEquals(listOf(false, true), result.videos.map { it.isShort })
        assertEquals(listOf("normal"), result.videos.filter { it.isListed }.map { it.videoId })
        assertEquals(1, result.videos.count { it.isListed })
    }

    @Test
    fun `UUSH membership wins over the embed shape for a channel Source`() {
        // "wideButInUUSH" looks like a normal wide video, but the channel filed it as a Short.
        // "tallButNotInUUSH" looks like a Short, but the channel did not.
        val refs = listOf(ref("wideButInUUSH", 2), ref("tallButNotInUUSH", 1))
        val (result, _) = walk(
            playlists = mapOf(
                UPLOADS to refs,
                UPLOADS_SHORTS to listOf(ref("wideButInUUSH", 2)),
            ),
            videos = mapOf(
                "wideButInUUSH" to normalVideo("wideButInUUSH"),
                "tallButNotInUUSH" to shortVideo("tallButNotInUUSH"),
            ),
            missingPlaylists = emptySet(),
        )
        assertTrue(result.videos.single { it.videoId == "wideButInUUSH" }.isShort)
        assertFalse(result.videos.single { it.videoId == "tallButNotInUUSH" }.isShort)
    }

    @Test
    fun `playlistNotFound on UUSH means the channel has no Shorts at all`() {
        val refs = listOf(ref("tall", 1))
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs),
            videos = mapOf("tall" to shortVideo("tall")),
            missingPlaylists = setOf(UPLOADS_SHORTS),
        )
        // The embed shape says Short, but the channel has no Shorts playlist, which is
        // authoritative. The video stays listed.
        assertFalse(result.videos.single().isShort)
    }

    @Test
    fun `a UUSH failure that is not playlistNotFound falls back on the embed-shape rule`() {
        val refs = listOf(ref("tall", 2), ref("wide", 1))
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs),
            videos = mapOf("tall" to shortVideo("tall"), "wide" to normalVideo("wide")),
            missingPlaylists = emptySet(),
            failingPlaylists = mapOf(UPLOADS_SHORTS to YouTubeApiException.Reason.NETWORK),
        )
        assertTrue(result.videos.single { it.videoId == "tall" }.isShort)
        assertFalse(result.videos.single { it.videoId == "wide" }.isShort)
    }

    @Test
    fun `a curated playlist uses the embed-shape rule, because it has no UUSH`() {
        val refs = listOf(ref("tall", 1))
        val (result, _) = walk(
            playlistId = CURATED,
            playlists = mapOf(CURATED to refs),
            videos = mapOf("tall" to shortVideo("tall")),
            missingPlaylists = emptySet(),
        )
        assertTrue(result.videos.single().isShort)
    }

    // --- Unavailable, upcoming and live ------------------------------------------------

    @Test
    fun `a video missing from videos_list is unavailable, not a normal video`() {
        val refs = listOf(ref("present", 2), ref("deleted", 1))
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs),
            videos = mapOf("present" to normalVideo("present")),
        )
        val deleted = result.videos.single { it.videoId == "deleted" }
        assertEquals(Availability.UNAVAILABLE, deleted.availability)
        assertEquals(Availability.AVAILABLE, result.videos.single { it.videoId == "present" }.availability)
    }

    @Test
    fun `a video with no publish date is unavailable and sorts last`() {
        val refs = listOf(ref("private", null), ref("older", 1), ref("newer", 5))
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs),
            videos = mapOf(
                "older" to normalVideo("older"),
                "newer" to normalVideo("newer"),
                "private" to normalVideo("private"),
            ),
        )
        assertEquals(listOf("newer", "older", "private"), result.videos.map { it.videoId })
        val private = result.videos.single { it.videoId == "private" }
        assertEquals(Availability.UNAVAILABLE, private.availability)
        assertNull(private.videoPublishedAt)
    }

    /**
     * A video with no `videos.list` reply is unavailable, and it must not also be called a
     * Short. The two are different facts and only one of them is permanent.
     *
     * **`isShort` is what matters here, not `isListed`.** Ticket 23 made an unavailable video
     * unlisted, so it is off the grid either way. If the walk had also stored it as a Short, it
     * could never come back: spec section 4.3 classifies `isShort` once and never again, so a
     * later walk that found the video public again would still hide it for ever.
     */
    @Test
    fun `an unavailable video is never hidden as a Short`() {
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to listOf(ref("gone", null))),
            videos = emptyMap(),
        )
        val gone = result.videos.single()
        assertFalse(gone.isShort)
        assertEquals(Availability.UNAVAILABLE, gone.availability)
        // Not on the grid, and not because of anything the walk decided and stored.
        assertFalse(gone.isListed)
    }

    /**
     * **This test said "and is still listed" until ticket 23.** A premiere that has not started
     * is now unlisted, exactly as a Short is. Spec section 4.4.
     *
     * Its `availability` stays `AVAILABLE`, which is the point: YouTube is serving the video,
     * it simply has not happened yet. The two facts are stored separately, and the premiere
     * becomes listed again at the walk that sees `liveBroadcastContent` stop saying `upcoming`.
     */
    @Test
    fun `an upcoming premiere is marked upcoming and is not listed`() {
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to listOf(ref("premiere", 1))),
            videos = mapOf("premiere" to upcomingVideo("premiere")),
        )
        val premiere = result.videos.single()
        assertTrue(premiere.isUpcoming)
        assertFalse(premiere.isListed)
        assertEquals(Availability.AVAILABLE, premiere.availability)
    }

    @Test
    fun `a finished live stream is kept and carries the WAS LIVE marker`() {
        val endedAt = Instant.parse("2026-08-31T18:06:40Z")
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to listOf(ref("vod", 1))),
            videos = mapOf("vod" to liveVodVideo("vod", endedAt)),
        )
        val vod = result.videos.single()
        assertTrue(vod.wasLive)
        assertTrue(vod.isListed)
        assertFalse(vod.isUpcoming)
        assertEquals(endedAt, vod.liveEndedAt)
    }

    @Test
    fun `a stream that is live right now is not mistaken for a Short`() {
        // It reports no embed size, and its duration is zero.
        val (result, _) = walk(
            playlistId = CURATED,
            playlists = mapOf(CURATED to listOf(ref("live", 1))),
            videos = mapOf("live" to liveVideo("live")),
            missingPlaylists = emptySet(),
        )
        assertFalse(result.videos.single().isShort)
        assertFalse(result.videos.single().wasLive)
    }

    // --- The same video twice in one playlist -------------------------------------------

    @Test
    fun `the same video listed twice becomes one row, keeping the first entry`() {
        val refs = listOf(ref("twice", 5), ref("other", 3), ref("twice", 1))
        val (result, _) = walk(
            playlistId = CURATED,
            playlists = mapOf(CURATED to refs),
            videos = mapOf("twice" to normalVideo("twice"), "other" to normalVideo("other")),
            missingPlaylists = emptySet(),
        )
        assertEquals(listOf("twice", "other"), result.videos.map { it.videoId })
        // The first entry's date wins, which is day 5, so it sorts above day 3.
        assertEquals(dayOf(5), result.videos.first().videoPublishedAt)
    }

    @Test
    fun `a repeated video is asked about once, not twice`() {
        val refs = listOf(ref("twice", 5), ref("twice", 1))
        val (_, api) = walk(
            playlistId = CURATED,
            playlists = mapOf(CURATED to refs),
            videos = mapOf("twice" to normalVideo("twice")),
            missingPlaylists = emptySet(),
        )
        assertEquals(listOf(1), api.videoBatchSizes)
    }

    // --- Scale --------------------------------------------------------------------------

    @Test
    fun `walks a Source of a few thousand videos without losing any`() {
        val n = 5_814
        val refs = (1..n).map { ref("v$it", it) }
        val shorts = (1..n step 10).map { ref("v$it", it) }
        val (result, _) = walk(
            playlists = mapOf(UPLOADS to refs, UPLOADS_SHORTS to shorts),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
            missingPlaylists = emptySet(),
        )
        assertEquals(n, result.videos.size)
        assertEquals(shorts.size, result.videos.count { it.isShort })
        assertEquals(n - shorts.size, result.videos.count { it.isListed })
        assertEquals(117, result.apiCalls.playlistPages)
        assertEquals(117, result.apiCalls.videoPages)
        assertEquals(12, result.apiCalls.shortsPages)
        assertTrue(result.videos.isSortedNewestFirst())
    }

    private fun List<StoredVideo>.isSortedNewestFirst(): Boolean =
        zipWithNext().all { (a, b) -> a.videoPublishedAt!! >= b.videoPublishedAt!! }
}
