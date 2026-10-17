package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOON: Instant = Instant.parse("2026-09-20T12:00:00Z")

/**
 * A range reaches only its own Source's videos, however the videos are handed in.
 *
 * Build ticket 22 measured the home rail on the household television and found that most of
 * the time it spent applying ranges went on asking every range whether it belonged to the
 * video's Source: 40,617 of those questions for 6,051 videos and 14 ranges. `LogReading` now
 * keys its ranges by Source id, so the Source is a map lookup and only the date is asked per
 * video, and `applyRanges` holds on to one Source's group while it walks that Source's videos.
 *
 * **These are the cases that change would break if it were wrong**, and none of them is about
 * speed. They are about a video getting the answer of a range that belongs somewhere else, or
 * missing the answer of the range that belongs to it.
 */
class RangesBySourceTest {

    private val store = InMemoryWatchStore()
    private var clock = NOON
    private val log = WatchLog(store, now = { clock })

    private fun tick(seconds: Long) {
        clock = clock.plusSeconds(seconds)
    }

    /** Day 1 to day 5 of Source `UUx`, oldest first. */
    private val ofX = (1..5).map { day -> testVideo("x$day", day = day, sourceId = "UUx") }

    /** Day 1 to day 5 of Source `UUy`, oldest first. */
    private val ofY = (1..5).map { day -> testVideo("y$day", day = day, sourceId = "UUy") }

    private fun watched(videos: List<StoredVideo>): Set<String> =
        log.watchStates(videos).values
            .filter { it.watched }
            .mapTo(mutableSetOf()) { it.videoId }

    @Test
    fun `a range in one Source leaves another Source's videos alone`() {
        log.markRange(ofX[4], side = RangeSide.OLDER, watched = true)

        assertEquals(setOf("x1", "x2", "x3", "x4", "x5"), watched(ofX + ofY))
    }

    @Test
    fun `each Source's own range is applied when both are handed in together`() {
        // `UUx` is marked watched up to day 5, `UUy` only up to day 2.
        log.markRange(ofX[4], side = RangeSide.OLDER, watched = true)
        tick(60)
        log.markRange(ofY[1], side = RangeSide.OLDER, watched = true)

        assertEquals(
            setOf("x1", "x2", "x3", "x4", "x5", "y1", "y2"),
            watched(ofX + ofY),
        )
    }

    @Test
    fun `the Sources may be interleaved in the list handed in`() {
        // `applyRanges` keeps the group of the Source it saw last, so a list that changes
        // Source on every video is the case that proves it looks the group up again.
        log.markRange(ofX[4], side = RangeSide.OLDER, watched = true)
        tick(60)
        log.markRange(ofY[1], side = RangeSide.OLDER, watched = true)

        val interleaved = ofX.zip(ofY).flatMap { (x, y) -> listOf(x, y) }

        assertEquals(
            setOf("x1", "x2", "x3", "x4", "x5", "y1", "y2"),
            watched(interleaved),
        )
    }

    @Test
    fun `a Source with no range of its own is untouched`() {
        log.markRange(ofX[4], side = RangeSide.OLDER, watched = true)

        assertEquals(emptySet<String>(), watched(ofY))
        // And it is not merely absent from the watched set: the reading says nothing about it
        // at all, which is what a Source no entry names has always looked like.
        assertNull(log.watchStates(ofY)["y1"])
    }

    @Test
    fun `the newest of two overlapping ranges in one Source still wins`() {
        log.markRange(ofX[4], side = RangeSide.OLDER, watched = true)
        tick(60)
        log.markRange(ofX[2], side = RangeSide.OLDER, watched = false)

        // Day 1 to day 3 were unmarked by the newer range. Day 4 and day 5 keep the older one.
        assertEquals(setOf("x4", "x5"), watched(ofX))
    }

    @Test
    fun `a range in another Source cannot decide a video id both Sources hold`() {
        // The same video id, listed in two Sources. Only `UUy`'s copy is covered.
        val inX = testVideo("shared", day = 1, sourceId = "UUx")
        val inY = testVideo("shared", day = 1, sourceId = "UUy")

        log.markRange(ofY[4], side = RangeSide.OLDER, watched = true)

        assertNull(log.watchStates(listOf(inX))["shared"])
        assertTrue(log.watchStates(listOf(inY)).getValue("shared").watched)
    }

    @Test
    fun `coversDate is the date half of covers, and covers is the Source test on top of it`() {
        val range = Range(
            sourceId = "UUx",
            pivotPublishedAt = testDay(3),
            madeAt = testDay(100),
            side = RangeSide.OLDER,
            watched = true,
            clearsLastPlayed = false,
        )

        assertTrue(range.coversDate(testDay(2)))
        assertTrue(range.coversDate(testDay(3)))
        assertFalse(range.coversDate(testDay(4)))
        // A video with no publish date is covered by nothing, and that rule is in the date
        // half rather than in the Source test.
        assertFalse(range.coversDate(null))

        // `covers` adds only the Source test.
        assertTrue(range.covers("UUx", testDay(2)))
        assertFalse(range.covers("UUy", testDay(2)))
    }
}
