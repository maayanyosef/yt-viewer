package local.youtubetv.player

import org.json.JSONObject

/**
 * The page that hosts the YouTube IFrame Player.
 *
 * It is the thinnest thing that will work. Every rule lives in Kotlin, in `PlaybackRules.kt`
 * and `PlaybackSession.kt`, and this page only reports what the player says and does what it
 * is told. A throwaway spike proved this arrangement on the television on
 * 2026-09-19; what is here is that page with its diagnostics taken out.
 *
 * Three things about it are not free choices, and all three cost a day on the television:
 *
 * - It must be loaded with [BASE_URL] through `loadDataWithBaseURL`, never `loadData`. The
 *   base URL becomes `window.origin` and is sent as the `Referer`. Without it the player
 *   answers error 153. Spec section 8.1.
 * - The page is sized in **viewport units, never percentages**. A percentage height needs an
 *   unbroken chain of definite heights up to the viewport, and one wrong WebView measurement
 *   collapses the whole chain to zero. The symptom is a black screen with working audio.
 * - `YT.Player` must build the iframe. A hand-written one misses `allow="autoplay ..."` and
 *   `origin=`, both of which the widget script fills in from `window.location`.
 */
object PlayerHtml {

    /**
     * Any https host. Nothing is ever fetched from it and it does not have to resolve.
     *
     * It only has to look like an origin, because that is all the player asks of it.
     */
    const val BASE_URL = "https://youtube-tv.local/"

    /** The name the JavaScript bridge is injected under. */
    const val BRIDGE = "YTTV"

    /**
     * How much bigger than the screen the picture is drawn, so its broken top rows fall off the
     * edge. Estimated from a photo of the Mi TV, 2026-10-21: the strip is about 1.5 percent of the height.
     */
    const val OVERSCAN = 1.04

    /** How often `getCurrentTime()` is polled. There is no progress event. Spec section 8.4. */
    const val POLL_MILLIS = 250

    /**
     * The page, with the player parameters of spec section 8.2 written into it.
     *
     * The parameters come from [playerVars] rather than being spelled out here, so a missing
     * one fails a JVM test instead of showing up as captions on the television.
     */
    fun build(hl: String = PLAYER_LANGUAGE, cappedSize: Pair<Int, Int>? = null): String {
        val vars = JSONObject().apply {
            // A string that is only digits is put in as a number, because that is what the
            // IFrame API expects for every parameter but hl and cc_lang_pref.
            playerVars(hl).forEach { (name, value) ->
                val number = value.toIntOrNull()
                if (number == null) put(name, value) else put(name, number)
            }
        }.toString()

        return PAGE
            .replace("__PLAYER_VARS__", vars)
            .replace("__BRIDGE__", BRIDGE)
            .replace("__POLL_MILLIS__", POLL_MILLIS.toString())
            .replace("__CAPPED_SIZE__", cappedSizeJs(cappedSize))
            .replace("__OVERSCAN__", OVERSCAN.toString())
    }

    /**
     * The player's size for a quality cap, as JavaScript, or `null` for no cap.
     *
     * See [VideoQuality] for why a cap is a size: YouTube picks the stream from the player's
     * size, and nothing else in the IFrame API has worked since 2019.
     */
    fun cappedSizeJs(cappedSize: Pair<Int, Int>?): String =
        if (cappedSize == null) {
            "null"
        } else {
            "{\"w\":${cappedSize.first},\"h\":${cappedSize.second}}"
        }

