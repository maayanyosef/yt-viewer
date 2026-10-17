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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every retry ladder still lands the highlight where it said it would.
 *
 * **Build ticket 47.** Six ladders across the two screens used to exit on
 * `runCatching { requestFocus() }.isSuccess`, which reports only that the call did not throw.
 * They now exit on a `HighlightRecord`, which only the nodes themselves write. That is a change
 * to when every one of those ladders stops, so this file is the proof that each one still
 * stops in the right place on a real screen.
 *
 * **What this file cannot prove, and no device test can.** The case the ladders exist for is a
 * node that is attached, is asked for the highlight, throws nothing and quietly does not take
 * it. Build ticket 41 tried three ways to produce that on the `googletv1080p` emulator,
 * including a rail of twelve Sources with the target card far outside what the lazy row held,
 * and a paused test clock stepped frame by frame. The ask landed every time. The JVM tests in
 * `app/src/test/kotlin/local/youtubetv/ui/HighlightRecordTest.kt` are what stands for that
 * case, because they hand each ladder a `holds` that never becomes true. This file is the other
 * half: the wiring, on a real screen, which no JVM test can describe.
 *
 * **The riskiest thing build ticket 47 changed is the card menu's level change**, and it has
 * two tests here. The old ladder wrote the menu's own key guard, `hasFocus`, true on the first
 * `requestFocus` that did not throw. That write is gone, and Compose reuses the line nodes
 * between the menu's two levels, so a node that already holds the highlight reports no focus
 * change and no report arrives. The record is deliberately not rebuilt on a level change, which
 * is what keeps the guard true through one.
 *
 * It runs on the `googletv1080p` emulator and never on the household television. See
 * `docs/agents/testing.md`.
 */
