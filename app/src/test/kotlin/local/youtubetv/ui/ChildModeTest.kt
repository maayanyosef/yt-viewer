package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Child mode's one rule that is not a label: a card's menu needs a long hold. */
class ChildModeTest {

    @Test
    fun `the normal hold fires on the first repeat`() {
        val gesture = SelectKeyGesture()
        assertEquals(SelectGesture.NONE, gesture.onKeyDown(0))
        assertEquals(SelectGesture.HOLD, gesture.onKeyDown(1))
    }

    @Test
    fun `in child mode a hold of a second or two is nothing, and four seconds opens the menu`() {
        val gesture = SelectKeyGesture(CHILD_MODE_HOLD_REPEATS)
        assertEquals(SelectGesture.NONE, gesture.onKeyDown(0))
        for (repeat in 1 until CHILD_MODE_HOLD_REPEATS) {
            assertEquals("repeat $repeat", SelectGesture.NONE, gesture.onKeyDown(repeat))
        }
        assertEquals(SelectGesture.HOLD, gesture.onKeyDown(CHILD_MODE_HOLD_REPEATS))
        // The release after a hold is not a tap, so letting go does not also open the Source.
        assertEquals(SelectGesture.NONE, gesture.onKeyUp())
    }

    @Test
    fun `a short press in child mode is still a tap`() {
        val gesture = SelectKeyGesture(CHILD_MODE_HOLD_REPEATS)
        gesture.onKeyDown(0)
        gesture.onKeyDown(1)
        gesture.onKeyDown(2)
        assertEquals(SelectGesture.TAP, gesture.onKeyUp())
    }

    @Test
    fun `the menu line and the toasts say the state`() {
        assertEquals("Child mode: On", childModeLine(true))
        assertEquals("Child mode: Off", childModeLine(false))
    }
}
