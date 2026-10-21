package local.youtubetv.player

import android.content.Context

/**
 * The highest picture the player may ask YouTube for.
 *
 * **This is a cap, not a choice, and that is not a free decision.** The IFrame Player API has
 * had no working way to choose a quality since 2019: `setPlaybackQuality` and the
 * `suggestedQuality` load option are still documented and do nothing. YouTube picks the stream
 * from the player's size on screen and from the connection. So the page draws the player at
 * the size of the cap and scales it up to fill the screen (see `PlayerHtml.kt`), and YouTube
 * then never picks a stream bigger than that size. It may still pick a smaller one on a slow
 * connection.
 *
 * A lower cap is also what helps a television with little memory: a 480p stream decodes into
 * about a quarter of the memory a 1080p one does.
 *
 * @property maxHeight the picture height in physical pixels, or 0 for no cap.
 */
enum class VideoQuality(val maxHeight: Int, val label: String) {
    AUTO(0, "Auto"),
    P1080(1080, "1080p"),
    P720(720, "720p"),
    P480(480, "480p"),
    ;

    /**
     * The setting `Left` or `Right` on the menu line moves to. It stops at both ends, like a
     * slider, so a held key cannot spin past the setting the household wanted.
     *
     * `Right` goes down the list, from `Auto` towards `480p`.
     */
    fun step(right: Boolean): VideoQuality {
        val to = if (right) ordinal + 1 else ordinal - 1
        return entries[to.coerceIn(0, entries.size - 1)]
    }

    companion object {
        /** A stored name this build does not know is read as [AUTO], never as a crash. */
        fun fromStored(name: String?): VideoQuality =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * The menu line that moves the cap on. It says what the cap is now, and its arrows say that
 * `Left` and `Right` change it. An arrow is left out at the end the setting cannot go past.
 */
fun videoQualityLine(quality: VideoQuality): String {
    val left = if (quality.ordinal > 0) "‹ " else ""
    val right = if (quality.ordinal < VideoQuality.entries.size - 1) " ›" else ""
    return "Video quality: $left${quality.label}$right"
}

/** What the toast says after the line was pressed. Read by the household at a distance. */
fun videoQualitySetLine(quality: VideoQuality): String =
    if (quality == VideoQuality.AUTO) {
        "Video quality: Auto. YouTube chooses."
    } else {
        "Video quality: up to ${quality.label}. From the next video."
    }

/**
 * The player's size in CSS pixels for [maxHeight], or null for no cap.
 *
 * Always 16:9. Null as well when the cap is as big as the screen or bigger, because a cap that
 * changes nothing should not put a scale transform on every frame.
 *
 * @param devicePixelRatio the WebView's `window.devicePixelRatio`. The Mi TV and the TCL both
 *   report 2 at 1080p.
 * @param viewportHeight the page's `window.innerHeight`, in CSS pixels.
 */
fun cappedPlayerSize(maxHeight: Int, devicePixelRatio: Double, viewportHeight: Int): Pair<Int, Int>? {
    if (maxHeight <= 0 || devicePixelRatio <= 0.0 || viewportHeight <= 0) return null
    val height = Math.round(maxHeight / devicePixelRatio).toInt()
    if (height >= viewportHeight) return null
    val width = Math.round(height * 16.0 / 9.0).toInt()
    return width to height
}

/** Where the cap is kept: one `SharedPreferences` file, like the API key. Not the database. */
class VideoQualityStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): VideoQuality = VideoQuality.fromStored(preferences.getString(KEY, null))

    fun write(quality: VideoQuality) {
        preferences.edit().putString(KEY, quality.name).apply()
    }

    private companion object {
        const val FILE = "player_settings"
        const val KEY = "video_quality"
    }
}
