package local.youtubetv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.youtubetv.data.Landing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A refresh that lands before the household has pressed a key moves the highlight to the
 * screen's new landing. One that lands after a key leaves it alone. Build ticket 55.
 *
 * **This needs keys, which is why it is here and not on the JVM.** `highlightAfterRefresh` in
 * `refresh/RefreshMerge.kt` is the rule and is unit-tested. What no JVM test can show is that
 * the screen's record of "a key was pressed" really hears a key: it is written by the outer
 * `Box`'s `onPreviewKeyEvent`, and a preview key event reaches only the ancestors of the node
 * holding the highlight. A record written in the wrong place would read as "no key" for ever,
 * and the highlight would be taken from a household that had moved it.
 *
 * It also needs focus: the landing has to scroll the grid and put the highlight on the new
 * card, which is Compose's work.
 */
@RunWith(AndroidJUnit4::class)
class RefreshLandingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val before = testSourceScreen(watchedUpTo = 8)

    /** Where the Source opens: the first gap after the nine watched videos. */
    private val openedOn = before.videos[9]

    /** The card to the right of it, where one `Right` press puts the highlight. */
    private val rightOfOpened = before.videos[10]

    /**
     * The same Source after a refresh stored a newly listed video. It is newer than everything,
     * so it goes to the top of this newest-first Source, and it becomes Next unwatched.
     */
    private val fresh = testVideo("fresh", day = 500, title = "Video that just arrived").copy(newlyListed = true)
    private val after = before.copy(videos = listOf(fresh) + before.videos)

    private val state = mutableStateOf<SourceScreenState>(before)

    private fun show() {
        compose.setContent {
            SourceScreen(
                sourceId = "UUx",
                sourceName = "A Source",
                state = state.value,
                thumbnails = NoThumbnails,
                openAt = Landing.NEXT_UNWATCHED,
            )
        }
    }

    private fun waitForFocus(videoId: String) {
        compose.waitUntil(FOCUS_TIMEOUT_MS) {
            compose
                .onAllNodes(hasTestTag(TestTags.videoCard(videoId)) and isFocused())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** One whole key press, sent through the root the card named by [tag] belongs to. */
    private fun press(tag: String, keyCode: Int) {
        val at = android.os.SystemClock.uptimeMillis()
        compose.onNodeWithTag(tag).performKeyPress(
            KeyEvent(AndroidKeyEvent(at, at, AndroidKeyEvent.ACTION_DOWN, keyCode, 0)),
        )
        compose.onNodeWithTag(tag).performKeyPress(
            KeyEvent(AndroidKeyEvent(at, at, AndroidKeyEvent.ACTION_UP, keyCode, 0)),
        )
    }

    @Test
    fun aRefreshBeforeAnyKeyMovesTheHighlightToTheNewVideo() {
        show()
        waitForFocus(openedOn.videoId)

        compose.runOnUiThread { state.value = after }

        // The grid scrolls to the top and the new video takes the highlight, with no key.
        waitForFocus(fresh.videoId)
        compose.onNodeWithTag(TestTags.videoCard(fresh.videoId)).assertIsFocused()
    }

    /**
     * The television keepalive's `SHIFT` press is not the household pressing a key.
     *
     * Found on the household television on 2026-10-03, in build ticket 55's own first
     * verification run: `tools/tv/keepalive.sh` pressed `KEYCODE_SHIFT_LEFT` 7 seconds after
     * TED opened, and the new video arrived 64 seconds later to a screen that kept the old
     * landing. See `countsAsHouseholdKey` in `ui/SourceSelection.kt`.
     */
    @Test
    fun aShiftPressDoesNotStopARefreshFromMovingTheHighlight() {
        show()
        waitForFocus(openedOn.videoId)
        press(TestTags.videoCard(openedOn.videoId), AndroidKeyEvent.KEYCODE_SHIFT_LEFT)
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.videoCard(openedOn.videoId)).assertIsFocused()

        compose.runOnUiThread { state.value = after }

        waitForFocus(fresh.videoId)
        compose.onNodeWithTag(TestTags.videoCard(fresh.videoId)).assertIsFocused()
    }

    /**
     * A key pressed while a refresh landing's retry ladder is still running wins.
     *
     * The case build ticket 55's `/code-review` made this file trace. `Right` inside a row is
     * carried by Compose's own focus search, so no new move restarts the ladder, and the ladder
     * used to ask for its card again on its next rung and take the highlight back.
     *
     * **This case does not catch that hole by itself, measured on `emulator-5554` on
     * 2026-10-03.** With the ladder fix taken out it still passed, because the press lands
     * after the ladder's first rung has already finished. What did catch it is
     * [aRefreshAfterAKeyLeavesTheHighlightWhereTheHouseholdPutIt] with the `before != after`
     * test in `highlightAfterRefresh` taken out as well: that run failed with the highlight on
     * `v9`, and putting the ladder fix back alone made it pass. This case is kept as the
     * description of what the household does, and it passes.
     */
    @Test
    fun aKeyPressedJustAfterARefreshLandingKeepsWhereItWent() {
        show()
        waitForFocus(openedOn.videoId)

        compose.runOnUiThread { state.value = after }
        waitForFocus(fresh.videoId)
        // At once, while the landing's ladder is still between rungs.
        press(TestTags.videoCard(fresh.videoId), AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        waitForFocus(before.videos[0].videoId)

        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        val holders = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            .map { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } }
        assertEquals(listOf(TestTags.videoCard(before.videos[0].videoId)), holders)
    }

    @Test
    fun aRefreshAfterAKeyLeavesTheHighlightWhereTheHouseholdPutIt() {
        show()
        waitForFocus(openedOn.videoId)
        press(TestTags.videoCard(openedOn.videoId), AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        waitForFocus(rightOfOpened.videoId)

        compose.runOnUiThread { state.value = after }
        compose.waitForIdle()
        // Long enough for a landing the screen should not have made to have happened.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        // Named in the message, so a failure says where the highlight went instead.
        val holders = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            .map { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } }
        assertEquals(listOf(TestTags.videoCard(rightOfOpened.videoId)), holders)
    }
}

/** Long enough for the grid's own retry ladder, which gives up at about 484 ms. */
private const val FOCUS_TIMEOUT_MS = 5_000L