@RunWith(AndroidJUnit4::class)
class LadderExitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val source = testSourceScreen(count = 24, watchedUpTo = 8)

    /** Where the Source opens: the first unwatched video after the nine watched ones. */
    private val openedOn = source.videos[9]

    private val openedOnCard = TestTags.videoCard(openedOn.videoId)

    /** Five Sources on the home rail, which is one more than the household has. */
    private val cards = (0 until 5).map { index ->
        testSourceCard(sourceId = "UUsource$index", title = "Source number $index")
    }

    /** The card the home menu is opened on. Not the first, so Compose's own choice is visible. */
    private val menuCard = cards[2]

    // ------------------------------------------------------------------------ the plumbing

    private fun awaitFocusOn(tag: String) {
        compose.waitUntil(LADDER_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The test tag of whatever holds the highlight right now, or null when the node holding it
     * has none.
     *
     * A panel's own lines carry no tag, so null means a panel line, a first-run line or nothing
     * at all. [holdersOfTheHighlight] is what tells those two apart.
     */
    private fun focusedTag(): String? = holdersOfTheHighlight().firstOrNull()

    /** One entry per node reporting that it holds the highlight, each its tag or null. */
    private fun holdersOfTheHighlight(): List<String?> =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.TestTag) }

    /**
     * Exactly one node holds the highlight and it carries no test tag, which on these two
     * screens means a card menu line, a home panel line or the first-run page's one line.
     *
     * **The Period jump list's rows are not among them any more.** Build ticket 49 gave them a
     * tag, so that ladder is waited for by [awaitOneRowOfThePeriodJumpListHoldsTheHighlight].
     */
    private fun awaitAnUntaggedNodeHoldsTheHighlight() {
        compose.waitUntil(LADDER_TIMEOUT_MS) {
            holdersOfTheHighlight().let { it.size == 1 && it.single() == null }
        }
    }

    /**
     * Exactly one node holds the highlight and it is a row of the Period jump list.
     *
     * The rows carry `TestTags.periodRow`, which build ticket 49 added so its own test could
     * say which row holds the highlight and which row a refresh destroyed. It says more here
     * than "no tag" did, because it names the panel rather than only ruling out the grid.
     *
     * **It asks the prefix and never builds the set of row tags.** `PERIOD_PANEL_MAX_ROWS` is
     * how many rows the panel is drawn tall and not how many it composes, so a set built from
     * it would miss row 8 and up on a Source cut into more Periods than that, and this wait
     * would then run to its timeout for a screen that is behaving correctly. Found by
     * `/code-review` on build ticket 49.
     */
    private fun awaitOneRowOfThePeriodJumpListHoldsTheHighlight() {
        compose.waitUntil(LADDER_TIMEOUT_MS) {
            holdersOfTheHighlight().let {
                it.size == 1 && it.single()?.startsWith(TestTags.PERIOD_ROW_PREFIX) == true
            }
        }
    }

    /**
     * One half of a key press, sent through the root the node named by [tag] belongs to.
     *
     * **The tag picks the root and not the receiver.** `performKeyPress` dispatches at the view
     * root and Android routes the event to whatever holds the highlight.
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

    private fun press(tag: String, keyCode: Int) {
        down(tag, keyCode, repeat = 0)
        up(tag, keyCode)
    }

    /**
     * A held centre button, which is **three** deliveries and not one.
     *
     * `SelectKeyGesture` counts a hold only after it has seen the first delivery: a key down
     * with a repeat count of 1 and no repeat 0 before it is deliberately ignored.
     */
    private fun hold(tag: String, keyCode: Int) {
        down(tag, keyCode, repeat = 0)
        down(tag, keyCode, repeat = 1)
        up(tag, keyCode)
    }

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
    }

    private fun openTheRail() {
        compose.setContent {
            HomeScreen(
                state = HomeScreenState.Ready(cards),
                focusOnSourceId = menuCard.sourceId,
                returnedFromSource = 1L,
            )
        }
    }

    // --------------------------------------------------------------- the grid's own ladder

    /**
     * Opening a Source lands the highlight on its Next unwatched video, which is the grid's
     * ladder doing the one thing it was written for: a card the lazy grid had not placed when
     * the screen was first composed.
     *
     * "Next unwatched" is the oldest video the household has not watched. Here it is the tenth
     * of twenty-four, because the first nine are marked watched, so it is well down the grid.
     */
    @Test
    fun openingASourceLandsTheHighlightOnItsNextUnwatchedVideo() {
        openTheSource()

        awaitFocusOn(openedOnCard)

        assertEquals(openedOnCard, focusedTag())
    }

    // ------------------------------------------------------------ the card menu's ladder

    /**
     * Holding the centre button on a card opens the card menu and the menu takes the
     * highlight off the card behind the scrim.
     */
    @Test
    fun openingTheCardMenuTakesTheHighlightOffTheGrid() {
        openTheSource()
        awaitFocusOn(openedOnCard)

        hold(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(PLAY_FROM_START).assertExists()

        awaitAnUntaggedNodeHoldsTheHighlight()
        assertNull(focusedTag())
    }

    /**
     * A level change keeps the highlight inside the card menu.
     *
     * **This is the test build ticket 47 most needed.** Choosing `Mark as watched` swaps the
     * menu's four top lines for a submenu of at most three, and Compose reuses the same nodes,
     * so the node holding the highlight can report no focus change at all. The old ladder wrote
     * the menu's key guard true itself on a `requestFocus` that did not throw; build ticket 47
     * took that write away, and what replaces it is a record that is deliberately not rebuilt
     * on a level change.
     *
     * The walk is two `Down` presses, because `cardMenuTopLines` is `Play from start`,
     * `Jump to a Period`, `Mark as watched`, `Mark as unwatched`, and the menu opens on the
     * first line.
     */
    @Test
    fun theCardMenuStillHoldsTheHighlightAfterALevelChange() {
        openTheSource()
        awaitFocusOn(openedOnCard)
        hold(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        awaitAnUntaggedNodeHoldsTheHighlight()

        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(cardMenuSubTitle(markWatched = true)).assertExists()

        awaitAnUntaggedNodeHoldsTheHighlight()
        assertNull(focusedTag())
    }

    /**
     * And the submenu still keeps the household's keys, which is what the menu's guard is for.
     *
     * The guard is `menu.isOpen && !cardMenuHasFocus`, read on the screen's outer `Box`, and it
     * swallows every key but `BACK` while no line of the menu holds the highlight. Build ticket
     * 47 changed what sets that flag, so a `Down` pressed after a level change must still walk
     * the submenu and must never reach a card in the grid behind the scrim.
     */
    @Test
    fun theSubmenuKeepsTheKeysRatherThanLettingThemReachTheGrid() {
        openTheSource()
        awaitFocusOn(openedOnCard)
        hold(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        awaitAnUntaggedNodeHoldsTheHighlight()
        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        awaitAnUntaggedNodeHoldsTheHighlight()

        repeat(4) { press(openedOnCard, AndroidKeyEvent.KEYCODE_DPAD_DOWN) }

        val holders = holdersOfTheHighlight()
        // **The count is asserted first, and leaving it out made this test unable to fail.**
        // `all` on an empty list is true, and an empty list is what `holdersOfTheHighlight`
        // answers when **nothing at all** holds the highlight, which is precisely the failure
        // this whole ticket is about. So the test passed both when the submenu kept the four
        // presses and when the level change had lost the highlight altogether. Found by
        // `/code-review` on build ticket 47.
        assertEquals(
            "nothing holds the highlight after the level change and four `Down` presses",
            1,
            holders.size,
        )
        assertTrue(
            "a key walked out of the submenu and reached the grid behind the scrim: $holders",
            holders.all { it == null },
        )
    }

    // ------------------------------------------------- the Period jump list's own ladder

    /**
     * `MENU` on a card opens the Period jump list and a row of it takes the highlight.
     *
     * A Period is one calendar block of a Source. The panel draws at most
     * `PERIOD_PANEL_MAX_ROWS` of them and scrolls for the rest, so a row can be one the
     * `LazyColumn` has not composed, which is the case this ladder was written for.
     */
    @Test
    fun openingThePeriodJumpListTakesTheHighlightOffTheGrid() {
        openTheSource()
        awaitFocusOn(openedOnCard)

        press(openedOnCard, AndroidKeyEvent.KEYCODE_MENU)
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()

        awaitOneRowOfThePeriodJumpListHoldsTheHighlight()
    }

    // ------------------------------------------------------- the home screen's two ladders

    /**
     * The first-run page lands the highlight on its one line.
     *
     * `Add a Source` is the only focusable node on that whole screen, and the home screen is
     * the screen `BACK` has nowhere to go from, so a ladder that stopped early would leave a
     * household with a page they can read and no key they can press. Nothing recovers that but
     * the `HOME` button. It is why build ticket 47 changed this ladder rather than leaving it.
     */
    @Test
    fun theFirstRunPageLandsTheHighlightOnItsOneLine() {
        compose.setContent { HomeScreen(state = HomeScreenState.Ready(emptyList())) }
        compose.onNodeWithText(FIRST_RUN_TITLE).assertExists()

        awaitAnUntaggedNodeHoldsTheHighlight()

        assertEquals(1, holdersOfTheHighlight().size)
    }

    /**
     * A home panel takes the highlight off the rail behind its scrim.
     *
     * That panel has no key guard of its own, unlike the Source screen's two, so a ladder that
     * stopped early would leave the highlight on a rail card behind the scrim. A covered rail
     * card swallows every key but `BACK`, so the household would see a menu that answers
     * nothing. It is why build ticket 47 changed this ladder rather than leaving it.
     */
    @Test
    fun openingAHomePanelTakesTheHighlightOffTheRail() {
        openTheRail()
        awaitFocusOn(TestTags.railCard(menuCard.sourceId))

        hold(TestTags.railCard(menuCard.sourceId), AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        // A line of the panel, and not the panel's title. The title is the Source's own name,
        // which is also written on the rail card behind the scrim, so `onNodeWithText` finds
        // two nodes and neither of them says which is which.
        compose.onNodeWithText(REMOVE_THIS_SOURCE).assertExists()

        awaitAnUntaggedNodeHoldsTheHighlight()
        assertNull(focusedTag())
    }
}

/** Long enough for any of the ladders, each of which gives up at about 484 ms. */
private const val LADDER_TIMEOUT_MS = 5_000L
