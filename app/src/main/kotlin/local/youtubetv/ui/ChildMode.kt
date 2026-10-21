package local.youtubetv.ui

import android.content.Context

/**
 * Child mode: the television remote does only the safe things.
 *
 * The household's viewer is two years old. A toddler holding the remote presses everything,
 * and before this mode existed that could remove a Source, mark two thousand videos watched,
 * or wander off into the key screen. With it on:
 *
 * - a press on a card plays, or opens the Source, and that is all a card does;
 * - the card menus do not open from a normal hold;
 * - the chip row above the grid is gone, so `Up` from the first row goes nowhere;
 * - a finished video rolls into the next one, whatever the Source's own autoplay setting.
 *
 * The parent gets back in two ways: the phone remote has a switch, and holding `OK` on a
 * rail card for about four seconds opens the Source menu, where the line is.
 */
class ChildModeStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): Boolean = preferences.getBoolean(KEY, false)

    fun write(on: Boolean) {
        preferences.edit().putBoolean(KEY, on).apply()
    }

    private companion object {
        const val FILE = "player_settings"
        const val KEY = "child_mode"
    }
}

/**
 * How many repeats of a held `OK` open a rail card's menu in child mode.
 *
 * Android repeats a held key about thirty times a second after a 400 ms delay, so a hundred
 * repeats is a hold of about four seconds. A toddler's hold is a second or two. The normal
 * hold is one repeat, which is [SelectKeyGesture]'s default.
 */
const val CHILD_MODE_HOLD_REPEATS = 100

fun childModeLine(on: Boolean): String = if (on) "Child mode: On" else "Child mode: Off"

const val CHILD_MODE_ON_SAID =
    "Child mode on. Hold OK on a card for four seconds, or use the phone, to open this menu again."
const val CHILD_MODE_OFF_SAID = "Child mode off."
