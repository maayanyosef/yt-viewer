package local.youtubetv.ui

import java.time.Instant
import local.youtubetv.data.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun state(
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

/** The red In progress bar on a video card. Spec section 7.3. */
class InProgressTest {

    @Test
    fun `a part watched video fills its bar to where it got`() {
        assertEquals(0.5f, inProgressFraction(state(300), durationSeconds = 600)!!, 0.001f)
    }

    @Test
    fun `a completed video carries no bar`() {
        // The green tick already says the whole story.
        assertNull(inProgressFraction(state(590, completed = true), durationSeconds = 600))
    }

    @Test
    fun `a video the log says nothing about carries no bar`() {
        assertNull(inProgressFraction(null, durationSeconds = 600))
    }

    @Test
    fun `a manual mark as watched carries no bar, because it has no position`() {
        assertNull(inProgressFraction(state(positionSeconds = null), durationSeconds = 600))
    }

    @Test
    fun `a video with no duration carries no bar`() {
        // A live stream and an upcoming premiere both report no usable length.
        assertNull(inProgressFraction(state(300), durationSeconds = null))
        assertNull(inProgressFraction(state(300), durationSeconds = 0))
    }

    @Test
    fun `a position past the duration fills the bar and no more`() {
        // The duration is the Data API's and the position is the player's. They are measured
        // differently and the player can run a second or two past.
        assertEquals(1f, inProgressFraction(state(605), durationSeconds = 600)!!, 0.001f)
    }

    @Test
    fun `a video marked unwatched after a play carries no bar`() {
        // An `unwatched` entry clears the position, so there is nothing to draw.
        assertNull(inProgressFraction(state(null, watched = false), durationSeconds = 600))
    }
}
