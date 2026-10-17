package local.youtubetv.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How wide a home rail card is, and how many of them reach the household's screen.
 *
 * This is a screen rule that a screenshot proves one screen at a time, so the arithmetic behind
 * it is pinned here instead. The same shape `CardOverlaysTest` uses for the video card's two
 * badges: the numbers live in an object, and the rules over them are JVM tests.
 *
 * **The numbers are calibrated against the panel and not against themselves.** The owner counted
 * two whole cards on the household television on 2026-09-20 at the old width of 352dp, and
 * a screenshot of that rail showed where each card's left edge fell. The
 * first test below reproduces both from the formula, so the formula is known to describe the
 * real set before any test asserts anything about the new width.
 */
class RailCardLayoutTest {

    /** What the rail was before this ticket. Only this test knows it. */
    private val oldWidth = 352.dp

    /**
     * The calibration. Every number on the right hand side was read off the household's own
     * panel, not worked out from the constants under test.
     */
    @Test
    fun `the formula reproduces the rail the owner counted on the television`() {
        val screen = RailCardLayout.HOUSEHOLD_SCREEN_WIDTH

        // He counted two cards fully visible, with the third running off the right edge.
        assertEquals(2, RailCardLayout.fullyVisible(screen, oldWidth))

        // And the screenshot puts the three left edges at 44dp, 410dp and 776dp. On the TCL
        // one dp is two pixels, so those are x = 88, 820 and 1552 in the full-size PNG.
        assertEquals(44.dp, RailCardLayout.leftEdgeOf(0, oldWidth))
        assertEquals(410.dp, RailCardLayout.leftEdgeOf(1, oldWidth))
        assertEquals(776.dp, RailCardLayout.leftEdgeOf(2, oldWidth))
    }

    /**
     * The ticket's own criterion. The owner asked for more Sources across the screen than the two
     * he counted, and named no number.
     */
    @Test
    fun `at least three whole cards reach the household's screen`() {
        val across = RailCardLayout.fullyVisible(RailCardLayout.HOUSEHOLD_SCREEN_WIDTH)

        assertTrue(
            "A ${RailCardLayout.WIDTH} card puts $across whole cards on the household's " +
                "screen, and the ticket asks for at least three.",
            across >= 3,
        )
    }

    /**
     * The rail must not look like it ends at the right edge of the screen.
     *
     * A rail whose last whole card ends flush with the screen is a rail the household reads as
     * finished, and there is no scrollbar on a television to say otherwise. A slice of the next
     * card is the only thing that says the rail carries on. It has to be a real slice: a few dp
     * of a rounded corner reads as an edge, not as a card.
     */
    @Test
    fun `a slice of the next card is still on screen`() {
        val peek = RailCardLayout.peek(RailCardLayout.HOUSEHOLD_SCREEN_WIDTH)

        assertTrue(
            "Only $peek of the next card is on screen, so the rail reads as ending at the " +
                "screen's edge.",
            peek >= 24.dp,
        )
    }

    /**
     * The card is narrower than it was, which is half of what this ticket is for. Written as a
     * test because the other half of the ticket, the picture, pulls the other way, and a later
     * session widening the card to fit a picture better would silently undo the width the owner
     * asked for.
     */
    @Test
    fun `the card is narrower than the one the owner rejected`() {
        assertTrue(RailCardLayout.WIDTH < oldWidth)
    }

    /**
     * The picture slot and the text under it have to fit inside the fixed height, or the date
     * at the bottom of the card is pushed past the card's own background, on every card of the
     * rail at once. Nothing clips it: a `Column` draws what does not fit.
     *
     * The height is fixed on purpose: ticket 10 fixed it so the rail does not change shape as
     * the household works down it, and the picture slot is drawn whether or not there is a
     * picture in it, so a finished Source's card is the same size as every other.
     */
    @Test
    fun `the fixed height holds the picture and everything under it`() {
        val picture = RailCardLayout.pictureHeight()
        val needed = picture + RailCardLayout.textBlockHeight() + RailCardLayout.PADDING_Y * 2

        assertTrue(
            "A ${RailCardLayout.WIDTH} card needs $needed for a $picture picture and the text " +
                "under it, and the card is only ${RailCardLayout.HEIGHT} tall.",
            needed <= RailCardLayout.HEIGHT,
        )
    }

    /**
     * And it has to hold them when the household has raised the system text size.
     *
     * Google TV exposes that setting, the picture does not grow with it, and the card's height
     * is fixed, so the room for text is whatever the picture leaves. Checking only font scale 1
     * is what let the first height chosen for this card fail at about 1.08, which
     * `/code-review` found.
     */
    @Test
    fun `the fixed height still holds them at the largest text size`() {
        val scale = RailCardLayout.MAX_FONT_SCALE
        val needed = RailCardLayout.pictureHeight() +
            RailCardLayout.textBlockHeight(scale) +
            RailCardLayout.PADDING_Y * 2

        assertTrue(
            "At font scale $scale the card needs $needed and is ${RailCardLayout.HEIGHT} tall.",
            needed <= RailCardLayout.HEIGHT,
        )
    }

    /**
     * The card must not be so tall that the rail stops fitting under the screen's heading.
     *
     * The home screen spends 28dp of top padding, a 33dp app name, an 8dp gap, a 14dp
     * `SOURCES` label and an 18dp gap before the rail starts, which is 101dp of the
     * television's 540dp of height. Growing the card is the other half of this ticket's
     * squeeze, so the room left is worth a test rather than a screenshot.
     */
    @Test
    fun `the card fits under the home screen's heading`() {
        val screenHeight = 540.dp
        val aboveTheRail = 101.dp

        assertTrue(
            "A ${RailCardLayout.HEIGHT} card does not fit in the " +
                "${screenHeight - aboveTheRail} the rail has.",
            RailCardLayout.HEIGHT <= screenHeight - aboveTheRail,
        )
    }
}
