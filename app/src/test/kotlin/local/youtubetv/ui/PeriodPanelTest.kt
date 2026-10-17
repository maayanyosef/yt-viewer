package local.youtubetv.ui

import local.youtubetv.data.Period
import local.youtubetv.data.PeriodUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun yearPeriod(year: Int, count: Int = 1, firstIndex: Int = 0) =
    Period(PeriodUnit.YEAR, year = year, month = null, firstIndex = firstIndex, count = count)

private fun monthPeriod(year: Int, month: Int, count: Int = 1, firstIndex: Int = 0) =
    Period(PeriodUnit.MONTH, year = year, month = month, firstIndex = firstIndex, count = count)

/**
 * How tall the panel is.
 *
 * The rule is an entry count, not a `Dp`, so it is a plain JVM test. The composable turns the
 * count into a height by multiplying one row. Spec section 7.4, folded in from section 9.3.
 */
class PeriodPanelRowsTest {

    @Test
    fun `a panel with three entries is three entries tall`() {
        // The cosmetic item from spec section 9.3: the panel was drawn full height even for
        // three entries. It is sized to its contents instead.
        assertEquals(3, periodPanelRows(3))
    }

    @Test
    fun `one entry is one entry tall`() {
        assertEquals(1, periodPanelRows(1))
    }

    @Test
    fun `a long list stops growing and scrolls instead`() {
        assertEquals(PERIOD_PANEL_MAX_ROWS, periodPanelRows(PERIOD_PANEL_MAX_ROWS))
        assertEquals(PERIOD_PANEL_MAX_ROWS, periodPanelRows(PERIOD_PANEL_MAX_ROWS + 1))
        // Fifteen years of TED is about nineteen year buckets.
        assertEquals(PERIOD_PANEL_MAX_ROWS, periodPanelRows(19))
        assertEquals(PERIOD_PANEL_MAX_ROWS, periodPanelRows(36))
    }

    @Test
    fun `no entries is no rows, and never a negative one`() {
        assertEquals(0, periodPanelRows(0))
        assertEquals(0, periodPanelRows(-1))
    }
}

/**
 * Which keys the panel keeps rather than letting them reach the grid behind the scrim.
 *
 * The rule the card menu does not have is the first one: a panel that has not got the
 * highlight yet keeps everything.
 *
 * **This test settles the rule and not where it is attached, and that difference once cost a
 * whole fix.** The first version of the `hasFocus` guard sat inside `PeriodPanelOverlay`, and
 * Compose delivers a preview key event only to the `onPreviewKeyEvent` modifiers on the
 * ancestor chain of the focused node. While the grid still holds the highlight the panel is
 * that card's sibling, so the handler was never visited and the branch below could not run.
 * These tests passed the whole time.
 *
 * The guard that does the work is on the Source screen's outer `Box`, which is the nearest
 * common ancestor of the panel and the grid. If this rule is ever moved, check that node
 * first, because no test here can.
 */
class PeriodPanelKeepsKeyTest {

    @Test
    fun `a panel without the highlight yet keeps every key there is`() {
        // On the emulator on 2026-09-20, `MENU` and then the centre button played a video
        // from behind the open panel, because the rows had not taken focus yet.
        for (move in MenuMove.entries) {
            assertTrue(periodPanelKeepsKey(move, focusedLine = 0, lineCount = 15, hasFocus = false))
        }
    }

    @Test
    fun `Up is kept at the top row and passed on below it`() {
        assertTrue(periodPanelKeepsKey(MenuMove.UP, focusedLine = 0, lineCount = 15, hasFocus = true))
        assertFalse(periodPanelKeepsKey(MenuMove.UP, focusedLine = 1, lineCount = 15, hasFocus = true))
    }

    @Test
    fun `Down is kept at the last row, which is the end of the list and not the end of the panel`() {
        // The panel scrolls, so the last row is the last Period, never the eighth one drawn.
        assertTrue(periodPanelKeepsKey(MenuMove.DOWN, focusedLine = 14, lineCount = 15, hasFocus = true))
        assertFalse(periodPanelKeepsKey(MenuMove.DOWN, focusedLine = 7, lineCount = 15, hasFocus = true))
    }

    @Test
    fun `sideways means nothing in a single column and is always kept`() {
        assertTrue(
            periodPanelKeepsKey(MenuMove.SIDEWAYS, focusedLine = 5, lineCount = 15, hasFocus = true),
        )
    }

