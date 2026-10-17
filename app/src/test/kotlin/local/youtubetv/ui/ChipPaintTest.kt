package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a chip of the Source screen's chip row is painted. Spec section 7.2, tickets 18 and 26.
 *
 * The one chip that carries a state is `Autoplay`. It says that state twice: in its word, which
 * is `autoplayChipLabel`, and in its fill, which is here. It was the fill alone from ticket 18
 * until ticket 26, because the owner could not tell the two fills apart from the sofa.
 *
 * **The fill rule below is exactly ticket 18's and ticket 26 did not touch it.** These are the
 * tests that keep the fill from being dropped now that the word carries the state as well.
 */
class ChipPaintTest {

    @Test
    fun `autoplay on carries the accent fill, and off the quiet one`() {
        // Ticket 18 built this fill. Ticket 26 kept it and added the words back on top, so
        // the chip now carries both signals. The owner asked for exactly that on 2026-09-20:
        // "put the word back, but when On is chosen, also keep the highlight fill it has now."
        assertEquals(ChipFill.ACCENT, autoplayChipFill(autoplayNext = true))
        assertEquals(ChipFill.QUIET, autoplayChipFill(autoplayNext = false))
    }

    @Test
    fun `an accent chip keeps its fill while it holds the highlight`() {
        // The household can only press the chip while standing on it, so a focus treatment
        // that painted over the fill would hide the state at the one moment it is being
        // changed, and the press would look like it did nothing.
        val paint = chipPaint(ChipFill.ACCENT, focused = true, enabled = true)
        assertEquals(ChipBody.ACCENT, paint.body)
        // The ring is what says focus on a chip whose body is already spoken for.
        assertEquals(ChipRing.FOCUS_FILL, paint.ring)
        assertEquals(ChipLabel.ON_FOCUS, paint.label)
    }

    @Test
    fun `an accent chip that is not focused carries no ring`() {
        val paint = chipPaint(ChipFill.ACCENT, focused = false, enabled = true)
        assertEquals(ChipBody.ACCENT, paint.body)
        assertEquals(ChipRing.NONE, paint.ring)
        assertEquals(ChipLabel.ON_FOCUS, paint.label)
    }

    @Test
    fun `a quiet chip fills with the focus ink when it takes the highlight`() {
        // Unchanged since the chip row was built: a focused chip is filled, not merely
        // outlined, because an outline alone was hard to find from the sofa.
        val paint = chipPaint(ChipFill.QUIET, focused = true, enabled = true)
        assertEquals(ChipBody.FOCUS_FILL, paint.body)
        assertEquals(ChipRing.ACCENT, paint.ring)
        assertEquals(ChipLabel.ON_FOCUS, paint.label)
    }

    @Test
    fun `a quiet chip at rest is the raised surface and nothing else`() {
        val paint = chipPaint(ChipFill.QUIET, focused = false, enabled = true)
        assertEquals(ChipBody.SURFACE_RAISED, paint.body)
        assertEquals(ChipRing.NONE, paint.ring)
        assertEquals(ChipLabel.ON_SURFACE, paint.label)
    }

    @Test
    fun `a chip that cannot act is drawn in the faintest ink`() {
        // `Jump to last played` is the only chip that is ever disabled. It is not focusable,
        // so it can never be focused, and it is still drawn so its place on the row can be
        // learnt.
        val paint = chipPaint(ChipFill.QUIET, focused = false, enabled = false)
        assertEquals(ChipBody.SURFACE_RAISED, paint.body)
        assertEquals(ChipRing.NONE, paint.ring)
        assertEquals(ChipLabel.ON_SURFACE_FAINT, paint.label)
    }
}
