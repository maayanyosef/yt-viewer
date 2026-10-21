package local.youtubetv.remote

import local.youtubetv.apikey.formField
import local.youtubetv.apikey.tokenMatches
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone remote: what the phone is told, and what it may ask for.
 *
 * Everything in this file is a plain value or a pure function, so every rule of the remote is
 * a JVM unit test. The server is `RemoteServer.kt`, the page is `RemotePageHtml.kt`, and
 * `MainActivity` is the only thing that turns a [RemoteCommand] into something on the screen.
 *
 * The remote exists for one reason: the household's viewer is two years old. A parent on the
 * sofa with a phone can start a video, stop it, and set a sleep timer without the television
 * remote ever leaving the drawer.
 */

/** Which screen the television is on. The phone draws its controls from this. */
enum class RemoteScreen { HOME, SOURCE, PLAYER, OTHER }

/** One Source, as the phone lists it. */
data class RemoteSource(val sourceId: String, val name: String, val listedCount: Int)

/** One video, as the phone lists it. The picture comes from YouTube, by [videoId]. */
data class RemoteVideo(
    val videoId: String,
    val title: String,
    val publishedAtMillis: Long?,
    val durationSeconds: Int?,
    val watched: Boolean,
    val positionSeconds: Int?,
)

/** What is playing right now, or null when nothing is. */
data class RemoteNowPlaying(
    val videoId: String,
    val title: String,
    val positionSeconds: Int,
    val durationSeconds: Int?,
    val playing: Boolean,
)

/**
 * The sleep timer. Both may be set at once; whichever comes first stops playback.
 *
 * @property stopAfterThisVideo playback stops when the current video ends.
 * @property stopAtMillis `SystemClock.elapsedRealtime()` at which playback stops, or null.
 */
data class SleepTimer(val stopAfterThisVideo: Boolean = false, val stopAtMillis: Long? = null) {
    val isSet: Boolean get() = stopAfterThisVideo || stopAtMillis != null

    companion object {
        val OFF = SleepTimer()
    }
}

/** Everything the phone's page needs to draw itself, read on the television's main thread. */
data class RemoteSnapshot(
    val screen: RemoteScreen,
    val openSourceId: String?,
    val nowPlaying: RemoteNowPlaying?,
    val sources: List<RemoteSource>,
    val sleep: SleepTimer,
    /** `SystemClock.elapsedRealtime()` when the snapshot was taken, to show the timer's remaining minutes. */
    val nowMillis: Long,
    /** Whether child mode is on. See `ui/ChildMode.kt`. The phone is the easy way to turn it off. */
    val childMode: Boolean = false,
)

/** What the phone asked the television to do. */
sealed interface RemoteCommand {
    data object TogglePlay : RemoteCommand
    data object Play : RemoteCommand
    data object Pause : RemoteCommand
    data class SeekBy(val seconds: Int) : RemoteCommand
    data object NextVideo : RemoteCommand
    data object PreviousVideo : RemoteCommand

    /** `BACK`: leave the player, or close the Source, whichever is on screen. */
    data object Back : RemoteCommand

    data class OpenSource(val sourceId: String) : RemoteCommand
    data class PlayVideo(val sourceId: String, val videoId: String) : RemoteCommand

    /** Set, or clear with both false and null. */
    data class Sleep(val afterThisVideo: Boolean, val minutes: Int?) : RemoteCommand

    data class ChildMode(val on: Boolean) : RemoteCommand
}

/**
 * Reads the phone's `POST /cmd` body. Null for a body that names no known action.
 *
 * Numbers are bounded, so a mistyped request cannot ask for a seek of a year.
 */
fun parseRemoteCommand(body: String): RemoteCommand? {
    val action = formField(body, "action") ?: return null
    fun int(name: String): Int? = formField(body, name)?.trim()?.toIntOrNull()
    fun id(name: String): String? = formField(body, name)?.takeIf { it.isNotBlank() && it.length <= 64 }
    return when (action) {
        "toggle" -> RemoteCommand.TogglePlay
        "play" -> RemoteCommand.Play
        "pause" -> RemoteCommand.Pause
        "seek" -> int("by")?.takeIf { it in -3600..3600 }?.let { RemoteCommand.SeekBy(it) }
        "next" -> RemoteCommand.NextVideo
        "prev" -> RemoteCommand.PreviousVideo
        "back" -> RemoteCommand.Back
        "open_source" -> id("source")?.let { RemoteCommand.OpenSource(it) }
        "play_video" -> {
            val source = id("source")
            val video = id("video")
            if (source != null && video != null) RemoteCommand.PlayVideo(source, video) else null
        }
        "sleep" -> {
            val minutes = int("minutes")?.takeIf { it in 1..600 }
            RemoteCommand.Sleep(afterThisVideo = formField(body, "after_video") == "1", minutes = minutes)
        }
        "child_mode" -> RemoteCommand.ChildMode(on = formField(body, "on") == "1")
        else -> null
    }
}

