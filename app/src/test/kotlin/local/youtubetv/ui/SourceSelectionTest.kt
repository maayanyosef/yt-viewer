package local.youtubetv.ui

import java.time.ZoneId
import local.youtubetv.data.periodIndexFor
import local.youtubetv.data.periodsOf
import local.youtubetv.data.testVideo
import local.youtubetv.refresh.refocusIndexAfterRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What is selected on the Source screen, and where the grid goes when a chip asks it to move.
 *
 * Ticket 16. The chip row can only be reached by walking `Up` through the grid, so the card the
 * screen was standing on when focus reached a chip is whatever the household passed on the way
 * up, never a card they chose to keep. Standing on a chip therefore selects nothing at all.
 */
class SourceSelectionTest {

    private val videos = listOf(
        testVideo("newest", day = 9),
        testVideo("middle", day = 5),
        testVideo("oldest", day = 1),
    )

    @Test
    fun `nothing selected is not an index into the list`() {
        // Every reader of the selection goes through `selectedVideo`, so this one value is
        // what makes the header line say nothing at all.
        assertNull(selectedVideo(videos, NO_SELECTION))
    }

    @Test
    fun `the selected video is the card holding the highlight`() {
        assertEquals("middle", selectedVideo(videos, 1)?.videoId)
    }

    @Test
    fun `a landing that takes focus selects the card it landed on`() {
        assertEquals(1, selectionAfterLanding(index = 1, takeFocus = true))
    }

    @Test
    fun `a landing that leaves focus on the chip selects nothing`() {
        // The Direction flip. The grid moves and the highlight stays on the `Order` chip, so
        // no card has the highlight and no video is selected.
        assertEquals(NO_SELECTION, selectionAfterLanding(index = 0, takeFocus = false))
    }

    @Test
    fun `the first card of the grid is where a flip and a Down from the chips both go`() {
        assertEquals(0, firstCardIndex(3))
    }

    @Test
    fun `a Source listing no videos has no first card to go to`() {
        assertNull(firstCardIndex(0))
    }

    @Test
    fun `the Period chip opens the jump list on the first Period`() {
        // The chip holds the highlight, so nothing is selected, and the panel opens where the
        // list starts. Spec section 7.4, amended by ticket 16.
        assertEquals(0, periodIndexFor(threeYears, NO_SELECTION))
    }

    @Test
    fun `the hamburger from the grid still opens on the Period holding the focused card`() {
        // The other door of the same panel. A card really is focused there, so it opens on
        // that card's own Period and not on the first.
        assertEquals(1, periodIndexFor(threeYears, 1))
        assertEquals(2, periodIndexFor(threeYears, 2))
    }

    @Test
    fun `a refresh moves nothing while no video is selected`() {
        // A refresh is not something the household did, so it may not take the highlight off
        // the chip row. There is no card to re-anchor either.
        val after = listOf(testVideo("added", day = 10)) + videos

        assertNull(refocusIndexAfterRefresh(videos, after, NO_SELECTION))
    }

    /** One video in each of three calendar years, which is three Periods of one video each. */
    private val threeYears = periodsOf(
        listOf(
            testVideo("2028", day = 800),
            testVideo("2027", day = 400),
            testVideo("2026", day = 1),
        ),
        ZoneId.of("UTC"),
    )
}

/**
 * A chip reporting the highlight while a panel is drawn over the grid.
 *
 * The selection follows the highlight only when the highlight follows the household. With the
 * card menu or the Period jump list on screen it does not: Compose moves focus by itself the
 * moment the node holding it leaves composition, and the nearest focusable it finds is a chip
 * on the row above the panel.
 *
 * Ticket 19 is what made this reachable. The card menu's top level has four lines and a
 * submenu at most three, so choosing `Mark as unwatched`, drawn fourth, always destroys the
 * node holding the highlight. It was seen on the household television: the selection cleared
 * behind an open menu, and two presses of `BACK` later the household was standing on a chip
 * with the header line empty.
 */
class SelectionAfterChipFocusTest {

    @Test
    fun `a chip really pressed clears the selection`() {
        assertEquals(NO_SELECTION, selectionAfterChipFocus(selection = 42, overlayOpen = false))
    }

    @Test
    fun `a chip that took the highlight behind an open panel changes nothing`() {
        assertEquals(42, selectionAfterChipFocus(selection = 42, overlayOpen = true))
    }

    @Test
    fun `nothing selected stays nothing selected, either way`() {
        assertEquals(
            NO_SELECTION,
            selectionAfterChipFocus(selection = NO_SELECTION, overlayOpen = true),
        )
        assertEquals(
            NO_SELECTION,
            selectionAfterChipFocus(selection = NO_SELECTION, overlayOpen = false),
        )
    }
}

/** Build ticket 55: which key downs count as the household touching the Source screen. */
class HouseholdKeyTest {

    @Test
    fun `the keepalive's shift press and every other modifier do not count`() {
        // KEYCODE_SHIFT_LEFT, which tools/tv/keepalive.sh presses every 240 seconds.
        assertEquals(false, countsAsHouseholdKey(59))
        for (code in listOf(57, 58, 60, 63, 113, 114, 115, 116, 117, 118, 119, 143)) {
            assertEquals("key code $code", false, countsAsHouseholdKey(code))
        }
    }

    @Test
    fun `every key on the household remote counts`() {
        // DPAD up, down, left, right, centre, BACK, MENU, channel up and down, page up and down.
        for (code in listOf(19, 20, 21, 22, 23, 4, 82, 166, 167, 92, 93, 66)) {
            assertEquals("key code $code", true, countsAsHouseholdKey(code))
        }
    }
}
