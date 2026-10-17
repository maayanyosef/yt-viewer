package local.youtubetv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card menu's two levels, and what `Back` means at each of them.
 *
 * The rule that matters most is spec section 7.3's: `Back` inside a submenu returns to the two
 * top lines and **never** jumps straight to the grid. A household that opened the wrong
 * submenu would otherwise have to hold the centre button again to get back where it was.
 */
class CardMenuTest {

    @Test
    fun `nothing is open to begin with`() {
        assertFalse(CardMenu.Closed.isOpen)
        assertNull(CardMenu.Closed.videoIndex)
    }

    @Test
    fun `holding the centre button on a card opens the top of the menu for that card`() {
        val menu = CardMenu.Top(index = 7)

        assertTrue(menu.isOpen)
        assertEquals(7, menu.videoIndex)
    }

    @Test
    fun `choosing a top line opens its submenu, on the same card`() {
        val menu = CardMenu.Top(index = 7).openSub(markWatched = true)

        assertEquals(CardMenu.Sub(index = 7, markWatched = true), menu)
        assertEquals(7, menu.videoIndex)
    }

    @Test
    fun `Back inside a submenu returns to the two top lines, on the same card`() {
        val menu = CardMenu.Sub(index = 7, markWatched = false).back()

        assertEquals(CardMenu.Top(index = 7), menu)
    }

    @Test
    fun `Back at the top lines closes the menu and goes to the grid`() {
        assertEquals(CardMenu.Closed, CardMenu.Top(index = 7).back())
    }

    @Test
    fun `Back with nothing open stays closed, so the app can handle it itself`() {
        assertEquals(CardMenu.Closed, CardMenu.Closed.back())
    }

    @Test
    fun `a closed menu cannot open a submenu out of nowhere`() {
        assertEquals(CardMenu.Closed, CardMenu.Closed.openSub(markWatched = true))
    }
}

/**
 * The four lines of the menu's top level, and the order the owner chose for them.
 *
 * The order is not a detail. The first line takes the highlight the moment the menu opens, so
 * whichever line is first is the one a household that presses the centre button twice gets.
 * The owner chose `Play from start` there on 2026-09-20. See ticket 19 and spec section 7.3.
 */
class CardMenuTopLinesTest {

    @Test
    fun `the top level offers four lines, in the order the owner chose`() {
        assertEquals(
            listOf(
                "Play from start",
                "Jump to a Period",
                "Mark as watched",
                "Mark as unwatched",
            ),
            cardMenuTopLines(),
        )
    }

    @Test
    fun `the line and the panel it opens say the same words`() {
        // The line names the panel, so a household that reads one recognises the other. Two
        // separate strings would drift the first time either was reworded.
        assertEquals(PERIOD_PANEL_TITLE, JUMP_TO_A_PERIOD)
    }
}

/**
 * Which keys the open menu keeps to itself.
 *
 * The menu is a panel over the grid, and the cards behind the scrim are still focusable.
 * Compose's focus search looks for the nearest focusable in the direction asked for, so a
 * `Down` from the last line of the menu would find a card the household cannot see.
 */
class CardMenuKeysTest {

    @Test
    fun `Left and Right are kept, because the menu is one column`() {
        assertTrue(cardMenuKeepsKey(MenuMove.SIDEWAYS, focusedLine = 0, lineCount = 2))
        assertTrue(cardMenuKeepsKey(MenuMove.SIDEWAYS, focusedLine = 1, lineCount = 2))
    }

    @Test
    fun `Up from the first line is kept, so focus cannot climb out to the chips`() {
        assertTrue(cardMenuKeepsKey(MenuMove.UP, focusedLine = 0, lineCount = 2))
    }

    @Test
    fun `Down from the last line is kept, so focus cannot drop onto a card behind`() {
        assertTrue(cardMenuKeepsKey(MenuMove.DOWN, focusedLine = 1, lineCount = 2))
    }

