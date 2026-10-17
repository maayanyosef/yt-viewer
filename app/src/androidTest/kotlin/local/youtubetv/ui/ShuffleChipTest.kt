package local.youtubetv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import local.youtubetv.KeyEventBackActivity
import local.youtubetv.data.Landing
import local.youtubetv.data.ShuffleRun
import local.youtubetv.data.shuffleNextIndex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `Shuffle` chip's focus and keys, on a real composition. Build ticket 69.
 *
 * What is here is what no JVM test can see: which node a key reaches, and what the long press
 * does on the way. The rules behind it, the label, the hint's words and when a press is a
 * Shuffle play, are JVM tests in `ShuffleTextTest` and `ShuffleSelectionTest`.
 *
 * **The long press is the case that matters.** One gesture is three deliveries of the centre
 * button: a key down, a repeat, and a key up. The chip has to answer the repeat by opening the
 * question and must not answer the key up by toggling. The key up lands while the question is
 * opening and before either of its lines holds the highlight, which is exactly the window the
 * screen's outer key guard exists for. On the household television a real held key cannot be
 * injected at all, so this test and the owner's thumb are the two halves of that proof.
 */
@RunWith(AndroidJUnit4::class)
class ShuffleChipTest {

    @get:Rule
    val compose = createAndroidComposeRule<KeyEventBackActivity>()

    private val run = ShuffleRun(seed = 69)
    private var state by mutableStateOf(testSourceScreen(count = 12).copy(shuffleRun = run))
    private val chip = TestTags.chip(SHUFFLE_CHIP)

    private var toggles = 0
    private var newRuns = 0
    private val plays = mutableListOf<Pair<String, Boolean>>()

    private fun open(shuffle: Boolean, openAt: Landing = Landing.NEXT_UNWATCHED) {
        state = state.copy(shuffle = shuffle)
        compose.setContent {
            SourceScreen(
                sourceId = "UUx",
                sourceName = "A Source",
                state = state,
                thumbnails = NoThumbnails,
                onPlay = { video, _, byShuffle -> plays += video.videoId to byShuffle },
                // The real one writes the row on another thread and the state follows. This
                // one follows at once, which is the same thing a frame sooner.
                onToggleShuffle = {
                    toggles++
                    state = state.copy(shuffle = !state.shuffle)
                },
                onStartNewShuffleRun = { newRuns++ },
                openAt = openAt,
            )
        }
        compose.waitForIdle()
    }

    private val runNextTag: String
        get() = TestTags.videoCard(state.videos[shuffleNextIndex(state.videos, run)!!].videoId)

    private fun onTheChip() {
        compose.onNodeWithTag(chip).requestFocus()
        awaitFocusOn(chip)
    }

    @Test
    fun okTogglesTheChipAndTurningItOnMovesTheHighlightToTheRunsNextVideo() {
        open(shuffle = false)
        onTheChip()
        compose.onNodeWithText(shuffleChipLabel(false)).assertExists()

        press(chip, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        assertEquals(1, toggles)
        compose.onNodeWithText(shuffleChipLabel(true)).assertExists()
        awaitFocusOn(runNextTag)
        // One press of OK there is a Shuffle play.
        press(runNextTag, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(listOf(state.videos[shuffleNextIndex(state.videos, run)!!].videoId to true), plays)
    }

    @Test
    fun okTurningItOffLeavesTheHighlightOnTheChip() {
        open(shuffle = true)
        onTheChip()

        press(chip, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        assertEquals(1, toggles)
        compose.onNodeWithText(shuffleChipLabel(false)).assertExists()
        assertEquals(listOf(chip), focusedTags())
    }

    @Test
    fun aLongPressAsksAndDoesNotToggle() {
        open(shuffle = true)
        onTheChip()

        hold(chip, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        assertEquals("the long press toggled the chip", 0, toggles)
        compose.onNodeWithText(shuffleQuestion(0)).assertExists()
        // `Keep this run` holds the highlight first, so a press made without reading keeps it.
        compose.waitUntil(TIMEOUT_MS) { focusedTags().singleOrNull()?.startsWith(UNTAGGED) == true }
        press(chip, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        assertEquals(0, newRuns)
        assertEquals(0, toggles)
        compose.onNodeWithText(shuffleQuestion(0)).assertDoesNotExist()
        awaitFocusOn(chip)
    }

    @Test
    fun startANewRunIsTheSecondAnswer() {
        open(shuffle = false)
        onTheChip()

        hold(chip, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) { focusedTags().singleOrNull()?.startsWith(UNTAGGED) == true }
        press(chip, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(chip, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        assertEquals(1, newRuns)
        assertEquals("starting a new run toggled the chip", 0, toggles)
        compose.onNodeWithText(shuffleChipLabel(false)).assertExists()
        awaitFocusOn(chip)
    }

    @Test
    fun theHintIsOnTheLineOnlyWhileTheChipHoldsTheHighlight() {
        open(shuffle = true)
        val hint = shuffleHintLine(0)
        compose.onNodeWithText(hint).assertDoesNotExist()

        onTheChip()
        compose.onNodeWithText(hint).assertExists()
        // The chip itself says only its state.
        compose.onNodeWithText(shuffleChipLabel(true)).assertExists()

        press(chip, AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitForIdle()
        compose.onNodeWithText(hint).assertDoesNotExist()
    }

    @Test
    fun aPlainOpeningRestsOnTheRunsNextVideoAndACardMovedToIsAnOrdinaryPlay() {
        open(shuffle = true, openAt = Landing.DEFAULT)
        awaitFocusOn(runNextTag)

        // Away and back: the same card, but the household moved to it. Towards the middle of
        // the grid, so the two presses always have a card to move to.
        val atStart = shuffleNextIndex(state.videos, run) == 0
        press(runNextTag, if (atStart) AndroidKeyEvent.KEYCODE_DPAD_RIGHT else AndroidKeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitForIdle()
        press(runNextTag, if (atStart) AndroidKeyEvent.KEYCODE_DPAD_LEFT else AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitForIdle()
        awaitFocusOn(runNextTag)
        press(runNextTag, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        assertEquals(listOf(state.videos[shuffleNextIndex(state.videos, run)!!].videoId to false), plays)
    }

    @Test
    fun anOpeningAskedForByNameWinsOverShuffle() {
        open(shuffle = true, openAt = Landing.NEXT_UNWATCHED)
        val nextUnwatched = TestTags.videoCard(state.videos[0].videoId)
        awaitFocusOn(nextUnwatched)

        press(nextUnwatched, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()

        assertEquals(listOf(state.videos[0].videoId to false), plays)
    }

    private fun awaitFocusOn(tag: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun focusedTags(): List<String> = compose
        .onAllNodes(isFocused())
        .fetchSemanticsNodes()
        .map { it.config.getOrNull(SemanticsProperties.TestTag) ?: "$UNTAGGED${it.id}" }

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

    /** A held centre button: a key down, one repeat, and the key up, as the television sends. */
    private fun hold(tag: String, keyCode: Int) {
        down(tag, keyCode, repeat = 0)
        down(tag, keyCode, repeat = 1)
        up(tag, keyCode)
    }

    private fun press(tag: String, keyCode: Int) {
        down(tag, keyCode, repeat = 0)
        up(tag, keyCode)
    }
}

private const val TIMEOUT_MS = 5_000L
private const val UNTAGGED = "untagged:"
