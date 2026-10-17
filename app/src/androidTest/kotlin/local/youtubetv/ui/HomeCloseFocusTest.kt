package local.youtubetv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.youtubetv.data.SourceCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Closing a home card menu never shows the highlight on another card, in any frame.
 *
 * **This is build ticket 38, and it is the one defect class this suite was built for.** Compose
 * moves focus by itself whenever the node holding it leaves composition, and the node it
 * chooses is the nearest focusable it can find, which behind a card menu is the first rail card
 * the lazy row has placed. Nothing about it is visible in a screenshot: measured on the
 * household television on 2026-09-21, the wrong card held the highlight for 109 ms and a
 * picture over adb costs about 1.5 s.
 *
 * **A paused clock is what makes a 109 ms window assertable.** With `autoAdvance` off, nothing
 * happens between one `advanceTimeByFrame` and the next, so the test can ask which card holds
 * the highlight at every frame of the close instead of only after it has settled. The defect
 * this pins is a frame, so a test that looked only at the end would pass on the broken build:
 * the broken build settles on the right card too, 109 ms late.
 *
 * **The menu is opened on a card that is not the first one, on purpose.** The card Compose
 * grabs is the first one, so a menu opened on the first card cannot tell the fix from the
 * defect.
 *
 * **Build ticket 41 added the last two tests**, over the same close and the same paused clock.
 * They are about the rail's retry ladder rather than about the frame: that the ordinary close
 * still costs no extra frame, and that a close whose hand-back cannot reach its card is landed
 * by the ladder instead. Each says in its own KDoc what it does and does not catch.
 */
