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
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.youtubetv.data.Landing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Source screen's outer key guard, tested on the one state that stands still.
 *
 * The guard is the two `if`s at the top of the `onPreviewKeyEvent` on `SourceScreen`'s outer
 * `Box` in `ui/SourceScreen.kt`. One is for the card menu and one is for the Period jump list,
 * and they say the same thing: **while an overlay is drawn over the grid and no line of it holds
 * the highlight, every key but `BACK` belongs to this screen.** Without them a `Down` scrolls
 * the grid the household cannot see and the centre button plays a video from behind the
 * overlay. That is not a theory: on the emulator on 2026-09-20, `MENU` followed a beat later by
 * `Down` and the centre button played a video from behind an open Period jump list.
 *
 * **It has to be on the outer `Box` and nowhere else, which is what makes this an
 * instrumentation test.** Compose delivers a preview key event only to the `onPreviewKeyEvent`
 * modifiers on the **ancestor chain of the focused node**. While a card behind the scrim holds
 * the highlight, the overlay is that card's sibling and not its ancestor, so a handler written
 * inside the overlay is never visited. The first version of this fix was written there and was
 * dead code that read as correct; `/code-review` caught it and no JVM test could have. The rule
 * itself is `overlayKeepsEveryKey` in `ui/CardMenu.kt`, unit-tested on the JVM in
 * `OverlayKeepsEveryKeyTest`, and its own KDoc says that stating the rule is not the same as
 * enforcing it. **It is not `cardMenuKeepsKey` or `periodPanelKeepsKey`**, which are a different
 * thing: those two are each overlay's own `Up` and `Down` rule, read inside that overlay's own
 * `onPreviewKeyEvent` once a line of it holds the highlight.
 *
 * **Build ticket 52 is why this file exists.** Until it, the guard's live test was
 * `noKeyReachesTheOrderChipWhenARefreshLeavesThePanelNoRowAtAll` in `PanelRowDestroyedTest`,
 * which emptied a Source under an open Period jump list and then pressed the centre button. That
 * worked only because nothing closed that panel, so the state lasted for ever. Build ticket 52
 * closes the panel, and closing it is the whole point, so that state is gone.
 *
 * **It was measured rather than assumed.** On `emulator-5554`, the `googletv1080p` emulator, on
 * 2026-09-21, the close was stepped frame by frame with the clock stopped, under both the v1
 * compose rule and the v2 one. Exactly one frame draws the panel after the refresh, and in it
 * the highlight is already on the `Period` chip that `closePeriods` handed it to, with
 * `periodsOpen` already false. So there is no frame left in which a key can be pressed against
 * that guard, whichever dispatcher the effects run on.
 *
 * **The wholly dim submenu is a state that does stand still, and it is not a contrivance.**
 * `firstLiveLine` in `ui/CardMenu.kt` answers null for a submenu whose every line is dim, so
 * nothing is ever asked for the highlight, `cardMenuHasFocus` stays false for as long as the
 * household leaves the menu up, and the card behind the scrim goes on holding the highlight.
 * That file's own KDoc names this guard as the thing that keeps such a submenu from leaking its
 * keys to the grid. It is reachable on **any** card while a Source's first walk is running,
 * which `/code-review` on ticket 19 corrected from being written up as the rare case: a Bulk
 * mark needs the whole list, so `cardMenuSubLines` offers only `Just this` then, and on an
 * unwatched card `Mark as unwatched → Just this` has nothing to do and is drawn dim.
 */
