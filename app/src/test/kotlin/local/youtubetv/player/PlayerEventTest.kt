package local.youtubetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning what the page pushes over the bridge into something the session can act on. */
class PlayerEventTest {

    @Test
    fun `a tick carries the position in whole seconds`() {
        val event = parsePlayerEvent(
            """{"type":"tick","data":{"t":754.62,"state":1,"quality":"hd1080"}}""",
        )
        assertEquals(PlayerEvent.Tick(754, playing = true, quality = "hd1080"), event)
    }

    @Test
    fun `buffering still counts as playing`() {
        // The position is moving towards the household as much during a buffer as during a
        // frame, and a buffer that stopped the position being written would lose it.
        val event = parsePlayerEvent("""{"type":"tick","data":{"t":10,"state":3}}""")
        assertTrue((event as PlayerEvent.Tick).playing)
    }

    @Test
    fun `paused, unstarted, cued and ended are not playing`() {
        for (state in listOf(-1, 0, 2, 5)) {
            val event = parsePlayerEvent("""{"type":"tick","data":{"t":10,"state":$state}}""")
            assertFalse(state.toString(), (event as PlayerEvent.Tick).playing)
        }
    }

    @Test
    fun `a time the player could not answer reads as the beginning`() {
        val event = parsePlayerEvent("""{"type":"tick","data":{"t":-1,"state":1}}""")
        assertEquals(0, (event as PlayerEvent.Tick).positionSeconds)
    }

    @Test
    fun `an error carries its code`() {
        assertEquals(
            PlayerEvent.Error(150),
            parsePlayerEvent("""{"type":"error","data":{"code":150}}"""),
        )
    }

    @Test
    fun `the end of a video is its own event`() {
        assertEquals(PlayerEvent.Ended, parsePlayerEvent("""{"type":"ended","data":{}}"""))
    }

    @Test
    fun `ready carries the origin, which is what error 153 is about`() {
        val event = parsePlayerEvent(
            """{"type":"ready","data":{"origin":"https://youtube-tv.local"}}""",
        )
        assertEquals(PlayerEvent.Ready("https://youtube-tv.local"), event)
    }

    @Test
    fun `the captions report says what the track was and what it is now`() {
        val event = parsePlayerEvent(
            """{"type":"captions","data":{"module":"captions","tracks":2,"before":"he","after":"none"}}""",
        )
        assertEquals(PlayerEvent.Captions("captions", 2, "he", "none"), event)
    }

    @Test
    fun `an event this build does not act on is simply nothing`() {
        assertNull(parsePlayerEvent("""{"type":"size","data":{"w":1920}}"""))
    }

    @Test
    fun `a string that is not JSON never brings the television down`() {
        val event = parsePlayerEvent("not json at all")
        assertTrue(event is PlayerEvent.PageProblem)
    }
}
