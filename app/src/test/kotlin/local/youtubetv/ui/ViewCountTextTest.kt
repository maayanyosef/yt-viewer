package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shortened view count on a card's date line. Build tickets 59 and 60.
 *
 * Build ticket 60 replaced ticket 59's rounding. The digits that are not shown are cut off, and
 * nothing is ever rounded up. The first test is ticket 60's table, row by row. The rest hold the
 * edges of the same rule.
 */
class ViewCountTextTest {

    @Test
    fun `every row of build ticket 60's table`() {
        assertEquals("845", roundedViewCount(845))
        assertEquals("1K", roundedViewCount(1_000))
        assertEquals("1K", roundedViewCount(1_099))
        assertEquals("1.2K", roundedViewCount(1_234))
        assertEquals("1.2K", roundedViewCount(1_250))
        assertEquals("9.9K", roundedViewCount(9_960))
        assertEquals("52K", roundedViewCount(52_539))
        assertEquals("116K", roundedViewCount(116_945))
        assertEquals("999K", roundedViewCount(999_600))
        assertEquals("1.2M", roundedViewCount(1_234_567))
        assertEquals("1.9M", roundedViewCount(1_999_999))
        assertEquals("2M", roundedViewCount(2_000_000))
        assertEquals("116M", roundedViewCount(116_945_000))
        assertEquals("1.5B", roundedViewCount(1_500_000_000))
    }

    @Test
    fun `a missing count shows nothing`() {
        assertNull(roundedViewCount(null))
    }

    @Test
    fun `a count of zero is a real count`() {
        assertEquals("0", roundedViewCount(0))
    }

    @Test
    fun `below a thousand the count is shown in full`() {
        assertEquals("1", roundedViewCount(1))
        assertEquals("999", roundedViewCount(999))
    }

    @Test
    fun `never a trailing point zero`() {
        assertEquals("1K", roundedViewCount(1_000))
        assertEquals("3M", roundedViewCount(3_000_001))
        assertEquals("3M", roundedViewCount(3_099_999))
    }

    @Test
    fun `one decimal place below ten of a unit, with the rest cut off`() {
        assertEquals("1.1K", roundedViewCount(1_100))
        assertEquals("1.1K", roundedViewCount(1_199))
        assertEquals("9.9K", roundedViewCount(9_999))
    }

    @Test
    fun `no decimal place from ten of a unit`() {
        assertEquals("10K", roundedViewCount(10_000))
        assertEquals("10K", roundedViewCount(10_999))
        assertEquals("99K", roundedViewCount(99_999))
    }

    @Test
    fun `a count never moves to the next unit early`() {
        assertEquals("999K", roundedViewCount(999_999))
        assertEquals("1M", roundedViewCount(1_000_000))
        assertEquals("999M", roundedViewCount(999_999_999))
        assertEquals("1B", roundedViewCount(1_000_000_000))
    }

    @Test
    fun `billions, which the most viewed videos reach`() {
        assertEquals("16B", roundedViewCount(16_234_567_890))
        assertEquals("1.4B", roundedViewCount(1_499_999_999))
    }
}
