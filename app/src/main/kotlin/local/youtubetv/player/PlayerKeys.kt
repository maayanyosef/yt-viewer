package local.youtubetv.player

import android.view.KeyEvent

/**
 * What the remote means while a video is on screen.
 *
 * The WebView is not focusable and the Activity intercepts every key in `dispatchKeyEvent`,
 * so this table is the whole of the player's input. Spec section 8.5.
 *
 * It is a plain function over a key code so that every row of that table is a JVM unit test.
 * The constants are `android.view.KeyEvent`'s own, which the compiler folds into numbers, so
 * the test runs with no device and still checks the real codes.
 */
enum class PlayerAction {

    /** Play if paused, pause if playing. `DPAD_CENTER` and `MEDIA_PLAY_PAUSE`. */
    TOGGLE_PLAY,

    /** `MEDIA_PLAY`, which some remotes and the Assistant send instead of the toggle. */
    PLAY,

    /** `MEDIA_PAUSE`, for the same reason. */
    PAUSE,

    /** Back [SEEK_STEP_SECONDS], or further while the key is held. `DPAD_LEFT`, `MEDIA_REWIND`. */
    SEEK_BACK,

    /** On [SEEK_STEP_SECONDS], or further while the key is held. `DPAD_RIGHT`, `MEDIA_FAST_FORWARD`. */
    SEEK_FORWARD,

    /**
     * Back [BIG_SEEK_STEP_SECONDS], five minutes. `DPAD_DOWN`.
     *
     * Chosen by the owner on 2026-09-20 over two other meanings for `DPAD_UP` and `DPAD_DOWN`:
     * doing nothing but showing the overlay, and opening a detail panel.
     */
    BIG_SEEK_BACK,

    /** On [BIG_SEEK_STEP_SECONDS], five minutes. `DPAD_UP`. */
    BIG_SEEK_FORWARD,

    /** `BACK`. Save the position, leave, and focus the video just played. */
    LEAVE,

    /**
     * The next video in the Source's **Direction**. Channel up and `MEDIA_NEXT`.
     *
     * **Channel up is next whatever the Direction.** The rocker points the way the household
     * is moving through the Source, and "next" already means the older video in a
     * newest-first Source. Spec section 8.5's closing line.
     *
     * It was `DPAD_DOWN` until 2026-09-20. The owner watched on the household set and asked for
     * the change, because a seek key and a video key next to each other on the same pad is
     * too easy to hit by accident.
     */
    NEXT_VIDEO,

    /** The previous video in the Source's Direction. Channel down and `MEDIA_PREVIOUS`. */
    PREVIOUS_VIDEO,

    /**
     * The app keeps the key and does nothing with it.
     *
     * The Source screen is still composed underneath the player, its grid is still focusable,
     * and a key that reached it would move the highlight while the household is watching
     * something. `BACK` would then come back to whichever card the stray key left focused.
     */
    SWALLOW,

    /** Not the player's key. The system, or whatever is underneath, gets it. */
    NONE,
}

/** How far one press of left or right moves. */
const val SEEK_STEP_SECONDS = 10

/**
 * How far one press of `DPAD_UP` or `DPAD_DOWN` moves. Five minutes.
 *
 * It never grows, because the big seek does not repeat while the key is held. See
 * [heldKeyKeepsActing].
 */
const val BIG_SEEK_STEP_SECONDS = 300

/**
 * What one key press means to the player.
 *
 * `MENU` is deliberately absent, and so is `GUIDE`. The TCL's own firmware takes both before
 * this app ever sees them, so nothing may be put on either. Both are proven on the household
 * television. Spec section 8.5.
 *
 * **The channel rocker is next and previous, since 2026-09-20.** It was [PlayerAction.SWALLOW]
 * before that, and the comment here said the second meaning was "left for the owner to ask for".
 * He has now asked. He watched on the household set and did not want next and previous on
 * `DPAD_UP` and `DPAD_DOWN`, so they moved to the rocker and the pad took the five minute
 * seek. The rocker still pages the grid on the Source screen; the player is the exception,
 * and the Activity's `dispatchKeyEvent` runs before the grid's own key handling, so the grid
 * never sees the press while a video is on.
 */
