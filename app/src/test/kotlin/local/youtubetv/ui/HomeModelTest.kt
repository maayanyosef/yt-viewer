package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.Direction
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.RangeSide
import local.youtubetv.data.SourceCard
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchKind
import local.youtubetv.data.WatchLog
import local.youtubetv.data.covers
import local.youtubetv.data.range
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOW: Instant = Instant.parse("2026-09-20T12:00:00Z")

private const val HASSON = "PLmadeUpPlaylistForTestsZ000000001"
private const val TED = "UUAuUUnT6oDeKwE6v1NGQxug"

/**
 * The home rail, driven end to end over the in-memory stores.
 *
 * Everything here is a rule the television could only show, never settle, so it is settled
 * here. See spec section 7.1.
 */
class HomeModelTest {

    private val sources = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore, now = { NOW })
    private val log = mutableListOf<String>()

    private fun model() = HomeModel(sources, watches) { line -> log += line }

    /**
     * A Source with [count] videos, newest on day [count].
     *
     * The list comes back **oldest first**, whatever the Source's Direction, so a test that
     * wants to cover the whole Source can pivot on `first()`. The store itself still hands the
     * screen its videos in the Source's Direction.
     */
    private fun storeSource(
        sourceId: String,
        count: Int,
        direction: Direction = Direction.NEWEST_FIRST,
        title: String? = null,
    ): List<StoredVideo> {
        val videos = (1..count).map { day ->
            testVideo("$sourceId-$day", day = day, sourceId = sourceId)
        }
        sources.saveSource(StoredSource(sourceId, title, NOW, direction))
        sources.replaceVideos(sourceId, videos)
        return videos
    }

    private fun cards(): List<SourceCard> {
        val state = model().let { runBlocking { it.load(); it.state.value } }
        return (state as HomeScreenState.Ready).cards
    }

    // ------------------------------------------------------------------ the rail

    @Test
    fun `the rail holds one card for every stored Source`() {
        storeSource(HASSON, count = 3)
        storeSource(TED, count = 5)

        assertEquals(setOf(HASSON, TED), cards().map { it.sourceId }.toSet())
    }

    /**
     * A Source is on the rail from the moment the household adds it, and is walked when it is
     * opened. Ticket 11 split those two: before it, a row could only come from a walk.
     */
    @Test
    fun `a Source that has been added but not walked is on the rail and says so`() {
        sources.saveSource(StoredSource(HASSON, "The curated one", lastWalkedAt = null))
        val model = model()

        runBlocking { model.load() }
        val cards = (model.state.value as HomeScreenState.Ready).cards

        assertEquals(listOf(HASSON), cards.map { it.sourceId })
        assertFalse(cards.single().walked)
        assertEquals(0, cards.single().listedCount)
    }

    @Test
    fun `nothing is on the rail that the household did not add`() {
        val model = model()

        runBlocking { model.load() }

        // There was one hardcoded Source id here until ticket 11, so that a fresh install had
        // something to open. A fresh install now opens on the first-run page instead.
        assertEquals(emptyList<SourceCard>(), (model.state.value as HomeScreenState.Ready).cards)
    }

    @Test
    fun `a household with no Source at all gets an empty rail`() {
        val model = model()

        runBlocking { model.load() }

        assertEquals(emptyList<SourceCard>(), (model.state.value as HomeScreenState.Ready).cards)
    }

    /** Every card carries its own Source's counts, not the other one's. */
    @Test
    fun `each card counts its own Source`() {
        storeSource(HASSON, count = 3)
        storeSource(TED, count = 5)

        val byId = cards().associateBy { it.sourceId }

        assertEquals(3, byId.getValue(HASSON).listedCount)
        assertEquals(3, byId.getValue(HASSON).unwatchedCount)
        assertEquals(5, byId.getValue(TED).listedCount)
    }

    /** Each Source is browsed its own way round, and the card says which. */
    @Test
    fun `a card states its own Source's Direction`() {
        storeSource(HASSON, count = 3, direction = Direction.OLDEST_FIRST)
        storeSource(TED, count = 5)

        val byId = cards().associateBy { it.sourceId }

        assertEquals(Direction.OLDEST_FIRST, byId.getValue(HASSON).direction)
        assertEquals(Direction.NEWEST_FIRST, byId.getValue(TED).direction)
    }

    /**
     * Next unwatched follows the Source's Direction, because the store hands the videos over
     * already in it. An oldest-first Source starts at its oldest video.
     */
    @Test
    fun `Next unwatched on a card follows the Source's Direction`() {
        storeSource(HASSON, count = 3, direction = Direction.OLDEST_FIRST)
        storeSource(TED, count = 3)

        val byId = cards().associateBy { it.sourceId }

        assertEquals("$HASSON-1", byId.getValue(HASSON).nextUnwatched?.videoId)
        assertEquals("$TED-3", byId.getValue(TED).nextUnwatched?.videoId)
    }

    // ------------------------------------------------------------------ the order

    @Test
    fun `the Source played most recently is first on the rail`() {
        val hasson = storeSource(HASSON, count = 3)
        val ted = storeSource(TED, count = 3)
        watches.startPlay(hasson[0], positionSeconds = 30, completed = false)
        watches.startPlay(ted[0], positionSeconds = 30, completed = false)

        assertEquals(listOf(TED, HASSON), cards().map { it.sourceId })
    }

    /** The criterion in one test: play in the other Source, and the rail turns round. */
    @Test
    fun `playing in the other Source changes the order`() {
        val hasson = storeSource(HASSON, count = 3)
        val ted = storeSource(TED, count = 3)
        watches.startPlay(ted[0], positionSeconds = 30, completed = false)
        assertEquals(listOf(TED, HASSON), cards().map { it.sourceId })

        watches.startPlay(hasson[1], positionSeconds = 12, completed = false)

        assertEquals(listOf(HASSON, TED), cards().map { it.sourceId })
    }

    /** A manual `Mark as watched` is a declaration, not a play, and moves no card. */
    @Test
    fun `marking a video watched by hand does not move its Source`() {
        val hasson = storeSource(HASSON, count = 3)
        val ted = storeSource(TED, count = 3)
        watches.startPlay(ted[0], positionSeconds = 30, completed = false)

        watches.markJustThis(hasson[0], watched = true)

        assertEquals(listOf(TED, HASSON), cards().map { it.sourceId })
    }

    /** Spec section 7.1: the rail has one sort rule and a finished Source keeps its place. */
    @Test
    fun `a Source that becomes fully watched keeps its place`() {
        val hasson = storeSource(HASSON, count = 2)
        val ted = storeSource(TED, count = 2)
        watches.startPlay(hasson[0], positionSeconds = 30, completed = false)
        watches.startPlay(ted[0], positionSeconds = 30, completed = false)
        assertEquals(listOf(TED, HASSON), cards().map { it.sourceId })

        // Everything in TED watched, in one range. It is still the Source played most recently.
        watches.markRange(ted.first(), RangeSide.NEWER, watched = true)

        val cards = cards()
        assertEquals(listOf(TED, HASSON), cards.map { it.sourceId })
        assertEquals(0, cards.first().unwatchedCount)
        assertNull(cards.first().nextUnwatched)
    }

    // ------------------------------------------------ Mark everything unwatched

    @Test
    fun `it writes exactly one range entry, however many videos it covers`() {
        storeSource(HASSON, count = 400)
        val model = model()
        val before = watchStore.entries().size

        val count = runBlocking { model.markEverythingUnwatched(HASSON) }

        val written = watchStore.entries().drop(before)
        assertEquals(400, count)
        assertEquals(1, written.size)
        assertEquals(WatchKind.RANGE, written.single().kind)
    }

    /** The one entry carries the flag that exists for this action and for nothing else. */
    @Test
    fun `the one entry clears Last played and covers the whole Source`() {
        val videos = storeSource(HASSON, count = 4)
        val model = model()

        runBlocking { model.markEverythingUnwatched(HASSON) }

        val written = watchStore.entries().last().range()
        assertNotNull(written)
        assertTrue(written!!.clearsLastPlayed)
        assertFalse(written.watched)
        for (video in videos) {
            assertTrue(video.videoId, written.covers(HASSON, video.videoPublishedAt))
        }
    }

    /**
     * The whole point of the action, end to end. A Source the household had watched through
     * comes back with every video unwatched and with nowhere for `Jump to last played` to go.
     */
    @Test
    fun `afterwards the Source shows nothing watched and no Last played`() {
        val videos = storeSource(HASSON, count = 4)
        watches.startPlay(videos[0], positionSeconds = 30, completed = true)
        watches.markRange(videos.first(), RangeSide.NEWER, watched = true)
        val model = model()

        runBlocking { model.load() }
        val before = (model.state.value as HomeScreenState.Ready).cards.single()
        assertEquals(0, before.unwatchedCount)
        assertEquals(videos[0].videoId, before.lastPlayedVideoId)

        runBlocking { model.markEverythingUnwatched(HASSON) }

        val after = (model.state.value as HomeScreenState.Ready).cards.single()
        assertEquals(4, after.unwatchedCount)
        assertEquals(4, after.listedCount)
        assertNull(after.lastPlayedVideoId)
    }

    /** It reaches only the Source it was asked about. */
    @Test
    fun `it leaves every other Source alone`() {
        val hasson = storeSource(HASSON, count = 3)
        val ted = storeSource(TED, count = 3)
        watches.markRange(hasson.first(), RangeSide.NEWER, watched = true)
        watches.markRange(ted.first(), RangeSide.NEWER, watched = true)
        val model = model()

        runBlocking { model.markEverythingUnwatched(HASSON) }

        val byId = (model.state.value as HomeScreenState.Ready).cards.associateBy { it.sourceId }
        assertEquals(3, byId.getValue(HASSON).unwatchedCount)
        assertEquals(0, byId.getValue(TED).unwatchedCount)
    }

    /** A later play sets Last played again, which is what makes the clear a reset and not a ban. */
    @Test
    fun `a play after it gives the Source a Last played again`() {
        val videos = storeSource(HASSON, count = 3)
        val model = model()
        runBlocking { model.markEverythingUnwatched(HASSON) }

        watches.startPlay(videos[1], positionSeconds = 8, completed = false)
        runBlocking { model.load() }

        val card = (model.state.value as HomeScreenState.Ready).cards.single()
        assertEquals(videos[1].videoId, card.lastPlayedVideoId)
        // Two videos have no Watch, and the badge counts **one** of them. The Source runs
        // newest-first, so the played video sits at index 1 and is the high-water mark; the
        // newer video above it was passed over and no landing offers it any more. Build
        // ticket 30, and `NextUnwatchedLanding` is where the rule is written.
        assertEquals(1, card.unwatchedCount)
        assertEquals(videos[0].videoId, card.nextUnwatched?.videoId)
    }

    @Test
    fun `a Source with no dated video writes nothing at all`() {
        sources.saveSource(StoredSource(HASSON, null, NOW, Direction.NEWEST_FIRST))
        sources.replaceVideos(HASSON, listOf(testVideo("gone", day = null, sourceId = HASSON)))
        val model = model()
        val before = watchStore.entries().size

        val count = runBlocking { model.markEverythingUnwatched(HASSON) }

        assertNull(count)
        assertEquals(before, watchStore.entries().size)
        assertTrue(log.any { it.contains("Nothing was marked") })
    }
}

