package local.youtubetv.player

import org.json.JSONException
import org.json.JSONObject

/**
 * Everything the page can tell the app.
 *
 * The page pushes one JSON object per event through the JavaScript bridge. Turning that
 * string into one of these is a rule like any other, so it lives here and is unit-tested,
 * and [PlaybackSession] never sees a string.
 */
sealed interface PlayerEvent {

    /** The IFrame API built the player. Nothing is loaded yet. */
    data class Ready(val origin: String) : PlayerEvent

    /**
     * A poll of `getCurrentTime()`, every 250 ms.
     *
     * @property positionSeconds where the player says it is. **This is not always the
     *   content's position**: during an in-stream ad it is the ad's own clock, counting from
     *   zero, while the state still says PLAYING. [isPositionBelievable] is what protects the
     *   household's log from that.
     * @property playing true while the player reports PLAYING or BUFFERING.
     * @property seeking true while a seek this app asked for has not landed yet. It is the
     *   one thing that legitimately moves the position backwards.
     * @property quality what the player says it is showing, such as `hd1080`. Never asked
     *   for: `setPlaybackQuality` stopped working in 2019. See spec section 8.7.
     */
    data class Tick(
        val positionSeconds: Int,
        val playing: Boolean,
        val quality: String,
        val seeking: Boolean = false,
    ) : PlayerEvent

    /** The player changed state. [code] is the IFrame API's own number. */
    data class State(val code: Int, val name: String) : PlayerEvent

    /** The video reached its end. */
    data object Ended : PlayerEvent

    /** The player refused the video. See [playerErrorReason]. */
    data class Error(val code: Int) : PlayerEvent

    /** Autoplay was blocked, which should never happen with the WebView settings of 8.1. */
    data object AutoplayBlocked : PlayerEvent

    /**
     * What the captions module said before and after its track was cleared.
     *
     * Turning captions off is an undocumented option, so the only honest way to know it
     * worked is to read the track back. It is also the only way to tell a caption YouTube
     * drew from a subtitle the creator burned into the picture, which no player can remove.
     *
     * @property tracks how many caption tracks the video offers. Zero means the video has
     *   none, so seeing it say `none` afterwards proves nothing.
     */
    data class Captions(
        val module: String,
        val tracks: Int,
        val before: String,
        val after: String,
    ) : PlayerEvent

    /** Something in the page threw, or the IFrame API script did not load. */
    data class PageProblem(val detail: String) : PlayerEvent
}

/** The IFrame API's player states, as spec section 8.4 and the API reference list them. */
object PlayerStateCode {
    const val UNSTARTED = -1
    const val ENDED = 0
    const val PLAYING = 1
    const val PAUSED = 2
    const val BUFFERING = 3
    const val CUED = 5
}

/**
 * One event, and which load of the page it belongs to.
 *
 * One player now shows a whole chain of videos, because autoplay-next has to replace the
 * related-video wall before it is seen and so cannot afford to build a new page. That leaves
 * the finished video's last ticks arriving while the next video is loading, and
 * [PlayerController] drops those by comparing [generation] with the load it is on.
 *
 * @property generation the page's `loadGen`, counted from one. **Zero means no load at all**:
 *   the player said it was ready, the IFrame script failed, or a script threw. Those belong
 *   to no video and are never dropped.
 */
data class PlayerEventEnvelope(val generation: Int, val event: PlayerEvent)

/**
 * One event from the page, or null when it says nothing this build acts on.
 *
 * Null rather than an exception, and a `try` around the parse, for the same reason
 * `WatchEntry.payloadObject()` has one: a string this app cannot read must never bring the
 * television down in the middle of a video.
 */
fun parsePlayerEvent(json: String): PlayerEvent? = parsePlayerEventEnvelope(json)?.event

/** The same parse, keeping the load number that [parsePlayerEvent] throws away. */
fun parsePlayerEventEnvelope(json: String): PlayerEventEnvelope? {
    val root = try {
        JSONObject(json)
    } catch (failure: JSONException) {
        return PlayerEventEnvelope(0, PlayerEvent.PageProblem("unreadable event: ${json.take(120)}"))
    }
    val data = root.optJSONObject("data") ?: JSONObject()
    val event = when (root.optString("type")) {
        "ready" -> PlayerEvent.Ready(origin = data.optString("origin"))
        "tick" -> {
            val state = data.optInt("state", PlayerStateCode.UNSTARTED)
            PlayerEvent.Tick(
                // Seconds, floored. The position the log keeps is a whole second: ADR 0001
                // stores an Int, and a quarter of a second is below anything the household
                // could notice when a video picks up again.
                positionSeconds = data.optDouble("t", 0.0).toSafeSeconds(),
                playing = state == PlayerStateCode.PLAYING || state == PlayerStateCode.BUFFERING,
                quality = data.optString("quality"),
                seeking = data.optBoolean("seeking"),
            )
        }
        "state" -> PlayerEvent.State(
            code = data.optInt("code", PlayerStateCode.UNSTARTED),
            name = data.optString("name"),
        )
        "ended" -> PlayerEvent.Ended
        "captions" -> PlayerEvent.Captions(
            module = data.optString("module"),
            tracks = data.optInt("tracks"),
            before = data.optString("before"),
            after = data.optString("after"),
        )
        "error" -> PlayerEvent.Error(code = data.optInt("code", -1))
        "autoplayBlocked" -> PlayerEvent.AutoplayBlocked
        "jsError", "apiScriptFailed" -> PlayerEvent.PageProblem(data.toString().take(200))
        else -> null
    }
    return event?.let { PlayerEventEnvelope(generation = root.optInt("gen"), event = it) }
}

/**
 * A polled time as whole seconds.
 *
 * The player answers -1 when it is asked before it is ready, and NaN has been seen between
 * videos. Both become 0, because "the very beginning" is the one answer that can never make
 * the app write a position the household did not reach.
 */
private fun Double.toSafeSeconds(): Int =
    if (isNaN() || this < 0.0) 0 else toInt()
