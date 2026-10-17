package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The D-pad rules of the grid, tested on the JVM with no device.
 *
 * Every case here is one of ticket 03's acceptance criteria. The numbers are indexes into the
 * flat chronological list, which is the order the household moves through, not the visual rows.
 */
class GridNavigationTest {

    @Test
    fun `the grid is four columns, and one screen is eight videos`() {
        // Four, not five. The owner judged five too tight on the television on 2026-09-19,
        // against a Source he watches. See spec section 7.2.
        assertEquals(4, GRID_COLUMNS)
        assertEquals(2, SCREEN_ROWS)
        assertEquals(8, PAGE_STEP)
    }

    // --- Right and Left wrap across rows ------------------------------------------------

    @Test
    fun `Right at the end of a row moves to the first card of the next row`() {
        assertEquals(4, gridTarget(GridMove.RIGHT, from = 3, count = 30))
    }

    @Test
    fun `Right inside a row is left to Compose, which already moves one card along`() {
        assertNull(gridTarget(GridMove.RIGHT, from = 2, count = 30))
    }

    @Test
    fun `Left at the start of a row wraps back to the end of the row above`() {
        assertEquals(3, gridTarget(GridMove.LEFT, from = 4, count = 30))
    }

    @Test
    fun `Left inside a row is left to Compose`() {
        assertNull(gridTarget(GridMove.LEFT, from = 5, count = 30))
    }

    @Test
    fun `Right on the very last card does not move`() {
        assertNull(gridTarget(GridMove.RIGHT, from = 29, count = 30))
    }

    @Test
    fun `Left on the very first card does not move`() {
        assertNull(gridTarget(GridMove.LEFT, from = 0, count = 30))
    }

    // --- Up and Down move by a whole row ------------------------------------------------

    @Test
    fun `Down moves one row, which is four videos along`() {
        assertEquals(6, gridTarget(GridMove.DOWN, from = 2, count = 30))
    }

    @Test
    fun `Up moves one row back`() {
        assertEquals(2, gridTarget(GridMove.UP, from = 6, count = 30))
    }

    @Test
    fun `Up from the first row is not taken, because that is how the chips are reached`() {
        assertNull(gridTarget(GridMove.UP, from = 2, count = 30))
    }

    @Test
    fun `Down from the last row lands on the last video rather than doing nothing`() {
        // 27 videos, so the last row holds 24, 25 and 26. Down from 23 has no card under it.
        assertEquals(26, gridTarget(GridMove.DOWN, from = 23, count = 27))
    }

    @Test
    fun `Down on the last video does not move`() {
        assertNull(gridTarget(GridMove.DOWN, from = 26, count = 27))
    }

    // --- Holding Up jumps to the first card in the list -------------------------------------------

    @Test
    fun `holding Up jumps to the first card from deep in a long Source`() {
        assertEquals(0, gridTarget(GridMove.UP_HELD, from = 620, count = 5_813))
    }

    @Test
    fun `holding Up on the first card moves nowhere, because it is already there`() {
        assertNull(gridTarget(GridMove.UP_HELD, from = 0, count = 5_813))
    }

    @Test
    fun `the first delivery of a key is a tap, and every one after it is a hold`() {
        // Android delivers the initial press with a repeat count of 0. If the key is still
        // down after the autorepeat delay, it delivers it again with 1, then 2, and so on.
        assertFalse(isHeld(0))
        assertTrue(isHeld(1))
        assertTrue(isHeld(7))
    }

    @Test
    fun `the grid keeps a held Up it cannot act on, so a hold never runs on into the chips`() {
        assertTrue(gridKeepsKey(GridMove.UP_HELD, from = 0, count = 30))
    }

    @Test
    fun `the grid lets a tapped Up on the first row go, because that is how the chips are reached`() {
        assertFalse(gridKeepsKey(GridMove.UP, from = 0, count = 30))
        assertFalse(gridKeepsKey(GridMove.UP, from = 3, count = 30))
    }

    @Test
    fun `the grid keeps no key when the highlight is not in it`() {
        assertFalse(gridKeepsKey(GridMove.UP_HELD, from = -1, count = 30))
        assertFalse(gridKeepsKey(GridMove.UP_HELD, from = 30, count = 30))
        assertFalse(gridKeepsKey(GridMove.UP_HELD, from = 0, count = 0))
    }

