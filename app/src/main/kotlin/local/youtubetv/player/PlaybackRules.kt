package local.youtubetv.player

import kotlin.math.abs
import kotlin.math.min
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchState

/**
 * Every rule a play obeys, as plain functions with no Android and no WebView in them.
 *
 * The player itself cannot be unit-tested: it is a YouTube iframe inside a WebView on a
 * television. So everything that decides an answer is pulled out here and proven on the JVM,
 * and the WebView is left with nothing to decide. See spec sections 8.2, 8.4 and 8.6.
 */

/**
 * How far playback must get before the household is judged to have played the video.
 *
 * A `played` entry is appended once, when this is passed, and never on start. A mis-press on
 * the wrong card must not remove that video from Next unwatched. Spec section 3.3, and ticket
 * 11 of the charting effort, which amended "on start" to this.
 */
const val PLAY_THRESHOLD_SECONDS = 10

/**
 * How much the position has to move before it is written again while playing.
 *
 * Spec section 8.4 asks for every ten seconds. It is a distance and not a timer, so a seek
 * counts as movement: the household that jumps forward half an hour and then presses `BACK`
 * gets the position it is actually at.
 */
const val POSITION_WRITE_INTERVAL_SECONDS = 10

/** The most of a video that can be left unwatched and still count as completed. */
const val COMPLETION_CAP_SECONDS = 60

/** The share of a video that can be left unwatched and still count as completed. */
const val COMPLETION_PERCENT = 5

/**
 * How near the end counts as having watched the whole thing.
 *
 * `min(60 seconds, 5% of the duration)`. Spec section 8.4. The cap stops a four-hour stream
 * from counting as watched twelve minutes before it ends, and the percentage stops a
 * ninety-second video from needing to be watched to the very last frame.
 *
 * The duration is always the Data API's, never the player's: during an in-stream ad
 * `getDuration()` reports the ad's length. Spec section 8.4.
 */
fun completionThresholdSeconds(durationSeconds: Int): Int =
    min(COMPLETION_CAP_SECONDS, durationSeconds * COMPLETION_PERCENT / 100)

/**
 * Whether this position counts as having finished the video.
 *
 * `ENDED` is the other way to be completed, and the session applies that one directly. This
 * answers the case the household causes: pressing `BACK` a few seconds before the end, which
 * the player never reports as `ENDED`.
 *
 * With no duration there is no threshold to compare against, so the answer is no. A live
 * stream and an upcoming premiere both report no usable duration.
 */
fun isCompletedAt(positionSeconds: Int, durationSeconds: Int?): Boolean {
    if (durationSeconds == null || durationSeconds <= 0) return false
    val remaining = durationSeconds - positionSeconds
    return remaining <= completionThresholdSeconds(durationSeconds)
}

/**
 * Where a video opens, or null when it starts from the beginning.
 *
 * An In progress video auto-resumes. Spec section 8.6. Three things stop a resume:
 *
 * - the household chose `Play from start` in the card menu, which is what [playFromStart] is;
 * - the video is completed, so there is nothing left to resume into;
 * - the stored position is below [PLAY_THRESHOLD_SECONDS].
 *
 * The last one cannot happen from a play, because no entry is written below the threshold. It
 * can happen from a log written by hand or by a later build, and resuming a video three
 * seconds in is worse than starting it.
 */
fun resumeFromSeconds(state: WatchState?, playFromStart: Boolean = false): Int? {
    if (playFromStart || state == null || state.completed) return null
    val position = state.positionSeconds ?: return null
    return position.takeIf { it >= PLAY_THRESHOLD_SECONDS }
}

/**
 * Whether the position has moved far enough to be written again.
 *
 * The distance is measured both ways on purpose. A seek backwards moves the position just as
 * much as a seek forwards does, and the household that jumps back to rewatch a passage and
 * then leaves should not have the position from before the jump written down.
 */
fun shouldWritePosition(positionSeconds: Int, lastWrittenSeconds: Int?): Boolean =
    lastWrittenSeconds == null ||
        abs(positionSeconds - lastWrittenSeconds) >= POSITION_WRITE_INTERVAL_SECONDS

