package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which line `Up` and `Down` move to inside an overlay panel.
 *
 * This rule exists because Compose's own focus search got it wrong on the household
 * television. From the last line of a rail card's six-line menu, one `Up` left the menu with no
 * highlight at all and scrolled the rail under the scrim, because a rail card's bottom edge was
 * nearer to that line than the line above it was. Build ticket 31.
 *
 * So the rule is spelt out here, over nothing but the dim flags and the line the highlight is
 * on, and the panel wires the answer with a `FocusRequester`.
 */
class MenuNeighbourTest {

    /** The rail card's six-line menu on a Source that has been walked: every line live. */
    private val sixLive = List(6) { true }

    // ---- the walk, on a column with no dim line -------------------------------------------

    @Test
    fun `Up from the last line of the six-line menu goes to the line above it`() {
        // The exact press that was broken on the television: the highlight was on
        // `Mark everything unwatched`, drawn fifth, and `Up` means `Remove this Source`.
        assertEquals(4, menuNeighbour(sixLive, line = 5, move = MenuMove.UP))
    }

    @Test
    fun `Up walks all the way back to the first line, one line per press`() {
        val walk = generateSequence(5) { menuNeighbour(sixLive, it, MenuMove.UP) }.toList()

        assertEquals(listOf(5, 4, 3, 2, 1, 0), walk)
    }

    @Test
    fun `Down walks all the way to the last line, one line per press`() {
        val walk = generateSequence(0) { menuNeighbour(sixLive, it, MenuMove.DOWN) }.toList()

        assertEquals(listOf(0, 1, 2, 3, 4, 5), walk)
    }

    @Test
    fun `Up on the first line moves nowhere`() {
        assertNull(menuNeighbour(sixLive, line = 0, move = MenuMove.UP))
    }

    @Test
    fun `Down on the last line moves nowhere`() {
        assertNull(menuNeighbour(sixLive, line = 5, move = MenuMove.DOWN))
    }

    // ---- sideways -------------------------------------------------------------------------

    @Test
    fun `Left, Right and the channel rocker move nowhere, because the panel is one column`() {
        assertNull(menuNeighbour(sixLive, line = 0, move = MenuMove.SIDEWAYS))
        assertNull(menuNeighbour(sixLive, line = 3, move = MenuMove.SIDEWAYS))
        assertNull(menuNeighbour(sixLive, line = 5, move = MenuMove.SIDEWAYS))
    }

    // ---- dim lines in the middle ------------------------------------------------------------

    @Test
    fun `a dim line in the middle is walked past in both directions`() {
        val live = listOf(true, true, true, false, true, true)

        assertEquals(2, menuNeighbour(live, line = 4, move = MenuMove.UP))
        assertEquals(4, menuNeighbour(live, line = 2, move = MenuMove.DOWN))
    }

    @Test
    fun `two dim lines side by side are both walked past in one press`() {
        val live = listOf(true, false, false, true)

        assertEquals(0, menuNeighbour(live, line = 3, move = MenuMove.UP))
        assertEquals(3, menuNeighbour(live, line = 0, move = MenuMove.DOWN))
    }

    // ---- a dim line at an end ---------------------------------------------------------------

    @Test
    fun `a dim first line shortens the column, so Up from the line below it moves nowhere`() {
        // `Open at Last played` is dim on a Source with nowhere to land. Spec section 7.1.
        val live = listOf(true, false, true, true, true, true)

        assertEquals(0, menuNeighbour(live, line = 2, move = MenuMove.UP))
        assertNull(menuNeighbour(live, line = 0, move = MenuMove.UP))
    }

    @Test
    fun `a dim line drawn first, with nothing live above it, is not an end the walk can reach`() {
        val live = listOf(false, true, true)

        assertNull(menuNeighbour(live, line = 1, move = MenuMove.UP))
        assertEquals(1, menuNeighbour(live, line = 0, move = MenuMove.DOWN))
    }

    @Test
    fun `a dim last drawn line shortens the column, so Down from the line above moves nowhere`() {
        // The menu of a Source that has never been walked. `Refresh this Source` is dim
        // because there is nothing to refresh yet, and `Mark everything unwatched` is dim
        // because a Bulk mark needs the whole list and there is not one. The second of those
        // is the **last** drawn line, which is the case that used to leave `Down` unguarded.
        val live = listOf(true, false, true, false, true, false)

        assertNull(menuNeighbour(live, line = 4, move = MenuMove.DOWN))
        assertEquals(2, menuNeighbour(live, line = 4, move = MenuMove.UP))
    }

    @Test
    fun `the menu of a Source that has never been walked walks its three live lines`() {
        val live = listOf(true, false, true, false, true, false)

        val down = generateSequence(0) { menuNeighbour(live, it, MenuMove.DOWN) }.toList()
        val up = generateSequence(4) { menuNeighbour(live, it, MenuMove.UP) }.toList()

        assertEquals(listOf(0, 2, 4), down)
        assertEquals(listOf(4, 2, 0), up)
    }

    // ---- the two-line confirmations ---------------------------------------------------------

    @Test
    fun `both two-line confirmations walk between their two lines and no further`() {
        val live = listOf(true, true)

        assertEquals(1, menuNeighbour(live, line = 0, move = MenuMove.DOWN))
        assertEquals(0, menuNeighbour(live, line = 1, move = MenuMove.UP))
        assertNull(menuNeighbour(live, line = 0, move = MenuMove.UP))
        assertNull(menuNeighbour(live, line = 1, move = MenuMove.DOWN))
    }

    // ---- nothing to walk --------------------------------------------------------------------

    @Test
    fun `a wholly dim panel has no neighbour anywhere, so BACK is the only way out`() {
        val live = listOf(false, false, false)

        assertNull(menuNeighbour(live, line = 0, move = MenuMove.UP))
        assertNull(menuNeighbour(live, line = 0, move = MenuMove.DOWN))
        assertNull(menuNeighbour(live, line = 2, move = MenuMove.UP))
        assertNull(menuNeighbour(live, line = 2, move = MenuMove.DOWN))
    }

    @Test
    fun `an empty panel has no neighbour and does not throw`() {
        assertNull(menuNeighbour(emptyList(), line = 0, move = MenuMove.UP))
        assertNull(menuNeighbour(emptyList(), line = 0, move = MenuMove.DOWN))
    }

    @Test
    fun `a line outside the panel has no neighbour and does not throw`() {
        // Nothing should ever ask, but a panel whose lines changed under a stale index must
        // not take the screen down with an index out of bounds.
        assertNull(menuNeighbour(sixLive, line = 99, move = MenuMove.DOWN))
        assertEquals(5, menuNeighbour(sixLive, line = 99, move = MenuMove.UP))
        assertNull(menuNeighbour(sixLive, line = -3, move = MenuMove.UP))
        assertEquals(0, menuNeighbour(sixLive, line = -3, move = MenuMove.DOWN))
    }
}
