package local.youtubetv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import local.youtubetv.data.StoredVideo
import local.youtubetv.ui.Fonts
import local.youtubetv.ui.Ink
import local.youtubetv.ui.exactDate
import local.youtubetv.ui.screenInk

/** How long the `Resuming from 12:34` message stays before it fades. Spec section 8.6. */
private const val RESUMING_MILLIS = 4_000L

/** How long the position and title stay on screen after a key press. */
private const val TRANSPORT_MILLIS = 3_000L

/** How long a short message such as "This is the last video in this Source." stays. */
private const val NOTICE_MILLIS = 2_600L

/**
 * The five second countdown over the paused next video. Spec section 8.6.
 *
 * It carries the video rather than reading it off the session because it is drawn on the
 * handover cover, with the **next** video's own session already in place underneath: this
 * says what the household is about to get and how long they have to stop it.
 *
 * @property secondsLeft counts 5, 4, 3, 2, 1 and then the video plays.
 */
data class CountdownUi(val next: StoredVideo, val secondsLeft: Int)

/**
 * One short message over the player, and how many have been said before it.
 *
 * [seq] is there so that the same words twice are two messages. Without it the effect that
 * takes the message away is keyed on text that did not change, never restarts, and the second
 * message is on screen for a moment. `ui/SourceScreen.kt` learnt this from `/code-review`.
 */
data class PlayerNotice(val seq: Long, val message: String)

/**
 * A video, full screen.
 *
 * The screen draws and nothing more. It holds no keys: the WebView is not focusable and the
 * Activity takes every key in `dispatchKeyEvent`, because a focusable WebView starts moving
 * Chromium's own focus ring around YouTube's chrome. Spec sections 8.1 and 8.5.
 *
 * Three things are drawn over the video, and nothing else:
 *
 * - the `Resuming from 12:34` message, which fades on its own;
 * - the title and the position, which appear on a key press and fade, and stay while paused;
 * - a player error, which stays until the household leaves.
 *
 * @param nudge bumped by the Activity on every key press it handled. It is what brings the
 *   title and position back, because with `controls=0` there is nothing on screen at all and
 *   a household that presses pause would otherwise see a still frame and no reason for it.
 * @param countdown the five second countdown over the paused next video, or null when a
 *   video is simply playing. It is drawn on [covered]'s panel. Spec section 8.6.
 * @param covered true from the instant a video ends until the next one is confirmed on the
 *   page. Ticket 17: behind it is YouTube's related-video wall.
 * @param notice one short sentence, such as the answer to a press of next on the last video.
 * @param endOfSource true when the Source has run out and playback has stopped. Spec section
 *   8.6 asks for a plain message and no wrap-around.
 */
