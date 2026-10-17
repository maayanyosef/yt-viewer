package local.youtubetv.player

import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchKind
import local.youtubetv.data.WatchLog
import local.youtubetv.data.played
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One play, end to end, over the real [WatchLog] on an in-memory store.
 *
 * Every acceptance criterion of ticket 07 that is about the log rather than about the picture
 * is here: the ten-second threshold, exactly one entry per play, the position cadence, the
 * pause and leave and `onStop` writes, completion, resuming, and the rule that a player error
 * writes nothing.
 */
class PlaybackSessionTest {

    private val store = InMemoryWatchStore()
    private val watches = WatchLog(store)

    /** Ten minutes long, so the completion threshold is 5% of it, which is 30 seconds. */
    private val video = testVideo("v1", durationSeconds = 600)

    private fun session(
        video: StoredVideo = this.video,
        resumeFrom: Int? = null,
    ) = PlaybackSession(video = video, watches = watches, resumeFrom = resumeFrom)

    private fun PlaybackSession.playTo(seconds: Int, seeking: Boolean = false) {
        onEvent(PlayerEvent.Tick(seconds, playing = true, quality = "hd1080", seeking = seeking))
    }

    private fun plays() = store.entries().filter { it.kind == WatchKind.PLAYED }

    private fun onlyPlay() = plays().single().played()!!

    // ---------------------------------------------------------------- the ten-second rule

    @Test
    fun `stopping before ten seconds writes nothing at all`() {
        val session = session()
        session.playTo(3)
        session.playTo(9)
        session.onLeaving()

        assertTrue(store.entries().isEmpty())
        assertNull(watches.state("v1"))
    }

    @Test
    fun `passing ten seconds writes exactly one played entry`() {
        val session = session()
        session.playTo(9)
        session.playTo(10)

        assertEquals(1, plays().size)
        assertEquals(10, onlyPlay().positionSeconds)
        assertEquals("v1", onlyPlay().videoId)
        assertTrue(watches.state("v1")!!.watched)
    }

    @Test
    fun `a whole play leaves one entry, however long it runs`() {
        val session = session()
        for (second in 1..300) session.playTo(second)
        session.onLeaving()
        session.onStopped()

        assertEquals(1, plays().size)
        assertEquals(300, onlyPlay().positionSeconds)
    }

    // ---------------------------------------------------------------- the position cadence

    @Test
    fun `the position is written every ten seconds and not between them`() {
        val session = session()
        session.playTo(12)
        assertEquals(12, onlyPlay().positionSeconds)

        session.playTo(21)
        assertEquals("nine seconds on is not written", 12, onlyPlay().positionSeconds)

        session.playTo(22)
        assertEquals(22, onlyPlay().positionSeconds)
    }

    @Test
    fun `pausing writes the position whatever the cadence says`() {
        val session = session()
        session.playTo(12)
        session.playTo(15)
        session.onEvent(PlayerEvent.State(PlayerStateCode.PAUSED, "PAUSED"))

        assertEquals(15, onlyPlay().positionSeconds)
        assertFalse(session.state.value.playing)
    }

    @Test
    fun `leaving writes the position`() {
        val session = session()
        session.playTo(12)
        session.playTo(18)
        session.onLeaving()

        assertEquals(18, onlyPlay().positionSeconds)
    }

    @Test
    fun `onStop writes the position, because no key press may ever arrive`() {
        val session = session()
        session.playTo(12)
        session.playTo(19)
        session.onStopped()

        assertEquals(19, onlyPlay().positionSeconds)
    }

    @Test
    fun `seeking backwards writes the position it seeked to`() {
        val session = session()
        session.playTo(400)
        session.playTo(100, seeking = true)

        assertEquals(100, onlyPlay().positionSeconds)
    }

    // ---------------------------------------------------------------- in-stream ads

    @Test
    fun `an ad's own clock never reaches the household's log`() {
        // During an in-stream ad the player reports the ad's clock, counting from zero, while
        // the state still says PLAYING. Taking it would throw the resume point away.
        val session = session()
        session.playTo(400)
        assertEquals(400, onlyPlay().positionSeconds)

        for (adSecond in 0..15) session.playTo(adSecond)

        assertEquals("the entry stays where the video was", 400, onlyPlay().positionSeconds)
        assertEquals("and so does the screen", 400, session.state.value.positionSeconds)
    }

    @Test
    fun `the video carries on where it left off once the ad is over`() {
        val session = session()
        session.playTo(400)
        for (adSecond in 0..15) session.playTo(adSecond)
        session.playTo(402)
        session.playTo(412)

        assertEquals(412, onlyPlay().positionSeconds)
    }