    // --- What the television actually delivers -------------------------------------------

    /**
     * Holding a key, as the run of deliveries Android sends while it stays down.
     *
     * Returns where the highlight ended, or null if focus left the grid, which on the
     * television means it landed on the chips above.
     */
    private fun holdUp(from: Int, count: Int, deliveries: Int): Int? {
        var index = from
        for (repeat in 0 until deliveries) {
            val move = if (isHeld(repeat)) GridMove.UP_HELD else GridMove.UP
            val target = gridTarget(move, from = index, count = count)
            when {
                target != null -> index = target
                gridKeepsKey(move, index, count) -> Unit
                else -> return null
            }
        }
        return index
    }

    @Test
    fun `holding Up from a shallow row reaches the first card and stops there`() {
        // The case that failed on the television on 2026-09-19. The owner pressed Down three or
        // four times and then held Up, and focus landed on the Order chip instead of on the
        // first card. A hold must never carry focus past the grid.
        assertEquals(0, holdUp(from = 12, count = 588, deliveries = 8))
        assertEquals(0, holdUp(from = 8, count = 588, deliveries = 8))
        assertEquals(0, holdUp(from = 4, count = 588, deliveries = 8))
    }

    @Test
    fun `holding Up from the row below the first card stops on it and goes no further`() {
        // The first delivery is a tap and moves the one row. Every delivery after it is a
        // hold aimed at a card the highlight is already on, and the grid keeps those rather
        // than letting them push focus up into the chips.
        assertEquals(0, holdUp(from = 4, count = 588, deliveries = 8))
        assertEquals(0, holdUp(from = 7, count = 588, deliveries = 20))
    }

    @Test
    fun `holding Up while already on the first card reaches the chips`() {
        // There is nowhere further up to go in the grid, and the very first delivery of any
        // key is a tap, which is exactly the press that is meant to reach the chips.
        assertNull(holdUp(from = 0, count = 588, deliveries = 8))
    }

    @Test
    fun `holding Up from deep in a long Source still reaches the first card`() {
        assertEquals(0, holdUp(from = 500, count = 588, deliveries = 8))
    }

    @Test
    fun `one tap of Up on the first row leaves the grid, which is how the chips are reached`() {
        assertNull(holdUp(from = 2, count = 588, deliveries = 1))
    }

    @Test
    fun `a tap one row down moves a row and does not leave the grid`() {
        assertEquals(0, holdUp(from = 4, count = 588, deliveries = 1))
    }

    // --- The channel rocker pages a screen ----------------------------------------------

    @Test
    fun `the channel rocker pages eight videos, which is one screen`() {
        assertEquals(8, PAGE_STEP)
        assertEquals(28, gridTarget(GridMove.PAGE_DOWN, from = 20, count = 5_813))
        assertEquals(12, gridTarget(GridMove.PAGE_UP, from = 20, count = 5_813))
    }

    @Test
    fun `a page near the end still moves, clamped to the last video`() {
        assertEquals(29, gridTarget(GridMove.PAGE_DOWN, from = 25, count = 30))
        assertEquals(0, gridTarget(GridMove.PAGE_UP, from = 3, count = 30))
    }

    @Test
    fun `a page that cannot move at all is not taken`() {
        assertNull(gridTarget(GridMove.PAGE_UP, from = 0, count = 30))
        assertNull(gridTarget(GridMove.PAGE_DOWN, from = 29, count = 30))
    }

    // --- Edges --------------------------------------------------------------------------

    @Test
    fun `an empty grid takes no key at all`() {
        for (move in GridMove.entries) {
            assertNull(move.name, gridTarget(move, from = -1, count = 0))
        }
    }

    @Test
    fun `an index outside the list takes no key at all`() {
        for (move in GridMove.entries) {
            assertNull(move.name, gridTarget(move, from = 30, count = 30))
        }
    }

    // --- Scrolling lands on a row boundary ----------------------------------------------

    @Test
    fun `a scroll target is the first card of the row holding the video`() {
        assertEquals(0, rowStart(0))
        assertEquals(0, rowStart(3))
        assertEquals(4, rowStart(4))
        assertEquals(620, rowStart(623))
    }
}