    @Test
    fun `moving between the lines is left to Compose`() {
        assertFalse(cardMenuKeepsKey(MenuMove.DOWN, focusedLine = 0, lineCount = 2))
        assertFalse(cardMenuKeepsKey(MenuMove.UP, focusedLine = 1, lineCount = 2))
    }

    @Test
    fun `a submenu of one line keeps both Up and Down`() {
        assertTrue(cardMenuKeepsKey(MenuMove.UP, focusedLine = 0, lineCount = 1))
        assertTrue(cardMenuKeepsKey(MenuMove.DOWN, focusedLine = 0, lineCount = 1))
    }
}

/**
 * How a dim line is stepped over, and what that does to the end-of-column rule.
 *
 * A dim line is not focusable, so Compose's focus search skips it on its own. What does **not**
 * skip it is counting: with `Just this` dim, the live lines of a three-line submenu are 1 and
 * 2, so `Up` from line 1 is the top of the column and must be kept. Counting raw indices there
 * would let the key through, and Compose would then find a card behind the scrim. That is the
 * same leak ticket 09 wrote up, arriving by a different road.
 *
 * So the menu counts **live lines**: [firstLiveLine] says where the highlight opens, and
 * [liveLineRank] turns a line's own index into its place among the live ones.
 */
class CardMenuDimLineKeysTest {

    /** `Just this` dim, the two range lines live. The case ticket 19 adds. */
    private val deadFirst = listOf(false, true, true)

    @Test
    fun `the highlight opens on the first line that is not dim`() {
        assertEquals(1, firstLiveLine(deadFirst))
        assertEquals(0, firstLiveLine(listOf(true, true, true)))
    }

    @Test
    fun `a wholly dim menu has no line to open on`() {
        assertNull(firstLiveLine(listOf(false)))
        assertNull(firstLiveLine(emptyList()))
    }

    @Test
    fun `a live line is ranked among the live lines only`() {
        assertEquals(0, liveLineRank(deadFirst, line = 1))
        assertEquals(1, liveLineRank(deadFirst, line = 2))
    }

    @Test
    fun `Up from the first live line is kept, even though it is not line zero`() {
        val live = deadFirst.count { it }

        assertTrue(cardMenuKeepsKey(MenuMove.UP, liveLineRank(deadFirst, line = 1), live))
    }

    @Test
    fun `Down from the last live line is kept`() {
        val live = deadFirst.count { it }

        assertTrue(cardMenuKeepsKey(MenuMove.DOWN, liveLineRank(deadFirst, line = 2), live))
    }

    @Test
    fun `moving between the two live lines is still left to Compose`() {
        val live = deadFirst.count { it }

        assertFalse(cardMenuKeepsKey(MenuMove.DOWN, liveLineRank(deadFirst, line = 1), live))
        assertFalse(cardMenuKeepsKey(MenuMove.UP, liveLineRank(deadFirst, line = 2), live))
    }

    @Test
    fun `the four top lines are all live, so nothing is stepped over there`() {
        val live = List(cardMenuTopLines().size) { true }

        assertEquals(0, firstLiveLine(live))
        assertEquals(3, liveLineRank(live, line = 3))
        assertTrue(cardMenuKeepsKey(MenuMove.DOWN, focusedLine = 3, lineCount = 4))
        assertFalse(cardMenuKeepsKey(MenuMove.DOWN, focusedLine = 2, lineCount = 4))
    }
}

/**
 * The guard that closes the key leak ticket 09 found and did not own.
 *
 * **An overlay drawn over the grid leaks keys to the grid behind it until one of its lines has
 * the highlight.** The lines are asked for focus from an effect that retries at 0, 60, 120 and
 * 240 milliseconds, and until one succeeds the highlight is still on a card behind the scrim,
 * where it is still focusable. The Period panel was seen to play a video that way on the
 * emulator on 2026-09-20.
 *
 * This states the rule. **It is not where the rule is enforced.** Compose delivers a preview
 * key event only to the `onPreviewKeyEvent` modifiers on the ancestor chain of the focused
 * node, and while the grid holds the highlight the menu is that card's sibling rather than its
 * ancestor. The guard that does the work sits on the Source screen's outer `Box`. No unit test
 * can catch that placement; it is settled by reading which node is an ancestor of which.
 */
