package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a Source screen panel hands the highlight to on its way out.
 *
 * Build ticket 39. The rule is [panelHandBack], and it has to answer for three doors that mean
 * three different things: the card menu's `BACK`, the Period jump list's `BACK` from a card,
 * and the Period jump list's `BACK` from the `Period` chip. Two copies of it could hand one
 * press to two different places.
 *
 * **It is not the home rail's rule.** `railHighlightTarget` falls back to the first card of the
 * rail, because a rail card is named by a Source id and a Source can leave the rail. A grid
 * card is named by an index, a hand-back never scrolls, and card 0 is off the screen whenever
 * the grid is scrolled, so there is no card to fall back to and this answers null instead.
 */
class PanelHandBackTest {

    /** What a `LazyVerticalGrid` holds while the grid is scrolled to the third row. */
    private val placed = setOf(8, 9, 10, 11, 12, 13, 14, 15)

    @Test
    fun `the card the panel is about takes the highlight`() {
        assertEquals(HandBack.Card(11), panelHandBack(returnTo = 11, placed = placed))
    }

    @Test
    fun `nothing selected means the Period chip`() {
        // The one door that ends on the chip row: the panel was opened from the `Period` chip,
        // so that is where the household was standing before it.
        assertEquals(HandBack.PeriodChip, panelHandBack(returnTo = NO_SELECTION, placed = placed))
    }

    @Test
    fun `the chip answer does not depend on what the grid is holding`() {
        assertEquals(
            HandBack.PeriodChip,
            panelHandBack(returnTo = NO_SELECTION, placed = emptySet()),
        )
    }

    @Test
    fun `a card the lazy grid is not holding is left to the ladder`() {
        // Naming a requester no node is attached to throws, and a hand-back may not scroll to
        // make one, so this is the case only the retry ladder can serve.
        assertNull(panelHandBack(returnTo = 3, placed = placed))
    }

    @Test
    fun `a grid holding nothing at all is left to the ladder`() {
        assertNull(panelHandBack(returnTo = 11, placed = emptySet()))
    }

    @Test
    fun `the first card is never a fallback`() {
        // The home rail's rule falls back to the first card. This one must not: card 0 is not
        // composed while the grid is scrolled, and it is not a card the household chose.
        assertNull(panelHandBack(returnTo = 40, placed = setOf(0, 1, 2, 3)))
    }

    @Test
    fun `the card the grid is holding at index zero is still the right answer for index zero`() {
        assertEquals(HandBack.Card(0), panelHandBack(returnTo = 0, placed = setOf(0, 1, 2, 3)))
    }

    @Test
    fun `an index below nothing-selected is not the chip row`() {
        // Only `NO_SELECTION` means the chip row. Any other negative is a value nobody meant,
        // and the ladder refuses it in exactly the same way.
        assertNull(panelHandBack(returnTo = -2, placed = placed))
    }
}