/**
 * The one card a rename changes, patched without reading the rail again. Build ticket 27.
 *
 * `HomeModel.renamed` exists only to close the window between the write landing and the read
 * that follows it, which on the household's 6,904 videos is over a second of the rail sitting
 * under the old name. `/code-review` found that window.
 */
class HomeRenamedCardTest {

    private val sources = InMemorySourceStore()
    private val watches = WatchLog(InMemoryWatchStore(), now = { NOW })
    private val model = HomeModel(sources, watches)

    private fun addSource(sourceId: String, title: String) {
        sources.saveSource(StoredSource(sourceId, title, NOW))
        sources.replaceVideos(sourceId, listOf(testVideo("$sourceId-1", day = 1, sourceId = sourceId)))
    }

    private fun cards() = (model.state.value as HomeScreenState.Ready).cards

    @Test
    fun `the renamed card takes the new name and no other card moves`() = runBlocking {
        addSource(TED, "TED")
        addSource(HASSON, "Hasson")
        model.load()
        val before = cards()

        model.renamed(TED, "TEDDY")

        val after = cards()
        // The rail's own order is untouched, so the cards are compared by Source id and never
        // by position: with no play written, the tie-break is the Source id and `HASSON` sorts
        // before `TED`.
        assertEquals(before.map { it.sourceId }, after.map { it.sourceId })
        val renamed = after.single { it.sourceId == TED }
        assertEquals("TEDDY", railCardName(renamed))
        // Only the one field of the one card. Everything else is the object it was.
        assertEquals(before.single { it.sourceId == TED }.copy(typedName = "TEDDY"), renamed)
        assertEquals(
            before.single { it.sourceId == HASSON },
            after.single { it.sourceId == HASSON },
        )
    }

    @Test
    fun `clearing puts the fetched name back on the card`() = runBlocking {
        addSource(TED, "TED")
        model.load()
        model.renamed(TED, "TEDDY")

        model.renamed(TED, null)

        assertEquals("TED", railCardName(cards().single()))
    }

    @Test
    fun `a Source the rail does not hold changes nothing`() = runBlocking {
        addSource(TED, "TED")
        model.load()
        val before = cards()

        model.renamed("never-added", "Talks")

        assertEquals(before, cards())
    }

    /** Nothing to patch before the first read, and the read will bring the name with it. */
    @Test
    fun `it does nothing while the rail is still being read`() {
        model.renamed(TED, "TEDDY")

        assertEquals(HomeScreenState.Starting, model.state.value)
    }
}