class CardMenuFocusGuardTest {

    @Test
    fun `a menu whose lines have not taken the highlight yet keeps every key`() {
        assertTrue(cardMenuKeepsKey(MenuMove.DOWN, 0, lineCount = 4, hasFocus = false))
        assertTrue(cardMenuKeepsKey(MenuMove.UP, 2, lineCount = 4, hasFocus = false))
        assertTrue(cardMenuKeepsKey(MenuMove.SIDEWAYS, 1, lineCount = 4, hasFocus = false))
    }

    @Test
    fun `once a line has the highlight the end-of-column rule is what matters again`() {
        assertFalse(cardMenuKeepsKey(MenuMove.DOWN, 0, lineCount = 4, hasFocus = true))
        assertTrue(cardMenuKeepsKey(MenuMove.DOWN, 3, lineCount = 4, hasFocus = true))
    }

    @Test
    fun `the Period panel and the card menu answer this the same way`() {
        // The two overlays have the same hole and the same fix. Stating it twice and letting
        // the two drift is worse than one of them calling the other, which is what they do.
        for (move in MenuMove.entries) {
            assertEquals(
                periodPanelKeepsKey(move, 1, lineCount = 3, hasFocus = false),
                cardMenuKeepsKey(move, 1, lineCount = 3, hasFocus = false),
            )
            assertEquals(
                periodPanelKeepsKey(move, 1, lineCount = 3, hasFocus = true),
                cardMenuKeepsKey(move, 1, lineCount = 3, hasFocus = true),
            )
        }
    }
}

/**
 * Telling a tap of the centre button from a hold of it.
 *
 * One key does two jobs on a card: a tap plays the video, a hold opens the menu. Android
 * delivers the initial press with a repeat count of 0 and, once the autorepeat delay has
 * passed, the same key again with 1, 2 and so on. So any repeat count above zero is a hold,
 * which is the same rule [isHeld] already uses for the grid's held `Up`.
 *
 * The part that needs a state machine is what happens afterwards. The key up still arrives
 * after a hold, and it must not then also play the video.
 */
class SelectKeyGestureTest {

    private val gesture = SelectKeyGesture()

    @Test
    fun `a tap acts when the button is released`() {
        assertEquals(SelectGesture.NONE, gesture.onKeyDown(repeatCount = 0))
        assertEquals(SelectGesture.TAP, gesture.onKeyUp())
    }

    @Test
    fun `a hold acts as soon as the key repeats, without waiting for the release`() {
        assertEquals(SelectGesture.NONE, gesture.onKeyDown(repeatCount = 0))
        assertEquals(SelectGesture.HOLD, gesture.onKeyDown(repeatCount = 1))
    }

    @Test
    fun `a hold acts once, however long it is held`() {
        gesture.onKeyDown(repeatCount = 0)
        gesture.onKeyDown(repeatCount = 1)

        assertEquals(SelectGesture.NONE, gesture.onKeyDown(repeatCount = 2))
        assertEquals(SelectGesture.NONE, gesture.onKeyDown(repeatCount = 9))
    }

    @Test
    fun `releasing after a hold does not also play the video`() {
        gesture.onKeyDown(repeatCount = 0)
        gesture.onKeyDown(repeatCount = 1)

        assertEquals(SelectGesture.NONE, gesture.onKeyUp())
    }