    @Test
    fun `no key ever escapes the panel, whatever the row and whatever the move`() {
        // **This is the whole ticket in one assertion.** A key the panel neither acts on nor
        // keeps is handed to Compose's two-dimensional focus search, and the search can find a
        // video card behind the scrim. So for every row of every panel size, at least one of
        // the two has to answer.
        //
        // `/code-review` on build ticket 33 found the one case where neither did: a refresh
        // that shrinks the list under an open panel leaves the row past the end, `periodTarget`
        // answers null there, and the guard reads `focusedLine <= 0` for `Up` and says no. The
        // panel now clamps the row into the list before asking either of them, which is why
        // this walks only rows that are in range.
        for (count in listOf(0, 1, 3, PERIOD_PANEL_MAX_ROWS, 21)) {
            for (from in 0 until count.coerceAtLeast(1)) {
                for (move in MenuMove.entries) {
                    val moved = periodTarget(move, from, count) != null
                    val kept = periodPanelKeepsKey(move, from, count, hasFocus = true)
                    assertTrue("count=$count from=$from move=$move", moved || kept)
                }
            }
        }
    }
}

/**
 * Which row the D-pad moves the highlight to inside the Period jump list.
 *
 * The panel stopped handing `Up` and `Down` to Compose's two-dimensional focus search on build
 * ticket 33. The card menu next door names its neighbour with a `FocusRequester`; this panel
 * cannot, because its rows are a `LazyColumn` and a row scrolled out of view is not composed.
 * So it works the target out and asks for it, the way the video grid does, and this is the
 * arithmetic half of that.
 */
class PeriodTargetTest {

    @Test
    fun `Down walks one row along`() {
        assertEquals(6, periodTarget(MenuMove.DOWN, from = 5, count = 21))
    }

    @Test
    fun `Up walks one row back`() {
        assertEquals(4, periodTarget(MenuMove.UP, from = 5, count = 21))
    }

    @Test
    fun `Up on the first row moves nowhere`() {
        // Not "moves to row 0". Null, so the key is declined and nothing is asked for focus.
        // A move to the row already held would still ask it for focus and could scroll.
        assertNull(periodTarget(MenuMove.UP, from = 0, count = 21))
    }

    @Test
    fun `Down on the last row moves nowhere`() {
        assertNull(periodTarget(MenuMove.DOWN, from = 20, count = 21))
    }

    @Test
    fun `the last row is the last Period and not the last row drawn`() {
        // TED has twenty-one Periods and the panel draws eight. Row 7 is the bottom of the
        // panel, and `Down` on it must still move: the list scrolls under the highlight.
        assertEquals(8, periodTarget(MenuMove.DOWN, from = 7, count = 21))
    }

    @Test
    fun `sideways means nothing in a single column`() {
        for (from in 0 until 21) {
            assertNull(periodTarget(MenuMove.SIDEWAYS, from = from, count = 21))
        }
    }

    @Test
    fun `a panel of one row is stuck in both directions`() {
        assertNull(periodTarget(MenuMove.UP, from = 0, count = 1))
        assertNull(periodTarget(MenuMove.DOWN, from = 0, count = 1))
    }

    @Test
    fun `a panel with no Periods answers nothing`() {
        // The screen never opens one: `openPeriods` says so and shows a message instead. It
        // is answered here anyway, because a rule that only holds while a caller behaves is a
        // rule nobody can move.
        for (move in MenuMove.entries) {
            assertNull(move.name, periodTarget(move, from = 0, count = 0))
        }
    }

    @Test
    fun `a row outside the list answers nothing`() {
        for (move in MenuMove.entries) {
            assertNull(move.name, periodTarget(move, from = -1, count = 21))
            assertNull(move.name, periodTarget(move, from = 21, count = 21))
        }
    }
}

/**
 * What the panel scrolls so the row taking the highlight is drawn.
 *
 * `scrollToItem` puts the row it is given at the **top** of the panel, so scrolling straight to
 * the target would jump the list by a whole panel every time the highlight left the visible
 * window. The rule below scrolls by exactly one row instead, which is what "the list scrolls
 * under the highlight" means.
 */
class PeriodPanelScrollToTest {

    @Test
    fun `a row already drawn needs no scroll`() {
        for (target in 3 until 11) {
            assertNull("$target", periodPanelScrollTo(target, firstVisible = 3, visibleRows = 8))
        }
    }

