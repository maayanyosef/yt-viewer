package local.youtubetv.ui

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a screenshot would only catch on the one card that happens to break them.
 *
 * The two badges are drawn into the same box, one on top of the other, so the only thing
 * keeping them apart is the corner each one is given.
 *
 * The watched marker left that box on 2026-09-20 by ticket 15 and now sits on the card's date
 * line, so its old rule, that it stands in neither badge's corner, has stopped being true and
 * is gone. Its rules are now about that line: it must not be the thing that sets the line's
 * height, and its tick must land inside its own green square.
 */
class CardOverlaysTest {

    @Test
    fun `the state badge does not stand in the same corner as the duration badge`() {
        assertNotEquals(CardOverlays.stateBadgeCorner, CardOverlays.durationBadgeCorner)
    }

    @Test
    fun `the watched marker is smaller than the date line it sits on`() {
        assertTrue(
            "The marker has to be a real size.",
            CardOverlays.WATCHED_MARKER_LINE_FRACTION > 0f,
        )
        // The marker is sized from the date line's own height, so this one number is the whole
        // rule. Below 1 the line is still as tall as its text and no grid row can grow; at or
        // above 1 the marker sets the line's height, and every row in the grid gets taller the
        // moment one card in it is watched.
        assertTrue(
            "The marker is ${CardOverlays.WATCHED_MARKER_LINE_FRACTION} of the date line's " +
                "height, so the marker, not the date, would set that line's height.",
            CardOverlays.WATCHED_MARKER_LINE_FRACTION < 1f,
        )
    }

    @Test
    fun `the view count's eye is smaller than the date line it sits on`() {
        // Build ticket 59. The same rule as the watched marker's, held the same way: the eye
        // is sized from the date line's own height, so below 1 the date still sets that line's
        // height, and a card with a count is exactly as tall as a card without one.
        assertTrue(
            "The eye has to be a real size.",
            CardOverlays.VIEW_COUNT_EYE_LINE_FRACTION > 0f,
        )
        assertTrue(
            "The eye is ${CardOverlays.VIEW_COUNT_EYE_LINE_FRACTION} of the date line's " +
                "height, so the eye, not the date, would set that line's height.",
            CardOverlays.VIEW_COUNT_EYE_LINE_FRACTION < 1f,
        )
    }

    @Test
    fun `the whole eye, lid and pupil, stays inside its own box`() {
        // Everything below is a fraction of the eye's height, so the rule holds at any font
        // scale. `ViewCountEye` draws on a Canvas that nothing clips, so a part that does not
        // fit paints over the date or the count beside it.
        val stroke = CardOverlays.VIEW_COUNT_EYE_STROKE_FRACTION
        val lidEnd = CardOverlays.VIEW_COUNT_EYE_LID_END_FRACTION
        val pupil = CardOverlays.VIEW_COUNT_EYE_PUPIL_FRACTION
        val width = CardOverlays.VIEW_COUNT_EYE_ASPECT

        assertTrue("The lid has to be a real stroke.", stroke > 0f)
        assertTrue("The pupil has to be a real size.", pupil > 0f)
        // The lid's round caps reach half a stroke past its two ends.
        assertTrue("The lid's ends fall below the box.", lidEnd + stroke / 2f <= 1f)
        // Its top is drawn half a stroke below the top edge, so the stroke just touches it.
        assertTrue("The lid's top is not below its ends, so it is not a curve.", stroke / 2f < lidEnd)
        // The pupil sits on the bottom edge, so it fits when its diameter fits the height.
        assertTrue("The pupil is taller than the box.", 2f * pupil <= 1f)
        assertTrue("The pupil is wider than the box.", 2f * pupil <= width)
        // The pupil sits under the lid, as in design 3, and does not cross it: its top is
        // below the lid's top at the centre.
        assertTrue("The pupil reaches over the lid.", 1f - 2f * pupil > stroke)
    }

    @Test
    fun `the whole tick, round caps and all, stays inside the square`() {
        assertTrue(
            "The tick's box has to be a real size.",
            CardOverlays.WATCHED_MARKER_GLYPH_FRACTION > 0f,
        )
        assertTrue(
            "The tick's stroke has to be a real width.",
            CardOverlays.WATCHED_TICK_STROKE_FRACTION > 0f,
        )

        // Everything below is a fraction of the green square's side, so the side itself drops
        // out and the rule holds at any size. That matters more than it used to: the side is
        // no longer a fixed dp value but a fraction of the date line's height, so it changes
        // with the television's font scale.
        //
        // `WatchedMarker` draws the square as a shaped background, not a clip, so nothing
        // trims the tick. A tick that does not fit paints onto the card's date line instead.
        //
        // The square is measured per axis, not by its diagonal. A stroked path inside a
        // centred box of side `glyphBox` reaches `glyphBox / 2` from the centre along each
        // axis, plus half a stroke width for the round cap. The corner of the glyph's box is
        // farther away than that, but so is the corner of the square it sits in, and both
        // corners lie on the same diagonal, so the axis is where the two edges come closest.
        val glyphBox = CardOverlays.WATCHED_MARKER_GLYPH_FRACTION
        val halfGlyphBox = glyphBox / 2f
        val roundCap = glyphBox * CardOverlays.WATCHED_TICK_STROKE_FRACTION / 2f
        val halfSquare = 0.5f

        assertTrue(
            "The tick reaches ${halfGlyphBox + roundCap} of the square's side from the centre, " +
                "and the square's edge is at $halfSquare. Lower " +
                "WATCHED_MARKER_GLYPH_FRACTION or WATCHED_TICK_STROKE_FRACTION, or give " +
                "WatchedMarker a clip back.",
            halfGlyphBox + roundCap < halfSquare,
        )
    }
}