    @Test
    fun `a tap straight after a hold still acts`() {
        gesture.onKeyDown(repeatCount = 0)
        gesture.onKeyDown(repeatCount = 1)
        gesture.onKeyUp()

        assertEquals(SelectGesture.NONE, gesture.onKeyDown(repeatCount = 0))
        assertEquals(SelectGesture.TAP, gesture.onKeyUp())
    }

    @Test
    fun `a release with no press before it is not a tap`() {
        assertEquals(SelectGesture.NONE, SelectKeyGesture().onKeyUp())
    }

    @Test
    fun `a second hold works even when the release of the first never arrived`() {
        // What really happens on a card: the hold opens the menu, focus moves to the menu's
        // first line, and the key up lands there instead. The card is never told the button
        // came back up, and the lazy grid keeps it composed, so this object lives on.
        gesture.onKeyDown(repeatCount = 0)
        gesture.onKeyDown(repeatCount = 1)
        gesture.onKeyDown(repeatCount = 2)

        assertEquals(SelectGesture.NONE, gesture.onKeyDown(repeatCount = 0))
        assertEquals(SelectGesture.HOLD, gesture.onKeyDown(repeatCount = 1))
    }

    @Test
    fun `a tap works even when the release of an earlier hold never arrived`() {
        gesture.onKeyDown(repeatCount = 0)
        gesture.onKeyDown(repeatCount = 1)

        gesture.onKeyDown(repeatCount = 0)
        assertEquals(SelectGesture.TAP, gesture.onKeyUp())
    }
}

/**
 * The Source screen's outer key guard, as a rule.
 *
 * **Build ticket 52 pulled it out of `ui/SourceScreen.kt`, where it was written twice inline**,
 * once for the card menu and once for the Period jump list. Two copies of one sentence drift.
 *
 * **These cases cannot prove the guard works**, and saying so is the point of this comment. What
 * they pin is the rule. Whether Compose delivers the key to the handler that asks it is settled
 * by where the handler sits, and the first version of that fix was written inside an overlay,
 * where a preview event never reaches it, and read as perfectly correct. `SourceKeyGuardTest` in
 * `app/src/androidTest/` is what presses a real key against the real placement.
 */
class OverlayKeepsEveryKeyTest {

    @Test
    fun `an overlay that has not got the highlight yet keeps every key there is`() {
        // The window the guard exists for. The highlight is still on a card behind the scrim,
        // so every key the screen let through would act on the grid.
        assertTrue(
            overlayKeepsEveryKey(overlayOpen = true, overlayHasFocus = false, isBack = false),
        )
    }

    @Test
    fun `an overlay holding the highlight answers its own keys`() {
        // Never a bare `overlayOpen`. The outer `Box` is an ancestor of the overlay as well and
        // preview runs from the top down, so keeping every key here would eat the overlay's own
        // `Up` and `Down`.
        assertFalse(
            overlayKeepsEveryKey(overlayOpen = true, overlayHasFocus = true, isBack = false),
        )
    }

    @Test
    fun `BACK is let through whatever else is kept`() {
        // A wholly dim submenu never lands the highlight at all, and the retry ladder gives up
        // at about 484 ms, so an overlay can hold no highlight for as long as it is open. A
        // guard with no exception would leave the household no way off it but the `HOME` button.
        assertFalse(
            overlayKeepsEveryKey(overlayOpen = true, overlayHasFocus = false, isBack = true),
        )
        assertFalse(
            overlayKeepsEveryKey(overlayOpen = true, overlayHasFocus = true, isBack = true),
        )
    }

    @Test
    fun `a screen with no overlay open keeps nothing`() {
        // The ordinary Source screen, where the grid and the chip row answer their own keys.
        for (hasFocus in listOf(false, true)) {
            for (isBack in listOf(false, true)) {
                assertFalse(
                    "hasFocus $hasFocus, isBack $isBack",
                    overlayKeepsEveryKey(
                        overlayOpen = false,
                        overlayHasFocus = hasFocus,
                        isBack = isBack,
                    ),
                )
            }
        }
    }
}
