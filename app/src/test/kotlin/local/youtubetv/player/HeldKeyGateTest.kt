package local.youtubetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a key held down means to the player, and how far a held seek jumps.
 *
 * Android repeats a held key about thirty times a second. Left alone, a held centre button
 * would toggle play and pause thirty times a second and a held seek would cross five minutes
 * of video every second.
 */
class HeldKeyGateTest {

    private companion object {
        /** How often Android delivers a repeat for a key held down: about thirty a second. */
        const val REPEAT_MILLIS = 33L
    }

    private val gate = HeldKeyGate()

    /** How far this delivery seeks, or null when it does nothing. */
    private fun seek(action: PlayerAction, repeatCount: Int, atMillis: Long): Int? =
        gate.command(action, repeatCount, atMillis)?.seekSeconds

    @Test
    fun `a fresh press always acts`() {
        assertNotNull(gate.command(PlayerAction.TOGGLE_PLAY, repeatCount = 0, atMillis = 0))
        assertNotNull(gate.command(PlayerAction.TOGGLE_PLAY, repeatCount = 0, atMillis = 1))
        assertNotNull(gate.command(PlayerAction.SEEK_FORWARD, repeatCount = 0, atMillis = 2))
        assertNotNull(gate.command(PlayerAction.LEAVE, repeatCount = 0, atMillis = 3))
    }

    @Test
    fun `an action that does not seek asks for no seconds`() {
        assertEquals(0, seek(PlayerAction.TOGGLE_PLAY, repeatCount = 0, atMillis = 0))
        assertEquals(0, seek(PlayerAction.LEAVE, repeatCount = 0, atMillis = 1))
        assertEquals(0, seek(PlayerAction.NEXT_VIDEO, repeatCount = 0, atMillis = 2))
        assertEquals(0, seek(PlayerAction.SWALLOW, repeatCount = 0, atMillis = 3))
    }

    @Test
    fun `one press of left or right moves exactly ten seconds, either way`() {
        assertEquals(10, seek(PlayerAction.SEEK_FORWARD, repeatCount = 0, atMillis = 0))
        assertEquals(-10, seek(PlayerAction.SEEK_BACK, repeatCount = 0, atMillis = 1))
    }

    @Test
    fun `a burst of separate presses is not slowed down, and each moves ten seconds`() {
        // `adb shell input keyevent 22 22 22` arrives as fresh presses with a repeat count of
        // zero, milliseconds apart. A gate that swallowed those, or that let them climb the
        // ladder, would make every machine verification in this project unreliable.
        for (at in 0L until 80L) {
            assertEquals("press $at", 10, seek(PlayerAction.SEEK_FORWARD, 0, at))
        }
    }

    @Test
    fun `holding the centre button does not toggle again and again`() {
        assertNotNull(gate.command(PlayerAction.TOGGLE_PLAY, repeatCount = 0, atMillis = 0))
        for (repeat in 1..30) {
            assertNull(
                "repeat $repeat",
                gate.command(PlayerAction.TOGGLE_PLAY, repeat, atMillis = repeat * 33L),
            )
        }
    }

    @Test
    fun `holding BACK asks to leave once`() {
        assertNotNull(gate.command(PlayerAction.LEAVE, repeatCount = 0, atMillis = 0))
        assertNull(gate.command(PlayerAction.LEAVE, repeatCount = 1, atMillis = 33))
        assertNull(gate.command(PlayerAction.LEAVE, repeatCount = 9, atMillis = 900))
    }

    @Test
    fun `holding a seek keeps seeking, at a rate a person can steer by`() {
        assertNotNull(gate.command(PlayerAction.SEEK_FORWARD, repeatCount = 0, atMillis = 0))
        // Thirty repeats a second. Only one in about five is allowed through.
        assertNull(gate.command(PlayerAction.SEEK_FORWARD, 1, atMillis = 33))
        assertNull(gate.command(PlayerAction.SEEK_FORWARD, 2, atMillis = 66))
        assertNull(gate.command(PlayerAction.SEEK_FORWARD, 3, atMillis = 99))
        assertNull(gate.command(PlayerAction.SEEK_FORWARD, 4, atMillis = 132))
        assertNotNull(gate.command(PlayerAction.SEEK_FORWARD, 5, atMillis = 165))
        assertNull(gate.command(PlayerAction.SEEK_FORWARD, 6, atMillis = 198))
    }

