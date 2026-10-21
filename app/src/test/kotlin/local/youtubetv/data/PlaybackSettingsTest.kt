package local.youtubetv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a Source plays: the stepping and the stored-value rules. Fork 0.5. */
class PlaybackSettingsTest {

    @Test
    fun `speed steps through the player's rates and stops at both ends`() {
        var p = PlaybackSettings()
        assertEquals(1.25f, p.stepRate(right = true).rate)
        assertEquals(0.75f, p.stepRate(right = false).rate)
        assertEquals(0.75f, p.stepRate(right = false).stepRate(right = false).rate)
        repeat(10) { p = p.stepRate(right = true) }
        assertEquals(2f, p.rate)
    }

    @Test
    fun `a skip moves five seconds a press, from off to three minutes`() {
        val p = PlaybackSettings().stepSkipStart(right = true).stepSkipStart(right = true)
        assertEquals(10, p.skipStartSeconds)
        assertEquals(0, PlaybackSettings().stepSkipEnd(right = false).skipEndSeconds)
        var q = PlaybackSettings()
        repeat(100) { q = q.stepSkipEnd(right = true) }
        assertEquals(PlaybackSettings.SKIP_MAX_SECONDS, q.skipEndSeconds)
    }

    @Test
    fun `a stored rate the player does not offer reads as 1x, and a stored skip is bounded`() {
        assertEquals(1f, PlaybackSettings.rateOfStored(null))
        assertEquals(1f, PlaybackSettings.rateOfStored(3.0))
        assertEquals(1.5f, PlaybackSettings.rateOfStored(1.5))
        assertEquals(0, PlaybackSettings.skipOfStored(null))
        assertEquals(180, PlaybackSettings.skipOfStored(9_999))
    }

    @Test
    fun `labels read as a speed and as seconds`() {
        assertEquals("1×", PlaybackSettings.rateLabel(1f))
        assertEquals("1.25×", PlaybackSettings.rateLabel(1.25f))
        assertEquals("Off", PlaybackSettings.skipLabel(0))
        assertEquals("15 s", PlaybackSettings.skipLabel(15))
    }
}
