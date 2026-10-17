package local.youtubetv.ui

import androidx.compose.ui.unit.dp
import local.youtubetv.data.Direction
import local.youtubetv.data.SourceCard
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun railCard(sourceId: String = "UUx") = SourceCard(
    sourceId = sourceId,
    title = "A Source",
    direction = Direction.NEWEST_FIRST,
    listedCount = 10,
    unwatchedCount = 4,
    nextUnwatched = testVideo("v1", day = 3),
    lastPlayedVideoId = null,
    everythingUnwatchedCount = 10,
    walked = true,
)

/**
 * Which of the three things the home screen draws. Build ticket 36.
 *
 * The defect this pins is a state the screen had no branch of its own for. `HomeScreenState`
 * has `Starting` and `Ready`, and the screen asked for `Ready` before it would draw the
 * first-run page, so `Starting` fell into the rail branch with no cards at all and the
 * `+ Add a Source` card pinned at the rail's end became the whole screen. The owner read that as a
 * household that had chosen no Source, on the household television on 2026-09-21.
 *
 * It is three assertions and no more, because that is the whole rule: one state, one body,
 * and `LOADING` and `FIRST_RUN` are never the same drawing.
 */
class HomeBodyTest {

    @Test
    fun `while storage is being read the screen says it is loading`() {
        assertEquals(HomeBody.LOADING, homeBody(HomeScreenState.Starting))
    }

    /**
     * The true empty household, and the one this ticket must leave alone. It is only ever
     * reached from `Ready`: an empty card list before the read has finished means nothing has
     * been counted yet, not that there is nothing to count.
     */
    @Test
    fun `a household with no Source still gets the first-run page`() {
        assertEquals(HomeBody.FIRST_RUN, homeBody(HomeScreenState.Ready(emptyList())))
    }

    @Test
    fun `a household with a Source gets the rail`() {
        assertEquals(HomeBody.RAIL, homeBody(HomeScreenState.Ready(listOf(railCard()))))
    }

    /**
     * The one that was wrong. Before build ticket 36 the loading state and the rail were the
     * same branch, and the rail draws `+ Add a Source` at its end whether or not it has cards.
     */
    @Test
    fun `the loading state is not the first-run page and is not the rail`() {
        val loading = homeBody(HomeScreenState.Starting)

        assertTrue(
            "The loading state draws $loading, which is what the true empty household draws.",
            loading != HomeBody.FIRST_RUN,
        )
        assertTrue(
            "The loading state draws $loading, which is the rail, and the rail always pins " +
                "`+ Add a Source` at its end.",
            loading != HomeBody.RAIL,
        )
    }
}

/**
 * The placeholders the loading state draws, and the line of text over them.
 *
 * The positions are `RailCardLayout`'s own, which `RailCardLayoutTest` calibrated against the
 * household's panel. What is asserted here is that the placeholder row covers the same width
 * of screen the rail does, so the swap fills no bare surface and leaves none.
 */
class LoadingRailTest {

    private val screen = RailCardLayout.HOUSEHOLD_SCREEN_WIDTH

    @Test
    fun `four tiles reach the household's screen, the three whole cards and the slice`() {
        assertEquals(3, RailCardLayout.fullyVisible(screen))
        assertEquals(34.dp, RailCardLayout.peek(screen))
        assertEquals(4, RailCardLayout.placeholderTiles(screen))
    }

    /**
     * The last tile starts on screen and the one after it would not, which is what makes four
     * the right number on this panel: no bare surface to the right of the tiles, and no tile
     * drawn entirely outside the clip.
     */
    @Test
    fun `the tiles cover the rail's whole width and no more`() {
        val tiles = RailCardLayout.placeholderTiles(screen)

        assertTrue(
            "Tile ${tiles - 1} starts at ${RailCardLayout.leftEdgeOf(tiles - 1)}, past the " +
                "screen's $screen.",
            RailCardLayout.leftEdgeOf(tiles - 1) < screen,
        )
        assertTrue(
            "Tile $tiles would start at ${RailCardLayout.leftEdgeOf(tiles)}, which is still " +
                "on a $screen screen, so a card's place is left bare.",
            RailCardLayout.leftEdgeOf(tiles) >= screen,
        )
    }

    /** A screen exactly as wide as its whole cards has no slice, so it has no extra tile. */
    @Test
    fun `a screen with no slice showing draws no extra tile`() {
        val exact = RailCardLayout.leftEdgeOf(2)

        assertEquals(0.dp, RailCardLayout.peek(exact))
        assertEquals(2, RailCardLayout.placeholderTiles(exact))
    }

    /**
     * The owner's own sentence is the bar: "it should show something that makes it clear to the
     * viewer that the homescreen (sources) is loading". So the screen must **say** it, in the
     * word `CONTEXT.md` gives, and a picture alone does not pass.
     */
    @Test
    fun `the loading line says it is loading and names Sources`() {
        assertTrue(SOURCES_LOADING, SOURCES_LOADING.contains("Loading"))
        assertTrue(SOURCES_LOADING, SOURCES_LOADING.contains("Sources"))
    }
}