/** What one request to the remote asks for. The routing is a pure function. */
sealed interface RemoteRequest {
    data object ShowPage : RemoteRequest
    data object State : RemoteRequest

    /** The video list of one Source, a page at a time. */
    data class Videos(val sourceId: String, val offset: Int, val limit: Int, val query: String) : RemoteRequest

    data class Command(val command: RemoteCommand) : RemoteRequest

    /** A command the page sent that this build does not know, or one with a bad number. */
    data object BadCommand : RemoteRequest
    data object NotFound : RemoteRequest
    data object MethodNotAllowed : RemoteRequest
}

/** How many videos one page of the list carries at most. */
const val REMOTE_VIDEOS_PAGE = 60

/**
 * Decides what one request is.
 *
 * The token is the first path segment, and nothing is served without it. A wrong token answers
 * exactly like a path that does not exist, as the Key page does.
 */
fun routeRemoteRequest(method: String, target: String, body: String, token: String): RemoteRequest {
    val path = target.substringBefore('?').trimEnd('/')
    val query = target.substringAfter('?', "")
    val segments = path.removePrefix("/").split('/')
    if (!path.startsWith("/") || segments.isEmpty() || !tokenMatches(segments[0], token)) {
        return RemoteRequest.NotFound
    }
    val rest = segments.drop(1).joinToString("/")
    return when (rest) {
        "" -> if (method == "GET" || method == "HEAD") RemoteRequest.ShowPage else RemoteRequest.MethodNotAllowed
        "state" -> if (method == "GET") RemoteRequest.State else RemoteRequest.MethodNotAllowed
        "videos" -> {
            if (method != "GET") return RemoteRequest.MethodNotAllowed
            val source = formField(query, "source")?.takeIf { it.isNotBlank() } ?: return RemoteRequest.NotFound
            RemoteRequest.Videos(
                sourceId = source,
                offset = (formField(query, "offset")?.toIntOrNull() ?: 0).coerceAtLeast(0),
                limit = (formField(query, "limit")?.toIntOrNull() ?: REMOTE_VIDEOS_PAGE).coerceIn(1, REMOTE_VIDEOS_PAGE),
                query = formField(query, "q").orEmpty().trim(),
            )
        }
        "cmd" -> {
            if (method != "POST") return RemoteRequest.MethodNotAllowed
            parseRemoteCommand(body)?.let { RemoteRequest.Command(it) } ?: RemoteRequest.BadCommand
        }
        else -> RemoteRequest.NotFound
    }
}

/**
 * The videos of one Source that match a search, one page of them, and how many matched in all.
 *
 * The search is on the title, case-insensitive, with every word having to appear. It is done
 * here on the television and costs no API unit: the titles are already in the database.
 */
fun pageOfVideos(all: List<RemoteVideo>, offset: Int, limit: Int, query: String): Pair<List<RemoteVideo>, Int> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val matching = if (words.isEmpty()) {
        all
    } else {
        all.filter { video -> words.all { word -> video.title.lowercase().contains(word) } }
    }
    return matching.drop(offset).take(limit) to matching.size
}

// ------------------------------------------------------------------------------ JSON

fun RemoteSnapshot.toJson(): String = JSONObject().apply {
    put("screen", screen.name.lowercase())
    put("openSource", openSourceId ?: JSONObject.NULL)
    put("childMode", childMode)
    put(
        "nowPlaying",
        nowPlaying?.let { now ->
            JSONObject().apply {
                put("videoId", now.videoId)
                put("title", now.title)
                put("position", now.positionSeconds)
                put("duration", now.durationSeconds ?: JSONObject.NULL)
                put("playing", now.playing)
            }
        } ?: JSONObject.NULL,
    )
    put(
        "sources",
        JSONArray().apply {
            sources.forEach { source ->
                put(
                    JSONObject().apply {
                        put("id", source.sourceId)
                        put("name", source.name)
                        put("count", source.listedCount)
                    },
                )
            }
        },
    )
    put(
        "sleep",
        JSONObject().apply {
            put("afterVideo", sleep.stopAfterThisVideo)
            val left = sleep.stopAtMillis?.let { ((it - nowMillis) / 1000).coerceAtLeast(0) }
            put("secondsLeft", left ?: JSONObject.NULL)
        },
    )
}.toString()

fun videosJson(videos: List<RemoteVideo>, total: Int, offset: Int): String = JSONObject().apply {
    put("total", total)
    put("offset", offset)
    put(
        "videos",
        JSONArray().apply {
            videos.forEach { video ->
                put(
                    JSONObject().apply {
                        put("id", video.videoId)
                        put("title", video.title)
                        put("published", video.publishedAtMillis ?: JSONObject.NULL)
                        put("duration", video.durationSeconds ?: JSONObject.NULL)
                        put("watched", video.watched)
                        put("position", video.positionSeconds ?: JSONObject.NULL)
                    },
                )
            }
        },
    )
}.toString()
