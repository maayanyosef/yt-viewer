package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.SourceStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOW: Instant = Instant.parse("2026-09-20T12:00:00Z")

private const val TED = "UUAuUUnT6oDeKwE6v1NGQxug"

/**
 * A card whose Next unwatched video cannot be read back.
 *
 * Build ticket 22 made the rail count a Source from a two-column key list and then read the
 * one video the card names by id. Those are two queries, so a walk or a change check can drop
 * that video between them. The card would then hold a count with no video to name, and
 * [nextUnwatchedSlot] would draw `Nothing left in this Source` beside `N UNWATCHED`. That is
 * the contradiction ticket 23 removed from the Source screen and spec sections 4.4 and 7.1
 * forbid it. Found by `/code-review`.
 *
 * `HomeModel.load` reads the whole rail again when it happens, **once**.
 */
class HomeRailStaleCardTest {

    private val real = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore, now = { NOW })
    private val log = mutableListOf<String>()

    /**
     * The store with one video that the key list still holds and [listedVideo] will not return.
     *
     * @param mendsAfter how many rail builds go by before the video can be read again. One
     *   means the second build finds it, which is the race mending itself. [Int.MAX_VALUE]
     *   means it never comes back.
     */
    private class HidesOneVideo(
        private val real: InMemorySourceStore,
        private val hidden: String,
        private val mendsAfter: Int,
    ) : SourceStore by real {

        /** How many times the rail has asked for the whole row of a card's named video. */
        var namedReads = 0
            private set

        override fun listedVideo(sourceId: String, videoId: String): StoredVideo? {
            namedReads++
            if (videoId == hidden && namedReads <= mendsAfter) return null
            return real.listedVideo(sourceId, videoId)
        }
    }

    private fun storeTed(count: Int): List<StoredVideo> {
        val videos = (1..count).map { day -> testVideo("$TED-$day", day = day, sourceId = TED) }
        real.saveSource(StoredSource(TED, "TED", NOW))
        real.replaceVideos(TED, videos)
        return videos
    }

    private fun railOver(store: SourceStore): List<local.youtubetv.data.SourceCard> {
        val model = HomeModel(store, watches) { line -> log += line }
        runBlocking { model.load() }
        return (model.state.value as HomeScreenState.Ready).cards
    }

    @Test
    fun `the rail is read again when a card's Next unwatched video cannot be read back`() {
        storeTed(count = 3)
        // Newest first, so the Next unwatched video is day 3.
        val store = HidesOneVideo(real, hidden = "$TED-3", mendsAfter = 1)

        val cards = railOver(store)

        // Two builds: the first could not read the video, the second could.
        assertEquals(2, store.namedReads)
        assertNotNull(cards.single().nextUnwatched)
        assertEquals("$TED-3", cards.single().nextUnwatched?.videoId)
        assertEquals(3, cards.single().unwatchedCount)
    }

    @Test
    fun `the rail says in the log why it read again`() {
        storeTed(count = 3)

        railOver(HidesOneVideo(real, hidden = "$TED-3", mendsAfter = 1))

        assertEquals(
            listOf(railStaleCardLine(TED, "$TED-3")),
            log.filter { it.startsWith("Home rail read") },
        )
    }

    @Test
    fun `it reads again once and no more, and still draws the household a rail`() {
        storeTed(count = 3)
        val store = HidesOneVideo(real, hidden = "$TED-3", mendsAfter = Int.MAX_VALUE)

        val cards = railOver(store)

        // Two builds and then it stops. A rail that read for ever would be worse than a card
        // that is briefly wrong.
        assertEquals(2, store.namedReads)
        assertNull(cards.single().nextUnwatched)
        // Both builds said so, which is what a household seeing it twice should leave behind.
        assertEquals(2, log.count { it.startsWith("Home rail read") })
    }

    @Test
    fun `a Source with nothing left names no video and is not a stale card`() {
        real.saveSource(StoredSource(TED, "TED", NOW))
        real.replaceVideos(TED, emptyList())
        val store = HidesOneVideo(real, hidden = "nothing", mendsAfter = Int.MAX_VALUE)

        val cards = railOver(store)

        assertEquals(0, store.namedReads)
        assertNull(cards.single().nextUnwatched)
        assertTrue(log.none { it.startsWith("Home rail read") })
    }
}