    /**
     * The page, with five placeholders.
     *
     * Placeholders and not Kotlin string interpolation, because the page is JavaScript and a
     * stray `$` in it would be read as an interpolation and fail the compile.
     */
    private val PAGE: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  /* Viewport units, never percentages. See the note above BASE_URL. */
  html, body { margin: 0; padding: 0; background: #000; width: 100vw; height: 100vh; overflow: hidden; }
  /* The IFrame API replaces this div with an iframe, which is inline by default and so sits
     on a text baseline, leaving a grey gap under it. Block removes the gap. */
  #player { width: 100vw; height: 100vh; display: block; border: 0; }
</style>
</head>
<body>
<div id="player"></div>
<script>
var player = null;
var pollTimer = null;

// Which load this event belongs to. One player now shows a whole chain of videos, so the
// events of the video that just finished are still arriving while the next one is loading.
// Without this number, a tick left over from a 40 minute video would be written down as the
// position of the video that replaced it. Every event carries it and Kotlin drops the ones
// that name a load it has moved on from. Spec section 8.6.
var loadGen = 0;

function report(type, data) {
  try {
    __BRIDGE__.event(JSON.stringify({ type: type, gen: loadGen, data: data }));
  } catch (e) {
    // The bridge is injected before the page loads, so this cannot normally happen.
  }
}

function safe(fn, fallback) {
  try { return fn(); } catch (e) { return fallback; }
}

window.onerror = function (msg, src, line) {
  report('jsError', { msg: String(msg), line: line });
};

// The quality cap, as a size in CSS pixels, or null for none. See VideoQuality.kt.
//
// The iframe is laid out at this small size, so YouTube picks a stream no bigger than it, and
// is then scaled up to fill the screen. A transform does not change the layout size, so the
// player still sees the small one. Inline style, because the stylesheet's #player rule would
// otherwise put the iframe straight back to the whole viewport.
var cappedSize = __CAPPED_SIZE__;

// How much bigger than the screen the picture is drawn, so its outermost rows fall off the
// edges. On the Mi TV the top rows of every frame drawn in this WebView are broken blocks,
// whatever the quality, and the official YouTube app on the same set shows none. Four
// percent hides two percent at each edge, which is less than a television's own overscan.
var OVERSCAN = __OVERSCAN__;

function applyCap() {
  if (!player) { return; }
  var frame = safe(function () { return player.getIframe(); }, null);
  if (!frame) { return; }
  var vw = window.innerWidth;
  var vh = window.innerHeight;
  var w = cappedSize ? cappedSize.w : vw;
  var h = cappedSize ? cappedSize.h : vh;
  frame.style.width = w + 'px';
  frame.style.height = h + 'px';
  // Scaled from the top-left corner, then moved up and left by half of what the overscan
  // adds, so the picture stays centred and the same amount falls off each edge.
  frame.style.transformOrigin = '0 0';
  frame.style.transform =
    'translate(' + (-(OVERSCAN - 1) * vw / 2) + 'px,' + (-(OVERSCAN - 1) * vh / 2) + 'px) ' +
    'scale(' + (vw / w * OVERSCAN) + ',' + (vh / h * OVERSCAN) + ')';
}

// The viewport is measured again if the WebView is resized, so the transform still fills it.
window.addEventListener('resize', applyCap);

function onYouTubeIframeAPIReady() {
  player = new YT.Player('player', {
    width: cappedSize ? cappedSize.w : '100%',
    height: cappedSize ? cappedSize.h : '100%',
    playerVars: __PLAYER_VARS__,
    events: {
      onReady: function () {
        applyCap();
        report('ready', { origin: String(window.origin) });
        startPolling();
      },
      onStateChange: function (e) {
        // Autoplay-next replaces the related-video wall by loading the next video the moment
        // the last one ends, and then pausing it behind a five second countdown. The pause
        // asked for at load time is often too early to stick, because loadVideoById has not
        // reached the new video yet, so it is asked for again the first time the new video
        // really starts playing. Spec section 8.6.
        if (e.data === 1 && pendingPause) { pendingPause = false; ytPause(); }
        report('state', { code: e.data });
        if (e.data === 0) { report('ended', {}); }
      },
      onError: function (e) { report('error', { code: e.data }); },
      onApiChange: function () { killCaptions(); },
      onPlaybackQualityChange: function (e) { report('quality', { quality: e.data }); },
      onAutoplayBlocked: function () { report('autoplayBlocked', {}); }
    }
  });
  applyCap();
}

// What a caption track option says, in one word.
//
// The three answers are worth telling apart, and an earlier version could not: a missing
// option, an empty object, which is what "no captions" looks like, and a real track with a
// language. 'empty' is the one this app wants to see after clearing.
function trackName(track) {
  if (!track) { return 'none'; }
  if (track.languageCode) { return track.languageCode; }
  for (var k in track) { if (Object.prototype.hasOwnProperty.call(track, k)) { return 'set'; } }
  return 'empty';
}

// Captions are turned off by clearing the track, not by cc_load_policy, which is undocumented
// folklore. The captions module is rebuilt for every video, so this runs on every onApiChange
// and again after every load. Spec section 8.3.
function killCaptions() {
  if (!player) { return; }
  var names = ['captions', 'cc'];
  for (var i = 0; i < names.length; i++) {
    var m = names[i];
    safe(function () { player.loadModule(m); });
    var had = safe(function () { return player.getOption(m, 'track'); }, null);
    var list = safe(function () { return player.getOption(m, 'tracklist'); }, null);
    safe(function () { player.setOption(m, 'track', {}); });
    var now = safe(function () { return player.getOption(m, 'track'); }, null);
    // Reported, not assumed. Captions are turned off by an undocumented option, so the one
    // honest way to know it worked is to read the track back. It is the only way to tell a
    // caption YouTube drew from a subtitle the creator burned into the picture.
    report('captions', {
      module: m,
      tracks: list ? list.length : 0,
      before: trackName(had),
      after: trackName(now)
    });
  }
}

// There is no time-update event. Polling is the only progress signal. Spec section 8.4.
function startPolling() {
  if (pollTimer) { return; }
  pollTimer = window.setInterval(function () {
    if (!player || typeof player.getCurrentTime !== 'function') { return; }
    report('tick', {
      t: seekAwareTime(),
      state: safe(function () { return player.getPlayerState(); }, -1),
      quality: safe(function () { return player.getPlaybackQuality(); }, ''),
      seeking: seekTarget !== null
    });
  }, __POLL_MILLIS__);
}

// ---- called from Kotlin with evaluateJavascript

// The Data API's duration, handed in at load. Never the player's own length, which is the
// ad's length while an in-stream ad plays. Zero when the API gave none, which a live stream
// and an upcoming premiere both do.
var contentDuration = 0;

// How far before the end a seek is allowed to land. Seeking past the end of a 4:29 video on
// the TCL on 2026-09-20 did not end it: it restarted it from zero, and the position written
// down went with it. A seek now stops just short instead.
var SEEK_END_MARGIN = 2;

// True while a load has asked for the video to stop on its first frame, which is what the
// autoplay-next countdown plays behind.
var pendingPause = false;

function ytLoad(videoId, startSeconds, durationSeconds, gen, pauseOnStart) {
  if (!player) { return; }
  contentDuration = durationSeconds || 0;
  seekTarget = null;
  loadGen = gen || 0;
  pendingPause = pauseOnStart ? true : false;
  safe(function () { player.loadVideoById({ videoId: videoId, startSeconds: startSeconds || 0 }); });
  // Asked for at once as well as on the first PLAYING, because the wall is replaced by the
  // new video's own first frame and the household must not hear two seconds of it.
  if (pendingPause) { safe(function () { player.pauseVideo(); }); }
  // Captions are rebuilt for every video, so they are cleared again after every load. Spec
  // section 8.3, which names loadVideoById in so many words.
  window.setTimeout(killCaptions, 1500);
}

// A household press of OK during the countdown. It cancels the pause the load asked for, or
// the next state change would put the video straight back to paused.
function ytPlay() { pendingPause = false; safe(function () { player.playVideo(); }); }

function ytPause() { safe(function () { player.pauseVideo(); }); }

function ytTogglePlay() {
  var s = safe(function () { return player.getPlayerState(); }, -1);
  if (s === 1 || s === 3) { ytPause(); } else { ytPlay(); }
}

// Where the last seek was aimed, and when. Without this, seeks collapse: getCurrentTime()
// still answers the old position for a moment after seekTo, so three presses in a row all
// compute the same target and the household moves ten seconds instead of thirty. Found on
// the TCL, 2026-09-20. A held key repeats about thirty times a second, so this is the normal
// case and not an edge one.
var seekTarget = null;
var seekTargetAt = 0;
var SEEK_CHAIN_MILLIS = 2000;

// How long a seek is still counted as in flight, which is a longer thing than how long the
// next press may chain onto it.
//
// These were one number until ticket 32, and two seconds was too short for the number the
// number was used for. A held seek now moves a minute a jump, so a target can be tens of
// minutes away and may take longer than two seconds to land over the household wifi. When the
// window ran out the page went back to reporting the player's stale pre-seek position, with
// `seeking` false, and the real landing that followed then looked to `isPositionBelievable`
// like the backwards jump of an in-stream ad. The position on screen froze and a `BACK` in
// that moment wrote the wrong resume point into the log.
//
// So chaining keeps its two seconds, which is about a household pressing the key again, and
// the in-flight window is its own, longer number, which is about how long a player takes.
var SEEK_SETTLE_MILLIS = 8000;

// The position, with a seek that has not landed yet taken into account.
//
// For a moment after seekTo the player still answers the old position. Reporting that would
// walk the number on screen backwards, and worse, a BACK pressed in that moment would write
// the position from before the seek into the household's log.
function seekAwareTime() {
  var t = safe(function () { return player.getCurrentTime(); }, -1);
  if (seekTarget === null) { return t; }
  if ((Date.now() - seekTargetAt) > SEEK_SETTLE_MILLIS) { seekTarget = null; return t; }
  if (t >= 0 && Math.abs(t - seekTarget) < 1.5) { seekTarget = null; return t; }
  return seekTarget;
}

function ytSeekBy(delta) {
  var now = Date.now();
  var base;
  if (seekTarget !== null && (now - seekTargetAt) < SEEK_CHAIN_MILLIS) {
    base = seekTarget;
  } else {
    base = safe(function () { return player.getCurrentTime(); }, 0);
  }
  var to = Math.max(0, base + delta);
  if (contentDuration > SEEK_END_MARGIN) {
    to = Math.min(to, contentDuration - SEEK_END_MARGIN);
  }
  seekTarget = to;
  seekTargetAt = now;
  safe(function () { player.seekTo(to, true); });
  // Report at once, so the position on screen moves with the press rather than at the next
  // poll a quarter of a second later.
  report('tick', {
    t: to,
    state: safe(function () { return player.getPlayerState(); }, -1),
    quality: safe(function () { return player.getPlaybackQuality(); }, ''),
    seeking: true
  });
}

(function () {
  var s = document.createElement('script');
  s.src = 'https://www.youtube.com/iframe_api';
  s.onerror = function () { report('apiScriptFailed', {}); };
  document.head.appendChild(s);
})();
</script>
</body>
</html>
""".trimIndent()
}