    @Test
    fun `leaving during an ad writes where the video was, not where the ad was`() {
        val session = session()
        session.playTo(400)
        session.playTo(3)
        session.onLeaving()

        assertEquals(400, onlyPlay().positionSeconds)
    }

    @Test
    fun `a play that starts during a pre-roll still starts at the beginning`() {
        // A pre-roll counts from zero and so does the video, so there is no jump to distrust.
        val session = session()
        session.playTo(4)
        session.playTo(12)

        assertEquals(12, onlyPlay().positionSeconds)
    }

    // ---------------------------------------------------------------- after leaving

    @Test
    fun `a tick that arrives after leaving cannot move the frozen entry`() {
        // The page keeps polling for a moment after BACK: the WebView's own thread already
        // has work queued when the household presses the key.
        val session = session()
        session.playTo(400)
        session.onLeaving()

        session.playTo(700)
        session.onEvent(PlayerEvent.Ended)

        assertEquals(400, onlyPlay().positionSeconds)
        assertFalse(onlyPlay().completed)
    }

    // ---------------------------------------------------------------- short videos

    @Test
    fun `a video shorter than ten seconds still counts when it is watched whole`() {
        // Without this it would never pass the threshold, and would stay Next unwatched for
        // ever, which ticket 08's autoplay-next would walk into on every pass.
        val session = session(video = testVideo("tiny", durationSeconds = 9))
        session.playTo(4)
        assertTrue(store.entries().isEmpty())

        session.onEvent(PlayerEvent.Ended)

        assertEquals(1, plays().size)
        assertTrue(onlyPlay().completed)
        assertEquals(9, onlyPlay().positionSeconds)
    }

    // ---------------------------------------------------------------- completion

    @Test
    fun `watching to the end sets completed`() {
        val session = session()
        session.playTo(400)
        session.onEvent(PlayerEvent.Ended)

        assertTrue(onlyPlay().completed)
        assertEquals(600, onlyPlay().positionSeconds)
        assertTrue(watches.state("v1")!!.completed)
        assertTrue(session.state.value.finished)
    }

    @Test
    fun `leaving within the completion threshold also sets completed`() {
        val session = session()
        session.playTo(400)
        assertFalse(onlyPlay().completed)

        // 600 s long, so the threshold is 30 s and 570 is the first completed second.
        session.playTo(570)
        session.onLeaving()

        assertTrue(onlyPlay().completed)
    }

    @Test
    fun `a video with no duration is never completed without ENDED`() {
        val session = session(video = testVideo("live", durationSeconds = null))
        session.playTo(9_000)
        session.onLeaving()

        assertFalse(onlyPlay().completed)
        assertTrue(watches.state("live")!!.watched)
    }

    @Test
    fun `ENDED completes a video that has no duration`() {
        val session = session(video = testVideo("live", durationSeconds = null))
        session.playTo(9_000)
        session.onEvent(PlayerEvent.Ended)

        assertTrue(onlyPlay().completed)
        assertEquals(9_000, onlyPlay().positionSeconds)
    }

    // ---------------------------------------------------------------- resuming

    @Test
    fun `a resumed play opens at its position and says so`() {
        val session = session(resumeFrom = 754)

        assertEquals(754, session.state.value.positionSeconds)
        assertEquals(754, session.state.value.resumeFromSeconds)
    }

    @Test
    fun `a second play of the same video still leaves one entry per play`() {
        val first = session()
        first.playTo(120)
        first.onLeaving()

        val second = session(resumeFrom = resumeFromSeconds(watches.state("v1")))
        second.playTo(300)
        second.onLeaving()

        assertEquals(2, plays().size)
        assertEquals(300, watches.state("v1")!!.positionSeconds)
    }

