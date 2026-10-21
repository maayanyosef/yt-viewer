package local.youtubetv.data

/**
 * How one Source plays: the cap on its picture, its speed, and how much of each video's start
 * and end is skipped.
 *
 * Per Source, like Direction and autoplay-next, and for the same reason: a cartoon channel
 * plays at 480p and 1x with its twenty second intro skipped, and a lecture channel plays at
 * 1080p and 1.5x. One setting for the whole app would be wrong for one of them.
 *
 * It lives on the Source's row and never in the Watch log: it is how the household likes to
 * watch, not what it watched. See ADR 0001.
 *
 * @property qualityName the name of a `player/VideoQuality` entry, or null for the app's own
 *   setting. A name is stored and not the enum, because `data/` does not import `player/`.
 * @property rate the playback speed, one of [RATES].
 * @property skipStartSeconds how many seconds a video started from the beginning skips.
 *   Nothing when the video is resumed.
 * @property skipEndSeconds how many seconds before the end a video counts as finished.
 */
data class PlaybackSettings(
    val qualityName: String? = null,
    val rate: Float = 1f,
    val skipStartSeconds: Int = 0,
    val skipEndSeconds: Int = 0,
) {
    fun stepRate(right: Boolean): PlaybackSettings {
        val at = RATES.indexOf(rate).coerceAtLeast(0)
        val to = (if (right) at + 1 else at - 1).coerceIn(0, RATES.size - 1)
        return copy(rate = RATES[to])
    }

    fun stepSkipStart(right: Boolean): PlaybackSettings =
        copy(skipStartSeconds = stepSkip(skipStartSeconds, right))

    fun stepSkipEnd(right: Boolean): PlaybackSettings =
        copy(skipEndSeconds = stepSkip(skipEndSeconds, right))

    companion object {
        val DEFAULT = PlaybackSettings()

        /** The speeds the IFrame Player offers, and nothing in between. */
        val RATES = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        /** One press of `Left` or `Right` moves a skip by this many seconds. */
        const val SKIP_STEP_SECONDS = 5

        /** The longest skip. Three minutes covers any intro a channel has. */
        const val SKIP_MAX_SECONDS = 180

        /** What the `playback_rate` column means, including a value this build does not offer. */
        fun rateOfStored(stored: Double?): Float =
            stored?.toFloat()?.takeIf { it in RATES } ?: 1f

        /** What a skip column means. Null is no skip, and nothing is read past the maximum. */
        fun skipOfStored(stored: Int?): Int = (stored ?: 0).coerceIn(0, SKIP_MAX_SECONDS)

        private fun stepSkip(seconds: Int, right: Boolean): Int =
            (if (right) seconds + SKIP_STEP_SECONDS else seconds - SKIP_STEP_SECONDS)
                .coerceIn(0, SKIP_MAX_SECONDS)

        /** `1x`, `1.25x`. The multiplication sign is typed, not the letter, so it reads as a speed. */
        fun rateLabel(rate: Float): String {
            val text = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()
            return "$text×"
        }

        /** `Off`, or `15 s`. */
        fun skipLabel(seconds: Int): String = if (seconds <= 0) "Off" else "$seconds s"
    }
}