    @Test
    fun `walking off the bottom scrolls one row`() {
        // Rows 3 to 10 are drawn. Row 11 becomes the bottom row, so row 4 becomes the top.
        assertEquals(4, periodPanelScrollTo(11, firstVisible = 3, visibleRows = 8))
    }

    @Test
    fun `walking off the top scrolls one row`() {
        assertEquals(2, periodPanelScrollTo(2, firstVisible = 3, visibleRows = 8))
    }

    @Test
    fun `the top of the list never scrolls past itself`() {
        assertEquals(0, periodPanelScrollTo(0, firstVisible = 1, visibleRows = 8))
        assertNull(periodPanelScrollTo(0, firstVisible = 0, visibleRows = 8))
    }

    @Test
    fun `the bottom row of TED's twenty-one leaves thirteen rows above it`() {
        assertEquals(13, periodPanelScrollTo(20, firstVisible = 12, visibleRows = 8))
    }

    @Test
    fun `a panel shorter than its contents never scrolls`() {
        // Three Periods in a three-row panel. Every row is drawn, so no move scrolls.
        for (target in 0 until 3) {
            assertNull("$target", periodPanelScrollTo(target, firstVisible = 0, visibleRows = 3))
        }
    }

    @Test
    fun `a panel with no rows drawn asks for no scroll`() {
        assertNull(periodPanelScrollTo(0, firstVisible = 0, visibleRows = 0))
    }

    @Test
    fun `every walk of TED's twenty-one keeps the highlight drawn`() {
        // The whole point, checked end to end rather than case by case: walk down the list and
        // back up, carrying the top row along, and assert the highlight is inside the drawn
        // window after every single press.
        val count = 21
        val rows = PERIOD_PANEL_MAX_ROWS
        var firstVisible = 0
        var line = 0
        for (move in listOf(MenuMove.DOWN, MenuMove.UP)) {
            repeat(count - 1) {
                val target = periodTarget(move, from = line, count = count)
                assertEquals("press $it of $move", true, target != null)
                line = target!!
                periodPanelScrollTo(line, firstVisible, rows)?.let { to -> firstVisible = to }
                assertTrue(
                    "row $line is not drawn, top row is $firstVisible",
                    line >= firstVisible && line <= firstVisible + rows - 1,
                )
            }
            assertNull(periodTarget(move, from = line, count = count))
        }
        assertEquals(0, line)
    }
}

/** What the panel writes on the television. */
class PeriodTextTest {

    @Test
    fun `a year Period is the bare year`() {
        assertEquals("2025", periodLabel(yearPeriod(2025)))
    }

    @Test
    fun `a month Period is the short month and the year`() {
        // The same shape as the card's `3 Jun 2025`, minus the day. Never a relative age.
        assertEquals("Jun 2025", periodLabel(monthPeriod(2025, 6)))
        assertEquals("Jan 2011", periodLabel(monthPeriod(2011, 1)))
        assertEquals("Dec 2026", periodLabel(monthPeriod(2026, 12)))
    }

    @Test
    fun `a Period states its own count, with no thousands separator`() {
        assertEquals("1 video", periodVideoCount(yearPeriod(2025, count = 1)))
        assertEquals("84 videos", periodVideoCount(yearPeriod(2025, count = 84)))
        assertEquals("1204 videos", periodVideoCount(yearPeriod(2025, count = 1204)))
    }

    @Test
    fun `the log line says which bucket the Source got, and how many entries`() {
        assertEquals(
            "Period list: 3 YEAR entries over 126 listed videos.",
            periodListLogLine(PeriodUnit.YEAR, entryCount = 3, videoCount = 126),
        )
        assertEquals(
            "Period list: 28 MONTH entries over 126 listed videos.",
            periodListLogLine(PeriodUnit.MONTH, entryCount = 28, videoCount = 126),
        )
    }

    @Test
    fun `a Source with no dated video says so rather than opening an empty panel`() {
        assertEquals("This Source has no dates to jump to.", NO_PERIODS)
    }

    @Test
    fun `the landing line names the Period, so logcat proves where the jump went`() {
        assertEquals(
            "Period 2024 landed on abc, at index 42 of 588 listed videos.",
            periodLandingLogLine(yearPeriod(2024, firstIndex = 42), videoId = "abc", count = 588),
        )
        assertEquals(
            "Period Jun 2025 landed on xyz, at index 0 of 5303 listed videos.",
            periodLandingLogLine(monthPeriod(2025, 6, firstIndex = 0), videoId = "xyz", count = 5303),
        )
    }
}