/**
 * Whether a polled position can be believed.
 *
 * **During an in-stream ad the player reports the ad's own clock.** `getCurrentTime()` starts
 * again from zero while `getPlayerState()` still says PLAYING, exactly as `getDuration()`
 * reports the ad's length. Spec section 8.4 already distrusts the duration for this reason;
 * the position needs the same distrust.
 *
 * Without this rule, a mid-roll ad fifteen minutes into a video writes `position = 0` into the
 * household's log, which throws away the resume point and empties the red In progress bar.
 * The same thing happens whenever the player answers a time it does not know, because both
 * `-1` and `NaN` are read as the beginning.
 *
 * So a position that jumps **backwards** by more than one seek step is thrown away, unless a
 * seek is in flight, which is the one thing that legitimately moves the clock backwards.
 * Nothing is written and nothing on screen moves: the position simply stays where it was until
 * the content comes back.
 *
 * Jumping forwards is always believed. There is no ad that makes the clock run ahead, and a
 * video that buffers past a few seconds really did move.
 */
fun isPositionBelievable(
    positionSeconds: Int,
    lastSeenSeconds: Int?,
    seeking: Boolean,
): Boolean {
    if (seeking || lastSeenSeconds == null) return true
    return lastSeenSeconds - positionSeconds <= SEEK_STEP_SECONDS
}

/**
 * The player parameters, in the order spec section 8.2 lists them.
 *
 * They are built here rather than written into the page so that the page has no rule of its
 * own to get wrong, and so that a parameter going missing fails a JVM test instead of showing
 * up as captions on the television.
 *
 * - `autoplay=1` with `mediaPlaybackRequiresUserGesture = false` is what starts a video with
 *   no pointer on the screen.
 * - `controls=0` and `disablekb=1` keep every key for the app. The WebView is not focusable
 *   either, which is the real defence; these two are the second line.
 * - `rel=0` does **not** turn the related-video wall off. It limits it to the same channel.
 *   It is kept as a safety net. Replacing the wall is ticket 08's autoplay-next.
 * - `hl` and `cc_lang_pref` are the auto-dubbing mitigation of spec section 8.7. They are a
 *   mitigation and not a control; there is no API for the audio track.
 * - `cc_load_policy=3` is undocumented folklore. Captions are really turned off by the
 *   captions module. See `killCaptions` in `PlayerHtml`.
 */
fun playerVars(hl: String = PLAYER_LANGUAGE): Map<String, String> = linkedMapOf(
    "autoplay" to "1",
    "controls" to "0",
    "disablekb" to "1",
    "rel" to "0",
    "fs" to "0",
    "modestbranding" to "1",
    "iv_load_policy" to "3",
    "playsinline" to "1",
    "hl" to hl,
    "cc_lang_pref" to hl,
    "cc_load_policy" to "3",
)

/** The player's interface language, and the caption language if one ever appears. */
const val PLAYER_LANGUAGE = "en"

// ---------------------------------------------------------------- the chain of videos

/**
 * How long the countdown over the paused next video lasts. Spec section 8.6 fixes it at five.
 *
 * It is long enough to read the next title and press `BACK`, and short enough that a
 * household watching a Source straight through does not sit through it every time.
 */
const val COUNTDOWN_SECONDS = 5

/**
 * How long playback may run with nobody touching the remote. Spec section 8.6: **two hours**.
 *
 * It is a whole number of seconds so a test, and a verification on a device, can hand in a
 * short one. The constant the app ships is this one, and `PlaybackRulesTest` asserts it is
 * 7,200 seconds, so shortening it for a verification cannot leave a short timeout behind.
 */
const val UNATTENDED_LIMIT_SECONDS = 2 * 60 * 60

/**
 * Whether nobody has touched the remote for long enough to stop playback.
 *
 * Spec section 8.6 stops **at the end of the current video**, never in the middle of one, so
 * this is asked once, when a video ends, and never on a timer that could cut a video short.
 * Any key press moves [lastKeyAtMillis] forward, which is what "any key press resets the
 * timer" means.
 *
 * Both times come from the same clock, and it must be one that only goes up:
 * `SystemClock.elapsedRealtime`, never the wall clock, which a television corrects over the
 * network and can move backwards.
 *
 * @param limitSeconds [UNATTENDED_LIMIT_SECONDS] in the shipped app. A verification hands in
 *   a short one, so the stop can be watched happening.
 */
fun isUnattended(
    lastKeyAtMillis: Long,
    nowMillis: Long,
    limitSeconds: Int = UNATTENDED_LIMIT_SECONDS,
): Boolean = nowMillis - lastKeyAtMillis >= limitSeconds * 1_000L

