package local.youtubetv.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The home rail's retry ladder, and the one answer it is allowed to test.
 *
 * **Build ticket 41, which is a defect in what build ticket 38 landed.** The rail asks a card
 * for the highlight and then has to find out whether the ask did anything.
 * `FocusRequester.requestFocus` throws when no node is attached to it and says nothing at all
 * about whether the highlight moved, so a card that is attached and quietly does not take the
 * highlight is invisible to the caller. The ladder exists for that card alone.
 *
 * Ticket 38 gave the screen one field for two different facts: the card the app chose, and the
 * card that holds the highlight. `handHighlightBack` wrote the first before the ladder was
 * armed, and the ladder read it as the second, so the first rung always saw what it was waiting
 * for and the 60, 120 and 240 ms rungs never ran. Found by build ticket 39 on 2026-09-21.
 *
 * So there are two rules here and they are tested apart:
 *
 * - [railHighlightHolder], which keeps the record of what the cards themselves report;
 * - [askUntilHighlightHolds], the ladder, which reads that record and nothing else.
 */
class RailHighlightLadderTest {

    private val curated = "PLmadeUpPlaylistForTestsZ000000001"
    private val ted = "UUAuUUnT6oDeKwE6v1NGQxug"

    // ------------------------------------------------------- which card holds the highlight

    @Test
    fun `a card taking the highlight becomes the holder`() {
        assertEquals(curated, railHighlightHolder(held = null, reported = curated, holdsIt = true))
    }

    @Test
    fun `a card taking the highlight from another card replaces it`() {
        assertEquals(ted, railHighlightHolder(held = curated, reported = ted, holdsIt = true))
    }

    /** Opening a card menu: the card loses the highlight to the panel's first line. */
    @Test
    fun `the holder losing the highlight leaves no holder at all`() {
        assertNull(railHighlightHolder(held = curated, reported = curated, holdsIt = false))
    }

    /**
     * Compose may report the gain before the loss when the highlight walks from one card to the
     * next. A loss applied without asking which card it is about would throw away the gain that
     * had just arrived, and the rail would read as holding the highlight nowhere.
     */
    @Test
    fun `a card that was not the holder losing the highlight changes nothing`() {
        assertEquals(ted, railHighlightHolder(held = ted, reported = curated, holdsIt = false))
    }

    @Test
    fun `a loss reported while no card holds it leaves no holder`() {
        assertNull(railHighlightHolder(held = null, reported = curated, holdsIt = false))
    }

    // ------------------------------------------------------------------------- the ladder

    /** One run of the ladder: every wait it asked for, and how many asks it made. */
    private class LadderRun(private val holdsAfterAsk: Int) {
        val waits = mutableListOf<Long>()
        var asks = 0
            private set

        /** True once the ask numbered [holdsAfterAsk] has been made. Never, when that is zero. */
        private fun holds(): Boolean = holdsAfterAsk > 0 && asks >= holdsAfterAsk

        fun climb() = runBlocking {
            askUntilHighlightHolds(
                wait = { millis -> waits += millis },
                ask = { asks++ },
                holds = { holds() },
            )
        }
    }

    /**
     * The ordinary close, where the card takes the highlight at once.
     *
     * One ask, one frame to report back, and nothing else. This is the criterion that says the
     * fix costs no delay on the path the household takes every time.
     */
    @Test
    fun `a first rung that works ends the ladder with no further wait`() {
        val run = LadderRun(holdsAfterAsk = 1)

        run.climb()

        assertEquals(1, run.asks)
        assertEquals(listOf(16L), run.waits)
    }

    /**
     * The failure the ladder exists for: a `requestFocus` that moves nothing and says nothing.
     *
     * This is the case ticket 38's ladder could no longer see. Every later rung has to run.
     */
    @Test
    fun `a first rung that does nothing is followed by the later rungs`() {
        val run = LadderRun(holdsAfterAsk = 0)

        run.climb()

        assertEquals(4, run.asks)
        assertEquals(listOf(16L, 60L, 16L, 120L, 16L, 240L, 16L), run.waits)
    }

    /** A card the lazy row places a frame late: the second rung is what reaches it. */
    @Test
    fun `the ladder stops at the rung that works`() {
        val run = LadderRun(holdsAfterAsk = 2)

        run.climb()

        assertEquals(2, run.asks)
        assertEquals(listOf(16L, 60L, 16L), run.waits)
    }

    /** The last rung counts too, and the ladder gives up after it rather than looping. */
    @Test
    fun `a last rung that works ends the ladder there`() {
        val run = LadderRun(holdsAfterAsk = 4)

        run.climb()

        assertEquals(4, run.asks)
        assertEquals(listOf(16L, 60L, 16L, 120L, 16L, 240L, 16L), run.waits)
    }

    /**
     * The ladder asks before it tests, on every rung.
     *
     * A ladder that tested first would return without asking at all when the record still named
     * the card from before a panel opened, which is the whole defect in another form.
     */
    @Test
    fun `every rung asks before it reads the answer`() {
        val order = mutableListOf<String>()

        runBlocking {
            askUntilHighlightHolds(
                wait = { millis -> order += "wait $millis" },
                ask = { order += "ask" },
                holds = { order += "read"; false },
                waits = listOf(0, 60),
            )
        }

        assertEquals(
            listOf("ask", "wait 16", "read", "wait 60", "ask", "wait 16", "read"),
            order,
        )
    }

    /** The rungs the rail really uses, so the numbers in the tests above are the app's own. */
    @Test
    fun `the ladder waits are the four the rail has always used`() {
        assertEquals(listOf(0, 60, 120, 240), HIGHLIGHT_LADDER_WAITS)
        assertEquals(16L, HIGHLIGHT_LADDER_SETTLE_MILLIS)
    }
}
