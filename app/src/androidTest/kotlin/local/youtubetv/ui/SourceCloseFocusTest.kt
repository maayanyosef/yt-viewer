package local.youtubetv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.youtubetv.data.Direction
import local.youtubetv.data.Landing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Closing a Source screen panel never shows the highlight on a chip, in any frame.
 *
 * **This is build ticket 39, and it is `HomeCloseFocusTest` for the other screen.** Compose
 * moves focus by itself whenever the node holding it leaves composition, and the nearest
 * focusable it finds behind the card menu or the Period jump list is the `Order` chip above the
 * grid. Ticket 19 saw the highlight land there; ticket 39 measured it on the household
 * television on 2026-09-21, with a log on every focus change: the chip took the highlight about
 * 52 ms after `BACK` and the card got it back at 128 ms, and the 60 fps recording coded three
 * frames for the close.
 *
 * **A paused clock is what makes an 80 ms window assertable.** With `autoAdvance` off, nothing
 * happens between one `advanceTimeByFrame` and the next, so the test can ask what holds the
 * highlight at every frame of the close instead of only after it has settled. A test that
 * looked only at the end passes on the broken build, because the broken build settles on the
 * right card too, 76 ms late.
 *
 * **The Source opens on a card that is not the first one**, which `testSourceScreen` arranges
 * through its watched videos. The chip the highlight is lost to is above the grid, so the
 * defect does not need a scrolled grid the way the home rail's did, but a card in the middle is
 * still the honest case: it is where the household is.
 */
