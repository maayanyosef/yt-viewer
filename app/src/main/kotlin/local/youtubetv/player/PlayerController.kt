package local.youtubetv.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The WebView, and the only thing in the app that talks to it.
 *
 * It owns the page, the JavaScript bridge and the four commands the player understands. It
 * holds **no rule at all**: everything it learns goes straight to [onEvent] as a
 * [PlayerEvent], and everything it is told to do comes from the Activity's key handling. That
 * is what leaves the rules in `PlaybackRules.kt` and `PlaybackSession.kt`, where they are
 * testable with no device.
 *
 * @param onEvent called on the main thread, because the bridge is called on a WebView thread
 *   of its own and everything downstream of it touches Compose state.
 */
class PlayerController(
    context: Context,
    private val language: String = PLAYER_LANGUAGE,
    private val onEvent: (PlayerEvent) -> Unit,
    private val log: (String) -> Unit = {},
) {

    /** What the household is waiting to see, held until the player says it is ready. */
    private var pending: Load? = null

    private var ready = false

    /**
     * Which load the page is on, counted from one.
     *
     * One controller now shows a whole chain of videos, because autoplay-next has to replace
     * the related-video wall before it is seen and so cannot build a new page. That leaves
     * one page reporting for two videos at the handover: the finished video's last ticks are
     * still queued on the WebView's thread while the next video is loading.
     *
     * Every event the page sends carries the load it belongs to, and an event naming a load
     * this controller has moved on from is dropped here. Without it a tick left over from a
     * forty minute video would be written into the log as the position of the video that
     * replaced it, and a position past the completion threshold would mark it watched.
     */
    private var generation = 0

    @SuppressLint("SetJavaScriptEnabled")
    val view: WebView = WebView(context).apply {
        // The WebView must never hold focus. Chromium turns spatial navigation on at the
        // first D-pad key and starts moving a focus ring around YouTube's own chrome. Every
        // key is taken by the Activity instead. Spec section 8.1.
        isFocusable = false
        isFocusableInTouchMode = false
        setBackgroundColor(Color.BLACK)

        // Without explicit LayoutParams the WebView is measured as wrap-content inside a
        // Compose AndroidView. A wrap-content WebView reports a zero-height viewport, so the
        // player iframe is 0 tall, MediaCodec drops every frame and the television shows a
        // black screen with working audio. Found on the TCL, 2026-09-19. Spec section 8.1.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        settings.javaScriptEnabled = true
        // YouTube's player writes to localStorage.
        settings.domStorageEnabled = true
        // The default is true, which would block autoplay with no pointer on the screen.
        settings.mediaPlaybackRequiresUserGesture = false

        webViewClient = WebViewClient()
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Kept, and quiet. The player is the one part of this app whose failures are
                // invisible otherwise, and a console line is often the only sign of one.
                log("player console: ${message.message()}")
                return true
            }
        }
        addJavascriptInterface(Bridge(), PlayerHtml.BRIDGE)

        // loadDataWithBaseURL, never loadData: the base URL becomes window.origin and is sent
        // as the Referer for the player iframe. Without it the player answers error 153.
        loadDataWithBaseURL(
            PlayerHtml.BASE_URL,
            PlayerHtml.build(hl = language),
            "text/html",
            "UTF-8",
            null,
        )
    }

    private inner class Bridge {
        @JavascriptInterface
        fun event(json: String) {
            val envelope = parsePlayerEventEnvelope(json) ?: return
            view.post {
                val event = envelope.event
                if (event is PlayerEvent.Ready) {
                    ready = true
                    pending?.let { load ->
                        pending = null
                        sendLoad(load)
                    }
                }
                // Generation 0 is everything the page says that belongs to no load at all:
                // it is ready, the IFrame script failed, a script threw. Those always pass.
                if (envelope.generation != 0 && envelope.generation != generation) return@post
                onEvent(event)
            }
        }
    }

    /**
     * Shows a video, from [startSeconds].
     *
     * Asked for before the page is ready, it is remembered and sent the moment it is. The
     * household presses the centre button well before the IFrame API script has downloaded,
     * and losing that press would look like a dead remote.
     *
     * **The same controller loads every video of a chain.** Autoplay-next has to replace the
     * related-video wall before the household sees it, and a new page takes about two
     * seconds to build a player, so there is no time to make one. Spec sections 8.6 and 8.7.
     *
     * @param pauseOnStart stops the video on its first frame, which is what the five second
     *   countdown of spec section 8.6 plays behind. [play] is what ends it.
     */
    fun load(
        videoId: String,
        startSeconds: Int,
        durationSeconds: Int?,
        pauseOnStart: Boolean = false,
    ) {
        // Bumped here and not in `sendLoad`, so that a load asked for before the page is
        // ready already owns its number and every event of the load before it is stale from
        // this moment on.
        generation += 1
        val load = Load(videoId, startSeconds, durationSeconds, pauseOnStart, generation)
        if (!ready) {
            pending = load
            return
        }
        sendLoad(load)
    }

    private fun sendLoad(load: Load) {
        // The id comes from the Data API and is always letters, digits, dash and underscore.
        // It is still quoted through JSONObject.quote, because a value from the network that
        // is pasted into JavaScript is exactly the shape of mistake that is worth not making.
        //
        // The duration is the Data API's, and the page uses it only to stop a seek landing
        // past the end. The player's own `getDuration` is never used, because an in-stream ad
        // makes it report the ad's length.
        js(
            "ytLoad(${org.json.JSONObject.quote(load.videoId)}, " +
                "${load.startSeconds}, ${load.durationSeconds ?: 0}, " +
                "${load.generation}, ${load.pauseOnStart});",
        )
    }

    /** What to show, held until the page is ready for it. */
    private data class Load(
        val videoId: String,
        val startSeconds: Int,
        val durationSeconds: Int?,
        val pauseOnStart: Boolean,
        val generation: Int,
    )

    /**
     * The app is going into the background, so the video stops and the page goes to sleep.
     *
     * Without this, pressing `HOME` left the video playing: the Google TV launcher came up
     * with the household still listening to it, and nothing stopped until Android happened to
     * kill the Activity. `WebView.onPause` also stops the page's own timers, so the position
     * is not polled against a video nobody is watching.
     *
     * Coming back does **not** start it again. The household left; a paused video with its
     * position on screen is what they should find.
     */
    fun pauseForBackground() {
        // Straight to the WebView, not through `js`, which posts. `Activity.onPause` already
        // runs on the main thread, and a posted command would land after `view.onPause()` had
        // stopped the page, which is exactly when it would be dropped and the audio would
        // carry on.
        runCatching { view.evaluateJavascript("ytPause();", null) }
        view.onPause()
        view.pauseTimers()
    }

    /** The app is on screen again. The page wakes, and the video stays paused. */
    fun resumeFromBackground() {
        view.onResume()
        view.resumeTimers()
    }

    fun togglePlay() = js("ytTogglePlay();")

    fun play() = js("ytPlay();")

    fun pause() = js("ytPause();")

    fun seekBy(deltaSeconds: Int) = js("ytSeekBy($deltaSeconds);")

    private fun js(code: String) {
        view.post { view.evaluateJavascript(code, null) }
    }

    /**
     * Stops the video and throws the WebView away.
     *
     * `loadUrl("about:blank")` first, because destroying a WebView that is still playing can
     * leave the audio running for a second or two after the household is back on the grid.
     */
    fun destroy() {
        runCatching {
            view.loadUrl("about:blank")
            view.removeJavascriptInterface(PlayerHtml.BRIDGE)
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    }
}