@RunWith(AndroidJUnit4::class)
class HomeCloseFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Five Sources, which is one more than the household has. */
    private val cards = (0 until 5).map { index ->
        testSourceCard(sourceId = "UUsource$index", title = "Source number $index")
    }

    /** The card the menu is opened on. Not the first, and not the one Compose would grab. */
    private val menuCard = cards[2]

    /**
     * The rail, with the highlight already on [menuCard].
     *
     * **It opens through the app's own door and not by asking a node for focus.** The household
     * reaches this state by coming back from a Source, and `focusOnSourceId` is what the
     * Activity passes to say so. `SemanticsNodeInteraction.requestFocus` was tried first and
     * left the highlight where it was, on the first card, so a test built on it would have
     * measured the wrong card's close.
     *
     * It also leaves the rail **scrolled**, which is the state the defect is visible in: the
     * card Compose grabs is the first one the lazy row has placed, and that card is mostly off
     * the left edge of the screen.
     */
    private fun openTheRail() {
        compose.setContent {
            HomeScreen(
                state = HomeScreenState.Ready(cards),
                focusOnSourceId = menuCard.sourceId,
                returnedFromSource = 1L,
            )
        }
        awaitFocusOn(TestTags.railCard(menuCard.sourceId))
    }

    /**
     * Twelve Sources, which is four times the rail's own width.
     *
     * Build ticket 41 needs a rail the lazy row cannot hold all of at once: its case is a card
     * the household cannot see being asked for the highlight, and with five cards the first one
     * is still placed. On the `googletv1080p` emulator, which is the household television's own
     * geometry, a 352dp card in 960dp of rail means fewer than three are placed at a time.
     */
    private val longRail = (0 until 12).map { index ->
        testSourceCard(sourceId = "UUlong$index", title = "Source number $index")
    }

    /** The card the menu is opened on there. Far enough along that card 0 is not placed. */
    private val farCard = longRail[8]

    /**
     * The rail's cards, when the test needs to change them under an open panel.
     *
     * Build ticket 41. A `HomeScreen` takes its whole state as a parameter, so a Source going
     * away under a panel is a list this test owns and writes to, and nothing here reads a
     * database or calls YouTube.
     */
    private val liveCards = mutableStateOf(longRail)

    /** [openTheRail], on [longRail], with [liveCards] behind it instead of a fixed list. */
    private fun openTheLongRail() {
        compose.setContent {
            HomeScreen(
                state = HomeScreenState.Ready(liveCards.value),
                focusOnSourceId = farCard.sourceId,
                returnedFromSource = 1L,
            )
        }
        awaitFocusOn(TestTags.railCard(farCard.sourceId))
    }

    private fun awaitFocusOn(tag: String) {
        compose.waitUntil(FOCUS_TIMEOUT_MS) { isFocused(tag) }
    }

    private fun isFocused(tag: String): Boolean =
        compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()

    /** Which of [of]'s cards holds the highlight right now, or null when none does. */
    private fun highlightedCard(of: List<SourceCard> = cards): String? =
        of.map { it.sourceId }.firstOrNull { isFocused(TestTags.railCard(it)) }

    /**
     * One half of a key press, sent through the root the node named by [tag] belongs to.
     *
     * **The tag picks the root and not the receiver**, which is worth saying because it reads
     * like the opposite. `performKeyPress` dispatches at the view root, and Android then routes
     * the event to whatever holds the highlight. So the third delivery of [hold] lands on the
     * menu's first line and not on the card, because the second delivery is what opened the
     * menu. That is harmless here, and it is harmless on the household television for the same
     * reason: `SelectKeyGesture.onKeyUp` answers `NONE` to a release it never saw the press
     * for, which is exactly the case it was written for. Found by `/code-review`.
     *
     * A node is still named rather than looked up by what holds the highlight, because a
     * `SemanticsNodeInteraction` runs its selector again on every operation and a selector of
     * "whatever is focused" would quietly change target mid-gesture.
     */
    private fun down(tag: String, keyCode: Int, repeat: Int) {
        val at = android.os.SystemClock.uptimeMillis()
        compose.onNodeWithTag(tag).performKeyPress(
            KeyEvent(AndroidKeyEvent(at, at, AndroidKeyEvent.ACTION_DOWN, keyCode, repeat)),
        )
    }

    private fun up(tag: String, keyCode: Int) {
        val at = android.os.SystemClock.uptimeMillis()
        compose.onNodeWithTag(tag).performKeyPress(
            KeyEvent(AndroidKeyEvent(at, at, AndroidKeyEvent.ACTION_UP, keyCode, 0)),
        )
    }

    /**
     * A held centre button, which is **three** deliveries and not one.
     *
     * `SelectKeyGesture` counts a hold only after it has seen the first delivery: a key down
     * with a repeat count of 1 and no repeat 0 before it is a release this element never saw
     * the press for, and it is deliberately ignored. The television's own
     * `input keyevent --longpress` sends the same three, which build ticket 32 measured.
     */
    private fun hold(tag: String, keyCode: Int) {
        down(tag, keyCode, repeat = 0)
        down(tag, keyCode, repeat = 1)
        up(tag, keyCode)
    }

    /** Stands on [menuCard] and holds the centre button, which is what opens the card menu. */
    private fun openTheMenu() {
        openTheRail()
        hold(TestTags.railCard(menuCard.sourceId), AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(OPEN_AT_NEXT_UNWATCHED).assertExists()
    }

    /** `BACK`, through the dispatcher the household's own remote reaches. */
    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    /**
     * Steps the close frame by frame and says which card held the highlight at each one.
     *
     * The list starts at the frame after the close and runs for [frames] frames, which at
     * 16 ms a frame is far longer than the 109 ms the defect lasted on the television and
     * longer than the rail's own retry ladder, which gives up at about 484 ms.
     *
     * @param close what closes the panel. `BACK` by default, which is the household's own way
     *   out. Build ticket 41 passes the other one: the Source the panel is about going away
     *   under it, which closes the panel from an effect.
     */
    private fun highlightThroughTheClose(
        frames: Int = 40,
        cards: List<SourceCard> = this.cards,
        close: () -> Unit = { compose.activity.onBackPressedDispatcher.onBackPressed() },
    ): List<String?> {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { close() }
        val seen = mutableListOf<String?>()
        repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
            seen += highlightedCard(cards)
        }
        compose.mainClock.autoAdvance = true
        return seen
    }

    /**
     * No frame of the close shows a card other than the menu's own card highlighted.
     *
     * The acceptance criterion of build ticket 38, in the form the 60 fps recording of the
     * household television takes.
     */
    @Test
    fun noFrameOfTheCloseHighlightsAnotherCard() {
        openTheMenu()

        val seen = highlightThroughTheClose()

        val wrong = seen.withIndex().filter { (_, id) -> id != null && id != menuCard.sourceId }
        assertTrue("frames on the wrong card: $wrong", wrong.isEmpty())
    }

    /**
     * And at most one frame of it shows no card highlighted at all.
     *
     * **This one is weaker than it looks, and that is worth knowing before trusting it.** On
     * the broken build it passes: Compose hands the highlight to the wrong card in the very
     * frame the panel goes, so the paused clock never sees a frame with nothing highlighted,
     * while the television showed one for 50 ms. What it does catch is a later change that
     * gives the highlight up without giving it to anybody, which is the shape
     * `RailCard`'s `enabled` records as already tried and wrong.
     */
    @Test
    fun atMostOneFrameOfTheCloseHasNoHighlight() {
        openTheMenu()

        val seen = highlightThroughTheClose()

        val blank = seen.count { it == null }
        assertTrue("frames with no highlight: $blank of ${seen.size}", blank <= 1)
    }

    /** And it settles on the menu's own card, which is what the household is left standing on. */
    @Test
    fun theCloseSettlesOnTheCardTheMenuWasAbout() {
        openTheMenu()

        pressBack()
        compose.onNodeWithText(OPEN_AT_NEXT_UNWATCHED).assertDoesNotExist()
        awaitFocusOn(TestTags.railCard(menuCard.sourceId))
        assertEquals(menuCard.sourceId, highlightedCard())
    }

    /**
     * The ordinary close puts the highlight back at once and nothing takes it away again.
     *
     * Build ticket 41 made the rail's retry ladder able to fail again, and the price of that
     * could have been a ladder that disturbs the close the household takes every time. The
     * highlight is on the menu's own card within two frames and stays there for the whole of the
     * ladder's last rung at 240 ms and past the 484 ms at which it gives up.
     *
     * **It cannot see the ladder's later rungs, and its name must not be read as saying it
     * can.** A rung that asks an already-focused card for the highlight moves nothing, so a
     * ladder that ran all four would leave these forty frames identical. Ticket 41's second
     * criterion, that the ladder exits at its first rung, is pinned on the JVM instead, in
     * `RailHighlightLadderTest`, where the asks can be counted. Found by `/code-review`.
     */
    @Test
    fun theOrdinaryCloseStillPutsTheHighlightBackAtOnce() {
        openTheMenu()

        val seen = highlightThroughTheClose()

        val firstTwo = seen.take(2)
        assertTrue(
            "the first two frames of the close were $firstTwo, and neither is the menu's card",
            firstTwo.contains(menuCard.sourceId),
        )
        val after = seen.dropWhile { it != menuCard.sourceId }
        assertTrue(
            "the highlight left the menu's card again during the close: $seen",
            after.all { it == menuCard.sourceId },
        )
    }

    /**
     * The rail is left with a highlight when the hand-back's own ask does nothing.
     *
     * **Build ticket 41.** The Source the card menu is about goes away under it, which a
     * background refresh does and which `Remove this Source` is written to do.
     * `railHighlightTarget` then falls back to the first card of the rail, and on a rail
     * scrolled to card 8 the lazy row has not placed card 0 at all, so `handHighlightBack` has
     * no `FocusRequester` to ask and does nothing whatever. Only the rail's retry ladder, which
     * scrolls first, can land the highlight here, and this is what says it does.
     *
     * **It does not go red on the defect ticket 41 was raised for, and that was measured rather
     * than assumed.** With the ladder's exit test put back to `focusedSourceId`, which is the
     * field `handHighlightBack` writes itself, this test still passes on `googletv1080p`: the
     * ladder's own first rung asks after its `scrollToItem`, by which time card 0 is composed
     * and registered, so stopping at the first rung costs nothing here. The ask the defect hides
     * is a `requestFocus` that reaches an attached node and quietly moves nothing, and no
     * gesture or state change reached through this app's own doors produces one on the emulator.
     * `RailHighlightLadderTest` on the JVM is what pins that case, by handing the ladder a
     * `holds` that stays false.
     *
     * What this test does catch is the ladder going away or stopping short of the card the rule
     * chose: with the `askUntilHighlightHolds` call taken out of `SourceRail`, it fails with the
     * highlight left on the card Compose grabbed when the panel went.
     */
    @Test
    fun theRailIsNotLeftStrandedWhenTheSourceUnderTheMenuGoesAway() {
        openTheLongRail()
        hold(TestTags.railCard(farCard.sourceId), AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(OPEN_AT_NEXT_UNWATCHED).assertExists()

        val seen = highlightThroughTheClose(cards = longRail) {
            liveCards.value = longRail - farCard
        }

        val firstCard = longRail.first().sourceId
        assertEquals("the frames of the close were $seen", firstCard, seen.last())
    }
}

/** Long enough for the screens' own retry ladder, which gives up at about 484 ms. */
private const val FOCUS_TIMEOUT_MS = 5_000L
