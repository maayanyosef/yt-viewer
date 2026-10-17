package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ADDED_AT: Instant = Instant.parse("2026-09-20T10:00:00Z")
private val WALKED_AT: Instant = Instant.parse("2026-09-20T10:00:30Z")

/**
 * Adding a Source, removing it, and adding it back with the household's history intact.
 *
 * Spec section 6.6 is the rule these prove, and it is the one rule of ticket 11 that the
 * household would never forgive being wrong: the Watch log is the only thing in this app that
 * nothing can fetch again.
 */
class AddAndRemoveSourceTest {

    private val store = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore)

    private val curated = "PLcurated"
    private val uploads = "UUuploads"

    private fun video(sourceId: String, videoId: String, day: Int) = StoredVideo(
        videoId = videoId,
        sourceId = sourceId,
        videoPublishedAt = Instant.parse("2026-09-0${day}T12:00:00Z"),
        title = "Video $videoId",
        thumbnailUrl = null,
        durationSeconds = 600,
        liveBroadcastContent = LiveBroadcastContent.NONE,
        liveEndedAt = null,
        availability = Availability.AVAILABLE,
        isShort = false,
    )

    private fun addSource(sourceId: String, title: String, direction: Direction) {
        store.saveSource(
            StoredSource(
                sourceId = sourceId,
                title = title,
                lastWalkedAt = null,
                direction = direction,
                addedAt = ADDED_AT,
            ),
        )
        watches.sourceAdded(sourceId, title, direction)
    }

    // ---------------------------------------------------------------- adding

    @Test
    fun `a Source is on the rail before it has been walked, and says so`() {
        addSource(curated, "The curated one", Direction.NEWEST_FIRST)

        val stored = store.source(curated)
        assertNotNull(stored)
        assertNull("a Source that has not been walked has no walk time", stored!!.lastWalkedAt)

        val card = sourceCard(curated, stored, emptyList(), emptyMap(), null)
        assertFalse("the card must not read as walked", card.walked)
        assertEquals("The curated one", card.title)
    }

    @Test
    fun `finishing the walk moves only the walk time`() {
        addSource(curated, "The curated one", Direction.OLDEST_FIRST)
        // Turned on, which is away from the default, so the assertion below cannot pass
        // merely because a new Source starts off. Ticket 18.
        store.setAutoplayNext(curated, true)

        store.markWalked(curated, WALKED_AT)

        val stored = store.source(curated)!!
        assertEquals(WALKED_AT, stored.lastWalkedAt)
        assertEquals("The curated one", stored.title)
        assertEquals(Direction.OLDEST_FIRST, stored.direction)
        assertTrue(stored.autoplayNext)
        assertEquals(ADDED_AT, stored.addedAt)
    }

    @Test
    fun `adding a Source writes one sourceAdded entry that decides nothing on screen`() {
        addSource(curated, "The curated one", Direction.NEWEST_FIRST)

        val entry = watchStore.entries().single()
        assertEquals(WatchKind.SOURCE_ADDED, entry.kind)
        assertEquals(SourceChange(curated, "The curated one"), entry.sourceChange())
        // It names no video, so no card's green tick can move because of it.
        assertNull(entry.namedVideoId())
        assertTrue(deriveWatchStates(listOf(entry)).isEmpty())
    }

    // ---------------------------------------------------------------- removing

    @Test
    fun `removing a Source takes its row and its cached videos, and nothing else`() {
        addSource(curated, "The curated one", Direction.NEWEST_FIRST)
        addSource(uploads, "The uploads one", Direction.OLDEST_FIRST)
        store.replaceVideos(curated, listOf(video(curated, "a", 1)))
        store.replaceVideos(uploads, listOf(video(uploads, "b", 2)))
        // Away from the default, so the assertion below proves the row was left alone.
        store.setAutoplayNext(uploads, true)

        store.removeSource(curated)
        watches.sourceRemoved(curated)

        assertNull(store.source(curated))
        assertTrue(store.listedVideos(curated).isEmpty())

        // The other Source's own preferences are exactly where they were. Ticket 08 left this
        // as the thing to be careful of: two per-Source preference columns now sit on that row.
        val other = store.source(uploads)!!
        assertEquals(Direction.OLDEST_FIRST, other.direction)
        assertTrue(other.autoplayNext)
        assertEquals("The uploads one", other.title)
        assertEquals(listOf("b"), store.listedVideos(uploads).map { it.videoId })
    }

    @Test
    fun `removing a Source keeps every Watch and every range it earned`() {
        addSource(curated, "The curated one", Direction.NEWEST_FIRST)
        val watched = video(curated, "a", 1)
        val pivot = video(curated, "b", 2)
        store.replaceVideos(curated, listOf(watched, pivot))

        watches.markJustThis(watched, watched = true)
        watches.markRange(pivot, RangeSide.OLDER, watched = true)
        val before = watchStore.entries().size

        store.removeSource(curated)
        watches.sourceRemoved(curated)

        // The removal is one more entry appended. Nothing was taken away.
        assertEquals(before + 1, watchStore.entries().size)
        assertEquals(1, watchStore.entries().count { it.kind == WatchKind.PLAYED })
        assertEquals(1, watchStore.entries().count { it.kind == WatchKind.RANGE })
        assertEquals(WatchKind.SOURCE_REMOVED, watchStore.entries().last().kind)
    }

    @Test
    fun `adding the same playlist id back restores every Watch and every range`() {
        addSource(curated, "The curated one", Direction.NEWEST_FIRST)
        val first = video(curated, "a", 1)
        val second = video(curated, "b", 2)
        val third = video(curated, "c", 3)
        store.replaceVideos(curated, listOf(first, second, third))
        store.markWalked(curated, WALKED_AT)

        watches.markJustThis(third, watched = true)
        watches.markRange(second, RangeSide.OLDER, watched = true)
        val beforeRemoval = watches.watchStates(listOf(first, second, third))

        store.removeSource(curated)
        watches.sourceRemoved(curated)

        // The household adds the same playlist id again. The walk starts from scratch,
        // because the cache went with the Source.
        addSource(curated, "The curated one", Direction.NEWEST_FIRST)
        assertTrue("the videos must be walked again", store.listedVideos(curated).isEmpty())
        assertNull("and the Source reads as never walked", store.source(curated)!!.lastWalkedAt)

        store.replaceVideos(curated, listOf(first, second, third))
        store.markWalked(curated, WALKED_AT)

        val afterReAdding = watches.watchStates(listOf(first, second, third))
        assertEquals(beforeRemoval.keys, afterReAdding.keys)
        for (videoId in beforeRemoval.keys) {
            assertEquals(
                "$videoId lost its Watch across a removal",
                beforeRemoval[videoId]?.watched,
                afterReAdding[videoId]?.watched,
            )
        }
        // All three: the range covers the first two and the direct mark covers the third.
        assertTrue(afterReAdding.values.all { it.watched })
    }

    @Test
    fun `a removal names the Source and decides nothing on screen`() {
        watches.sourceRemoved(curated)

        val entry = watchStore.entries().single()
        assertEquals(WatchKind.SOURCE_REMOVED, entry.kind)
        assertEquals(SourceChange(curated, null), entry.sourceChange())
        assertNull(entry.namedVideoId())
        assertTrue(deriveWatchStates(listOf(entry)).isEmpty())
    }

    @Test
    fun `removing a Source that is not there changes nothing`() {
        addSource(uploads, "The uploads one", Direction.NEWEST_FIRST)
        store.removeSource("PLneverAdded")
        assertEquals(listOf(uploads), store.allSources().map { it.sourceId })
    }

    // ---------------------------------------------------------------- the rail's order

    @Test
    fun `a Source added today sits after every Source added before it`() {
        store.saveSource(StoredSource("PLb", "B", null, addedAt = ADDED_AT))
        store.saveSource(StoredSource("PLa", "A", null, addedAt = ADDED_AT.plusSeconds(60)))

        assertEquals(listOf("PLb", "PLa"), store.allSources().map { it.sourceId })
    }

    @Test
    fun `a Source stored before added-at existed keeps its place at the front`() {
        store.saveSource(StoredSource("PLold", "Old", WALKED_AT, addedAt = null))
        store.saveSource(StoredSource("PLaaa", "New", null, addedAt = ADDED_AT))

        assertEquals(listOf("PLold", "PLaaa"), store.allSources().map { it.sourceId })
    }

}