    @Test
    fun `the first play's entry is frozen once the second play starts`() {
        val first = session()
        first.playTo(120)
        first.onLeaving()

        val second = session()
        second.playTo(300)

        val positions = plays().mapNotNull { it.played()!!.positionSeconds }
        assertEquals(listOf(120, 300), positions.sorted())
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun `a player error leaves the log untouched`() {
        val session = session()
        session.onEvent(PlayerEvent.Error(150))
        // The player keeps ticking after an error on some builds. None of it may be written.
        session.playTo(40)
        session.onLeaving()
        session.onStopped()

        assertTrue(store.entries().isEmpty())
        assertNull(watches.state("v1"))
        assertNotNull(session.state.value.errorReason)
        assertFalse(session.state.value.started)
    }

    @Test
    fun `an error part way through freezes what was already played`() {
        val session = session()
        session.playTo(120)
        session.onEvent(PlayerEvent.Error(5))
        session.playTo(400)
        session.onLeaving()

        assertEquals(1, plays().size)
        assertEquals("the entry stops where the failure did", 120, onlyPlay().positionSeconds)
    }

    // ---------------------------------------------------------------- the card behind

    @Test
    fun `the card is told as soon as the play is written down`() {
        var told = 0
        val session = PlaybackSession(
            video = video,
            watches = watches,
            onWatchState = { told += 1 },
        )
        session.playTo(12)
        assertEquals(1, told)
        session.playTo(30)
        assertEquals(2, told)
    }

    @Test
    fun `a video with no id is never written down`() {
        val session = session(video = testVideo(""))
        session.playTo(120)
        session.onLeaving()

        assertTrue(store.entries().isEmpty())
    }

    // ------------------------------------------- the video the countdown is sitting over

    private fun heldSession(resumeFrom: Int? = null) = PlaybackSession(
        video = video,
        watches = watches,
        resumeFrom = resumeFrom,
        heldForCountdown = true,
    )

    @Test
    fun `BACK during the countdown leaves no trace of the declined video`() {
        // The next video is In progress at 15:00, so it is loaded at 15:00 and the five
        // second countdown sits over it. The household presses BACK. Nothing was watched, so
        // nothing may be written. The ten second threshold cannot help here, because a
        // resumed position is above it by construction. Found by `/code-review`.
        val session = heldSession(resumeFrom = 300)
        session.onLeaving()

        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `the moment of PLAYING before the pause lands writes nothing`() {
        // loadVideoById is asynchronous, so the page reaches PLAYING for a moment before the
        // pause takes effect, and that one tick would otherwise open a play.
        val session = heldSession(resumeFrom = 300)
        session.playTo(300)
        session.playTo(301)

        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `walking past a video with next during a countdown leaves no trace`() {
        val session = heldSession(resumeFrom = 300)
        session.playTo(300)
        session.onLeaving()

        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `Activity onStop during the countdown writes nothing`() {
        val session = heldSession(resumeFrom = 300)
        session.onStopped()

        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a released video is written down exactly like any other play`() {
        val session = heldSession(resumeFrom = 300)
        session.playTo(300)
        assertTrue(store.entries().isEmpty())

        // The five seconds ran out, or the household pressed the centre button.
        session.release()
        session.playTo(310)
        session.onLeaving()

        assertEquals(310, onlyPlay().positionSeconds)
        assertEquals(1, plays().size)
    }

    // ------------------------------------------- ticket 17: the video is on the page

    @Test
    fun `a new session has not been confirmed on the page`() {
        assertFalse(session().state.value.onScreen)
    }

    @Test
    fun `buffering is enough to say the load has taken effect`() {
        // It is the first state the page reports for a video that is fetching its first
        // bytes, and it is the earliest honest moment the cover of ticket 17 may come down.
        val session = session()
        session.onEvent(PlayerEvent.State(PlayerStateCode.BUFFERING, "BUFFERING"))
        assertTrue(session.state.value.onScreen)
    }

    @Test
    fun `the paused first frame of a countdown counts`() {
        // The autoplay-next countdown holds the next video paused. That is a video on the
        // page, so the wall behind it is gone, whatever the countdown is still drawing.
        val session = heldSession(resumeFrom = 300)
        session.onEvent(PlayerEvent.State(PlayerStateCode.PAUSED, "PAUSED"))
        assertTrue(session.state.value.onScreen)
    }

    @Test
    fun `an unstarted player is not a video on the page`() {
        // This is the state a player sits in with the related-video wall painted over it.
        val session = session()
        session.onEvent(PlayerEvent.State(PlayerStateCode.UNSTARTED, "UNSTARTED"))
        assertFalse(session.state.value.onScreen)
    }

    @Test
    fun `once confirmed it is never taken back`() {
        val session = session()
        session.onEvent(PlayerEvent.State(PlayerStateCode.PLAYING, "PLAYING"))
        session.onEvent(PlayerEvent.State(PlayerStateCode.UNSTARTED, "UNSTARTED"))
        session.onEvent(PlayerEvent.Ended)
        assertTrue(session.state.value.onScreen)
    }

    @Test
    fun `a paused state still writes the position down`() {
        // The confirmation is set first in the same function, so this is here to prove it
        // did not swallow the write that was already there.
        val session = session()
        session.playTo(120)
        session.onEvent(PlayerEvent.State(PlayerStateCode.PAUSED, "PAUSED"))

        assertEquals(120, onlyPlay().positionSeconds)
        assertTrue(session.state.value.onScreen)
        assertFalse(session.state.value.playing)
    }
}
