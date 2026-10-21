package local.youtubetv.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page, checked for the handful of things that cost a day on the television when wrong.
 *
 * It cannot be run here, so this does not prove it plays a video. It proves that the
 * parameters reach it, that the sizing rule of spec 8.1 is still in it, and that nothing
 * hand-writes the iframe.
 */
class PlayerHtmlTest {

    private val page = PlayerHtml.build()

    @Test
    fun `every player parameter of spec section 8_2 reaches the page`() {
        val start = page.indexOf("playerVars:") + "playerVars:".length
        val json = page.substring(start, page.indexOf('\n', start)).trim().trimEnd(',')
        val vars = JSONObject(json)

        for ((name, value) in playerVars()) {
            assertEquals(name, value, vars.get(name).toString())
        }
    }

    @Test
    fun `the language can be changed and lands in both places`() {
        val hebrew = PlayerHtml.build(hl = "he")
        assertTrue(hebrew.contains("\"hl\":\"he\""))
        assertTrue(hebrew.contains("\"cc_lang_pref\":\"he\""))
    }

    @Test
    fun `the page is sized in viewport units and never in percentages`() {
        // A percentage height needs an unbroken chain of definite heights up to the viewport.
        // One wrong WebView measurement collapses the chain to zero, and the television shows
        // a black screen with working audio. Found on the TCL, 2026-09-19.
        val style = page.substringAfter("<style>").substringBefore("</style>")
        assertTrue(style.contains("100vh"))
        assertFalse(style.contains("height: 100%"))
    }

    @Test
    fun `the IFrame API builds the iframe, and the page does not`() {
        // A hand-written iframe misses allow="autoplay ..." and origin=, which the widget
        // script fills in from window.location.
        assertFalse(page.contains("<iframe"))
        assertTrue(page.contains("new YT.Player("))
    }

    @Test
    fun `captions are cleared through the module, not left to cc_load_policy`() {
        assertTrue(page.contains("loadModule"))
        assertTrue(page.contains("setOption"))
        // The module is rebuilt per video, so it has to run after a load as well.
        assertTrue(page.contains("window.setTimeout(killCaptions"))
    }

    @Test
    fun `the position is polled, because there is no progress event`() {
        assertTrue(page.contains("setInterval"))
        assertTrue(page.contains(PlayerHtml.POLL_MILLIS.toString()))
    }

    @Test
    fun `seeks chain, instead of each one reading a position that has not moved yet`() {
        // Three presses of left moved the position ten seconds, not thirty, on the TCL on
        // 2026-09-20: getCurrentTime() still answered the old position, so all three seeks
        // computed the same target. A held key repeats about thirty times a second, so this
        // is the normal case.
        assertTrue(page.contains("seekTarget"))
        assertTrue(page.contains("SEEK_CHAIN_MILLIS"))
        assertTrue(page.contains("function seekAwareTime()"))
    }

    @Test
    fun `a seek stops short of the end, using the Data API duration`() {
        // Seeking past the end of a 4:29 video on the TCL on 2026-09-20 did not end it. It
        // restarted it from zero, and the position written down went with it.
        assertTrue(page.contains("SEEK_END_MARGIN"))
        assertTrue(page.contains("contentDuration"))
        // Never the player's own duration: an in-stream ad makes it report the ad's length.
        assertFalse(page.contains("getDuration"))
    }

    @Test
    fun `no placeholder is left unfilled`() {
        assertFalse(page.contains("__"))
    }

    @Test
    fun `the base URL is a real https origin`() {
        // loadData gives origin "null" and player error 153. Spec section 8.1.
        assertTrue(PlayerHtml.BASE_URL.startsWith("https://"))
    }

    @Test
    fun `every event the page sends carries the load it belongs to`() {
        // One page now shows a whole chain of videos, so a tick left over from the video that
        // just finished must be droppable. Spec section 8.6.
        assertTrue(page.contains("gen: loadGen"))
        assertTrue(page.contains("loadGen = gen"))
    }

    @Test
    fun `a load can ask for the video to stop on its first frame`() {
        // That is what the five second countdown of spec section 8.6 plays behind, and it is
        // how the related-video wall is replaced before the household sees it.
        assertTrue(page.contains("function ytLoad(videoId, startSeconds, durationSeconds, gen, pauseOnStart, rate)"))
        assertTrue(page.contains("pendingPause"))
    }

    @Test
    fun `a load carries the Source's speed and asks for it again on the first PLAYING`() {
        // Fork 0.5. The player builds a new video module per load, and a rate asked for too
        // early does not stick, so it is asked for twice.
        assertTrue(page.contains("currentRate = rate || 1;"))
        assertTrue(page.contains("player.setPlaybackRate(currentRate)"))
        assertTrue(page.contains("if (e.data === 1 && currentRate !== 1)"))
    }

    @Test
    fun `captions are cleared again after every load`() {
        // Spec section 8.3 names loadVideoById in so many words, and autoplay-next calls it
        // once per video of a chain.
        assertTrue(page.contains("window.setTimeout(killCaptions, 1500)"))
    }
}

/**
 * One event from the page, and the load it belongs to.
 *
 * The load number is what keeps a finished video's late ticks off the next video's entry.
 * Without it, a tick from a forty minute video arriving a moment after the next one loaded
 * would be written down as that video's position, and a position past the completion
 * threshold would mark it watched. Spec section 8.6.
 */
class PlayerEventEnvelopeTest {

    @Test
    fun `an event names the load it came from`() {
        val envelope = parsePlayerEventEnvelope(
            """{"type":"tick","gen":3,"data":{"t":12.5,"state":1,"quality":"hd1080"}}""",
        )
        assertEquals(3, envelope?.generation)
        assertEquals(PlayerEvent.Tick(12, playing = true, quality = "hd1080"), envelope?.event)
    }

    @Test
    fun `an event that belongs to no load at all is generation zero`() {
        // Ready, the IFrame script failing, and a script throwing all happen before or
        // outside any load. Those are never dropped.
        val ready = parsePlayerEventEnvelope("""{"type":"ready","data":{"origin":"x"}}""")
        assertEquals(0, ready?.generation)
    }

    @Test
    fun `the old parse still answers, and still ignores what this build does not act on`() {
        assertEquals(PlayerEvent.Ended, parsePlayerEvent("""{"type":"ended","gen":2,"data":{}}"""))
        assertNull(parsePlayerEvent("""{"type":"somethingElse","data":{}}"""))
    }
}
