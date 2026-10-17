package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.Direction
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.RangeSide
import local.youtubetv.data.StoredSource
import local.youtubetv.data.WatchLog
import local.youtubetv.data.testVideo
import local.youtubetv.walk.NoYouTubeCalls
import local.youtubetv.walk.SourceWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val SOURCE = "PLmadeUpPlaylistForTestsZ000000001"
private val WALKED_AT: Instant = Instant.parse("2026-09-19T12:00:00Z")
private val PLAYED_AT: Instant = Instant.parse("2026-09-20T18:30:00Z")

/** Nothing on any of these paths may call YouTube. */
private class NoApi : NoYouTubeCalls("flipping Direction")

/**
 * Direction and the two landings, as the Source screen holds them.
 *
 * The rules themselves are covered in `LandingsTest` and `WatchDerivationTest`. What is here is
 * the wiring: the Source opens the right way round, `Order` turns it and the choice survives,
 * and `Jump to last played` points where the household actually played.
 */
class SourceDirectionTest {

    private val log = mutableListOf<String>()
    private val store = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore, now = { PLAYED_AT })

    /** Day 1, day 5 and day 9 of one Source. */
    private val videos = listOf(
        testVideo("newest", day = 9, sourceId = SOURCE),
        testVideo("middle", day = 5, sourceId = SOURCE),
        testVideo("oldest", day = 1, sourceId = SOURCE),
    )

    private fun model() = SourceScreenModel(
        sourceId = SOURCE,
        store = store,
        walk = SourceWalk(NoApi()),
        watches = watches,
        log = { log += it },
        now = { WALKED_AT },
    )

    private fun ready(): Pair<SourceScreenModel, SourceScreenState.Ready> {
        store.saveSource(StoredSource(SOURCE, title = null, lastWalkedAt = WALKED_AT))
        store.replaceVideos(SOURCE, videos)
        val model = model()
        runBlocking { model.load() }
        return model to model.state.value as SourceScreenState.Ready
    }

    private fun stateOf(model: SourceScreenModel) =
        model.state.value as SourceScreenState.Ready

    @Test
    fun `a Source opens newest first until the household says otherwise`() {
        val (_, opened) = ready()

        assertEquals(Direction.NEWEST_FIRST, opened.direction)
        assertEquals(listOf("newest", "middle", "oldest"), opened.videos.map { it.videoId })
    }

    @Test
    fun `Order flips the Direction and turns the list around`() {
        val (model, _) = ready()

        val flipped = runBlocking { model.flip() }

        assertEquals(Direction.OLDEST_FIRST, flipped)
        assertEquals(
            listOf("oldest", "middle", "newest"),
            stateOf(model).videos.map { it.videoId },
        )
    }

    @Test
    fun `the chosen Direction survives leaving the Source and coming back`() {
        val (model, _) = ready()
        runBlocking { model.flip() }

        // A fresh model over the same storage, which is what coming back is.
        val second = model()
        runBlocking { second.load() }

        val reopened = stateOf(second)
        assertEquals(Direction.OLDEST_FIRST, reopened.direction)
        assertEquals(listOf("oldest", "middle", "newest"), reopened.videos.map { it.videoId })
    }

    @Test
    fun `flipping twice returns the Source to where it started`() {
        val (model, opened) = ready()

        runBlocking { model.flip() }
        runBlocking { model.flip() }

        assertEquals(Direction.NEWEST_FIRST, stateOf(model).direction)
        assertEquals(opened.videos.map { it.videoId }, stateOf(model).videos.map { it.videoId })
    }

    @Test
    fun `a flip keeps the Source's title and its last walk time`() {
        // The flip writes one column. A whole-row save would carry these away.
        val (model, _) = ready()
        store.saveSource(StoredSource(SOURCE, title = "Example Channel", lastWalkedAt = WALKED_AT))

        runBlocking { model.flip() }

        val stored = store.source(SOURCE)!!
        assertEquals("Example Channel", stored.title)
        assertEquals(WALKED_AT, stored.lastWalkedAt)
        assertEquals(Direction.OLDEST_FIRST, stored.direction)
    }

    @Test
    fun `a Source nobody has played has no last played`() {
        val (_, opened) = ready()

        assertNull(opened.lastPlayedVideoId)
    }

    @Test
    fun `a manual Mark as watched does not become the last played video`() {
        // It writes a `played` entry with no position, which is a declaration and not a play.
        val (model, opened) = ready()

        runBlocking { model.markJustThis(opened.videos[1], watched = true) }
        val second = model()
        runBlocking { second.load() }

        assertNull(stateOf(second).lastPlayedVideoId)
    }

    @Test
    fun `a real play becomes the last played video`() {
        val (_, opened) = ready()
        watches.startPlay(opened.videos[1], positionSeconds = 42, completed = false)

        val second = model()
        runBlocking { second.load() }

        assertEquals("middle", stateOf(second).lastPlayedVideoId)
    }

    @Test
    fun `a Bulk mark does not move the last played video`() {
        val (_, opened) = ready()
        watches.startPlay(opened.videos[2], positionSeconds = 42, completed = false)
        val withPlay = model()
        runBlocking { withPlay.load() }

        // Everything older than the newest video, which is every video in this Source.
        runBlocking { withPlay.markRange(opened.videos[0], RangeSide.OLDER, watched = true) }

        // The Bulk mark did happen.
        assertEquals(3, stateOf(withPlay).watches.values.count { it.watched })
        assertEquals("oldest", stateOf(withPlay).lastPlayedVideoId)
        // And it is the same answer after coming back, so nothing written undoes it later.
        val second = model()
        runBlocking { second.load() }
        assertEquals("oldest", stateOf(second).lastPlayedVideoId)
    }

    @Test
    fun `a play while the screen is open moves last played at once`() {
        // `applyWatchState` is what the player calls on every progress tick. Without this,
        // `Jump to last played` would still point at the video before this one until the
        // next launch.
        val (model, opened) = ready()
        val play = watches.startPlay(opened.videos[0], positionSeconds = 30, completed = false)!!

        model.applyWatchState(play.state)

        assertEquals("newest", stateOf(model).lastPlayedVideoId)
    }

    @Test
    fun `the launch says in logcat which way round the Source is`() {
        ready()

        assertEquals(
            1,
            log.count { it.startsWith("Direction is NEWEST_FIRST:") && it.contains("newest") },
        )
    }
}
