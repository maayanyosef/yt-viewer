package local.youtubetv.ui

import local.youtubetv.data.Availability
import local.youtubetv.data.LiveBroadcastContent
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three lines of a submenu, and the counts they state.
 *
 * Two rules are worth more than the rest here, and both are spec section 7.3's.
 *
 * - The wording is **older** and **newer**, never "before" and "after". Before and after
 *   invert when Direction is flipped.
 * - Every count is **over listed videos only**. A Short, an unavailable video and an upcoming
 *   premiere are all hidden everywhere in this app and are counted nowhere, even though a
 *   range still covers them by date.
 */
class CardMenuSubLinesTest {

    /** Day 1 to day 5 of one Source, oldest first. */
    private val videos = (1..5).map { day -> testVideo("v$day", day = day) }

    private fun labels(pivotIndex: Int, listed: List<local.youtubetv.data.StoredVideo> = videos) =
        cardMenuSubLines(videos[pivotIndex], listed).map { it.label }

    @Test
    fun `a submenu offers three lines`() {
        assertEquals(
            listOf(MarkScope.JUST_THIS, MarkScope.ALL_OLDER, MarkScope.ALL_NEWER),
            cardMenuSubLines(videos[2], videos).map { it.scope },
        )
    }

    @Test
    fun `the older count holds the pivot and everything older`() {
        assertEquals("This and all older (3 videos)", labels(pivotIndex = 2)[1])
    }

    @Test
    fun `the newer count holds the pivot and everything newer`() {
        assertEquals("This and all newer (3 videos)", labels(pivotIndex = 2)[2])
    }

    @Test
    fun `the oldest video has one older and all of them newer`() {
        assertEquals("This and all older (1 video)", labels(pivotIndex = 0)[1])
        assertEquals("This and all newer (5 videos)", labels(pivotIndex = 0)[2])
    }

    @Test
    fun `the first line always marks exactly one video`() {
        assertEquals("Just this (1 video)", labels(pivotIndex = 3)[0])
        assertEquals(1, cardMenuSubLines(videos[3], videos).first().count)
    }

    @Test
    fun `a hidden Short is in no count`() {
        val withShorts = videos + testVideo("short", day = 2, isShort = true)

        assertEquals("This and all older (3 videos)", labels(pivotIndex = 2, listed = withShorts)[1])
    }

    /**
     * The same rule, for the two kinds ticket 23 unlisted.
     *
     * An upcoming premiere is the case that bites: it carries a publish date, so a range really
     * does cover it, and only the count leaves it out. That is exactly how a hidden Short is
     * already treated. See spec sections 4.3 and 4.4.
     */
    @Test
    fun `an unavailable video and an upcoming premiere are in no count`() {
        val hidden = videos +
            testVideo("gone", day = 2, availability = Availability.UNAVAILABLE) +
            testVideo("soon", day = 2, liveBroadcastContent = LiveBroadcastContent.UPCOMING)

        assertEquals("This and all older (3 videos)", labels(pivotIndex = 2, listed = hidden)[1])
        assertEquals("This and all newer (3 videos)", labels(pivotIndex = 2, listed = hidden)[2])
    }

    @Test
    fun `an undated video is in no count, because no side can hold it`() {
        val withGone = videos + testVideo("gone", day = null)

        assertEquals("This and all older (5 videos)", labels(pivotIndex = 4, listed = withGone)[1])
    }

    @Test
    fun `a video with no publish date is offered only Just this`() {
        val undated = testVideo("gone", day = null)

        assertEquals(
            listOf(MarkScope.JUST_THIS),
            cardMenuSubLines(undated, videos + undated).map { it.scope },
        )
    }

    @Test
    fun `no line says before or after`() {
        for (line in cardMenuSubLines(videos[2], videos)) {
            assertTrue(line.label, !line.label.contains("before", ignoreCase = true))
            assertTrue(line.label, !line.label.contains("after", ignoreCase = true))
        }
    }

