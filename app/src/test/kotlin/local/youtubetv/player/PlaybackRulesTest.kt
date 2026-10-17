package local.youtubetv.player

import java.time.Instant
import local.youtubetv.data.WatchState
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun watchState(
    positionSeconds: Int?,
    completed: Boolean = false,
    watched: Boolean = true,
): WatchState = WatchState(
    videoId = "v1",
    watched = watched,
    positionSeconds = positionSeconds,
    completed = completed,
    lastPlayedAt = Instant.parse("2026-09-20T12:00:00Z"),
)

/** Spec sections 8.2, 8.4 and 8.6, one test per rule. */
class PlaybackRulesTest {

    // ---------------------------------------------------------------- completion

    @Test
    fun `a short video is completed within five percent of its end`() {
        // 600 s: 5% is 30 s, which is under the 60 s cap, so 30 s is the threshold.
        assertEquals(30, completionThresholdSeconds(600))
        assertFalse(isCompletedAt(569, 600))
        assertTrue(isCompletedAt(570, 600))
    }

    @Test
    fun `a long video is completed within a minute of its end, never five percent`() {
        // Four hours. 5% would be twelve minutes, which is why the cap exists.
        assertEquals(60, completionThresholdSeconds(14_400))
        assertFalse(isCompletedAt(14_339, 14_400))
        assertTrue(isCompletedAt(14_340, 14_400))
    }

    @Test
    fun `watching past the end still counts as completed`() {
        assertTrue(isCompletedAt(600, 600))
        assertTrue(isCompletedAt(605, 600))
    }

    @Test
    fun `a video with no duration is never completed by position`() {
        // A live stream and an upcoming premiere both report no usable duration. Only ENDED
        // can complete those, and the session applies that one directly.
        assertFalse(isCompletedAt(9_000, null))
        assertFalse(isCompletedAt(9_000, 0))
    }

    // ---------------------------------------------------------------- resuming

    @Test
    fun `an in progress video resumes at its position`() {
        assertEquals(754, resumeFromSeconds(watchState(positionSeconds = 754)))
    }

    @Test
    fun `a completed video starts again from the beginning`() {
        assertNull(resumeFromSeconds(watchState(positionSeconds = 590, completed = true)))
    }

    @Test
    fun `a video the log says nothing about starts from the beginning`() {
        assertNull(resumeFromSeconds(null))
        assertNull(resumeFromSeconds(watchState(positionSeconds = null)))
    }

    @Test
    fun `Play from start beats a stored position`() {
        assertNull(resumeFromSeconds(watchState(positionSeconds = 754), playFromStart = true))
    }

    @Test
    fun `a position below the play threshold is not resumed`() {
        // No play can write one, because no entry exists below ten seconds. A log written by
        // hand or by a later build can, and resuming nine seconds in is worse than starting.
        assertNull(resumeFromSeconds(watchState(positionSeconds = 9)))
        assertEquals(10, resumeFromSeconds(watchState(positionSeconds = 10)))
    }

    // ---------------------------------------------------------------- position cadence

    @Test
    fun `the first position is always written`() {
        assertTrue(shouldWritePosition(positionSeconds = 12, lastWrittenSeconds = null))
    }

    @Test
    fun `the position is written again every ten seconds`() {
        assertFalse(shouldWritePosition(positionSeconds = 19, lastWrittenSeconds = 10))
        assertTrue(shouldWritePosition(positionSeconds = 20, lastWrittenSeconds = 10))
    }

    @Test
    fun `seeking backwards moves the position just as much as seeking forwards`() {
        assertTrue(shouldWritePosition(positionSeconds = 100, lastWrittenSeconds = 900))
    }

    // ---------------------------------------------------------------- player parameters

    @Test
    fun `the player parameters are the eleven spec section 8_2 lists`() {
        assertEquals(
            mapOf(
                "autoplay" to "1",
                "controls" to "0",
                "disablekb" to "1",
                "rel" to "0",
                "fs" to "0",
                "modestbranding" to "1",
                "iv_load_policy" to "3",
                "playsinline" to "1",
                "hl" to "en",
                "cc_lang_pref" to "en",
                "cc_load_policy" to "3",
            ),
            playerVars(),
        )
    }

    @Test
    fun `the language sets both the interface and the caption preference`() {
        val vars = playerVars(hl = "he")
        assertEquals("he", vars["hl"])
        assertEquals("he", vars["cc_lang_pref"])
    }
}

/**
 * What happens when a video reaches its end. Spec section 8.6, all four endings.
 *
 * The next video is worked out by `nextPlayableVideo` in `data/Landings.kt`, which
 * `PlayerStepTest` covers, so these tests hand one in and only ask which ending it is.
 */
class AfterVideoTest {

    private val next = testVideo("next", day = 2)

    @Test
    fun `with autoplay-next on, a finished video counts down into the next one`() {
        assertEquals(AfterVideo.Countdown(next), afterVideo(next, autoplayNext = true))
    }

    @Test
    fun `with autoplay-next off, the grid lands on the next video`() {
        assertEquals(AfterVideo.LandOnNext(next), afterVideo(next, autoplayNext = false))
    }

