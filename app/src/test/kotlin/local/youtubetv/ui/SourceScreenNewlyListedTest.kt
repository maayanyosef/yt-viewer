package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.Direction
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.data.testVideo
import local.youtubetv.walk.NoYouTubeCalls
import local.youtubetv.walk.SourceWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SOURCE = "PLmadeUpPlaylistForTestsZ000000001"
private val WALKED_AT: Instant = Instant.parse("2026-09-19T12:00:00Z")
private val PLAYED_AT: Instant = Instant.parse("2026-09-20T18:30:00Z")

/** Nothing on any of these paths may call YouTube. */
private class NoNewlyListedApi : NoYouTubeCalls("the Newly listed flag on the Source screen")

/**
 * The Source screen keeps its own Newly listed flags in step with storage.
 *
 * **This is the wiring commit `d33b1e2` was written to fix, and it had no test of its own.**
 * That commit tested the pure helper `clearedNewlyListed` and left `applyWatchState` and
 * `markJustThis`, which are the two methods that were actually wrong, unguarded. Found by
 * `/code-review high` on 2026-09-21, reviewing that commit.
 *
 * The defect it closed: `WatchLog` clears the flag in storage for the video played and
 * everything at or past it. The home rail rebuilds from storage, so it drops the video at
 * once. The Source screen holds its own list and did not, so the rail's badge and card moved
 * on while the screen's `Next unwatched` chip still jumped to a video the household had
 * already skipped past. That card-versus-screen disagreement is the thing build ticket 30 had
 * to undo once already.
 *
 * So the assertion that matters here is not "the flag is false". It is **the screen and
 * storage say the same thing**, which is what [screenAgreesWithStorage] checks. A test that
 * only pinned the screen's own answer would go green again the day the two drifted the other
 * way.
 */
class SourceScreenNewlyListedTest {

