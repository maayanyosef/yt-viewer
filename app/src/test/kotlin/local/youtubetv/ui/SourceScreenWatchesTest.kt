package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.RangeSide
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.data.testVideo
import local.youtubetv.walk.NoYouTubeCalls
import local.youtubetv.walk.SourceWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SOURCE = "PLmadeUpPlaylistForTestsZ000000001"
private val WALKED_AT: Instant = Instant.parse("2026-09-19T12:00:00Z")
private val MARKED_AT: Instant = Instant.parse("2026-09-20T18:30:00Z")

/** The ids the grid would draw a green tick on. */
private fun SourceScreenState.Ready.watchedIds(): Set<String> =
    watches.values.filter { it.watched }.mapTo(mutableSetOf()) { it.videoId }

/** An API that fails the moment it is touched. Nothing on this path may call YouTube. */
private class NoApiAllowed : NoYouTubeCalls("marking a video")

/**
 * What the Source screen does with the Watch log.
 *
 * The rules themselves are covered in `WatchLogTest`. What is here is the wiring: a launch
 * reads the watched videos out of storage with no API call, and marking one video changes what
 * the grid draws without rebuilding anything else.
 */
class SourceScreenWatchesTest {

    private val log = mutableListOf<String>()
    private val store = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore, now = { MARKED_AT })

    private val videos = listOf(
        testVideo("newest", day = 9, sourceId = SOURCE, title = "The newest one"),
        testVideo("middle", day = 5, sourceId = SOURCE),
        testVideo("oldest", day = 1, sourceId = SOURCE),
    )

    private fun model() = SourceScreenModel(
        sourceId = SOURCE,
        store = store,
        walk = SourceWalk(NoApiAllowed()),
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

    private fun video(videoId: String): StoredVideo = videos.first { it.videoId == videoId }

    @Test
    fun `a launch with nothing marked shows no video as watched`() {
        val (_, state) = ready()

        assertTrue(state.watchedIds().isEmpty())
    }

    @Test
    fun `marking a video watched puts it in what the grid draws`() {
        val (model, _) = ready()

        runBlocking { model.markJustThis(video("middle"), watched = true) }

        val state = model.state.value as SourceScreenState.Ready
        assertEquals(setOf("middle"), state.watchedIds())
    }

    @Test
    fun `marking it unwatched takes it out again`() {
        val (model, _) = ready()

        runBlocking {
            model.markJustThis(video("middle"), watched = true)
            model.markJustThis(video("middle"), watched = false)
        }

        assertTrue((model.state.value as SourceScreenState.Ready).watchedIds().isEmpty())
    }

    @Test
    fun `marking one video leaves the others alone`() {
        val (model, _) = ready()

        runBlocking {
            model.markJustThis(video("middle"), watched = true)
            model.markJustThis(video("oldest"), watched = true)
        }

        assertEquals(setOf("middle", "oldest"), (model.state.value as SourceScreenState.Ready).watchedIds())
    }

    @Test
    fun `a later launch still shows what was marked, with no API call`() {
        val (model, _) = ready()
        runBlocking { model.markJustThis(video("middle"), watched = true) }

        val second = model()
        runBlocking { second.load() }

        assertEquals(setOf("middle"), (second.state.value as SourceScreenState.Ready).watchedIds())
    }

    @Test
    fun `a video watched in another Source still shows watched here`() {
        // ADR 0001: a Watch is household-level and answered by videoId alone. The same video
        // sits in a channel's uploads and in a curated playlist, and it was watched once.
        watches.markJustThis(testVideo("middle", day = 5, sourceId = "UUsomewhereElse"), watched = true)

        val (_, state) = ready()

        assertEquals(setOf("middle"), state.watchedIds())
    }

    @Test
    fun `the launch says in one line whether it had to derive`() {
        ready()

        assertTrue(log.toString(), log.any { it.contains("Derived") })
    }

    @Test
    fun `a rebuild asked for by hand gives the same answer`() {
        val (model, _) = ready()
        runBlocking { model.markJustThis(video("middle"), watched = true) }
        watchStore.dropDerivedTable()

        runBlocking { model.rebuildDerived() }

        assertEquals(setOf("middle"), (model.state.value as SourceScreenState.Ready).watchedIds())
    }

    @Test
    fun `a marked video's title and date are readable after it leaves the video cache`() {
        val (model, _) = ready()
        runBlocking { model.markJustThis(video("newest"), watched = true) }

        // What a re-walk does when YouTube stops serving the video: its row simply goes.
        store.replaceVideos(SOURCE, videos.filter { it.videoId != "newest" })
        log.clear()
        runBlocking { model().load() }

        val line = log.single { it.startsWith("Kept video") }
        assertTrue(line, line.contains("newest"))
        assertTrue(line, line.contains("The newest one"))
        assertTrue(line, line.contains("Jan 2026"))
    }

    @Test
    fun `a Bulk mark changes the whole grid and writes one log entry`() {
        val (model, _) = ready()

        runBlocking { model.markRange(video("middle"), side = RangeSide.OLDER, watched = true) }

        val ready = model.state.value as SourceScreenState.Ready
        assertEquals(setOf("middle", "oldest"), ready.watchedIds())
        assertEquals(1, watchStore.entries().size)
    }

    @Test
    fun `a Bulk mark survives a relaunch, with the log read again from storage`() {
        val (model, _) = ready()
        runBlocking { model.markRange(video("middle"), side = RangeSide.OLDER, watched = true) }

        val relaunched = model()
        runBlocking { relaunched.load() }

        val ready = relaunched.state.value as SourceScreenState.Ready
        assertEquals(setOf("middle", "oldest"), ready.watchedIds())
    }

    @Test
    fun `a video a later walk finds inside a Bulk mark needs no second Bulk mark`() {
        val (model, _) = ready()
        runBlocking { model.markRange(video("middle"), side = RangeSide.OLDER, watched = true) }
        val entriesAfterTheMark = watchStore.entries().size

        // An upload made public months late, with an old publish date, found by a later walk.
        store.replaceVideos(SOURCE, videos + testVideo("late", day = 3, sourceId = SOURCE))
        val relaunched = model()
        runBlocking { relaunched.load() }

        val ready = relaunched.state.value as SourceScreenState.Ready
        assertTrue(ready.watchedIds().toString(), "late" in ready.watchedIds())
        assertEquals(entriesAfterTheMark, watchStore.entries().size)
    }

    @Test
    fun `the Bulk mark line counts this Source's own videos, not every video in the log`() {
        // A video of another Source, watched before this Source is ever opened. The watch map
        // is keyed by video id across every Source, so counting the map would count this one.
        watches.markJustThis(testVideo("elsewhere", day = 4, sourceId = "UUsomewhere"), true)
        val (model, _) = ready()

        runBlocking { model.markRange(video("middle"), side = RangeSide.OLDER, watched = true) }

        val line = log.single { it.startsWith("Bulk marked") }
        assertTrue(line, line.contains("2 of 3 listed videos now watched"))
    }

    @Test
    fun `a Bulk mark makes no API call`() {
        val (model, _) = ready()

        // `NoApiAllowed` fails the moment anything touches YouTube, so reaching the end of
        // this test is the assertion.
        runBlocking { model.markRange(video("newest"), side = RangeSide.NEWER, watched = false) }
    }

    @Test
    fun `a video still in the cache is not reported as kept`() {
        val (model, _) = ready()
        runBlocking { model.markJustThis(video("newest"), watched = true) }
        log.clear()

        runBlocking { model().load() }

        assertFalse(log.toString(), log.any { it.startsWith("Kept video") })
    }
}