/**
 * Where the highlight goes back to when `BACK` closes the panel without choosing a Period.
 *
 * The panel has three doors since ticket 19, and each one has a different answer:
 *
 * - The `Period` chip. The household was standing on the chip row, so `BACK` puts them back on
 *   that chip. Being dropped into the grid would be the panel moving the highlight after all.
 * - The card menu's `Jump to a Period`. The menu was opened by holding the centre button on a
 *   card, so `BACK` puts the highlight back on that card.
 * - `MENU`, which reaches this app on any set whose firmware does not eat the key, but not on
 *   the household TCL. It opens the panel from wherever the highlight was, which may be a card
 *   or may be a chip.
 *
 * **The card travels with the request.** Reading the highlight when `BACK` is pressed would
 * read it after Compose had handed the highlight to the first chip, which clears the selection.
 * The same reason `Refocus` in `ui/SourceScreen.kt` carries its card.
 */
class PeriodPanelDoorTest {

    @Test
    fun `the Period chip is returned to, whatever the grid was standing on`() {
        assertEquals(
            NO_SELECTION,
            periodPanelReturnsTo(fromChip = true, cardIndex = null, focused = 42),
        )
    }

    @Test
    fun `the card menu returns to the card the menu was opened on`() {
        assertEquals(
            42,
            periodPanelReturnsTo(fromChip = false, cardIndex = 42, focused = NO_SELECTION),
        )
    }

    @Test
    fun `MENU from a card returns to that card`() {
        assertEquals(
            7,
            periodPanelReturnsTo(fromChip = false, cardIndex = null, focused = 7),
        )
    }

    @Test
    fun `MENU from the chip row returns to the chip row`() {
        // Nothing is selected while a chip holds the highlight, and a refocus never scrolls, so
        // asking for a card here could leave the screen with no highlight at all.
        assertEquals(
            NO_SELECTION,
            periodPanelReturnsTo(fromChip = false, cardIndex = null, focused = NO_SELECTION),
        )
    }

    @Test
    fun `the card the menu names wins over a stale highlight`() {
        // Closing the card menu hands the highlight to the first chip, which clears the
        // selection. The menu's own card is the one that is still true.
        assertEquals(
            3,
            periodPanelReturnsTo(fromChip = false, cardIndex = 3, focused = 0),
        )
    }
}

/**
 * Which row the panel asks for the highlight back after a refresh has landed under it.
 *
 * **Build ticket 51.** The Period jump list's rows are keyed by their place in the panel, so a
 * refresh that empties the last Period bucket destroys the last row. If the household was
 * standing on that row, Compose takes the highlight out of the panel and nothing used to put it
 * back: the household was left looking at an open panel that answered nothing but `BACK`.
 *
 * **The rule has two halves, and the second one is the one to be careful with.** It names a row
 * when the row the household was standing on is gone, and it names nothing at all otherwise. A
 * refresh is not something the household did, so a refresh that destroys no row must not move
 * the highlight by so much as one row.
 */
class PeriodRowAfterRefreshTest {

    @Test
    fun `a refresh that destroys the row the household is on lands on the last row left`() {
        // Three Periods, the household standing on the last of them, and the refresh empties
        // that Period's bucket. Two rows are left, so row 1 is the one to land on.
        assertEquals(1, periodRowAfterRefresh(focusedRow = 2, rowCount = 2))
    }

    @Test
    fun `a refresh that destroys no row asks for nothing`() {
        // Every row from 0 to 2 is still there, so the household stays exactly where they are.
        for (row in 0..2) {
            assertNull("row $row", periodRowAfterRefresh(focusedRow = row, rowCount = 3))
        }
    }

    @Test
    fun `a refresh that destroys a row below the household asks for nothing`() {
        // The panel goes from five Periods to three and the household is standing on row 1.
        // Rows 3 and 4 are destroyed, row 1 is not, so nothing is asked for.
        assertNull(periodRowAfterRefresh(focusedRow = 1, rowCount = 3))
    }

    @Test
    fun `a refresh that destroys several rows still lands on the last row left`() {
        // TED's twenty-one Periods cut down to four while the household stands on row 20.
        assertEquals(3, periodRowAfterRefresh(focusedRow = 20, rowCount = 4))
    }