    @Test
    fun `only the small seek keeps acting while held`() {
        assertTrue(heldKeyKeepsActing(PlayerAction.SEEK_BACK))
        assertTrue(heldKeyKeepsActing(PlayerAction.SEEK_FORWARD))
        assertFalse(heldKeyKeepsActing(PlayerAction.BIG_SEEK_BACK))
        assertFalse(heldKeyKeepsActing(PlayerAction.BIG_SEEK_FORWARD))
        assertFalse(heldKeyKeepsActing(PlayerAction.TOGGLE_PLAY))
        assertFalse(heldKeyKeepsActing(PlayerAction.PLAY))
        assertFalse(heldKeyKeepsActing(PlayerAction.PAUSE))
        assertFalse(heldKeyKeepsActing(PlayerAction.LEAVE))
        assertFalse(heldKeyKeepsActing(PlayerAction.NEXT_VIDEO))
        assertFalse(heldKeyKeepsActing(PlayerAction.PREVIOUS_VIDEO))
        assertFalse(heldKeyKeepsActing(PlayerAction.SWALLOW))
    }

    @Test
    fun `a new press after a hold starts the rate again`() {
        assertNotNull(gate.command(PlayerAction.SEEK_FORWARD, repeatCount = 0, atMillis = 0))
        assertNotNull(gate.command(PlayerAction.SEEK_FORWARD, 5, atMillis = 165))
        // The household let go and pressed again straight away.
        assertNotNull(gate.command(PlayerAction.SEEK_FORWARD, repeatCount = 0, atMillis = 180))
    }

    // ------------------------------------------------------------------ the ladder

    @Test
    fun `the ladder is the three rungs the ticket names`() {
        assertEquals(10, heldSeekStepSeconds(0))
        assertEquals(10, heldSeekStepSeconds(1_999))
        assertEquals(30, heldSeekStepSeconds(2_000))
        assertEquals(30, heldSeekStepSeconds(3_999))
        assertEquals(60, heldSeekStepSeconds(4_000))
        assertEquals(60, heldSeekStepSeconds(60_000))
    }

    @Test
    fun `the ladder starts at zero and only ever climbs`() {
        assertEquals(0L, HELD_SEEK_LADDER.first().afterMillis)
        assertEquals(SEEK_STEP_SECONDS, HELD_SEEK_LADDER.first().stepSeconds)
        for (pair in HELD_SEEK_LADDER.zipWithNext()) {
            assertTrue("$pair", pair.second.afterMillis > pair.first.afterMillis)
            assertTrue("$pair", pair.second.stepSeconds > pair.first.stepSeconds)
        }
    }

    /**
     * One hold, delivered the way Android delivers one.
     *
     * A fresh press at [fromMillis], then a repeat every [REPEAT_MILLIS] until the key has
     * been down for [heldMillis]. Every jump the gate allows is collected, in order.
     */
    private fun hold(
        action: PlayerAction,
        heldMillis: Long,
        fromMillis: Long = 0,
        gate: HeldKeyGate = this.gate,
    ): List<Int> {
        val jumps = mutableListOf<Int>()
        var repeat = 0
        var at = fromMillis
        while (at - fromMillis <= heldMillis) {
            // Every repeat of one hold carries the same `downTime`, which is the press.
            gate.command(action, repeat, at, downAtMillis = fromMillis)
                ?.let { jumps += it.seekSeconds }
            repeat += 1
            at += REPEAT_MILLIS
        }
        return jumps
    }

    @Test
    fun `a held seek grows its step, not its rate`() {
        // One hold of five seconds, delivered at Android's own thirty a second. The gate lets
        // one delivery in about five through, so the gap between jumps stays at 150 ms, and
        // it is the size of each jump that climbs the ladder.
        val jumps = hold(PlayerAction.SEEK_FORWARD, heldMillis = 5_000)
        assertEquals(setOf(10, 30, 60), jumps.toSet())
        assertEquals("the first jump", 10, jumps.first())
        assertEquals("the last jump", 60, jumps.last())
        // Never smaller than the jump before it: the ladder only climbs.
        for (pair in jumps.zipWithNext()) {
            assertTrue("$pair", pair.second >= pair.first)
        }
    }