    @Test
    fun `the end of the Source stops, whatever the toggle says, and never wraps`() {
        assertEquals(AfterVideo.EndOfSource, afterVideo(null, autoplayNext = true))
        assertEquals(AfterVideo.EndOfSource, afterVideo(null, autoplayNext = false))
    }

    @Test
    fun `an unattended player stops even with autoplay-next on`() {
        // A household that has not touched the remote for two hours has stopped watching,
        // whatever the toggle says. The highlight still goes to the next video, so the
        // Source screen they find in the morning is where they stopped.
        assertEquals(
            AfterVideo.Unattended(next),
            afterVideo(next, autoplayNext = true, unattended = true),
        )
    }

    @Test
    fun `the countdown is the five seconds spec section 8_6 asks for`() {
        assertEquals(5, COUNTDOWN_SECONDS)
    }
}

/**
 * The unattended stop of spec section 8.6.
 *
 * It is asked once, when a video ends, so playback is never cut off in the middle of a video.
 * Both times come from a clock that only goes up: `SystemClock.elapsedRealtime` on a device,
 * and plain numbers here.
 */
class UnattendedTest {

    @Test
    fun `the app ships a two hour limit`() {
        // The limit is shortened for a verification on a device, through the
        // `unattended_seconds` intent extra, and never in the code. This is what proves the
        // shipped constant was put back. Ticket 08, and the owner's ruling on 2026-09-20.
        assertEquals(2 * 60 * 60, UNATTENDED_LIMIT_SECONDS)
        assertEquals(7_200, UNATTENDED_LIMIT_SECONDS)
    }

    @Test
    fun `a key press one second inside the limit keeps playback going`() {
        val limit = 7_200
        assertFalse(isUnattended(lastKeyAtMillis = 0, nowMillis = 7_199_000, limitSeconds = limit))
    }

    @Test
    fun `the limit itself stops playback`() {
        val limit = 7_200
        assertTrue(isUnattended(lastKeyAtMillis = 0, nowMillis = 7_200_000, limitSeconds = limit))
        assertTrue(isUnattended(lastKeyAtMillis = 0, nowMillis = 9_000_000, limitSeconds = limit))
    }

    @Test
    fun `any key press resets the timer`() {
        // The key press is the only thing that moves: two hours of video with a press at the
        // end of the first hour leaves an hour on the clock, not none.
        val limit = 7_200
        assertFalse(
            isUnattended(lastKeyAtMillis = 3_600_000, nowMillis = 7_200_000, limitSeconds = limit),
        )
    }

    @Test
    fun `a short limit is what a verification on a device hands in`() {
        assertFalse(isUnattended(lastKeyAtMillis = 0, nowMillis = 19_000, limitSeconds = 20))
        assertTrue(isUnattended(lastKeyAtMillis = 0, nowMillis = 20_000, limitSeconds = 20))
    }
}

/**
 * Ticket 17: the opaque cover that hides YouTube's related-video wall across a handover.
 *
 * The defect was seen on the household television on 2026-09-20, and no test can see a wall.
 * What a test can hold is the rule that decides when the cover is up, which is the part that
 * was wrong: the old code had nothing opaque at all between `ENDED` and the next first frame.
 */
class HandoverCoverTest {

    @Test
    fun `the two states a player reports with a wall on it prove nothing`() {
        // UNSTARTED is the state the player sits in with the wall up, and ENDED is the
        // state that puts it there. Neither may take the cover down.
        assertFalse(isVideoOnScreen(PlayerStateCode.UNSTARTED))
        assertFalse(isVideoOnScreen(PlayerStateCode.ENDED))
    }

    @Test
    fun `the four states that prove loadVideoById has taken effect`() {
        assertTrue(isVideoOnScreen(PlayerStateCode.BUFFERING))
        assertTrue(isVideoOnScreen(PlayerStateCode.PLAYING))
        assertTrue(isVideoOnScreen(PlayerStateCode.PAUSED))
        assertTrue(isVideoOnScreen(PlayerStateCode.CUED))
    }

    @Test
    fun `a state code this build has never heard of proves nothing`() {
        assertFalse(isVideoOnScreen(99))
        assertFalse(isVideoOnScreen(-7))
    }

    @Test
    fun `the cover comes down once the next video is on the page`() {
        assertTrue(
            coverMayComeDown(videoFinished = false, videoOnScreen = true, countingDown = false),
        )
    }

    @Test
    fun `the cover stays up while the countdown runs on it`() {
        // The countdown is drawn on the cover. Taking the cover down would take the next
        // title and the five seconds with it.
        assertFalse(
            coverMayComeDown(videoFinished = false, videoOnScreen = true, countingDown = true),
        )
    }

    @Test
    fun `the cover stays up until the player confirms the video`() {
        // The window this ticket exists for: the load has been sent and the page has not yet
        // acted on it, so the wall is still what is painted.
        assertFalse(
            coverMayComeDown(videoFinished = false, videoOnScreen = false, countingDown = false),
        )
    }

    @Test
    fun `a finished video can never take its own cover down`() {
        // At the end of a Source nothing is loaded after the end, so no new signal ever
        // arrives. The video that ended is on screen and was on screen, and if that counted
        // the cover would come down onto the wall it was raised over.
        assertFalse(
            coverMayComeDown(videoFinished = true, videoOnScreen = true, countingDown = false),
        )
    }
}
