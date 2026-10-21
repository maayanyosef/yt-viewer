package local.youtubetv.ui

import android.content.Context

/**
 * How the household likes to look at the app: whether watched videos are shown on the grid,
 * and whether the text is drawn large. Fork 0.6.
 *
 * Both on the app settings panel of the home menu, and both of this television, so they live
 * in the same `SharedPreferences` file as the other settings and never in the database.
 */
class ViewSettingsStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hideWatched(): Boolean = preferences.getBoolean(HIDE_WATCHED, false)
    fun setHideWatched(on: Boolean) = preferences.edit().putBoolean(HIDE_WATCHED, on).apply()

    fun largeText(): Boolean = preferences.getBoolean(LARGE_TEXT, false)
    fun setLargeText(on: Boolean) = preferences.edit().putBoolean(LARGE_TEXT, on).apply()

    private companion object {
        const val FILE = "player_settings"
        const val HIDE_WATCHED = "hide_watched"
        const val LARGE_TEXT = "large_text"
    }
}

/**
 * How much bigger every piece of text is drawn with `Text size: Large`.
 *
 * Applied as the font scale of the whole screen, so every size in [Fonts] grows together and
 * every line keeps its `maxLines` and its ellipsis. 1.2 was the largest that left the chip
 * row and the four-column grid whole on a 1080p screen.
 */
const val LARGE_TEXT_SCALE = 1.2f

fun hideWatchedLine(on: Boolean): String = if (on) "Hide watched: On" else "Hide watched: Off"
fun textSizeLine(large: Boolean): String = if (large) "Text size: Large" else "Text size: Normal"
