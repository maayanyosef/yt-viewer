package local.youtubetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * What the remote's centre button means, in one place.
 *
 * A Google TV remote does not agree with itself about which key code the centre button sends,
 * so all three are accepted. Every focusable thing on a screen has to answer the same way, and
 * writing that twice is how two of them end up disagreeing.
 */

/** The keys a Google TV remote sends for the centre button. */
fun isSelectKey(event: KeyEvent): Boolean =
    event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter

/**
 * Runs [onSelect] when the centre button is released on this element.
 *
 * The key up is what acts, not the key down, so that holding the button can mean something
 * else. On a video card it does: see [SelectKeyGesture].
 *
 * Use this only where a hold means nothing, such as a chip. Anything that can be held needs
 * [SelectKeyGesture], because this fires on a key up it never saw the press for.
 */
fun Modifier.onSelectKey(onSelect: () -> Unit): Modifier =
    this.onKeyEvent { event ->
        if (isSelectKey(event) && event.type == KeyEventType.KeyUp) {
            onSelect()
            true
        } else {
            false
        }
    }

/** What one delivery of the centre button turned out to mean. */
enum class SelectGesture {
    /** Nothing to do yet, or nothing at all. */
    NONE,

    /** The button was tapped and released. On a video card that plays the video. */
    TAP,

    /** The button is being held. On a video card that opens the card menu. */
    HOLD,
}

/**
 * Tells a tap of the centre button from a hold of it.
 *
 * One key does two jobs on a video card: a tap plays the video, a hold opens the card menu.
 * Spec section 7.3 asks for both. Getting that right needs a little state, for two reasons.
 *
 * - **The key up still arrives after a hold.** Without remembering that the hold already
 *   fired, releasing the button after opening the menu would also play the video.
 * - **A key up can arrive with no key down before it.** That is exactly what happens when a
 *   hold opens the menu: focus moves to the menu's first line, and the release lands there.
 *   A line that acted on it would choose itself the instant the menu appeared.
 *
 * One instance per focusable element, remembered for as long as the element lives.
 *
 * @param holdAfterRepeats how many repeats make a hold. One is the normal hold, about 400 ms.
 *   Child mode asks a rail card for [CHILD_MODE_HOLD_REPEATS], about four seconds.
 */
class SelectKeyGesture(private val holdAfterRepeats: Int = 1) {

    private var pressed = false
    private var holdFired = false

    /** @param repeatCount Android's own count: 0 on the first delivery, then 1, 2 and so on. */
    fun onKeyDown(repeatCount: Int): SelectGesture {
        // A fresh press clears everything, and that is what makes this safe. After a hold
        // opens the card menu the key up usually lands on the menu, not on the card, so the
        // card may never be told the button came back up. Without this reset its own gesture
        // would stay latched and every later press on that card would be swallowed, because
        // a lazy grid keeps the card composed and so keeps this object alive.
        if (repeatCount == 0) {
            pressed = true
            holdFired = false
            return SelectGesture.NONE
        }
        if (holdFired || !pressed) return SelectGesture.NONE
        if (repeatCount < holdAfterRepeats) return SelectGesture.NONE
        holdFired = true
        return SelectGesture.HOLD
    }

    fun onKeyUp(): SelectGesture {
        val wasHeld = holdFired
        val wasPressed = pressed
        holdFired = false
        pressed = false
        return if (wasPressed && !wasHeld) SelectGesture.TAP else SelectGesture.NONE
    }
}

/**
 * Runs [onSelect] when the centre button is tapped, and [onHold] when it is held.
 *
 * The gesture is [SelectKeyGesture], remembered for as long as this element is composed, so a
 * release that follows a hold does nothing and a release this element never saw the press for
 * does nothing either. Both of those happen on a video card.
 */
@Composable
fun Modifier.onSelectOrHoldKey(
    onSelect: () -> Unit,
    onHold: () -> Unit,
    holdAfterRepeats: Int = 1,
): Modifier {
    val gesture = remember(holdAfterRepeats) { SelectKeyGesture(holdAfterRepeats) }
    return this.onKeyEvent { event ->
        if (!isSelectKey(event)) return@onKeyEvent false
        val meaning = when (event.type) {
            KeyEventType.KeyDown -> gesture.onKeyDown(event.nativeKeyEvent.repeatCount)
            KeyEventType.KeyUp -> gesture.onKeyUp()
            else -> SelectGesture.NONE
        }
        when (meaning) {
            SelectGesture.TAP -> onSelect()
            SelectGesture.HOLD -> onHold()
            // A key this element owns, whether or not this delivery of it meant anything.
            // Passing it on would let the grid or a parent act on the same press twice.
            SelectGesture.NONE -> Unit
        }
        true
    }
}
