package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.RangeSide
import local.youtubetv.data.StoredSource
import local.youtubetv.data.WatchLog
import local.youtubetv.data.testVideo
import local.youtubetv.walk.NoYouTubeCalls
import local.youtubetv.walk.SourceWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SOURCE = "PLmadeUpPlaylistForTestsZ000000069"
private val WALKED_AT: Instant = Instant.parse("2026-10-17T12:00:00Z")

private class NoShuffleApi : NoYouTubeCalls("Shuffle")

/**
 * The Source screen's side of Shuffle: the setting, the run, and what survives what. Build
 * ticket 69. The run's own rules are `ShuffleRunTest`, and the High-water mark's are
 * `ShufflePlayTest`.
 */
class SourceScreenShuffleTest {

    private val store = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore, store, now = { WALKED_AT })
    private var seeds = 100L
    private val videos = (1..12).map { testVideo("v$it", day = it, sourceId = SOURCE) }

    private fun model() = SourceScreenModel(
        sourceId = SOURCE,
        store = store,
        walk = SourceWalk(NoShuffleApi()),
        watches = watches,
        now = { WALKED_AT },
        newShuffleSeed = { seeds++ },
    )

    private fun opened(row: StoredSource = StoredSource(SOURCE, null, WALKED_AT)): SourceScreenModel {
        if (store.source(SOURCE) == null) {
            store.saveSource(row)
            store.replaceVideos(SOURCE, videos)
        }
        return model().also { runBlocking { it.load() } }
    }

    private fun SourceScreenModel.screen(): SourceScreenState.Ready =
        state.value as SourceScreenState.Ready

    @Test
    fun `a reload keeps a play the run holds but the row does not yet`() {
        // The play's row write is queued behind the player. A refresh that reloads in between
        // must not forget it. Found by `/code-review` on build ticket 69.
        val model = opened()
        runBlocking { model.toggleShuffle() }
        val played = model.screen().shuffleNext()!!.videoId
        model.recordShufflePlay(played)

        runBlocking { model.reloadFromStorage() }

        assertTrue(model.screen().shuffleRun!!.played.contains(played))
        assertTrue(model.screen().shuffleNext()!!.videoId != played)
    }

    @Test
    fun `a Shuffle play keeps the mark a Bulk mark gave the video on screen`() {
        // Found by `/code-review` on build ticket 69. The play's own state is worked out from
        // the entries naming the video, and the Bulk mark names none.
        watches.markRange(videos[5], RangeSide.OLDER, watched = true)
        val model = opened()
        assertTrue(model.screen().watches.getValue("v6").countsForHighWaterMark)

        val play = watches.startPlay(videos[5], positionSeconds = 30, completed = true, shuffle = true)!!
        model.applyWatchState(play.state, shufflePlay = true)

        assertTrue(model.screen().watches.getValue("v6").countsForHighWaterMark)
        assertEquals(
            watches.watchStates(model.screen().videos).getValue("v6"),
            model.screen().watches.getValue("v6"),
        )
    }

    @Test
    fun `a Shuffle play after a Bulk unmark does not make the video a mark on screen`() {
        watches.startPlay(videos[8], positionSeconds = 30, completed = true)
        watches.markRange(videos[2], RangeSide.NEWER, watched = false)
        val model = opened()

        val play = watches.startPlay(videos[8], positionSeconds = 30, completed = true, shuffle = true)!!
        model.applyWatchState(play.state, shufflePlay = true)

        val onScreen = model.screen().watches.getValue("v9")
        assertTrue(onScreen.watched)
        assertFalse(onScreen.countsForHighWaterMark)
        assertEquals(
            watches.watchStates(model.screen().videos).getValue("v9").countsForHighWaterMark,
            onScreen.countsForHighWaterMark,
        )
    }

    @Test
    fun `a new Source and a Source stored before Shuffle both read as off`() {
        assertFalse(StoredSource(SOURCE, null, null).shuffle)
        val model = opened()

        assertFalse(model.screen().shuffle)
        assertNull(model.screen().shuffleNext())
    }

    @Test
    fun `turning Shuffle on makes a run and writes both down`() {
        val model = opened()

        val on = runBlocking { model.toggleShuffle() }

        assertEquals(true, on)
        assertTrue(store.source(SOURCE)!!.shuffle)
        assertNotNull(store.source(SOURCE)!!.shuffleRun)
        assertNotNull(model.screen().shuffleNext())
    }

    @Test
    fun `the run's next video is the same after the app restarts`() {
        val first = opened()
        runBlocking { first.toggleShuffle() }
        val next = first.screen().shuffleNext()!!
        first.recordShufflePlay(next.videoId)!!.let(first::saveShuffleRun)
        val after = first.screen().shuffleNext()

        val restarted = model().also { runBlocking { it.load() } }

        assertTrue(restarted.screen().shuffle)
        assertEquals(after, restarted.screen().shuffleNext())
        assertEquals(1, restarted.screen().shufflePlayedCount())
    }

    @Test
    fun `the run survives Shuffle being turned off and on again`() {
        val model = opened()
        runBlocking { model.toggleShuffle() }
        model.recordShufflePlay(model.screen().shuffleNext()!!.videoId)!!.let(model::saveShuffleRun)
        val run = model.screen().shuffleRun

        runBlocking { model.toggleShuffle() }
        runBlocking { model.toggleShuffle() }

        assertEquals(run, model.screen().shuffleRun)
        assertEquals(run, store.source(SOURCE)!!.shuffleRun)
    }

    @Test
    fun `nothing is recorded in the run while Shuffle is off`() {
        val model = opened()

        assertNull(model.recordShufflePlay("v3"))
        assertNull(model.screen().shuffleRun)
    }

    @Test
    fun `a Bulk mark never changes the run`() {
        val model = opened()
        runBlocking { model.toggleShuffle() }
        model.recordShufflePlay("v4")!!.let(model::saveShuffleRun)
        val before = model.screen().shuffleRun
        val nextBefore = model.screen().shuffleNext()

        runBlocking { model.markRange(videos[6], RangeSide.OLDER, watched = true) }

        assertEquals(before, model.screen().shuffleRun)
        assertEquals(nextBefore, model.screen().shuffleNext())
        assertEquals(before, store.source(SOURCE)!!.shuffleRun)
    }

    @Test
    fun `a new run started early forgets the old one and changes no Watch`() {
        val model = opened()
        runBlocking { model.toggleShuffle() }
        val played = model.screen().shuffleNext()!!
        watches.startPlay(played, positionSeconds = 20, completed = false, shuffle = true)
        model.recordShufflePlay(played.videoId)!!.let(model::saveShuffleRun)
        val entries = watchStore.entries()

        val fresh = runBlocking { model.startNewShuffleRun() }!!

        assertEquals(0, model.screen().shufflePlayedCount())
        assertEquals(fresh, store.source(SOURCE)!!.shuffleRun)
        // Its first video is never the old run's last.
        assertTrue(model.screen().shuffleNext()!!.videoId != played.videoId)
        // Not one entry was added, edited or removed.
        assertEquals(entries, watchStore.entries())
        assertTrue(watches.watchStates(videos).getValue(played.videoId).watched)
    }

    @Test
    fun `a new run can be started with Shuffle off, and Shuffle stays off`() {
        val model = opened()

        runBlocking { model.startNewShuffleRun() }

        assertFalse(model.screen().shuffle)
        assertNotNull(store.source(SOURCE)!!.shuffleRun)
    }

    @Test
    fun `a Source opened with Shuffle on and no run gets one written down`() {
        val model = opened(StoredSource(SOURCE, null, WALKED_AT, shuffle = true))

        assertNotNull(model.screen().shuffleRun)
        assertEquals(model.screen().shuffleRun, store.source(SOURCE)!!.shuffleRun)
    }

    @Test
    fun `a whole run through the model plays every video once`() {
        val model = opened()
        runBlocking { model.toggleShuffle() }
        val order = (1..12).map {
            val next = model.screen().shuffleNext()!!.videoId
            model.recordShufflePlay(next)!!.let(model::saveShuffleRun)
            next
        }

        assertEquals(videos.map { it.videoId }.toSet(), order.toSet())
        // And the next run starts at once, with a different first video.
        assertTrue(model.screen().shuffleNext()!!.videoId != order.last())
        assertEquals(0, model.screen().shufflePlayedCount())
    }
}
