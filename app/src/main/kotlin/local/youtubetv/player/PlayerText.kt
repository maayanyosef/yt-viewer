package local.youtubetv.player

/**
 * Every string the player puts on the television.
 *
 * Kept apart from the Composables in the same way `ui/SourceScreenText.kt` is, so each wording
 * rule is a JVM unit test with no device.
 */

/** `12:34`, and `1:02:03` once a video passes an hour. Never a negative time. */
fun clock(seconds: Int): String {
    val safe = if (seconds < 0) 0 else seconds
    val hours = safe / 3_600
    val minutes = (safe % 3_600) / 60
    val rest = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%d:%02d".format(minutes, rest)
    }
}

/**
 * The message shown when an In progress video picks up where it stopped.
 *
 * It fades on its own. Spec section 8.6 asks for exactly this wording, so the number is the
 * only part that changes.
 */
fun resumingLine(seconds: Int): String = "Resuming from ${clock(seconds)}"

/** `12:34 / 1:02:03`, or just the position when the Data API gave no duration. */
fun positionLine(positionSeconds: Int, durationSeconds: Int?): String =
    if (durationSeconds == null || durationSeconds <= 0) {
        clock(positionSeconds)
    } else {
        "${clock(positionSeconds)} / ${clock(durationSeconds)}"
    }

/**
 * What the household is told when the player refuses a video.
 *
 * One short sentence, in plain words, and never the bare number. The player stays where it is
 * and **no entry is written**, so a video that would not play is still Next unwatched. Spec
 * section 8.6.
 *
 * The codes are the IFrame Player API's own:
 *
 * - `2` the id is not a video id at all.
 * - `5` the HTML5 player could not play it.
 * - `100` removed or private.
 * - `101` and `150` the owner does not allow the video off YouTube. They mean the same thing
 *   and YouTube returns either one.
 * - `153` the request carried no `Referer`. That is a defect in this app, not in the video:
 *   it means the page was loaded without a real base URL. See spec section 8.1.
 */
/**
 * The way out of a player error.
 *
 * The player stays where it is when a video is refused, which spec section 8.6 asks for, and
 * there is nothing else on the screen. Without this line the household is looking at a still
 * panel with no way of knowing which button ends it.
 */
const val PLAYER_ERROR_WAY_OUT = "Press BACK to return to the list."

/**
 * The word over the paused next video, above its title. Spec section 8.6's countdown.
 *
 * `CONTEXT.md` keeps "Next unwatched" for the chip that finds the first gap, so this one word
 * is deliberately not that phrase. It names the next video in the Source's Direction, which
 * is usually a different video.
 */
const val NEXT_UP_LABEL = "Next"

/**
 * How long until the next video starts, as a whole sentence.
 *
 * The number counts down once a second, so the wording changes with it and "1 seconds" would
 * be read by a household that is looking straight at it.
 */
fun countdownLine(secondsLeft: Int): String {
    val safe = if (secondsLeft < 1) 1 else secondsLeft
    return if (safe == 1) "Playing in 1 second" else "Playing in $safe seconds"
}

/**
 * The two ways out of the countdown, in one line.
 *
 * With `controls=0` there is nothing else on the screen, so a household that does not want
 * the next video has to be told which button stops it. The error panel carries
 * [PLAYER_ERROR_WAY_OUT] for the same reason.
 */
const val COUNTDOWN_WAY_OUT = "Press OK to play it now, or BACK for the list."

/**
 * What the household is told at the end of a Source.
 *
 * Spec section 8.6 asks for a plain message and **no wrap-around**, so it does not offer to
 * start the Source again. "Last" means last in the Source's own Direction: the oldest video
 * in a newest-first Source, and the newest one in an oldest-first Source.
 */
const val END_OF_SOURCE = "That was the last video in this Source."

/** What a press of next says when the player is already on the last video in Direction. */
const val NO_NEXT_VIDEO = "This is the last video in this Source."

/** What a press of previous says when the player is already on the first video in Direction. */
const val NO_PREVIOUS_VIDEO = "This is the first video in this Source."

/**
 * What a press of previous says on the first video of a Shuffle run. Build ticket 69, rule 8.
 * Previous walks back through the run in the order the videos played, and stops here.
 */
const val FIRST_OF_SHUFFLE_RUN = "This is the first video of this Shuffle run."

fun playerErrorReason(code: Int): String = when (code) {
    2 -> "YouTube does not know this video id."
    5 -> "This television's player could not play this video."
    100 -> "This video has been removed, or is private."
    101, 150 -> "The owner does not allow this video to be played outside YouTube."
    153 -> "This app asked YouTube the wrong way. The video was not played."
    else -> "YouTube could not play this video. Player error $code."
}
