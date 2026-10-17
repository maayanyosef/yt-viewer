package local.youtubetv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoDurationTest {

    @Test
    fun `parses a minutes and seconds duration`() {
        assertEquals(631, parseIso8601DurationSeconds("PT10M31S"))
    }

    @Test
    fun `parses an hours minutes and seconds duration`() {
        assertEquals(3875, parseIso8601DurationSeconds("PT1H4M35S"))
    }

    @Test
    fun `parses a seconds-only duration`() {
        assertEquals(45, parseIso8601DurationSeconds("PT45S"))
    }

    @Test
    fun `parses a duration with days`() {
        assertEquals(93_784, parseIso8601DurationSeconds("P1DT2H3M4S"))
    }

    @Test
    fun `parses the P0D that a live stream and an upcoming premiere both carry`() {
        assertEquals(0, parseIso8601DurationSeconds("P0D"))
    }

    @Test
    fun `returns null for a missing duration`() {
        assertNull(parseIso8601DurationSeconds(null))
    }

    @Test
    fun `returns null for text it does not understand, rather than guessing zero`() {
        assertNull(parseIso8601DurationSeconds("10:31"))
        assertNull(parseIso8601DurationSeconds(""))
        assertNull(parseIso8601DurationSeconds("PT"))
    }
}