@RunWith(AndroidJUnit4::class)
class SourceCloseFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val source = testSourceScreen(count = 24, watchedUpTo = 8)

    /** Where the Source opens: the first gap after the nine watched videos. */
    private val openedOn = source.videos[9]

    private val orderChip = TestTags.chip(orderChipLabel(Direction.NEWEST_FIRST))
    private val periodChip = TestTags.chip(PERIOD_CHIP)
    private val openedOnCard = TestTags.videoCard(openedOn.videoId)

    private fun openTheSource() {
        compose.setContent {
            SourceScreen(
                sourceId = "UUx",
                sourceName = "A Source",
                state = source,
                thumbnails = NoThumbnails,
                openAt = Landing.NEXT_UNWATCHED,
            )
        }
        awaitFocusOn(openedOnCard)
    }

    private fun awaitFocusOn(tag: String) {
        compose.waitUntil(FOCUS_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The test tag of whatever holds the highlight right now, or null when the node holding it
     * has none.
     *
     * One query a frame, and it names the node rather than testing a guess: a card, a chip, or
     * null for a panel's own line, which carries no tag. Asking each candidate tag in turn
     * would be a dozen queries a frame and would answer nothing for a node nobody thought of.
     */
    private fun focusedTag(): String? =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    /**
     * One half of a key press, sent through the root the node named by [tag] belongs to.
     *
     * **The tag picks the root and not the receiver.** `performKeyPress` dispatches at the view
     * root and Android routes the event to whatever holds the highlight. `OverlayFocusTest.press`
     * said the opposite in its own KDoc until this ticket corrected it.
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
     * with a repeat count of 1 and no repeat 0 before it is deliberately ignored. The
     * television's own `input keyevent --longpress` sends the same three.
     */
    private fun hold(tag: String, keyCode: Int) {
        down(tag, keyCode, repeat = 0)
        down(tag, keyCode, repeat = 1)
        up(tag, keyCode)
    }

    private fun press(tag: String, keyCode: Int) {
        down(tag, keyCode, repeat = 0)
        up(tag, keyCode)
    }

    /** `BACK`, through the dispatcher the household's own remote reaches. */
    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    /**
     * The panel, and nothing else, holds the highlight.
     *
     * **Every frame test below depends on this and none of them proved it.** A panel whose own
     * focus ladder has not landed yet leaves the highlight on the card behind the scrim, and
     * then the panel goes without holding it, every frame reads as the card, and the test
     * passes without exercising the defect at all. So exactly one node holds the highlight and
     * it is a line of the open panel. Found by `/code-review`.
     */
    private fun awaitThePanelHoldsTheHighlight() {
        compose.waitUntil(FOCUS_TIMEOUT_MS) {
            val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            focused.size == 1 &&
                isAPanelLine(focused.single().config.getOrNull(SemanticsProperties.TestTag))
        }
    }

    /**
     * Whether [tag] names a line of one of the two panels this file opens.
     *
     * **The card menu's lines carry no tag and the Period jump list's rows carry one**, which
     * build ticket 49 added so its own test could say which row holds the highlight and which
     * row a refresh destroyed. Before that, "no tag" alone stood for "a panel line" here, and
     * the tag turned every wait in this file into a five-second timeout.
     *
     * **It asks the prefix and never builds the set of row tags.** `PERIOD_PANEL_MAX_ROWS` is
     * how many rows the panel is drawn tall and not how many it composes, so a set built from
     * it would miss row 8 and up on a Source cut into more Periods than that. Found by
     * `/code-review` on build ticket 49.
     */
    private fun isAPanelLine(tag: String?): Boolean =
        tag == null || tag.startsWith(TestTags.PERIOD_ROW_PREFIX)

    /** Stands on [openedOn] and holds the centre button, which is what opens the card menu. */
    private fun openTheCardMenu() {
        openTheSource()
        hold(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(PLAY_FROM_START).assertExists()
        awaitThePanelHoldsTheHighlight()
    }

    /**
     * Opens the Period jump list with `MENU`, which is the panel's door that keeps the card.
     *
     * The other card door is the card menu's `Jump to a Period` line, and it is not used here,
     * because choosing a line of that menu on the household television is one press away from a
     * Bulk mark. The two doors meet in `periodPanelReturnsTo` and are one target from there on.
     */
    private fun openThePeriodPanelFromTheCard() {
        openTheSource()
        press(openedOnCard, AndroidKeyEvent.KEYCODE_MENU)
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()
        awaitThePanelHoldsTheHighlight()
    }

    /**
     * Steps a close frame by frame and says what held the highlight at each one.
     *
     * The list starts at the frame after the `BACK` and runs for [frames] frames, which at
     * 16 ms a frame is far past the 80 ms the defect lasted on the television and past the
     * screen's own retry ladder, which gives up at about 484 ms.
     */
    private fun highlightThroughTheClose(frames: Int = 40): List<String?> {
        compose.mainClock.autoAdvance = false
        pressBack()
        val seen = mutableListOf<String?>()
        repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
            seen += focusedTag()
        }
        compose.mainClock.autoAdvance = true
        return seen
    }

    /**
     * No frame of the card menu's close shows the highlight anywhere but on the menu's card.
     *
     * The acceptance criterion of build ticket 39, in the form the 60 fps recording of the
     * household television takes. It is the whole of it: a frame on a chip and a frame on
     * nothing are both frames that are not the card.
     */
    @Test
    fun noFrameOfTheCardMenuCloseLeavesTheCard() {
        openTheCardMenu()

        val seen = highlightThroughTheClose()

        val wrong = seen.withIndex().filter { (_, tag) -> tag != openedOnCard }
        assertTrue(
            "frames not on the card the menu was about, which the defect put on $orderChip: $wrong",
            wrong.isEmpty(),
        )
    }

    /** And the card menu's close settles there, which is what the household is left on. */
    @Test
    fun theCardMenuCloseSettlesOnItsOwnCard() {
        openTheCardMenu()

        pressBack()
        compose.onNodeWithText(PLAY_FROM_START).assertDoesNotExist()
        awaitFocusOn(openedOnCard)
        assertEquals(openedOnCard, focusedTag())
    }

    /** The Period jump list, closed back onto the card it was opened from, behaves the same. */
    @Test
    fun noFrameOfThePeriodPanelCloseLeavesTheCard() {
        openThePeriodPanelFromTheCard()

        val seen = highlightThroughTheClose()

        val wrong = seen.withIndex().filter { (_, tag) -> tag != openedOnCard }
        assertTrue(
            "frames not on the card the panel was opened from, the defect being $orderChip: $wrong",
            wrong.isEmpty(),
        )
    }

    /**
     * And closed back onto the `Period` chip, no frame stops on another chip or on a card.
     *
     * This door cannot assert "the target in every frame" the way the two card doors can: the
     * `Period` chip is handed the highlight while the panel is still composed, and the panel's
     * own line holds it until then, which is a node with no tag. What it does assert is the
     * defect itself, which is the **`Order`** chip, the first focusable on the screen and the
     * one Compose reaches for.
     *
     * **And it is the one test here that does not fail on the build without the fix.** On the
     * household television this door's defect lasted about 32 ms, from the `Order` chip at
     * +48 ms to the `Period` chip at +80, and under a paused clock the chip's own retry ladder
     * lands in the same frame Compose hands the highlight over, so no frame shows it. Even the
     * 60 fps recording of the broken build missed it, and the probe log is what caught it. So
     * this test guards the door rather than proving it, and the set is what settled it.
     */
    @Test
    fun noFrameOfThePeriodPanelCloseStopsOnTheOrderChip() {
        openTheSource()
        // The household walks `Up` out of the grid to reach this chip, and that walk is
        // Compose's own two-dimensional focus search, which does not run in the path a test's
        // key event takes. So the chip is asked directly, and the ask is checked, because a
        // `requestFocus` that quietly did nothing would leave this pressing the centre button
        // on a card and playing a video instead.
        compose.onNodeWithTag(periodChip).requestFocus()
        awaitFocusOn(periodChip)
        // The chip's own door, which is the centre button on it. The key goes to whatever
        // holds the highlight, and that is now the chip.
        press(periodChip, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()
        awaitThePanelHoldsTheHighlight()

        val seen = highlightThroughTheClose()

        // A frame on a row of the panel is the panel still holding the highlight and is not a
        // defect. What must never appear is another node taking it: the `Order` chip is the one
        // Compose reaches for, and a card is a place the household never asked to be.
        val wrong = seen.withIndex().filter { (_, tag) ->
            !isAPanelLine(tag) && tag != periodChip
        }
        assertTrue("frames on something other than the `Period` chip: $wrong", wrong.isEmpty())
        assertEquals(periodChip, seen.last())
    }
}

/** Long enough for the screen's own retry ladder, which gives up at about 484 ms. */
private const val FOCUS_TIMEOUT_MS = 5_000L
