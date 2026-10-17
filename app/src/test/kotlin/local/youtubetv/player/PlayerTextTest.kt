package local.youtubetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every string the player puts on the television. */
class PlayerTextTest {

    @Test
    fun `a time under an hour reads as minutes and seconds`() {
        assertEquals("0:00", clock(0))
        assertEquals("0:07", clock(7))
        assertEquals("12:34", clock(754))
        assertEquals("59:59", clock(3_599))
    }

    @Test
    fun `a time over an hour grows an hours field`() {
        assertEquals("1:00:00", clock(3_600))
        assertEquals("1:02:03", clock(3_723))
        assertEquals("10:00:00", clock(36_000))
    }

    @Test
    fun `a negative time reads as the beginning`() {
        // The player answers -1 when asked before it is ready.
        assertEquals("0:00", clock(-1))
    }

    @Test
    fun `the resuming message is the wording spec section 8_6 asks for`() {
        assertEquals("Resuming from 12:34", resumingLine(754))
    }

    @Test
    fun `the position line carries the duration when there is one`() {
        assertEquals("12:34 / 20:00", positionLine(754, 1_200))
    }

    @Test
    fun `the position line stands alone when the Data API gave no duration`() {
        assertEquals("12:34", positionLine(754, null))
        assertEquals("12:34", positionLine(754, 0))
    }

    @Test
    fun `an embedding refusal says the owner does not allow it`() {
        assertEquals(playerErrorReason(101), playerErrorReason(150))
        assertTrue(playerErrorReason(101).contains("owner"))
    }

    @Test
    fun `a removed video says so`() {
        assertTrue(playerErrorReason(100).contains("removed"))
    }

    @Test
    fun `an unknown code still says something, and names the number`() {
        assertTrue(playerErrorReason(42).contains("42"))
    }

    @Test
    fun `no reason is ever only a number`() {
        // The household reads these from the sofa. A bare "101" says nothing.
        for (code in listOf(2, 5, 100, 101, 150, 153, 42)) {
            val reason = playerErrorReason(code)
            assertTrue(reason, reason.length > 20)
            assertTrue(reason, reason.endsWith("."))
        }
    }

    @Test
    fun `the countdown counts in whole seconds and says second once`() {
        assertEquals("Playing in 5 seconds", countdownLine(5))
        assertEquals("Playing in 2 seconds", countdownLine(2))
        // The number counts down while the household is looking at it, so "1 seconds" would
        // be read.
        assertEquals("Playing in 1 second", countdownLine(1))
    }

    @Test
    fun `the countdown never shows zero or a negative number`() {
        assertEquals("Playing in 1 second", countdownLine(0))
        assertEquals("Playing in 1 second", countdownLine(-3))
    }

    @Test
    fun `the end of a Source does not offer to start it again`() {
        // Spec section 8.6: a plain message, and no wrap-around. Nothing in the wording may
        // suggest the Source will begin again.
        assertTrue(END_OF_SOURCE.endsWith("."))
        assertFalse(END_OF_SOURCE.contains("again", ignoreCase = true))
        assertFalse(END_OF_SOURCE.contains("first", ignoreCase = true))
    }

    @Test
    fun `every message the player shows names its way out or ends in a full stop`() {
        for (line in listOf(NO_NEXT_VIDEO, NO_PREVIOUS_VIDEO, END_OF_SOURCE, COUNTDOWN_WAY_OUT)) {
            assertTrue(line, line.endsWith("."))
        }
        // With `controls=0` there is nothing else on the screen, so the countdown has to name
        // the two buttons that act on it. Spec section 8.6.
        assertTrue(COUNTDOWN_WAY_OUT.contains("OK"))
        assertTrue(COUNTDOWN_WAY_OUT.contains("BACK"))
    }

    @Test
    fun `the countdown label is not the Next unwatched chip`() {
        // `CONTEXT.md` keeps "Next unwatched" for the first gap in a Source, which is usually
        // a different video from the next one in Direction. The two must not read alike.
        assertEquals("Next", NEXT_UP_LABEL)
    }
}
