package local.youtubetv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.youtubetv.data.Landing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Closing a panel over the grid gives the highlight back to the card it came from.
 *
 * **This is the defect class that has cost this run the most time, and no JVM test can see any
 * of it.** Compose moves focus by itself whenever the node holding it leaves composition, and
 * the nearest focusable it then finds is a chip on the row above the panel. The damage is
 * invisible at the moment it happens: the panel still looks right, and the household finds out
 * one `BACK` press later, standing on a chip with the header line empty. Build tickets 19, 33
 * and 37 are all this, and all three were found by a person or an agent looking at a screen.
 *
 * `ui/SourceSelection.kt` holds the rule and `SourceSelectionTest` pins it on the JVM. What no
 * JVM test can show is the screen honouring it, because the thing that breaks it is Compose's
 * own focus search and that does not exist off a device.
 *
 * **The Source does not open on card 0, on purpose.** Build ticket 33 shipped a panel that
 * always opened on row 0 instead of the row it was asked for, and the first television pass did
 * not see it, because that Source's own landing happened to be row 0. Nine videos here are
 * watched, so the Source opens on card 9 and a wrong answer of 0 cannot pass.
 */
@RunWith(AndroidJUnit4::class)
class OverlayFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val screen = testSourceScreen(watchedUpTo = 8)

    /** Where the Source opens: the first gap after the nine watched videos. */
    private val openedOn = screen.videos[9]

    private fun openTheSource() {
        compose.setContent {
            SourceScreen(
                sourceId = "UUx",
                sourceName = "A Source",
                state = screen,
                thumbnails = NoThumbnails,
                openAt = Landing.NEXT_UNWATCHED,
            )
        }
        awaitFocusOn(TestTags.videoCard(openedOn.videoId))
    }

    /** Waits for the tagged node to hold the highlight, and fails the test when it never does. */
    private fun awaitFocusOn(tag: String) {
        compose.waitUntil(FOCUS_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * One press and release of a key, sent through the root the node named by [tag] belongs to.
     *
     * **The tag picks the root and not the receiver**, which is worth saying because it reads
     * like the opposite. `performKeyPress` dispatches at the **view root**, and Android then
     * routes the event to whatever holds the highlight. So naming a node does not deliver
     * anything to it, and both halves of a press land wherever focus is at the time.
     *
     * A node is still named rather than looked up by what holds the highlight, because a
     * `SemanticsNodeInteraction` runs its selector again on every operation, and a selector of
     * "whatever is focused" would quietly change target mid-gesture.
     *
     * The first version of this KDoc said the two halves were delivered to the named node.
     * Build ticket 38's `/code-review` found that claim in its own new test and corrected it
     * there; build ticket 39 corrected this copy.
     */
    private fun press(tag: String, keyCode: Int) {
        val node = compose.onNodeWithTag(tag)
        node.performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keyCode)))
        node.performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, keyCode)))
    }

    /**
     * `MENU` opens the Period jump list, and `BACK` puts the highlight back on the card.
     *
     * `BACK` is sent through the Activity's own back dispatcher and not as a key event, because
     * that is what `BackHandler` listens to and so is the path the household's remote really
     * takes.
     */
    @Test
    fun backOutOfThePeriodPanelReturnsToTheCard() {
        openTheSource()

        press(TestTags.videoCard(openedOn.videoId), AndroidKeyEvent.KEYCODE_MENU)
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertDoesNotExist()
        awaitFocusOn(TestTags.videoCard(openedOn.videoId))
    }

    /**
     * And the header line names that video again, which is the household-visible half of it.
     *
     * The selection and the highlight are two pieces of state that have to agree. The defect
     * this run kept meeting is exactly them disagreeing: a chip took the highlight behind the
     * scrim, the selection was cleared, and the header line went empty while the card still
     * looked highlighted.
     */
    @Test
    fun theHeaderStillNamesTheVideoAfterThePanelCloses() {
        openTheSource()

        press(TestTags.videoCard(openedOn.videoId), AndroidKeyEvent.KEYCODE_MENU)
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitFocusOn(TestTags.videoCard(openedOn.videoId))

        // Two nodes carry the title: the card, and the focused-title line under the Source
        // name. The card's is the one with the tag, so the other one is the header's.
        compose.onNodeWithTag(TestTags.videoCard(openedOn.videoId)).assertExists()
        compose.onAllNodesWithText(openedOn.title).assertCountEquals(2)
    }
}

/** Long enough for the screens' own retry ladder, which gives up at about 484 ms. */
private const val FOCUS_TIMEOUT_MS = 5_000L
