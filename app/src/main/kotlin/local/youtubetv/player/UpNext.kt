package local.youtubetv.player

import local.youtubetv.data.PlayStep
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.nextPlayableVideo

/** How many videos the up-next row shows. Six fit across a 1080p screen with room to read. */
const val UP_NEXT_COUNT = 6

/**
 * The row of videos that come after the one playing, drawn over the bottom of the picture
 * when the household presses `Down`.
 *
 * It exists because `Down` was the key every household member pressed looking for "more
 * videos", and it jumped five minutes back instead. The row is what they were looking for.
 *
 * @property videos the next playable videos in the Source's Direction, nearest first.
 * @property selected which of them the highlight is on.
 */
data class UpNextUi(val videos: List<StoredVideo>, val selected: Int) {
    val current: StoredVideo? get() = videos.getOrNull(selected)
}

/**
 * The videos the row shows: the next [count] playable ones after [fromVideoId], in the order
 * [videos] is already in, which is the Source's Direction. Shorts, unavailable videos and
 * upcoming premieres are stepped over, exactly as the channel rocker steps over them.
 */
fun upNextVideos(videos: List<StoredVideo>, fromVideoId: String, count: Int = UP_NEXT_COUNT): List<StoredVideo> {
    val result = mutableListOf<StoredVideo>()
    var from = fromVideoId
    while (result.size < count) {
        val next = nextPlayableVideo(videos, from, PlayStep.NEXT) ?: break
        result += next
        from = next.videoId
    }
    return result
}

/** `Left` or `Right` on the row. It stops at both ends, like every row in this app. */
fun UpNextUi.moved(right: Boolean): UpNextUi {
    if (videos.isEmpty()) return this
    val to = (if (right) selected + 1 else selected - 1).coerceIn(0, videos.size - 1)
    return copy(selected = to)
}

/** The row's title line. */
const val UP_NEXT_TITLE = "Up next"

/** What the player says when `Down` is pressed on the last video of the Source. */
const val UP_NEXT_EMPTY = "There is nothing after this video in this Source."