fun playerAction(keyCode: Int): PlayerAction = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    -> PlayerAction.TOGGLE_PLAY

    KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerAction.PLAY
    KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerAction.PAUSE

    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_MEDIA_REWIND,
    -> PlayerAction.SEEK_BACK

    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    -> PlayerAction.SEEK_FORWARD

    KeyEvent.KEYCODE_DPAD_UP -> PlayerAction.BIG_SEEK_FORWARD
    KeyEvent.KEYCODE_DPAD_DOWN -> PlayerAction.BIG_SEEK_BACK

    KeyEvent.KEYCODE_CHANNEL_UP,
    KeyEvent.KEYCODE_MEDIA_NEXT,
    -> PlayerAction.NEXT_VIDEO

    KeyEvent.KEYCODE_CHANNEL_DOWN,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    -> PlayerAction.PREVIOUS_VIDEO

    KeyEvent.KEYCODE_BACK -> PlayerAction.LEAVE

    // Everything else the player keeps, unless the television needs it. Spec section 8.5's
    // table says the player owns the remote while a video is on.
    //
    // This is a deny-list and not a list of the keys the player knows, on purpose. The Source
    // screen is still composed under the player and its cards are still focusable, so any key
    // that reaches it moves the highlight. `BACK` would then come back to whichever card the
    // stray key left focused, and not to the video just played.
    //
    // A list of known keys would have leaked every key nothing has named yet: `MENU`, which
    // the TCL eats anyway, and `TAB` from a paired keyboard, which Compose reads as a focus
    // move.
    in SYSTEM_KEYS -> PlayerAction.NONE

    else -> PlayerAction.SWALLOW
}

/**
 * The keys the player never takes.
 *
 * Volume, Home and Power are spec section 8.5's own row, and the rest are the same kind of
 * thing: a key that belongs to the television or to Google TV, and that a video must not be
 * able to swallow. Taking one of these would leave the household holding a remote whose
 * volume does nothing.
 */
private val SYSTEM_KEYS = setOf(
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_MUTE,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_TV_POWER,
    KeyEvent.KEYCODE_SLEEP,
    KeyEvent.KEYCODE_SOFT_SLEEP,
    KeyEvent.KEYCODE_WAKEUP,
    KeyEvent.KEYCODE_APP_SWITCH,
    KeyEvent.KEYCODE_SEARCH,
    KeyEvent.KEYCODE_ASSIST,
    KeyEvent.KEYCODE_VOICE_ASSIST,
    KeyEvent.KEYCODE_SETTINGS,
    KeyEvent.KEYCODE_TV_INPUT,
    KeyEvent.KEYCODE_NOTIFICATION,
    KeyEvent.KEYCODE_BRIGHTNESS_UP,
    KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
)

/**
 * How long a held seek waits between one jump and the next.
 *
 * About seven jumps a second. The gap is **not** what makes a held seek faster the longer it
 * is held: 150 ms is already at the limit of what the player will honour, and asking it to
 * seek more often than that only makes it drop seeks. [HELD_SEEK_LADDER] grows the step
 * instead.
 */
const val HELD_SEEK_GAP_MILLIS = 150L

/**
 * One rung of [HELD_SEEK_LADDER].
 *
 * @param afterMillis how long the key must have been held for this rung to apply.
 * @param stepSeconds how far each jump moves on this rung.
 */
data class HeldSeekRung(val afterMillis: Long, val stepSeconds: Int)

/**
 * How far a held seek jumps, by how long the key has been down.
 *
 * The owner asked on 2026-09-20 for a held left or right to speed up. A held key repeats about
 * thirty times a second and that rate is not this app's to change, and the 150 ms gap of
 * [HELD_SEEK_GAP_MILLIS] is already at the player's limit. So the **step** grows, not the
 * rate.
 *
 * At one jump every 150 ms, a rung of N seconds crosses about 6.7 x N seconds of video for
 * every second the key is held:
 *
 * | Held for | Each jump moves | Video crossed per second held |
 * | --- | --- | --- |
 * | 0 to 2 s | 10 s | about 70 s |
 * | 2 to 4 s | 30 s | about 200 s |
 * | over 4 s | 60 s | about 400 s |
 *
 * The first rung must start at 0 and the rungs must be in order, because
 * [heldSeekStepSeconds] takes the last rung the hold has reached.
 */
val HELD_SEEK_LADDER = listOf(
    HeldSeekRung(afterMillis = 0L, stepSeconds = SEEK_STEP_SECONDS),
    HeldSeekRung(afterMillis = 2_000L, stepSeconds = 30),
    HeldSeekRung(afterMillis = 4_000L, stepSeconds = 60),
)

/**
 * How far one jump of a held seek moves, in seconds.
 *
 * @param heldForMillis how long the key has been down, from the fresh press to now. A fresh
 *   press is 0 and lands on the first rung, which is [SEEK_STEP_SECONDS].
 */
fun heldSeekStepSeconds(heldForMillis: Long): Int =
    HELD_SEEK_LADDER.lastOrNull { heldForMillis >= it.afterMillis }?.stepSeconds
        ?: SEEK_STEP_SECONDS

/**
 * Whether holding this key down should keep acting, or act once and then wait for a new press.
 *
 * Android repeats a held key about thirty times a second. Only the small seek is worth
 * repeating.
 *
 * - A held centre button would toggle play and pause thirty times a second, which leaves the
 *   player in whichever state the last repeat happened to land on.
 * - A held `BACK` would ask to leave the player thirty times.
 * - A held small seek is how a household scrubs through a long video, and is the normal way
 *   to use the key.
 * - A held **big** seek is not. It is a deliberate five minute jump, and repeating it at the
 *   150 ms gap would cross about twenty minutes of video a second.
 * - A held channel rocker would walk the whole Source.
 */
