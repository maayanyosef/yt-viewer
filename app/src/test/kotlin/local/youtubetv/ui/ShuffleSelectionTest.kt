package local.youtubetv.ui

import local.youtubetv.data.Direction
import local.youtubetv.data.Landing
import local.youtubetv.data.ShuffleRun
import local.youtubetv.data.openingLanding
import local.youtubetv.data.shuffleNextIndex
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where Shuffle puts the highlight, and when one press of OK is a Shuffle play. Build ticket
 * 69, rules 6 and 7.
 */
class ShuffleSelectionTest {

    private val videos = (1..8).map { testVideo("v$it", day = 9 - it) }
    private val run = ShuffleRun(seed = 17)
    private val shuffleNext = shuffleNextIndex(videos, run)!!

    @Test
    fun `a plain opening with Shuffle on lands on the run's next video`() {
        val opening = openingLanding(Landing.DEFAULT, videos, emptyMap(), Direction.NEWEST_FIRST, null, shuffleNext)

        assertEquals(shuffleNext, opening)
        assertEquals(videos[shuffleNext].videoId, shuffleArmedOnOpening(Landing.DEFAULT, opening, shuffleNext, videos))
    }

    @Test
    fun `a plain opening with Shuffle off lands on Next unwatched, as before`() {
        assertEquals(0, openingLanding(Landing.DEFAULT, videos, emptyMap(), Direction.NEWEST_FIRST, null, null))
        assertNull(shuffleArmedOnOpening(Landing.DEFAULT, 0, null, videos))
    }

    @Test
    fun `an opening the household asked for by name wins over Shuffle`() {
        for (asked in listOf(Landing.NEXT_UNWATCHED, Landing.LAST_PLAYED, Landing.NEWEST)) {
            val opening = openingLanding(asked, videos, emptyMap(), Direction.NEWEST_FIRST, null, shuffleNext)
            assertEquals(asked.name, 0, opening)
            assertNull(asked.name, shuffleArmedOnOpening(asked, opening, shuffleNext, videos))
        }
    }

    @Test
    fun `OK keeps Shuffle's choice, and any other key the household presses clears it`() {
        assertEquals("v3", shuffleArmedAfterKey("v3", isSelect = true, counts = true))
        assertNull(shuffleArmedAfterKey("v3", isSelect = false, counts = true))
        // The keepalive's SHIFT is not the household.
        assertEquals("v3", shuffleArmedAfterKey("v3", isSelect = false, counts = false))
    }
}