    @Test
    fun `a refresh that leaves one Period lands on it`() {
        assertEquals(0, periodRowAfterRefresh(focusedRow = 7, rowCount = 1))
    }

    @Test
    fun `a refresh that leaves no Period at all asks for nothing`() {
        // There is no row to land on, so this rule cannot answer that state at all: putting the
        // highlight back needs a row. **It is reachable**, by a refresh emptying a Source whose
        // panel is already open, because `openPeriods` in `ui/SourceScreen.kt` refuses to
        // **open** the panel for a Source with no Period and nothing used to close an open one.
        // An earlier version of this comment called the state unreachable; `/code-review` on
        // build ticket 51 found that wrong. **Build ticket 52 answers it by closing the panel**,
        // which is `periodPanelCloseAfterRefresh` and `PeriodPanelCloseAfterRefreshTest` below.
        assertNull(periodRowAfterRefresh(focusedRow = 2, rowCount = 0))
        assertNull(periodRowAfterRefresh(focusedRow = 0, rowCount = 0))
    }

    @Test
    fun `a row below the list is clamped up to the first row`() {
        // Nothing in `PeriodPanelOverlay` can make `focusedLine` negative today: it opens at
        // `openOnEntry.coerceAtLeast(0)` and every later value comes from `periodTarget`, which
        // only answers a row inside the list. It is answered here anyway, because a rule that
        // only holds while its caller behaves is a rule nobody can move.
        assertEquals(0, periodRowAfterRefresh(focusedRow = -1, rowCount = 3))
    }

    @Test
    fun `a refresh that makes the list longer asks for nothing`() {
        // A Source that gains a video in a calendar month it had none in grows the panel. The
        // household's own row is untouched, so it is left alone.
        assertNull(periodRowAfterRefresh(focusedRow = 2, rowCount = 4))
    }

    @Test
    fun `every row of TED's twenty-one is left alone while the list keeps them`() {
        for (row in 0 until 21) {
            assertNull("row $row", periodRowAfterRefresh(focusedRow = row, rowCount = 21))
        }
    }

    @Test
    fun `the row answered is always a row the panel still lists`() {
        // The whole point of the clamp: whatever it names has to be a row that can take the
        // highlight, or the retry ladder spends all four of its rungs and the household is left
        // exactly where this ticket found them.
        for (rowCount in 1..21) {
            for (focusedRow in -1..30) {
                val answer = periodRowAfterRefresh(focusedRow, rowCount) ?: continue
                assertTrue(
                    "focusedRow $focusedRow of $rowCount answered $answer",
                    answer in 0 until rowCount,
                )
            }
        }
    }
}

/**
 * The card the Period jump list closes onto, checked against the list the Source has now.
 *
 * **Build ticket 52.** `periodPanelReturnsTo` answers this question when the panel **opens**,
 * and it has to, for the reason written on it. This rule is the same answer read again at the
 * moment the panel goes away, and the two differ on one path only: a refresh landed while the
 * panel was open and took that card out of the Source.
 */
class PeriodPanelClosesOntoTest {

    @Test
    fun `a card the Source still lists is the card the panel closes onto`() {
        // The ordinary close, which is every `BACK` the household presses on a Source nothing
        // happened to. Nothing about it changes.
        assertEquals(3, periodPanelClosesOnto(returnTo = 3, videoCount = 12))
    }

    @Test
    fun `the first and the last card of the Source are both still cards`() {
        assertEquals(0, periodPanelClosesOnto(returnTo = 0, videoCount = 12))
        assertEquals(11, periodPanelClosesOnto(returnTo = 11, videoCount = 12))
    }

    @Test
    fun `the chip row stays the chip row`() {
        // The panel was opened from the `Period` chip, so the household was never on a card.
        assertEquals(NO_SELECTION, periodPanelClosesOnto(returnTo = NO_SELECTION, videoCount = 12))
    }

    @Test
    fun `a Source left listing no video closes onto the chip row`() {
        // The case build ticket 52 was raised for. `refocusIndexAfterRefresh` in
        // `refresh/RefreshMerge.kt` answers null for an empty list, because there is no card to
        // move an index onto, so `periodsReturnTo` goes on naming a card that is gone. The
        // video grid is replaced by `This Source lists no videos.` and holds nothing focusable,
        // so the `Period` chip is the one place the highlight can be put on purpose.
        assertEquals(NO_SELECTION, periodPanelClosesOnto(returnTo = 4, videoCount = 0))
    }