    private val log = mutableListOf<String>()
    private val store = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore, sources = store, now = { PLAYED_AT })

    /** Day 1, day 5 and day 9, with the two newest flagged as a walk would leave them. */
    private val videos = listOf(
        testVideo("newest", day = 9, sourceId = SOURCE, newlyListed = true),
        testVideo("middle", day = 5, sourceId = SOURCE, newlyListed = true),
        testVideo("oldest", day = 1, sourceId = SOURCE),
    )

    private fun model() = SourceScreenModel(
        sourceId = SOURCE,
        store = store,
        walk = SourceWalk(NoNewlyListedApi()),
        watches = watches,
        log = { log += it },
        now = { WALKED_AT },
    )

    private fun ready(
        direction: Direction = Direction.NEWEST_FIRST,
    ): Pair<SourceScreenModel, SourceScreenState.Ready> {
        store.saveSource(
            StoredSource(SOURCE, title = null, lastWalkedAt = WALKED_AT, direction = direction),
        )
        store.replaceVideos(SOURCE, videos)
        val model = model()
        runBlocking { model.load() }
        return model to model.state.value as SourceScreenState.Ready
    }

    private fun stateOf(model: SourceScreenModel) =
        model.state.value as SourceScreenState.Ready

    /** The ids the screen still thinks are Newly listed. */
    private fun onTheScreen(model: SourceScreenModel) =
        stateOf(model).videos.filter { it.newlyListed }.map { it.videoId }.toSet()

    /** The ids storage still says are Newly listed, which is what the home rail reads. */
    private fun inStorage() =
        store.allVideos(SOURCE).filter { it.newlyListed }.map { it.videoId }.toSet()

    /**
     * The whole point: the screen and the home rail cannot disagree about which video is
     * still Newly listed, because the rail rebuilds from storage and the screen does not.
     */
    private fun screenAgreesWithStorage(model: SourceScreenModel) {
        assertEquals(
            "the Source screen and storage disagree about the Newly listed flag",
            inStorage(),
            onTheScreen(model),
        )
    }

    private fun videoOn(model: SourceScreenModel, videoId: String): StoredVideo =
        stateOf(model).videos.first { it.videoId == videoId }

    @Test
    fun `a play clears the flag on the screen as well as in storage`() {
        val (model, _) = ready()
        assertEquals(setOf("newest", "middle"), onTheScreen(model))

        // Newest first, playing the middle video means the household has worked past
        // everything newer, so `middle` and `newest` both stop being Newly listed.
        val play = watches.startPlay(
            videoOn(model, "middle"),
            positionSeconds = 30,
            completed = false,
        )!!
        model.applyWatchState(play.state)

        assertEquals(emptySet<String>(), onTheScreen(model))
        screenAgreesWithStorage(model)
    }

    @Test
    fun `a play of the newest video leaves the one below it flagged, on both sides`() {
        val (model, _) = ready()

        val play = watches.startPlay(
            videoOn(model, "newest"),
            positionSeconds = 30,
            completed = false,
        )!!
        model.applyWatchState(play.state)

        assertEquals(setOf("middle"), onTheScreen(model))
        screenAgreesWithStorage(model)
    }

    @Test
    fun `an oldest-first Source clears the other way round, on both sides`() {
        val (model, _) = ready(Direction.OLDEST_FIRST)

        // At or past `middle` in this Direction means published no later than day 5, so
        // `middle` goes and `newest` stays.
        val play = watches.startPlay(
            videoOn(model, "middle"),
            positionSeconds = 30,
            completed = false,
        )!!
        model.applyWatchState(play.state)

        assertEquals(setOf("newest"), onTheScreen(model))
        screenAgreesWithStorage(model)
    }

    @Test
    fun `a Shuffle play clears no flag, on either side`() {
        // Build ticket 72, "A Shuffle play never ends Newly listed". Newest first, an ordinary
        // play of `oldest` would end both flags.
        val (model, _) = ready()

        val play = watches.startPlay(
            videoOn(model, "oldest"),
            positionSeconds = 30,
            completed = false,
            shuffle = true,
        )!!
        model.applyWatchState(play.state, shufflePlay = true)

        assertEquals(setOf("newest", "middle"), onTheScreen(model))
        screenAgreesWithStorage(model)
        // It is still a play: it moves Last played.
        assertEquals("oldest", stateOf(model).lastPlayedVideoId)
    }

    /**
     * The behaviour build ticket 72, "A Shuffle play never ends Newly listed", exists for, read
     * where the household reads it: the screen's own Next unwatched. The household has worked
     * down to `d5` by an ordinary play written before the walk listed `newest`, so `d5` is the
     * High-water mark and the exception offers `newest`.
     */
    @Test
    fun `after a Shuffle play below it the screen still offers the Newly listed video`() {
        val listed = listOf(testVideo("newest", day = 9, sourceId = SOURCE, newlyListed = true)) +
            (5 downTo 1).map { testVideo("d$it", day = it, sourceId = SOURCE) }
        store.saveSource(
            StoredSource(SOURCE, title = null, lastWalkedAt = WALKED_AT, direction = Direction.NEWEST_FIRST),
        )
        store.replaceVideos(SOURCE, listed)
        watchStore.append(
            kind = local.youtubetv.data.WatchKind.PLAYED,
            payload = local.youtubetv.data.playedPayload("d5", SOURCE, 40, completed = true),
            at = PLAYED_AT.minusSeconds(3600),
        )
        val model = model()
        runBlocking { model.load() }
        assertEquals("newest", screenNextUnwatched(model))

        val play = watches.startPlay(
            videoOn(model, "d2"),
            positionSeconds = 30,
            completed = false,
            shuffle = true,
        )!!
        model.applyWatchState(play.state, shufflePlay = play.shuffle)

        assertEquals("newest", screenNextUnwatched(model))
        screenAgreesWithStorage(model)
        val d2 = stateOf(model).watches.getValue("d2")
        assertTrue(d2.watched)
        assertEquals(false, d2.countsForHighWaterMark)
    }

    /** Where the screen's `Next unwatched` chip lands, by the screen's own state. */
    private fun screenNextUnwatched(model: SourceScreenModel): String? {
        val ready = stateOf(model)
        return local.youtubetv.data.nextUnwatchedIndex(ready.videos, ready.watches, ready.direction)
            ?.let { ready.videos[it].videoId }
    }

    @Test
    fun `a Shuffle play of a Newly listed video leaves its own flag, on either side`() {
        val (model, _) = ready()

        val play = watches.startPlay(
            videoOn(model, "middle"),
            positionSeconds = 30,
            completed = false,
            shuffle = true,
        )!!
        model.applyWatchState(play.state, shufflePlay = true)

        assertEquals(setOf("newest", "middle"), onTheScreen(model))
        screenAgreesWithStorage(model)
    }

    @Test
    fun `Mark as watched clears the flag on the screen as well as in storage`() {
        val (model, _) = ready()

        runBlocking { model.markJustThis(videoOn(model, "middle"), watched = true) }

        assertEquals(emptySet<String>(), onTheScreen(model))
        screenAgreesWithStorage(model)
    }

    @Test
    fun `Mark as unwatched reaches no flag at all, on either side`() {
        // Only a `played` entry ends the Newly listed state. An `unwatched` entry reaches
        // nothing, which is the rule `SourceStore.clearNewlyListedUpTo` states once.
        val (model, _) = ready()

        runBlocking { model.markJustThis(videoOn(model, "oldest"), watched = false) }

        assertEquals(setOf("newest", "middle"), onTheScreen(model))
        screenAgreesWithStorage(model)
    }

    @Test
    fun `a progress tick with no position moves no flag`() {
        // The player calls `applyWatchState` on every tick. A state with no position is not a
        // play, so nothing may be cleared on the strength of it.
        val (model, _) = ready()
        val declared = watches.markJustThis(videoOn(model, "oldest"), watched = true)!!
        store.replaceVideos(SOURCE, videos)

        model.applyWatchState(declared.copy(positionSeconds = null))

        assertEquals(setOf("newest", "middle"), onTheScreen(model))
    }

    /**
     * The defect as the household would have met it: the chip going on offering a video the
     * home rail has already dropped.
     *
     * `middle` is played, so nothing is Newly listed any more and the ordinary high-water
     * rule takes over. Before `d33b1e2` the screen still held `newest` as flagged and the
     * exception pulled it back up to just after the mark, so the chip offered a video the
     * household had just worked past.
     */
    @Test
    fun `after a play the chip no longer offers the video the rail has dropped`() {
        val (model, _) = ready()

        val play = watches.startPlay(
            videoOn(model, "middle"),
            positionSeconds = 30,
            completed = false,
        )!!
        model.applyWatchState(play.state)

        val ready = stateOf(model)
        val landing = local.youtubetv.data.nextUnwatchedLanding(
            videos = ready.videos,
            watches = ready.watches,
            direction = ready.direction,
            newlyListed = { it.newlyListed },
        ) { true }
        assertEquals("oldest", landing.index?.let { ready.videos[it].videoId })
        assertTrue("the log recorded the play", log.isNotEmpty())
    }
}
