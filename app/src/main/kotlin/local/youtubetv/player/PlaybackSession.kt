package local.youtubetv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.youtubetv.data.PlayInProgress
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.data.WatchState

/**
 * What the player draws over the video.
 *
 * @property positionSeconds where the play is, polled every 250 ms. Spec section 8.4.
 * @property started true once this play has its `played` entry, so ten seconds have passed.
 * @property resumeFromSeconds the position an In progress video picked up from, or null. The
 *   screen turns it into the fading `Resuming from 12:34` message of spec section 8.6.
 * @property errorReason one short sentence when the player refused the video. The player
 *   stays where it is and nothing is written. Spec section 8.6.
 * @property quality what the player reports it is showing, such as `hd1080`. Nothing can ask
 *   for a quality, so this is only ever read back. Spec section 8.7.
 * @property finished the video reached its end and the household should be back on the grid.
 * @property onScreen the player has confirmed this video is the one on the page, so whatever
 *   was there before it, which after a handover is YouTube's related-video wall, is gone. It
 *   is what takes ticket 17's opaque cover back down. See [isVideoOnScreen]. It never goes
 *   back to false: one session is one video on one load.
 */
data class PlaybackUi(
    val positionSeconds: Int = 0,
    val durationSeconds: Int? = null,
    val playing: Boolean = false,
    val started: Boolean = false,
    val resumeFromSeconds: Int? = null,
    val errorReason: String? = null,
    val quality: String = "",
    val finished: Boolean = false,
    val onScreen: Boolean = false,
)

/**
 * One play of one video, from the first frame to the moment the household leaves it.
 *
 * It is the rule half of playback. It takes [PlayerEvent]s in, decides when the household's
 * Watch log is written, and says what the screen draws. It holds no WebView and no Android
 * import, so every rule in spec sections 8.4 and 8.6 is a JVM unit test: the ten-second
 * threshold, the ten-second position cadence, the completion threshold, and the rule that a
 * player error writes nothing at all.
 *
 * The [WatchLog] it writes through is the app's only door to the log, and in a test it is the
 * same class over an `InMemoryWatchStore`, so a test proves the rule and not a fake of it.
 *
 * @param resumeFrom where this play starts, from [resumeFromSeconds]. The session does not
 *   work it out itself, because `Play from start` is a choice made on the Source screen before
 *   the player exists.
 * @param onWatchState called whenever this video's derived row changes, so the card behind the
 *   player already carries its green tick and its red In progress bar when the household gets
 *   back to the grid.
 */