fun heldKeyKeepsActing(action: PlayerAction): Boolean =
    action == PlayerAction.SEEK_BACK || action == PlayerAction.SEEK_FORWARD

/**
 * What one delivery of a key asks the player to do.
 *
 * @param seekSeconds signed seconds for the four seek actions, negative for back, and 0 for
 *   every other action.
 */
data class PlayerCommand(val action: PlayerAction, val seekSeconds: Int)

/**
 * Decides whether one delivery of a key acts, and how far it seeks.
 *
 * Android's repeat rate is about thirty a second, and it is not the app's to change. So a
 * repeat is either thrown away, for every key but a small seek, or slowed to the
 * [HELD_SEEK_GAP_MILLIS] gap, which is a rate a person can steer by.
 *
 * A **fresh press always acts, always moves exactly [SEEK_STEP_SECONDS], and always starts
 * [HELD_SEEK_LADDER] again.** That matters for more than the household: a burst of separate
 * presses sent with `adb shell input keyevent 22 22 22` arrives as fresh presses with a
 * repeat count of zero, and a ladder that carried over between them would make every machine
 * verification in this project unreliable without saying so.
 *
 * **How long the key has been down is Android's own `KeyEvent.downTime`, not a time this
 * class writes down.** Every repeat of a held key carries the moment that key went down, so
 * the ladder is measured per key and needs no state. A gate that remembered the press itself
 * would drop a held right back to the bottom rung the moment any other key was pressed, and
 * would have to guess for a repeat whose press it never saw, which happens when the player is
 * built while a key is already held.
 *
 * One instance per player. It holds the time of the last seek it allowed, and the moment the
 * ladder was last sent back to the bottom by [restartHold].
 */
class HeldKeyGate(private val gapMillis: Long = HELD_SEEK_GAP_MILLIS) {

    // Null and not a number, so the first repeat of a key whose press was never seen cannot
    // be judged against a sentinel that overflows when it is subtracted from.
    private var lastActedAt: Long? = null

    /** When the ladder was last sent back to the bottom under a key that is still held. */
    private var restartedAt: Long? = null

    /**
     * @param repeatCount Android's own count: 0 on the first delivery, then 1, 2 and so on.
     * @param atMillis when this delivery arrived. `KeyEvent.eventTime`.
     * @param downAtMillis when the key now held went down. `KeyEvent.downTime`, which is on
     *   the same clock as [atMillis] and is the same value for every repeat of one hold.
     * @return what to do, or null when this delivery does nothing.
     */
    fun command(
        action: PlayerAction,
        repeatCount: Int,
        atMillis: Long,
        downAtMillis: Long = atMillis,
    ): PlayerCommand? {
        if (repeatCount == 0) {
            lastActedAt = atMillis
            restartedAt = null
            return PlayerCommand(action, signedSeek(action, SEEK_STEP_SECONDS))
        }
        if (!heldKeyKeepsActing(action)) return null
        val last = lastActedAt
        if (last != null && atMillis - last < gapMillis) return null
        lastActedAt = atMillis
        val since = maxOf(downAtMillis, restartedAt ?: downAtMillis)
        return PlayerCommand(action, signedSeek(action, heldSeekStepSeconds(atMillis - since)))
    }

    /**
     * Sends the ladder back to the bottom, without waiting for the key to be let go.
     *
     * The player throws a seek away while the autoplay-next countdown is running and on the
     * end-of-Source message, and a household leaning on the key through one of those would
     * otherwise come out of it at the top rung. The first seek that really runs would move a
     * minute on a video that had just started. Spec section 8.6 says a seek does nothing
     * during the countdown, and this is what makes the ladder agree with it.
     */
    fun restartHold(atMillis: Long) {
        restartedAt = atMillis
    }
}

/**
 * Turns a step into the signed number of seconds [PlayerController.seekBy] takes.
 *
 * The big seek ignores [stepSeconds] on purpose. It does not repeat, so it has no ladder.
 */
private fun signedSeek(action: PlayerAction, stepSeconds: Int): Int = when (action) {
    PlayerAction.SEEK_FORWARD -> stepSeconds
    PlayerAction.SEEK_BACK -> -stepSeconds
    PlayerAction.BIG_SEEK_FORWARD -> BIG_SEEK_STEP_SECONDS
    PlayerAction.BIG_SEEK_BACK -> -BIG_SEEK_STEP_SECONDS
    else -> 0
}

/**
 * Whether the app takes this key away from everything underneath it.
 *
 * Every action but [PlayerAction.NONE] is kept, including [PlayerAction.SWALLOW]. The key-up
 * of a kept key is swallowed too, or the WebView and the grid behind it would each see half
 * of an event.
 */
fun playerKeepsKey(keyCode: Int): Boolean = playerAction(keyCode) != PlayerAction.NONE
