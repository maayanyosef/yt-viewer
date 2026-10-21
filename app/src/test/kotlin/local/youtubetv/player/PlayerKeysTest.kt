package local.youtubetv.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key table of spec section 8.5, one row at a time.
 *
 * `KeyEvent`'s codes are compile-time constants, so this runs on the JVM with no device and
 * still checks the real numbers. The numbers in the comments are what
 * `adb shell input keyevent <n>` takes, which is how the same rows are checked on the
 * television.
 */
class PlayerKeysTest {

    @Test
    fun `the centre button and play-pause both toggle`() {
        // 23, 66, 85.
        assertEquals(PlayerAction.TOGGLE_PLAY, playerAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(PlayerAction.TOGGLE_PLAY, playerAction(KeyEvent.KEYCODE_ENTER))
        assertEquals(PlayerAction.TOGGLE_PLAY, playerAction(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(PlayerAction.TOGGLE_PLAY, playerAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test
    fun `the separate play and pause keys are not a toggle`() {
        // 126 and 127. The Assistant sends these, and a toggle would do the opposite of what
        // was asked when the player is already in that state.
        assertEquals(PlayerAction.PLAY, playerAction(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(PlayerAction.PAUSE, playerAction(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    @Test
    fun `left and rewind seek back, right and fast forward seek on`() {
        // 21, 89, 22, 90.
        assertEquals(PlayerAction.SEEK_BACK, playerAction(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerAction.SEEK_BACK, playerAction(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(PlayerAction.SEEK_FORWARD, playerAction(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlayerAction.SEEK_FORWARD, playerAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
    }

    @Test
    fun `back leaves the player`() {
        assertEquals(PlayerAction.LEAVE, playerAction(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `up seeks forward five minutes and down opens the up-next row`() {
        // 19 and 20. Up kept the five minute jump the owner chose on 2026-09-20. Down became
        // the up-next row on 2026-10-21, because it was the key everybody pressed looking for
        // more videos.
        assertEquals(PlayerAction.BIG_SEEK_FORWARD, playerAction(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PlayerAction.UP_NEXT, playerAction(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(300, BIG_SEEK_STEP_SECONDS)
        assertTrue(playerKeepsKey(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(playerKeepsKey(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `the big seek moves five minutes, signed`() {
        val gate = HeldKeyGate()
        assertEquals(
            PlayerCommand(PlayerAction.BIG_SEEK_FORWARD, 300),
            gate.command(PlayerAction.BIG_SEEK_FORWARD, repeatCount = 0, atMillis = 0),
        )
        assertEquals(
            PlayerCommand(PlayerAction.BIG_SEEK_BACK, -300),
            gate.command(PlayerAction.BIG_SEEK_BACK, repeatCount = 0, atMillis = 10),
        )
    }

    @Test
    fun `channel up is next and channel down is previous, whatever the Direction`() {
        // 166 and 167. Moved here from DPAD_UP and DPAD_DOWN on 2026-09-20. The rocker points
        // the way the household is moving through the Source, and next already means the
        // older video in a newest-first Source. Spec section 8.5's closing line.
        assertEquals(PlayerAction.NEXT_VIDEO, playerAction(KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(PlayerAction.PREVIOUS_VIDEO, playerAction(KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertTrue(playerKeepsKey(KeyEvent.KEYCODE_CHANNEL_UP))
        assertTrue(playerKeepsKey(KeyEvent.KEYCODE_CHANNEL_DOWN))
    }

    @Test
    fun `media next and media previous still change the video`() {
        // 87 and 88. Unchanged by the 2026-09-20 table.
        assertEquals(PlayerAction.NEXT_VIDEO, playerAction(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(PlayerAction.PREVIOUS_VIDEO, playerAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(playerKeepsKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(playerKeepsKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun `a held next key moves one video, not thirty a second`() {
        // Android repeats a held key about thirty times a second. Only the small seek is
        // worth repeating; a held rocker would otherwise walk the whole Source.
        assertFalse(heldKeyKeepsActing(PlayerAction.NEXT_VIDEO))
        assertFalse(heldKeyKeepsActing(PlayerAction.PREVIOUS_VIDEO))
        val gate = HeldKeyGate()
        assertNotNull(gate.command(PlayerAction.NEXT_VIDEO, repeatCount = 0, atMillis = 0))
        assertNull(gate.command(PlayerAction.NEXT_VIDEO, repeatCount = 1, atMillis = 33))
        assertNull(gate.command(PlayerAction.NEXT_VIDEO, repeatCount = 2, atMillis = 5_000))
    }

    @Test
    fun `a held big seek jumps once and then waits for a new press`() {
        // At the 150 ms gap a repeating five minute jump would cross about twenty minutes of
        // video a second, which no household could steer.
        assertFalse(heldKeyKeepsActing(PlayerAction.BIG_SEEK_FORWARD))
        assertFalse(heldKeyKeepsActing(PlayerAction.BIG_SEEK_BACK))
        val gate = HeldKeyGate()
        assertNotNull(gate.command(PlayerAction.BIG_SEEK_FORWARD, repeatCount = 0, atMillis = 0))
        for (repeat in 1..40) {
            assertNull(
                "repeat $repeat",
                gate.command(PlayerAction.BIG_SEEK_FORWARD, repeat, atMillis = repeat * 33L),
            )
        }
    }

    @Test
    fun `a key nothing has named is kept, and does nothing`() {
        // The Source screen is composed under the player and its cards are focusable, so any
        // key that reaches it moves the highlight, and `BACK` would then come back to the
        // wrong card. `MENU` and `GUIDE` never arrive at all on the TCL, which eats both.
        assertEquals(PlayerAction.SWALLOW, playerAction(KeyEvent.KEYCODE_MENU))
        assertEquals(PlayerAction.SWALLOW, playerAction(KeyEvent.KEYCODE_TAB))
        assertEquals(PlayerAction.SWALLOW, playerAction(KeyEvent.KEYCODE_GUIDE))
        assertTrue(playerKeepsKey(KeyEvent.KEYCODE_MENU))
    }

    @Test
    fun `volume, home and power stay with the system`() {
        for (code in listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER,
        )) {
            assertEquals("key code $code", PlayerAction.NONE, playerAction(code))
            assertFalse(playerKeepsKey(code))
        }
    }

    @Test
    fun `one press of left or right moves ten seconds`() {
        assertEquals(10, SEEK_STEP_SECONDS)
    }
}