class PlaybackSession(
    val video: StoredVideo,
    private val watches: WatchLog,
    resumeFrom: Int? = null,
    heldForCountdown: Boolean = false,
    /**
     * True when Shuffle chose this play. Its `played` entry then carries the Shuffle mark, so
     * it never moves the High-water mark. Build ticket 69 and ADR 0002.
     */
    private val shuffle: Boolean = false,
    private val log: (String) -> Unit = {},
    private val onWatchState: (WatchState) -> Unit = {},
) {

    private val mutableState = MutableStateFlow(
        PlaybackUi(
            positionSeconds = resumeFrom ?: 0,
            durationSeconds = video.durationSeconds,
            resumeFromSeconds = resumeFrom,
        ),
    )
    val state: StateFlow<PlaybackUi> = mutableState.asStateFlow()

    /** This play's own entry, once ten seconds have passed. The log's one mutable entry. */
    private var play: PlayInProgress? = null

    /** The position last written down, so the ten-second cadence is a distance, not a timer. */
    private var lastWrittenSeconds: Int? = null

    /** The last position that was believed, which is what an ad's clock is measured against. */
    private var lastSeenSeconds: Int? = resumeFrom

    /** True while the player's clock is being ignored, so the logcat line is written once. */
    private var ignoringPosition = false

    /**
     * True once the player has refused the video.
     *
     * From then on nothing is written, ever. A failed play must never close a Next unwatched
     * gap. Spec section 8.6. An entry written before the failure is left exactly as it was,
     * because that part of the video really was played.
     */
    private var refused = false

    /**
     * True once [startPlay] has been asked and answered with nothing.
     *
     * It cannot normally happen. If it does, asking again on the next poll would append
     * another entry every 250 ms for the rest of the video, and the log is append-only and
     * never compacted in v1. One failure is one failure.
     */
    private var startRefusedByTheLog = false

    /**
     * True while the autoplay-next countdown is sitting over this video, so **nothing is
     * written at all**.
     *
     * The video behind the countdown is already loaded and already paused, and an In progress
     * one is loaded at its stored position. Without this, three things went wrong at once,
     * and `/code-review` found all three in one finding:
     *
     * - `BACK` during the countdown called [onLeaving], which wrote a `played` entry at the
     *   stored position, for a video the household had just declined;
     * - the page reaches PLAYING for a moment before the pause lands, so a tick with
     *   `playing = true` wrote the same entry even without a key press;
     * - either write moved Last played onto a video nobody watched, and put today's date on
     *   its card.
     *
     * The ten second threshold exists to stop exactly this after a mis-press, and it cannot
     * help here, because a resumed position is above it by construction. So the play is held
     * until the household lets it start. [release] is what lets it.
     */
    private var held = heldForCountdown

    /**
     * True once the household has left this video for good.
     *
     * The page keeps polling for a moment after `BACK`, because the WebView's own thread has
     * work already queued. Those late ticks must not reach the entry: spec section 3.3 says a
     * play's entry freezes when the play leaves the screen.
     */
    private var closed = false

    /**
     * The countdown is over and this video is really being watched, so it may be written now.
     *
     * Called when the five seconds run out, and when the household presses the centre button
     * to play it at once. It is deliberately not called by anything else: a video the
     * household walked past with next, or left with `BACK`, stays held and leaves no trace.
     */
    fun release() {
        held = false
    }

    /** Every event the page pushes, in the order it pushed them. */
    fun onEvent(event: PlayerEvent) {
        if (closed) return
        when (event) {
            is PlayerEvent.Tick -> onTick(event)
            is PlayerEvent.State -> onState(event)
            PlayerEvent.Ended -> onEnded()
            is PlayerEvent.Error -> onError(event.code)
            is PlayerEvent.Ready -> log("Player ready, origin ${event.origin}.")
            PlayerEvent.AutoplayBlocked ->
                log("Autoplay was blocked, which the WebView settings of spec 8.1 should stop.")
            is PlayerEvent.Captions -> log(
                "Captions on ${video.videoId} through '${event.module}': " +
                    "${event.tracks} tracks, was ${event.before}, now ${event.after}.",
            )
            is PlayerEvent.PageProblem -> log("Player page problem: ${event.detail}")
        }
    }

    private fun onTick(tick: PlayerEvent.Tick) {
        val before = mutableState.value

        // An in-stream ad reports its own clock, counting from zero, while the state still
        // says PLAYING. Taking it would write `position = 0` over a real position and throw
        // the household's resume point away. Nothing moves until the content comes back.
        if (!isPositionBelievable(tick.positionSeconds, lastSeenSeconds, tick.seeking)) {
            if (!ignoringPosition) {
                ignoringPosition = true
                log(
                    "The player's clock jumped back to ${clock(tick.positionSeconds)} from " +
                        "${clock(lastSeenSeconds ?: 0)} with no seek, which is what an " +
                        "in-stream ad looks like. The position is left alone.",
                )
            }
            return
        }
        if (ignoringPosition) {
            ignoringPosition = false
            log("The player's clock is back on the video at ${clock(tick.positionSeconds)}.")
        }
        lastSeenSeconds = tick.positionSeconds

        mutableState.value = before.copy(
            positionSeconds = tick.positionSeconds,
            playing = tick.playing,
            quality = tick.quality.ifBlank { before.quality },
        )
        // Nothing can ask for a quality: `setPlaybackQuality` stopped working in 2019. It is
        // only ever read back, and it is the one way to see whether the television really
        // reached `hd1080`, which spec section 8.7 says is the ceiling for this design.
        val quality = mutableState.value.quality
        if (quality.isNotBlank() && quality != before.quality) {
            log("Playback quality of ${video.videoId} is now $quality.")
        }
        if (tick.playing) record(force = false)
    }

    private fun onState(event: PlayerEvent.State) {
        // First, and before any other branch can return: ticket 17's opaque cover is waiting
        // on this, and the household is looking at a blank screen until it arrives.
        if (!mutableState.value.onScreen && isVideoOnScreen(event.code)) {
            mutableState.value = mutableState.value.copy(onScreen = true)
            // The code and not [PlayerEvent.State.name]: the page sends only the number, so
            // the name is always blank and the line read `state .` on the television.
            log("The player has ${video.videoId} on the page, state ${event.code}.")
        }
        if (event.code == PlayerStateCode.PAUSED) {
            mutableState.value = mutableState.value.copy(playing = false)
            // Spec section 8.4 writes the position on pause, whatever the cadence says. A
            // household that pauses and walks away has said where it stopped.
            record(force = true)
        }
    }

    /**
     * The video reached its end.
     *
     * The position is taken as the Data API's duration rather than whatever the player last
     * polled, because the poll can be a second or two short of the end and this play really
     * did reach it.
     *
     * What happens next is spec section 8.6, decided by `afterVideo` in the Activity. The
     * related-video wall is behind the player from this instant, and it cannot be turned off,
     * so the Activity puts ticket 17's opaque cover up on the same event that reaches here.
     */
    private fun onEnded() {
        val end = video.durationSeconds ?: mutableState.value.positionSeconds
        lastSeenSeconds = end
        mutableState.value = mutableState.value.copy(
            positionSeconds = end,
            playing = false,
            finished = true,
        )
        record(force = true, endedNow = true)
        log("Video ${video.videoId} ended at ${clock(end)}.")
    }

    private fun onError(code: Int) {
        refused = true
        mutableState.value = mutableState.value.copy(
            playing = false,
            errorReason = playerErrorReason(code),
        )
        log("Player error $code on ${video.videoId}. No entry was written.")
    }

    /**
     * The household pressed `BACK`. Spec section 8.4 writes the position on leaving.
     *
     * This play is over. Nothing the page says afterwards is acted on, because its entry is
     * frozen from here. The page keeps polling for a moment, since the WebView's own thread
     * has work already queued when the household presses the key.
     */
    fun onLeaving() {
        record(force = true)
        closed = true
    }

    /**
     * `Activity.onStop`.
     *
     * The last chance to write anything. Android can stop this Activity without another key
     * press ever arriving, for instance when the television is switched off at the remote or
     * the household presses Home. Spec section 8.4 names it explicitly.
     */
    fun onStopped() {
        record(force = true)
    }

    /**
     * Writes this play down, if there is anything to write.
     *
     * The three rules that decide are all in `PlaybackRules.kt`, so this function only puts
     * them in order:
     *
     * 0. A play the autoplay-next countdown is still sitting over writes nothing at all.
     * 1. A play the player refused writes nothing at all, ever.
     * 2. Below ten seconds there is no entry yet and none is made, so the video stays Next
     *    unwatched after a mis-press. **Reaching `ENDED` is the exception**: a video shorter
     *    than ten seconds can be watched whole without ever passing the threshold, and it
     *    would otherwise stay Next unwatched for ever.
     * 3. After that, the entry is rewritten when the position has moved ten seconds, or when
     *    the caller says this is a moment that always writes: a pause, the end, leaving, or
     *    `onStop`.
     *
     * @param endedNow true only from `ENDED`, which is proof the whole video was watched.
     */
    private fun record(force: Boolean, endedNow: Boolean = false) {
        if (refused) return
        // A video the autoplay-next countdown is sitting over has not been watched yet, so
        // it leaves no trace at all until [release]. See [held].
        if (held) return
        val position = mutableState.value.positionSeconds
        val isCompleted = endedNow || isCompletedAt(position, video.durationSeconds)

        val open = play
        if (open == null) {
            if (startRefusedByTheLog) return
            if (position < PLAY_THRESHOLD_SECONDS && !endedNow) return
            val started = watches.startPlay(video, position, isCompleted, shuffle)
            if (started == null) {
                // Asking again on the next poll would append another entry every 250 ms for
                // the rest of the video, into a log that is never compacted.
                startRefusedByTheLog = true
                log("The Watch log would not open a play for ${video.videoId}. Not trying again.")
                return
            }
            play = started
            lastWrittenSeconds = position
            mutableState.value = mutableState.value.copy(started = true)
            onWatchState(started.state)
            log(
                "Play of ${video.videoId} passed $PLAY_THRESHOLD_SECONDS s: " +
                    "one played entry written at ${clock(position)}" +
                    if (isCompleted) ", completed." else ".",
            )
            return
        }

        if (!force && !shouldWritePosition(position, lastWrittenSeconds)) return
        // Nothing to say when the position has not moved and completion has not changed. A
        // pause followed by leaving would otherwise rewrite the same entry twice.
        if (position == lastWrittenSeconds && isCompleted == open.state.completed) return

        val updated = watches.updatePlay(open, video, position, isCompleted) ?: return
        play = updated
        lastWrittenSeconds = position
        onWatchState(updated.state)
        log(
            "Position of ${video.videoId} saved at ${clock(position)}" +
                if (isCompleted) ", completed." else ".",
        )
    }
}