@Composable
fun PlayerScreen(
    controller: PlayerController,
    session: PlaybackSession,
    nudge: Long,
    countdown: CountdownUi? = null,
    covered: Boolean = false,
    notice: PlayerNotice? = null,
    endOfSource: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val ink = screenInk()
    val state by session.state.collectAsState()

    // Keyed on the session, and the session is one play. Autoplay-next keeps one player
    // across a whole chain of videos, so without the key the second video of a chain would
    // never show its own `Resuming from 12:34`: this is the same call site every time.
    var resumingVisible by remember(session) { mutableStateOf(state.resumeFromSeconds != null) }
    // Keyed on the session for the same reason, and on nothing else, so it is never
    // restarted within one play. The wait starts when the video starts, because loading the
    // page and the player takes a second or two and a message that spent half its life over
    // a black screen would be half read. Keying it on `playing` instead cancelled it
    // whenever the household paused, and the message then sat there until they pressed play.
    //
    // Keyed on whether a countdown is up as well, because the message is hidden behind one.
    // A video loaded for a countdown reaches PLAYING for a moment before the pause lands, so
    // without this key its four seconds would run out inside the five second countdown and
    // the household would never see the message at all. Found by `/code-review`.
    LaunchedEffect(session, countdown == null) {
        if (!resumingVisible || countdown != null) return@LaunchedEffect
        session.state.first { it.playing }
        delay(RESUMING_MILLIS)
        resumingVisible = false
    }

    var transportVisible by remember { mutableStateOf(true) }
    LaunchedEffect(nudge, state.playing) {
        transportVisible = true
        // While paused it stays. A still frame with nothing on it says nothing about why.
        if (!state.playing) return@LaunchedEffect
        delay(TRANSPORT_MILLIS)
        transportVisible = false
    }

    var noticeShown by remember { mutableStateOf<PlayerNotice?>(null) }
    LaunchedEffect(notice) {
        noticeShown = notice
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MILLIS)
        noticeShown = null
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            // The controller built the WebView once, outside composition. Building it in the
            // factory would rebuild the player whenever Compose decided to run the factory
            // again, and the household would see the video restart.
            factory = { controller.view },
            modifier = Modifier.fillMaxSize(),
        )

        // A countdown is only ever drawn on the cover, so a countdown with no cover would
        // leave the wall showing behind the next title. The two are drawn as one thing.
        val wallIsCovered = covered || countdown != null

        // Hidden while a notice is up, because both pills are drawn in the same corner and
        // one would be laid straight over the other. Found by `/code-review`.
        AnimatedVisibility(
            visible = resumingVisible &&
                state.errorReason == null &&
                !wallIsCovered &&
                noticeShown == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(48.dp),
        ) {
            Pill(text = resumingLine(state.resumeFromSeconds ?: 0), ink = ink)
        }

        // Nothing is drawn over the video while the cover is up, because the cover is what
        // the household is looking at and the video behind it is not on screen.
        AnimatedVisibility(
            visible = transportVisible && state.errorReason == null && !wallIsCovered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Transport(
                title = session.video.title.ifBlank { session.video.videoId },
                positionSeconds = state.positionSeconds,
                durationSeconds = state.durationSeconds,
                playing = state.playing,
                ink = ink,
            )
        }

        // Ticket 17. A video has ended, so YouTube's related-video wall is on the page
        // behind this, and the WebView is not focusable so nothing in the app can press it.
        // Opaque and full screen from the instant `ENDED` arrives, and the countdown is
        // drawn on it rather than beside it.
        if (wallIsCovered && state.errorReason == null) {
            HandoverCover(ink = ink, countdown = countdown)
        }

        // After the cover, so a `There is no next video.` answered during a countdown is
        // read rather than hidden by the thing it was said over.
        noticeShown?.let { shown ->
            Box(modifier = Modifier.align(Alignment.TopStart).padding(48.dp)) {
                Pill(text = shown.message, ink = ink)
            }
        }

        // The end of the Source, with no wrap-around. Spec section 8.6.
        //
        // Opaque and full screen, for the same reason the error panel is: behind it is
        // YouTube's own related-video wall, which cannot be turned off and which nothing in
        // this app can press, because the WebView is not focusable. Drawn after the cover,
        // so the one that says something wins over the one that only hides.
        if (endOfSource) {
            Panel(ink = ink, heading = END_OF_SOURCE, detail = null, wayOut = PLAYER_ERROR_WAY_OUT)
        }

        state.errorReason?.let { reason ->
            Panel(
                ink = ink,
                heading = session.video.title.ifBlank { session.video.videoId },
                detail = reason,
                wayOut = PLAYER_ERROR_WAY_OUT,
            )
        }
    }
}

/**
 * One opaque panel over the whole picture: a player error, or the end of the Source.
 *
 * Opaque, not a scrim. Underneath is YouTube's own page, either its refusal page or the
 * related-video wall, and both carry buttons the household cannot press: the WebView is not
 * focusable, so nothing in the page can ever take the highlight. Showing two things at once,
 * one of them unusable, is worse than showing one. Seen on the television on 2026-09-20.
 */
