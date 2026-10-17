package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which card the highlight goes to, the one rule both halves of a close read.
 *
 * The home screen asks it twice for one press of `BACK`. First the closing panel asks, so that
 * it can hand the highlight to a card **before** it leaves composition, which is what stops
 * Compose choosing a card of its own; build ticket 38. Then, if there was nothing to hand it
 * to, `SourceRail`'s retry ladder asks, and it has to reach the same answer or the two would
 * walk to different cards for the same gesture.
 *
 * The cases below are all real ones. A preference that is no longer on the rail is
 * `Remove this Source`, which takes away exactly the card the highlight was on. A null
 * preference is the first frame of a cold start, before any card has reported anything.
 */
class RailHighlightTargetTest {

    private val curated = "PLmadeUpPlaylistForTestsZ000000001"
    private val ted = "UUAuUUnT6oDeKwE6v1NGQxug"
    private val lofi = "UUSJ4gkVC6NrvII8umztf0Ow"
    private val rail = listOf(curated, ted, lofi)

    @Test
    fun `the card the household is standing on keeps the highlight`() {
        assertEquals(lofi, railHighlightTarget(listOf(lofi), rail))
    }

    @Test
    fun `the first preference wins while it is still on the rail`() {
        assertEquals(ted, railHighlightTarget(listOf(ted, lofi), rail))
    }

    /** `Remove this Source`: the named card has gone, so the next preference has its turn. */
    @Test
    fun `a preference that has left the rail is skipped`() {
        assertEquals(lofi, railHighlightTarget(listOf("UUgone", lofi), rail))
    }

    /** A cold start, where nothing has reported the highlight yet. */
    @Test
    fun `a null preference is skipped rather than answered`() {
        assertEquals(curated, railHighlightTarget(listOf(null), rail))
    }

    @Test
    fun `nulls between real preferences are skipped too`() {
        assertEquals(lofi, railHighlightTarget(listOf(null, lofi, ted), rail))
    }

    /**
     * The fallback is the first card, and it is what makes `Remove` safe.
     *
     * A request naming a card the lazy row no longer holds lands nowhere at all, so without a
     * fallback the rail would be drawn with no highlight and no key would move one.
     */
    @Test
    fun `the first card takes it when no preference is on the rail`() {
        assertEquals(curated, railHighlightTarget(listOf("UUgone", "UUalsogone"), rail))
    }

    @Test
    fun `no preference at all still lands on the first card`() {
        assertEquals(curated, railHighlightTarget(emptyList(), rail))
    }

    /**
     * The last Source was removed, so there is no card to hand the highlight to.
     *
     * The caller has to tell this case from every other one: the home screen falls back to the
     * rail's own ladder when the answer is null, and the rail draws the first-run page rather
     * than a rail at all.
     */
    @Test
    fun `an empty rail has no target`() {
        assertNull(railHighlightTarget(listOf(lofi), emptyList()))
        assertNull(railHighlightTarget(emptyList(), emptyList()))
    }

    /**
     * The `+ Add a Source` card is never a target, because it is not a Source and its key is
     * not in the card list this rule is given.
     */
    @Test
    fun `only Sources are ever named`() {
        assertEquals(curated, railHighlightTarget(listOf("+ add a source"), rail))
    }
}

/**
 * Whether the rail has to scroll for a card to be able to take the highlight.
 *
 * The rule exists because `scrollToItem` **aligns**: it puts the card it names at the start of
 * the viewport, whether or not that card was already on the screen. The rail's retry ladder
 * called it on every ask, so closing a panel dragged the rail to the left edge and let
 * Compose's own bring-into-view walk it back, 480 px and about 290 ms of it on the
 * `googletv1080p` emulator. The one thing a scroll is really needed for is a card the lazy row
 * has not placed, because a card that does not exist cannot take focus. Build ticket 38, after
 * `/code-review`.
 */
class RailMustScrollToTest {

    private val curated = "PLmadeUpPlaylistForTestsZ000000001"
    private val ted = "UUAuUUnT6oDeKwE6v1NGQxug"
    private val lofi = "UUSJ4gkVC6NrvII8umztf0Ow"

    /** The close path: the card is on the screen, so nothing has to move. */
    @Test
    fun `a card the lazy row is holding needs no scroll`() {
        assertFalse(railMustScrollTo(lofi, setOf(curated, ted, lofi)))
    }

    /** Coming back to a Source whose card is far enough along the rail to have been dropped. */
    @Test
    fun `a card the lazy row has dropped needs one`() {
        assertTrue(railMustScrollTo(lofi, setOf(curated, ted)))
    }

    /**
     * The first frame, before any card has registered.
     *
     * The ladder asks again with growing patience, so the answer here only has to be safe: a
     * scroll to an index the lazy row has not reached is what places it.
     */
    @Test
    fun `nothing placed means a scroll`() {
        assertTrue(railMustScrollTo(curated, emptySet()))
    }

    /**
     * **A placed card that is partly off the screen still needs no scroll**, and that is the
     * whole point of the rule. Taking the highlight brings it into view by itself, which is
     * the rail's own walk and the smallest movement that shows the card.
     */
    @Test
    fun `being placed is the question, not being fully visible`() {
        assertFalse(railMustScrollTo(curated, setOf(curated)))
    }
}