    @Test
    fun `a card past the end of a shorter Source closes onto the chip row`() {
        // A refresh can also leave the Source listing fewer videos than the card the panel was
        // opened about. Asking the retry ladder for it would spend four rungs and about 484 ms
        // on a card that cannot exist.
        assertEquals(NO_SELECTION, periodPanelClosesOnto(returnTo = 9, videoCount = 4))
    }

    @Test
    fun `the answer is always a card the Source lists or the chip row`() {
        for (videoCount in 0..12) {
            for (returnTo in -3..20) {
                val answer = periodPanelClosesOnto(returnTo, videoCount)
                assertTrue(
                    "returnTo $returnTo of $videoCount answered $answer",
                    answer == NO_SELECTION || answer in 0 until videoCount,
                )
            }
        }
    }
}

/**
 * Whether a refresh has left the Period jump list with nothing in it, and where to close it to.
 *
 * **Build ticket 52.** `openPeriods` in `ui/SourceScreen.kt` refuses to **open** this panel for
 * a Source with no Period, and nothing closed an open one. So a refresh that took the last
 * Period away left the panel drawn over the grid with no row in it: no row could take the
 * highlight, `periodRowAfterRefresh` had none to ask for, and the household was left looking at
 * an empty box that answered nothing but `BACK`.
 *
 * **The rule has two halves and the second one is the one to be careful with.** It closes the
 * panel when the panel lists nothing, and it leaves the panel alone in every other case. A
 * refresh is not something the household did, so a refresh that left a Period standing must not
 * take the panel away from under them.
 */
class PeriodPanelCloseAfterRefreshTest {

    @Test
    fun `a refresh that empties the Source closes the panel onto the chip row`() {
        // The whole of build ticket 52's own case: every video of the Source has gone, so the
        // panel lists no Period, and the card the panel was opened about is gone with them.
        assertEquals(
            NO_SELECTION,
            periodPanelCloseAfterRefresh(periodCount = 0, returnTo = 4, videoCount = 0),
        )
    }

    @Test
    fun `a Source whose every remaining video is private closes onto the card`() {
        // `periodsOf` in `data/Periods.kt` gives a Period only to a video that is listed and
        // carries a publish date, so a Source can lose its last Period while still listing
        // videos. The card the panel was opened about is still on the screen, so that is where
        // the highlight goes, exactly as an ordinary `BACK` would put it.
        assertEquals(
            4,
            periodPanelCloseAfterRefresh(periodCount = 0, returnTo = 4, videoCount = 12),
        )
    }

    @Test
    fun `a panel opened from the Period chip closes back onto the chip row`() {
        assertEquals(
            NO_SELECTION,
            periodPanelCloseAfterRefresh(
                periodCount = 0,
                returnTo = NO_SELECTION,
                videoCount = 12,
            ),
        )
    }

    @Test
    fun `a refresh that leaves one Period leaves the panel open`() {
        // One row is a panel the household can still use, and build ticket 51's
        // `periodRowAfterRefresh` is what puts the highlight on it. This rule says nothing.
        assertNull(periodPanelCloseAfterRefresh(periodCount = 1, returnTo = 4, videoCount = 12))
    }

    @Test
    fun `a refresh that destroys no row leaves the panel open`() {
        // The property build ticket 51 was careful about, restated for this rule: a refresh is
        // not something the household did, so it may not take the panel away from under them.
        assertNull(periodPanelCloseAfterRefresh(periodCount = 3, returnTo = 4, videoCount = 12))
    }

    @Test
    fun `every panel that still lists a Period is left alone whatever the Source lists`() {
        for (periodCount in 1..21) {
            for (returnTo in -1..13) {
                for (videoCount in 0..12) {
                    assertNull(
                        "$periodCount Periods, returnTo $returnTo of $videoCount videos",
                        periodPanelCloseAfterRefresh(periodCount, returnTo, videoCount),
                    )
                }
            }
        }
    }

    @Test
    fun `the close target is always a card the Source lists or the chip row`() {
        for (returnTo in -3..20) {
            for (videoCount in 0..12) {
                val answer = periodPanelCloseAfterRefresh(0, returnTo, videoCount)
                assertTrue(
                    "returnTo $returnTo of $videoCount answered $answer",
                    answer == NO_SELECTION || answer in 0 until videoCount,
                )
            }
        }
    }
}