@Composable
private fun Panel(ink: Ink, heading: String, detail: String?, wayOut: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(ink.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 720.dp)
                .background(ink.surfaceRaised, RoundedCornerShape(14.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text(
                text = heading,
                color = ink.onSurface,
                style = Fonts.FocusedTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Spacer(Modifier.height(10.dp))
                Text(text = it, color = ink.onSurfaceMuted, style = Fonts.Meta)
            }
            Spacer(Modifier.height(14.dp))
            Text(text = wayOut, color = ink.onSurfaceFaint, style = Fonts.Meta)
        }
    }
}

/**
 * The opaque cover across a handover from one video to the next. Ticket 17.
 *
 * **It exists to hide YouTube's related-video wall**, which is painted the moment a video
 * ends, cannot be turned off by any player parameter, and carries buttons the household can
 * never press because the WebView is not focusable. `rel=0` only limits the suggestions to
 * the same channel. The wall is beaten by covering it and by nothing else.
 *
 * Opaque, not a scrim: `ink.surface` is `#FF0E0F12`, the same solid the error panel and the
 * end-of-Source panel are drawn on. A scrim would show the wall through it, which is what
 * the bottom countdown strip this replaces did, and what the owner saw on the television on
 * 2026-09-20.
 *
 * The countdown is drawn **on** it, in the same raised card the other two panels use, so a
 * handover is one thing on screen and not two. With no countdown the cover is a plain dark
 * screen: that is the sub-second gap before the countdown starts, and the moment after the
 * household cuts the countdown short with `OK`.
 *
 * The title and the publish date, because those are the two facts the household decides on,
 * and they are the same two the video card carries. The date is the **exact** day, in the
 * same format the grid uses: this app shows no relative ages anywhere. Spec section 7.3.
 */
@Composable
private fun HandoverCover(ink: Ink, countdown: CountdownUi?) {
    Box(
        modifier = Modifier.fillMaxSize().background(ink.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (countdown == null) return@Box
        val next = countdown.next
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 720.dp)
                .background(ink.surfaceRaised, RoundedCornerShape(14.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text(
                text = NEXT_UP_LABEL,
                color = ink.onSurfaceFaint,
                style = Fonts.Meta,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = next.title.ifBlank { next.videoId },
                color = ink.onSurface,
                style = Fonts.FocusedTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = exactDate(next.videoPublishedAt),
                color = ink.onSurfaceMuted,
                style = Fonts.Meta,
                maxLines = 1,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = countdownLine(countdown.secondsLeft),
                color = ink.inProgress,
                style = Fonts.Meta,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = COUNTDOWN_WAY_OUT,
                color = ink.onSurfaceFaint,
                style = Fonts.Meta,
                maxLines = 1,
            )
        }
    }
}

/** The title, the position and a progress bar, over the bottom of the picture. */
@Composable
private fun Transport(
    title: String,
    positionSeconds: Int,
    durationSeconds: Int?,
    playing: Boolean,
    ink: Ink,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ink.badgeScrim)
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        Text(
            text = title,
            color = ink.onSurface,
            style = Fonts.FocusedTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // The word, not a symbol. A pause glyph depends on whatever font the television
            // falls back to, which is the same reason the watched tick is drawn and not typed.
            text = if (playing) {
                positionLine(positionSeconds, durationSeconds)
            } else {
                "Paused  ${positionLine(positionSeconds, durationSeconds)}"
            },
            color = ink.onSurfaceMuted,
            style = Fonts.Meta,
            maxLines = 1,
        )
        if (durationSeconds != null && durationSeconds > 0) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(
                fraction = (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f),
                ink = ink,
            )
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, ink: Ink) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(ink.inProgressTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(ink.inProgress),
        )
    }
}

@Composable
private fun Pill(text: String, ink: Ink) {
    Box(
        modifier = Modifier
            .background(ink.surfaceRaised, RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text = text, color = ink.onSurface, style = Fonts.Meta, maxLines = 1)
    }
}