    @Test
    fun `a Bulk mark line says how many videos it will change`() {
        assertEquals("Marked 3 videos as watched.", markedRangeLine(3, watched = true))
        assertEquals("Marked 1 video as unwatched.", markedRangeLine(1, watched = false))
    }

    @Test
    fun `a large count carries no thousands separator, like every other count on the screen`() {
        val many = (1..5_303).map { day -> testVideo("v$day", day = day) }

        assertEquals(
            "This and all older (5303 videos)",
            cardMenuSubLines(many.last(), many)[1].label,
        )
    }
}

/**
 * The one submenu line that does nothing, and why it is dim rather than gone.
 *
 * On any one card exactly one `Just this` changes nothing: `Mark as watched` on a card that is
 * already watched, and `Mark as unwatched` on one that is not. The owner chose dim over hiding it
 * on 2026-09-20, so the place stays learnable and the card's own state is visible from the
 * menu. See ticket 19 and spec section 7.3.
 *
 * The two range lines are never dim. The range scopes are independent of the focused video's
 * own state, and that is the whole reason both marking lines stay on the top level.
 */
class CardMenuDeadLineTest {

    private val videos = (1..5).map { day -> testVideo("v$day", day = day) }

    private fun enabled(markWatched: Boolean, pivotWatched: Boolean) =
        cardMenuSubLinesEnabled(
            lines = cardMenuSubLines(videos[2], videos),
            markWatched = markWatched,
            pivotWatched = pivotWatched,
        )

    @Test
    fun `Mark as watched then Just this is dim on a watched card`() {
        assertEquals(
            listOf(false, true, true),
            enabled(markWatched = true, pivotWatched = true),
        )
    }

    @Test
    fun `Mark as unwatched then Just this is dim on an unwatched card`() {
        assertEquals(
            listOf(false, true, true),
            enabled(markWatched = false, pivotWatched = false),
        )
    }

    @Test
    fun `Mark as watched is wholly live on an unwatched card`() {
        assertEquals(
            listOf(true, true, true),
            enabled(markWatched = true, pivotWatched = false),
        )
    }

    @Test
    fun `Mark as unwatched is wholly live on a watched card`() {
        assertEquals(
            listOf(true, true, true),
            enabled(markWatched = false, pivotWatched = true),
        )
    }

    @Test
    fun `a submenu that offers only Just this can be wholly dim`() {
        // A private or deleted video has no publish date, so it has no range to pivot on and
        // gets one line. That line can be the dead one, and then the submenu has nothing to
        // do. It is still drawn, so the household sees why, and `BACK` is the way out.
        val undated = testVideo("gone", day = null)

        assertEquals(
            listOf(false),
            cardMenuSubLinesEnabled(
                lines = cardMenuSubLines(undated, videos + undated),
                markWatched = true,
                pivotWatched = true,
            ),
        )
    }

    @Test
    fun `during a first walk every unwatched card has a wholly dim Mark as unwatched`() {
        // Spec section 6.5 holds the Bulk mark back until the walk ends, so `Just this` is the
        // only line either submenu has. On an unwatched card that one line is the dead one.
        // `/code-review` caught this being written up as the rare case. It is not: it is every
        // card of a Source that is still filling.
        val lines = cardMenuSubLines(videos[2], videos, walkFinished = false)

        assertEquals(1, lines.size)
        assertEquals(
            listOf(false),
            cardMenuSubLinesEnabled(lines, markWatched = false, pivotWatched = false),
        )
        assertEquals(
            listOf(true),
            cardMenuSubLinesEnabled(lines, markWatched = true, pivotWatched = false),
        )
    }

    @Test
    fun `a wholly dim submenu has no line for the highlight to open on`() {
        val lines = cardMenuSubLines(videos[2], videos, walkFinished = false)
        val live = cardMenuSubLinesEnabled(lines, markWatched = false, pivotWatched = false)

        // Null is what makes the menu keep every key but `BACK`, because nothing can report
        // focus and `cardMenuHasFocus` therefore stays false. See `CardMenuFocusGuardTest`.
        assertNull(firstLiveLine(live))
    }
}
