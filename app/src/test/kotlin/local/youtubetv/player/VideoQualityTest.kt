package local.youtubetv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The quality cap. Whether YouTube really picks a smaller stream needs the television, and the
 * `quality` field of the player's ticks in logcat is what says so there. The size it is asked
 * for is arithmetic, and is tested here.
 */
class VideoQualityTest {

    /** Left and Right stop at both ends, so a held key cannot spin past the wanted setting. */
    @Test
    fun `Left and Right step one setting and stop at the ends`() {
        assertEquals(VideoQuality.P720, VideoQuality.P1080.step(right = true))
        assertEquals(VideoQuality.P1080, VideoQuality.P720.step(right = false))
        assertEquals(VideoQuality.P480, VideoQuality.P480.step(right = true))
        assertEquals(VideoQuality.AUTO, VideoQuality.AUTO.step(right = false))
    }

    /** The arrows say which way the setting can still move. */
    @Test
    fun `the line shows an arrow only where the setting can move`() {
        assertEquals("Video quality: Auto ›", videoQualityLine(VideoQuality.AUTO))
        assertEquals("Video quality: ‹ 720p ›", videoQualityLine(VideoQuality.P720))
        assertEquals("Video quality: ‹ 480p", videoQualityLine(VideoQuality.P480))
    }

    @Test
    fun `a stored name this build does not know reads as Auto`() {
        assertEquals(VideoQuality.AUTO, VideoQuality.fromStored(null))
        assertEquals(VideoQuality.AUTO, VideoQuality.fromStored("P2160"))
        assertEquals(VideoQuality.P480, VideoQuality.fromStored("P480"))
    }

    /** A 1080p television at 320 dpi: density 2, a viewport 540 CSS pixels tall. */
    @Test
    fun `720p on a 1080p television is a 640 by 360 player`() {
        assertEquals(640 to 360, cappedPlayerSize(720, devicePixelRatio = 2.0, viewportHeight = 540))
    }

    @Test
    fun `480p on a 1080p television is a 427 by 240 player`() {
        assertEquals(427 to 240, cappedPlayerSize(480, devicePixelRatio = 2.0, viewportHeight = 540))
    }

    /** A cap as big as the screen changes nothing, so it puts no transform on the picture. */
    @Test
    fun `a cap the screen cannot exceed is no cap`() {
        assertNull(cappedPlayerSize(1080, devicePixelRatio = 2.0, viewportHeight = 540))
        assertNull(cappedPlayerSize(0, devicePixelRatio = 2.0, viewportHeight = 540))
    }

    @Test
    fun `the page is handed null or a size`() {
        assertEquals("null", PlayerHtml.cappedSizeJs(null))
        assertEquals("{\"w\":640,\"h\":360}", PlayerHtml.cappedSizeJs(640 to 360))
        assert(PlayerHtml.build(cappedSize = 640 to 360).contains("var cappedSize = {\"w\":640,\"h\":360};"))
        assert(PlayerHtml.build().contains("var cappedSize = null;"))
    }

    @Test
    fun `the toast says when the cap applies`() {
        assertEquals("Video quality: up to 720p. From the next video.", videoQualitySetLine(VideoQuality.P720))
        assertEquals("Video quality: Auto. YouTube chooses.", videoQualitySetLine(VideoQuality.AUTO))
    }
}
