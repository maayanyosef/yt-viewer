package local.youtubetv.ui

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.SourceStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.data.testVideo
import local.youtubetv.walk.NoYouTubeCalls
import local.youtubetv.walk.SourceWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PLAYLIST = "PLmadeUpPlaylistForTestsA000000002"

/**
 * A store whose first read of a Source's listed videos stops half way, until the test lets it
 * go on. The list it hands back is the one it read **before** stopping, which is exactly what a
 * slow SQLite read does when a write lands while it runs.
 */
private class PausingStore(private val inner: InMemorySourceStore) : SourceStore by inner {
    val readStarted = CountDownLatch(1)
    val goOn = CountDownLatch(1)
    private var paused = false

    override fun listedVideos(sourceId: String): List<StoredVideo> {
        val read = inner.listedVideos(sourceId)
        if (!paused) {
            paused = true
            readStarted.countDown()
            goOn.await(5, TimeUnit.SECONDS)
        }
        return read
    }
}

/**
 * A refresh that lands while a Source screen is still reading storage is not lost. Build
 * ticket 55.
 *
 * Since that ticket a refresh reaches the screens the moment one Source is stored, so it can
 * arrive while the screen the household has just opened is still reading the old list. Before
 * the fix, `reloadFromStorage` found the screen not yet `Ready` and gave up, and the read then
 * finished with the list from before the refresh. The household saw the old list with nothing
 * left to correct it, which is the defect build ticket 55 was raised for, reached another way.
 */
class SourceScreenReloadRaceTest {

    @Test
    fun `a reload that arrives while the screen is reading storage still reaches the screen`() {
        val inner = InMemorySourceStore()
        inner.saveSource(
            StoredSource(
                sourceId = PLAYLIST,
                title = "A Source",
                lastWalkedAt = Instant.parse("2026-09-19T12:00:00Z"),
                remoteCount = 1,
            ),
        )
        inner.replaceVideos(PLAYLIST, listOf(testVideo("old", day = 1, sourceId = PLAYLIST)))
        val store = PausingStore(inner)
        val model = SourceScreenModel(
            sourceId = PLAYLIST,
            store = store,
            walk = SourceWalk(NoYouTubeCalls("a Source opened from storage")),
            watches = WatchLog(InMemoryWatchStore()),
        )

        runBlocking {
            val opening = async(Dispatchers.IO) { model.load() }
            assertTrue(store.readStarted.await(5, TimeUnit.SECONDS))

            // The refresh stores the new video while the screen's read is half way through,
            // and then tells the screen.
            inner.replaceVideos(
                PLAYLIST,
                listOf(
                    testVideo("new", day = 2, sourceId = PLAYLIST),
                    testVideo("old", day = 1, sourceId = PLAYLIST),
                ),
            )
            val reload = async(Dispatchers.IO) { model.reloadFromStorage() }
            // Long enough for a reload that does not wait to have given up already.
            Thread.sleep(200)
            store.goOn.countDown()
            opening.await()
            reload.await()
        }

        assertEquals(listOf("new", "old"), model.ready()?.videos?.map { it.videoId })
    }
}
