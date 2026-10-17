package local.youtubetv.walk

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsTest {

    private val afterTheLimitRose: Instant = Instant.parse("2026-09-18T19:00:11Z")
    private val beforeTheLimitRose: Instant = Instant.parse("2023-05-01T00:00:00Z")

    @Test
    fun `a tall two-minute video published today is a Short`() {
        // Real reply for video DnUAXX41KJ8, read from the API on 2026-09-19.
        assertTrue(isShort(405, 720, 121, afterTheLimitRose))
    }

    @Test
    fun `a wide ten-minute video is not a Short`() {
        // Real reply for video eMade-Up_01, read from the API on 2026-09-19.
        assertFalse(isShort(1280, 720, 631, afterTheLimitRose))
    }

    @Test
    fun `a tall video is not a Short when it runs past the limit`() {
        assertFalse(isShort(405, 720, 181, afterTheLimitRose))
    }

    @Test
    fun `exactly the limit still counts as a Short`() {
        assertTrue(isShort(405, 720, 180, afterTheLimitRose))
        assertTrue(isShort(405, 720, 60, beforeTheLimitRose))
    }

    @Test
    fun `the old sixty-second limit applies before 2024-10-15`() {
        assertTrue(isShort(405, 720, 59, beforeTheLimitRose))
        assertFalse(isShort(405, 720, 61, beforeTheLimitRose))
    }

    @Test
    fun `the day the limit rose already uses the new limit`() {
        assertTrue(isShort(405, 720, 120, Instant.parse("2024-10-15T00:00:00Z")))
    }

    @Test
    fun `a wide video is never a Short however short it is`() {
        assertFalse(isShort(1280, 720, 5, afterTheLimitRose))
    }

    @Test
    fun `a square video is not a Short, because width must be under height`() {
        assertFalse(isShort(720, 720, 5, afterTheLimitRose))
    }

    @Test
    fun `a missing embed size defaults to not-Short, so a live stream stays visible`() {
        // A stream that is live right now reports no embed size.
        assertFalse(isShort(null, null, 0, afterTheLimitRose))
    }

    @Test
    fun `a missing duration defaults to not-Short`() {
        assertFalse(isShort(405, 720, null, afterTheLimitRose))
    }

    @Test
    fun `a missing publish date defaults to not-Short, rather than picking a limit`() {
        assertFalse(isShort(405, 720, 30, null))
    }
}