    @Test
    fun `a longer hold crosses much more video than a short one`() {
        // The two hold lengths the ticket asks to be measured on the television. These are
        // what the set should agree with, to within the player's own rounding and whatever
        // seeks it drops.
        assertEquals(130, hold(PlayerAction.SEEK_FORWARD, heldMillis = 2_000).sum())
        assertEquals(850, hold(PlayerAction.SEEK_FORWARD, heldMillis = 5_000, gate = HeldKeyGate()).sum())
    }

    @Test
    fun `a held seek back climbs the same ladder, backwards`() {
        val jumps = hold(PlayerAction.SEEK_BACK, heldMillis = 5_000)
        assertEquals(setOf(-10, -30, -60), jumps.toSet())
        assertEquals(-850, jumps.sum())
    }

    @Test
    fun `releasing and pressing again moves ten seconds, not the accelerated step`() {
        val first = hold(PlayerAction.SEEK_FORWARD, heldMillis = 5_000)
        assertEquals(60, first.last())
        // The household let go and pressed again. The ladder starts from the bottom, whatever
        // it had climbed to, and then climbs again from this press and not from the first.
        val second = hold(PlayerAction.SEEK_FORWARD, heldMillis = 5_000, fromMillis = 5_200)
        assertEquals(10, second.first())
        assertEquals(850, second.sum())
    }

    @Test
    fun `a repeat whose press was never seen is still measured from its own downTime`() {
        // The player can be built while a key is already down, so the first thing this gate
        // ever sees may be a repeat. Android's own `downTime` says when that key went down,
        // so there is nothing to guess.
        assertEquals(
            10,
            gate.command(PlayerAction.SEEK_FORWARD, 7, atMillis = 9_000, downAtMillis = 8_800)
                ?.seekSeconds,
        )
        assertEquals(
            60,
            gate.command(PlayerAction.SEEK_FORWARD, 200, atMillis = 20_000, downAtMillis = 8_800)
                ?.seekSeconds,
        )
    }

    @Test
    fun `another key's press does not send a held seek back to the bottom rung`() {
        // Two keys at once is not what the household does, but the ladder must not depend on
        // it. `downTime` is per key, so a held right keeps its own hold.
        assertEquals(60, hold(PlayerAction.SEEK_FORWARD, heldMillis = 5_000).last())
        assertNotNull(
            gate.command(PlayerAction.TOGGLE_PLAY, repeatCount = 0, atMillis = 5_010),
        )
        assertEquals(
            60,
            gate.command(PlayerAction.SEEK_FORWARD, 200, atMillis = 5_200, downAtMillis = 0)
                ?.seekSeconds,
        )
    }

    @Test
    fun `restartHold sends the ladder back to the bottom under a key still held`() {
        // What the countdown and the end-of-Source message do: the key is still down, but no
        // seek is running behind them, so the hold must not go on climbing.
        assertEquals(60, hold(PlayerAction.SEEK_FORWARD, heldMillis = 5_000).last())
        gate.restartHold(atMillis = 5_010)
        assertEquals(
            10,
            gate.command(PlayerAction.SEEK_FORWARD, 200, atMillis = 5_200, downAtMillis = 0)
                ?.seekSeconds,
        )
        // And it climbs again from the restart, not from the press.
        assertEquals(
            30,
            gate.command(PlayerAction.SEEK_FORWARD, 300, atMillis = 7_100, downAtMillis = 0)
                ?.seekSeconds,
        )
    }

    @Test
    fun `a fresh press clears a restart`() {
        gate.restartHold(atMillis = 10_000)
        assertEquals(10, seek(PlayerAction.SEEK_FORWARD, repeatCount = 0, atMillis = 1_000))
        assertEquals(
            60,
            gate.command(PlayerAction.SEEK_FORWARD, 200, atMillis = 6_000, downAtMillis = 1_000)
                ?.seekSeconds,
        )
    }

    @Test
    fun `a held big seek never climbs the ladder`() {
        // It does not repeat at all, so every delivery that acts is a fresh press of exactly
        // five minutes, however long the household leans on the key.
        assertEquals(300, seek(PlayerAction.BIG_SEEK_FORWARD, repeatCount = 0, atMillis = 0))
        assertNull(seek(PlayerAction.BIG_SEEK_FORWARD, 150, atMillis = 5_000))
        assertEquals(300, seek(PlayerAction.BIG_SEEK_FORWARD, repeatCount = 0, atMillis = 5_200))
    }
}
