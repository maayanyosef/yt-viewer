package local.youtubetv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a panel is drawn over the home rail, which decides two things at once.
 *
 * The rule exists because those two things used to be decided separately, and only one of them
 * was right. A card behind the scrim already swallowed every key, because ticket 09's hole was
 * known. Nothing stopped the rail **scrolling** to a card that took the highlight, and one
 * does: choosing `Remove this Source` swaps the menu for its confirmation from a different
 * `when` branch, the node holding the highlight is destroyed, and Compose hands focus to the
 * first focusable it can find, which is the rail's first card. The rail scrolled home under the
 * scrim. Measured on the `googletv1080p` emulator on 2026-09-21; build ticket 37.
 *
 * One fact now feeds both, so a later edit cannot move one and leave the other behind.
 */
class RailIsCoveredTest {

    private val curated = "PLmadeUpPlaylistForTestsZ000000001"
    private val ted = "PLmadeUpPlaylistForTestsY00000003"

    @Test
    fun `a bare rail is not covered`() {
        assertFalse(railIsCovered(menuFor = null, confirmFor = null))
    }

    @Test
    fun `an open card menu covers the rail`() {
        assertTrue(railIsCovered(menuFor = curated, confirmFor = null))
    }

    @Test
    fun `an open confirmation covers the rail`() {
        assertTrue(railIsCovered(menuFor = null, confirmFor = curated))
    }

    /**
     * The moment the defect lives in.
     *
     * The home screen clears `menuFor` and sets `confirm` in the same press, so this pair is
     * never held for long. It is held for long enough to recompose, though, and the answer has
     * to be the same on both sides of that swap: if the rail were uncovered for one frame,
     * Compose's default bring-into-view rule would be back and the stray focus would scroll the
     * rail, which is exactly what this rule is here to stop.
     */
    @Test
    fun `the swap from a menu to its confirmation never uncovers the rail`() {
        assertTrue("the menu alone", railIsCovered(menuFor = curated, confirmFor = null))
        assertTrue("both at once", railIsCovered(menuFor = curated, confirmFor = curated))
        assertTrue("the confirmation alone", railIsCovered(menuFor = null, confirmFor = curated))
    }

    /**
     * The rail reorders itself whenever a video is played, so the two ids are allowed to name
     * different Sources. The rule is about whether anything is drawn, never about which Source.
     */
    @Test
    fun `two different Sources still only mean the rail is covered`() {
        assertTrue(railIsCovered(menuFor = curated, confirmFor = ted))
    }

    /** An empty string is a Source id the household could never have, but it is not null. */
    @Test
    fun `an empty id still counts as an open panel`() {
        assertTrue(railIsCovered(menuFor = "", confirmFor = null))
        assertTrue(railIsCovered(menuFor = null, confirmFor = ""))
    }
}
