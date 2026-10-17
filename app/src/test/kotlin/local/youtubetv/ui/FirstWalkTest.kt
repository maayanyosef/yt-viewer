package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.Direction
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.data.testDay
import local.youtubetv.data.testVideo
import local.youtubetv.walk.FakeYouTubeApi
import local.youtubetv.walk.SourceWalk
import local.youtubetv.walk.normalVideo
import local.youtubetv.youtube.PlaylistItemRef
import local.youtubetv.youtube.VideoDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CURATED = "PLcuratedSourceIdOfARealLength"
private val ADDED_AT: Instant = Instant.parse("2026-09-20T10:00:00Z")
private val WALKED_AT: Instant = Instant.parse("2026-09-20T10:00:30Z")

/**
 * The first walk of a newly added Source. Spec section 6.5.
 *
 * Three rules, and every one of them was unreachable before ticket 11, because the build
 * opened one hardcoded Source and the only way to make it walk again was to wipe the app's
 * data. That is why ticket 03 left the progressive fill unbuilt.
 */
class FirstWalkTest {

    private val store = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore)
    private val log = mutableListOf<String>()

    /** 137 videos, which is three playlist pages and three enrichment batches. */
    private val refs = (1..137).map { PlaylistItemRef("v$it", testDay(it)) }

    private val api = FakeYouTubeApi(
        playlists = mapOf(CURATED to refs),
        videos = refs.associate { it.videoId to normalVideo(it.videoId) },
    )

    private fun model() = SourceScreenModel(
        sourceId = CURATED,
        store = store,
        walk = SourceWalk(api),
        watches = watches,
        log = { log += it },
        now = { WALKED_AT },
    )

    private fun addSource(direction: Direction = Direction.NEWEST_FIRST) {
        store.saveSource(
            StoredSource(CURATED, "The curated one", lastWalkedAt = null, direction = direction, addedAt = ADDED_AT),
        )
    }

    // ---------------------------------------------------------------- the walk itself

    @Test
    fun `the walk hands back finished videos batch by batch, already in date order`() {
        val batches = mutableListOf<List<StoredVideo>>()
        runBlocking { SourceWalk(api).walk(CURATED, onVideos = { batches += it }) }

        // Three batches of 50, 50 and 37, and each one carries everything so far.
        assertEquals(listOf(50, 100, 137), batches.map { it.size })
        for (batch in batches) {
            assertEquals(
                "a batch arrived out of publish-date order",
                batch.sortedByDescending { it.videoPublishedAt },
                batch,
            )
        }
    }

    @Test
    fun `the walk's own result is the same list the last batch carried`() {
        val batches = mutableListOf<List<StoredVideo>>()
        val result = runBlocking { SourceWalk(api).walk(CURATED, onVideos = { batches += it }) }

        assertEquals(result.videos.map { it.videoId }, batches.last().map { it.videoId })
    }

    // ---------------------------------------------------------------- the screen

    @Test
    fun `the Source screen fills page by page instead of staying blank`() {
        addSource()
        val sizes = mutableListOf<Int>()
        lateinit var model: SourceScreenModel

        // A fake that looks at the screen just before it answers each enrichment batch. That
        // is the only moment worth sampling: it is between two batches, which is exactly the
        // window ticket 03 left the grid blank for.
        val watched = object : FakeYouTubeApi(
            playlists = mapOf(CURATED to refs),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
        ) {
            override suspend fun videos(videoIds: List<String>): List<VideoDetails> {
                sizes += (model.state.value as? SourceScreenState.Ready)?.videos?.size ?: 0
                return super.videos(videoIds)
            }
        }
        model = SourceScreenModel(
            sourceId = CURATED,
            store = store,
            walk = SourceWalk(watched),
            watches = watches,
            log = { log += it },
            now = { WALKED_AT },
        )

        runBlocking { model.load() }

        // Before the first batch the grid is empty, and it grows with every batch after that.
        // Ticket 03 left this at 0, 0, 0 and a blank screen until the whole walk finished.
        assertEquals(listOf(0, 50, 100), sizes)
        assertEquals(137, (model.state.value as SourceScreenState.Ready).videos.size)
    }

    @Test
    fun `a walk in progress is a Ready state carrying its progress`() {
        addSource()
        val seen = mutableListOf<SourceScreenState>()
        val model = SourceScreenModel(
            sourceId = CURATED,
            store = store,
            walk = SourceWalk(api),
            watches = watches,
            log = { log += it },
            now = { WALKED_AT },
        )

        runBlocking {
            SourceWalk(
                FakeYouTubeApi(
                    playlists = mapOf(CURATED to refs),
                    videos = refs.associate { it.videoId to normalVideo(it.videoId) },
                ),
            ).walk(CURATED, onVideos = { finished ->
                seen += SourceScreenState.Ready(
                    videos = finished,
                    updatedAt = null,
                    fromStorage = false,
                    walking = SourceScreenState.WalkProgress(finished.size, 137),
                )
            })
            model.load()
        }

        // While it runs, the header shows the loading line and no updated time.
        val midWalk = seen.first() as SourceScreenState.Ready
        assertEquals("loading 50 of 137", headerLine(midWalk).count)
        assertNull(headerLine(midWalk).updated)

        // When it finishes, the header shows the count and the updated time.
        val done = model.state.value as SourceScreenState.Ready
        assertNull("the walk is over", done.walking)
        assertEquals("137 items", headerLine(done).count)
        assertEquals(WALKED_AT, done.updatedAt)
    }

    @Test
    fun `a Source is walked in the Direction chosen on the add screen`() {
        addSource(Direction.OLDEST_FIRST)
        val model = model()

        runBlocking { model.load() }

        val ready = model.state.value as SourceScreenState.Ready
        assertEquals(Direction.OLDEST_FIRST, ready.direction)
        assertEquals("v1", ready.videos.first().videoId)
    }

    @Test
    fun `finishing the walk keeps the title the add screen stored`() {
        addSource()
        runBlocking { model().load() }

        val stored = store.source(CURATED)!!
        assertEquals("The curated one", stored.title)
        assertEquals(WALKED_AT, stored.lastWalkedAt)
        assertEquals(ADDED_AT, stored.addedAt)
    }

    // ---------------------------------------------------------------- what is unavailable

    @Test
    fun `Bulk mark offers only Just this while the walk is still running`() {
        val listed = (1..10).map { testVideo("v$it", day = it, sourceId = CURATED) }

        val running = cardMenuSubLines(listed[4], listed, walkFinished = false)
        assertEquals(listOf(MarkScope.JUST_THIS), running.map { it.scope })

        val finished = cardMenuSubLines(listed[4], listed, walkFinished = true)
        assertEquals(
            listOf(MarkScope.JUST_THIS, MarkScope.ALL_OLDER, MarkScope.ALL_NEWER),
            finished.map { it.scope },
        )
    }

    @Test
    fun `the walk is reported as finished by default, so nothing else has to say so`() {
        val listed = (1..3).map { testVideo("v$it", day = it, sourceId = CURATED) }
        assertEquals(3, cardMenuSubLines(listed[1], listed).size)
    }

    @Test
    fun `the header says loading with no total until YouTube reports one`() {
        val state = SourceScreenState.Ready(
            videos = emptyList(),
            updatedAt = null,
            fromStorage = false,
            walking = SourceScreenState.WalkProgress(50, null),
        )
        assertEquals("loading 50", headerLine(state).count)
    }


    // ------------------------------ the offer that used to be here, and is not any more

    // Ticket 25 removed the one-time mark-everything-watched offer. The owner chose removal over
    // keeping it, and over making it a quieter chip: Bulk mark from a card's long-press menu
    // does the same thing whenever the household wants it, and ticket 05 proved that path at
    // 5,303 videos in about 220 ms. This test is what stops the offer coming back by accident.
    // A finished first walk now leaves the grid alone: nothing covers it, and nothing is
    // written to the household's log until the household itself asks for something.
    @Test
    fun `a finished first walk writes nothing to the Watch log and asks nothing`() {
        addSource()
        val model = model()

        runBlocking { model.load() }

        val ready = model.state.value as SourceScreenState.Ready
        assertNull("the walk has finished", ready.walking)
        assertEquals(137, ready.videos.size)
        assertTrue(
            "no video is watched, because nothing offered to mark them",
            ready.videos.none { ready.watches[it.videoId]?.watched == true },
        )
        assertTrue("nothing was appended to the log", watchStore.entries().isEmpty())
    }

    @Test
    fun `the walk costs exactly what the formula says`() {
        addSource()
        runBlocking { model().load() }

        // 137 videos: 3 playlist pages and 3 enrichment pages, and no UUSH page because a
        // curated `PL...` playlist has no Shorts companion.
        assertEquals(3, api.playlistItemCalls)
        assertEquals(3, api.videoCalls)
        assertTrue(log.any { it.contains("6 API calls") })
    }
}
