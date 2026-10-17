package local.youtubetv.ui

import local.youtubetv.data.WatchState

/**
 * How much of a card's red In progress bar is filled, or null when it carries none.
 *
 * `CONTEXT.md` defines In progress as a video whose latest Watch has a position but is not
 * completed. Spec section 7.3 draws it as a red bar on the bottom edge of the thumbnail. It is
 * the other half of the green tick: the tick says the household has seen a video, and the bar
 * says how far it got in one it has not finished.
 *
 * Four things mean no bar:
 *
 * - the log says nothing about this video;
 * - the video is completed, so the tick already tells the whole story;
 * - there is no position, which is what a manual `Mark as watched` writes;
 * - the Data API gave no duration, so there is no length to measure against. A live stream
 *   and an upcoming premiere are both like this.
 *
 * The fraction is clamped, because a position past the duration is possible: the duration is
 * the Data API's, the position is the player's, and the two are measured differently.
 */
fun inProgressFraction(state: WatchState?, durationSeconds: Int?): Float? {
    if (state == null || state.completed) return null
    val position = state.positionSeconds ?: return null
    if (durationSeconds == null || durationSeconds <= 0) return null
    return (position.toFloat() / durationSeconds).coerceIn(0f, 1f)
}