@RunWith(AndroidJUnit4::class)
class SourceKeyGuardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * A Source whose first walk is still running, with nothing watched.
     *
     * Both halves matter. The walk is what cuts every submenu down to `Just this`, and nothing
     * watched is what makes `Mark as unwatched → Just this` the line with nothing to do.
     */
    private val walkingSource = testSourceScreen(count = 24, watchedUpTo = -1)
        .copy(walking = SourceScreenState.WalkProgress(loaded = 24, total = 60))

    /** Where the Source opens: nothing is watched, so Next unwatched is the newest video. */
    private val openedOnCard = TestTags.videoCard(walkingSource.videos[0].videoId)

    /** How many videos were played. The guard must keep this at 0. */
    private var plays = 0

    /**
     * The centre button does not play the video behind a wholly dim submenu.
     *
     * The household holds the centre button on a card, walks to `Mark as unwatched` and chooses
     * it. The submenu that opens has one line, `Just this`, and that line is dim because the
     * card is already unwatched, so no line of the menu can take the highlight and the card
     * behind the scrim keeps it. An open guard would play that video while the household is
     * looking at a menu.
     *
     * `Down` is pressed as well, because the two keys fail differently: the centre button plays
     * a video and `Down` walks the grid under the scrim, and only the second leaves the menu
     * looking untouched.
     *
     * **`BACK` is not asserted here, and the reason is measured.** `overlayKeepsEveryKey` lets
     * `BACK` through whatever else it keeps, so that a household on an overlay holding no
     * highlight is never left with no way off it but the `HOME` button. **Nothing in this suite
     * can prove that exception**, because on this emulator `BACK` does not reach `BackHandler`
     * through Compose's key pipeline at all: with the exception removed by hand, so that the
     * guard swallows `BACK`, an injected `KEYCODE_BACK` still closes the submenu. See build
     * ticket 54. An earlier version of this case asserted the close and could not fail, which is
     * what `/code-review` on build ticket 52 found; the assertion was taken out rather than left
     * reading as a guard. The rule's own half of it has four JVM cases in
     * `OverlayKeepsEveryKeyTest`.
     */
    @Test
    fun noKeyReachesTheGridBehindAWhollyDimSubmenu() {
        openTheSource()
        hold(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        awaitTheMenuHoldsTheHighlight()

        // Down to the fourth line of the top level, `Mark as unwatched`, and choose it.
        repeat(3) { press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_DOWN) }
        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(cardMenuSubTitle(markWatched = false)).assertExists()
        compose.onNodeWithText(JUST_THIS_LINE).assertExists()

        // The submenu is wholly dim, so the card behind the scrim is holding the highlight and
        // the guard is the only thing between it and the household's next key.
        assertEquals(
            "a line of the wholly dim submenu took the highlight",
            listOf(openedOnCard),
            focusedTags(),
        )

        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals("the centre button played the video behind the menu", 0, plays)

        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        assertEquals(
            "a key moved the highlight in the grid behind the menu",
            listOf(openedOnCard),
            focusedTags(),
        )
        assertEquals("a key played the video behind the menu", 0, plays)
        compose.onNodeWithText(JUST_THIS_LINE).assertExists()

    }

    /**
     * The centre button does not play the video behind a Period jump list that has just opened.
     *
     * **This is the other call site of the same guard**, the one for the Period jump list, and it
     * is the original defect in its original shape: on the emulator on 2026-09-20, `MENU`
     * followed a beat later by `Down` and the centre button played a video from behind the open
     * panel.
     *
     * **The clock is stopped before `MENU`, and that is what makes the window big enough to press
     * in.** `openPeriods` writes `periodsOpen = true` and `periodPanelHasFocus = false` inside the
     * key handler, before any composition. With the clock stopped, no frame runs, so the panel is
     * not composed, its retry ladder never asks, and the card behind it is still holding the
     * highlight when the next key arrives. On a real television the same window is up to about
     * 484 ms wide, which is what the ladder spends before it gives up.
     *
     * **The panel is asserted to have really opened afterwards**, so the case cannot pass by
     * never reaching the state it is about.
     */
    @Test
    fun noKeyReachesTheGridInTheMomentThePeriodJumpListOpens() {
        openTheSource()

        compose.mainClock.autoAdvance = false
        press(openedOnCard, AndroidKeyEvent.KEYCODE_MENU)
        assertEquals(
            "the panel took the highlight before the second key, so the guard was never asked",
            listOf(openedOnCard),
            focusedTags(),
        )

        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("the centre button played the video behind the panel", 0, plays)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()
        assertEquals("the centre button played the video behind the panel", 0, plays)
    }

    private fun openTheSource() {
        compose.setContent {
            SourceScreen(
                sourceId = "UUx",
                sourceName = "A Source",
                state = walkingSource,
                thumbnails = NoThumbnails,
                onPlay = { _, _ -> plays++ },
                openAt = Landing.NEXT_UNWATCHED,
            )
        }
        awaitFocusOn(openedOnCard)
    }

    /** Waits for the tagged node to hold the highlight, and fails the test when it never does. */
    private fun awaitFocusOn(tag: String) {
        compose.waitUntil(GUARD_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Waits until a line of the card menu holds the highlight, and nothing else does.
     *
     * A menu line carries no test tag, which is how `SourceCloseFocusTest` tells one apart too.
     * Without this wait the walk to `Mark as unwatched` would be pressed while the card still
     * held the highlight, and the case would never reach the state it is about.
     */
    private fun awaitTheMenuHoldsTheHighlight() {
        compose.waitUntil(GUARD_TIMEOUT_MS) {
            focusedTags().let { it.size == 1 && it.single().startsWith(UNTAGGED) }
        }
    }

    /**
     * The test tag of every node reporting that it holds the highlight, in order.
     *
     * A node with no tag is named `untagged:<id>`, so a card menu line is told apart from a
     * missing answer rather than read as one.
     */
    private fun focusedTags(): List<String> = compose
        .onAllNodes(isFocused())
        .fetchSemanticsNodes()
        .map { it.config.getOrNull(SemanticsProperties.TestTag) ?: "$UNTAGGED${it.id}" }

    /**
     * One half of a key press, sent through the root the node named by [tag] belongs to.
     *
     * **The tag picks the root and not the receiver.** `performKeyPress` dispatches at the view
     * root and Android routes the event to whatever holds the highlight. `OverlayFocusTest` says
     * the same thing at more length.
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

}

/** Long enough for every retry ladder on this screen, which give up at about 484 ms. */
private const val GUARD_TIMEOUT_MS = 5_000L

/** What [SourceKeyGuardTest] calls a node that carries no test tag, such as a card menu line. */
private const val UNTAGGED = "untagged:"

/** The one line a submenu offers while the Source's first walk is still running. */
private const val JUST_THIS_LINE = "Just this (1 video)"
