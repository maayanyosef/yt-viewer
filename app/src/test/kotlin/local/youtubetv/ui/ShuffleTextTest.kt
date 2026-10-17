package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words Shuffle puts on the Source screen. Build ticket 69, rules 1, 10 and 11. */
class ShuffleTextTest {

    @Test
    fun `the chip names what it is, like Autoplay`() {
        assertEquals("Shuffle: On", shuffleChipLabel(true))
        assertEquals("Shuffle: Off", shuffleChipLabel(false))
    }

    @Test
    fun `the hint line says how to start a new run and how far this one has got`() {
        assertEquals(
            "Hold OK to start a new Shuffle run. 30 videos played in this run.",
            shuffleHintLine(30),
        )
        assertEquals("Hold OK to start a new Shuffle run. 1 video played in this run.", shuffleHintLine(1))
        assertEquals("Hold OK to start a new Shuffle run. 0 videos played in this run.", shuffleHintLine(0))
        assertEquals(
            "Hold OK to start a new Shuffle run. 1,204 videos played in this run.",
            shuffleHintLine(1204),
        )
    }

    @Test
    fun `the question names the count, and says so plainly when there is none`() {
        assertEquals(
            "Start a new Shuffle run? The 30 videos already played in this run can play again.",
            shuffleQuestion(30),
        )
        assertEquals(
            "Start a new Shuffle run? The 1 video already played in this run can play again.",
            shuffleQuestion(1),
        )
        assertEquals("Start a new Shuffle run? No video has played in this run yet.", shuffleQuestion(0))
    }
}