/**
 * Whether this player state proves the video the app asked for is the one on the page.
 *
 * It is what takes the opaque cover of ticket 17 back down, so it has to be a state the
 * player cannot report while YouTube's related-video wall is still up. Four of the six
 * qualify, and each one means the player has committed to the new video:
 *
 * - `BUFFERING`, which is the new video fetching its first bytes;
 * - `PLAYING`;
 * - `PAUSED`, which is where the autoplay-next countdown holds it;
 * - `CUED`, which nothing in this app asks for and which is included anyway, because a
 *   player that reports it has a video ready and is not showing a wall.
 *
 * The two that do not qualify are `UNSTARTED`, which is the state a player sits in with the
 * wall on it, and `ENDED`, which is the wall's own state.
 *
 * **This is not proof of a painted frame**, and no such proof exists: the IFrame API has no
 * frame callback and `getCurrentTime` is the nearest thing to one. What it does prove is that
 * `loadVideoById` has taken effect, so the wall is gone. Between that and the first decoded
 * frame the picture is black, and black is not the defect.
 */
fun isVideoOnScreen(stateCode: Int): Boolean =
    stateCode == PlayerStateCode.BUFFERING ||
        stateCode == PlayerStateCode.PLAYING ||
        stateCode == PlayerStateCode.PAUSED ||
        stateCode == PlayerStateCode.CUED

/**
 * Whether the opaque cover over the related-video wall may come down. Ticket 17.
 *
 * Three conditions, asked of the play that is on the player **now**, and none is redundant.
 *
 * - The play has not itself finished. A video that ended is the video the wall belongs to,
 *   so it can never be the proof that the wall is gone. This is what holds the cover up at
 *   the end of a Source, where nothing is loaded after the end and so no other signal ever
 *   arrives.
 * - The player has confirmed the video is on the page, which is [isVideoOnScreen].
 * - No countdown is running. The countdown is drawn **on** the cover, so a cover that came
 *   down the moment the player confirmed the video would take the countdown with it and
 *   leave the household watching a paused first frame with nothing to say why. It also keeps
 *   the cover up while a household walks along the Source with next and previous, which
 *   loads a new video each time.
 *
 * @param videoFinished [PlaybackUi.finished] of the play on the player now.
 * @param videoOnScreen [PlaybackUi.onScreen] of the play on the player now.
 * @param countingDown true while the five second countdown is running.
 */
fun coverMayComeDown(
    videoFinished: Boolean,
    videoOnScreen: Boolean,
    countingDown: Boolean,
): Boolean = !videoFinished && videoOnScreen && !countingDown

/**
 * What the player does when a video reaches its end. Spec section 8.6.
 *
 * It is a value and not a branch inside the Activity, so all four endings are JVM unit tests
 * and none of them needs a television.
 */
sealed interface AfterVideo {

    /**
     * Autoplay-next is on and there is a next video: load it now, pause it, and count down.
     *
     * The load happens **before** the countdown is drawn, not after it. The related-video
     * wall cannot be turned off, so the only way never to show it is to have replaced it
     * already. Spec sections 8.6 and 8.7.
     */
    data class Countdown(val next: StoredVideo) : AfterVideo

    /** Autoplay-next is off: leave the player and put the highlight on the next video. */
    data class LandOnNext(val next: StoredVideo) : AfterVideo

    /**
     * There is no next video: playback stops with a plain message and never wraps around.
     *
     * It is the same answer whether autoplay-next is on or off, because spec section 8.6
     * writes "at the end of the Source, playback stops with a plain message" with no
     * condition on it.
     */
    data object EndOfSource : AfterVideo

    /**
     * Two hours went by with no key press, so playback stops at this video's end.
     *
     * It beats autoplay-next: a household that fell asleep two videos ago must not be handed
     * a third. The highlight still goes to the next video, so the Source screen they find in
     * the morning is where they stopped. Spec section 8.6.
     */
    data class Unattended(val next: StoredVideo?) : AfterVideo
}

/**
 * Which of the four endings this one is.
 *
 * The order of the tests is the rule. Unattended comes first, because a household that has
 * not touched the remote for two hours has stopped watching whatever the toggle says. Then
 * the end of the Source, because there is nothing to autoplay into. Only then the toggle.
 *
 * @param next the next playable video in the Source's Direction, or null at the end of the
 *   Source. It is worked out by `nextPlayableVideo` in `data/Landings.kt`, which is what
 *   makes autoplay skip Shorts, unavailable videos and upcoming premieres.
 */
fun afterVideo(
    next: StoredVideo?,
    autoplayNext: Boolean,
    unattended: Boolean = false,
): AfterVideo = when {
    unattended -> AfterVideo.Unattended(next)
    next == null -> AfterVideo.EndOfSource
    autoplayNext -> AfterVideo.Countdown(next)
    else -> AfterVideo.LandOnNext(next)
}
