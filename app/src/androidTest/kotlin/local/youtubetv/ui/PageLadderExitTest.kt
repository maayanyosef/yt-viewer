package local.youtubetv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.youtubetv.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The add screen's four retry ladders and the rename screen's one still land the highlight.
 *
 * **Build ticket 48.** All five used to exit on `runCatching { requestFocus() }.isSuccess`,
 * which reports only that the call did not throw. They now exit on a `HighlightRecord`, which
 * only the nodes themselves write. That is a change to when every one of those ladders stops,
 * so this file is the proof that each one still stops in the right place on a real screen.
 * `LadderExitTest` is the same proof for the six ladders build ticket 47 changed.
 *
 * **What this file cannot prove, and no device test can.** The case the ladders exist for is a
 * node that is attached, is asked for the highlight, throws nothing and quietly does not take
 * it. Build tickets 41 and 47 both tried to produce that on the `googletv1080p` emulator and
 * the ask landed every time. The JVM tests in
 * `app/src/test/kotlin/local/youtubetv/ui/HighlightRecordTest.kt` are what stands for that case,
 * because they hand each ladder a `holds` that never becomes true. This file is the other half:
 * the wiring, on a real screen, which no JVM test can describe.
 *
 * **The genuinely new thing in build ticket 48 is the text field**, and
 * [aBasicTextFieldReportsThroughTheCallersOwnOnFocusChanged] is the measurement of it rather
 * than an assumption about it. Both screens put the highlight on a `BasicTextField`, and
 * nothing in this app had asked a text field to report whether it holds the highlight before.
 *
 * It runs on the `googletv1080p` emulator and never on the household television. See
 * `docs/agents/testing.md`.
 */
@RunWith(AndroidJUnit4::class)
class PageLadderExitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Two rows of results, so "the first row" is a choice the screen could get wrong. */
    private val rows = listOf(
        testCandidate(playlistId = "PLfirst", title = "The first thing it found"),
        testCandidate(playlistId = "PLsecond", title = "The second thing it found"),
    )

    /** The Source the household already has, named the way their own Sources are named. */
    private val existingName = "ערוץ לדוגמה - כל הסרטונים"

    // ------------------------------------------------------------------------ the plumbing

    private fun awaitFocusOn(tag: String) {
        compose.waitUntil(LADDER_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Exactly one node holds the highlight and it is the one tagged [tag].
     *
     * The count is asserted as well as the tag, because `onAllNodes(...).isNotEmpty()` on its own
     * cannot fail when **nothing** holds the highlight, which is the failure this whole ticket is
     * about. `/code-review` found that hole in build ticket 47's own tests.
     */
    private fun assertOnlyHolderIs(tag: String) {
        val holders = holdersOfTheHighlight()
        assertEquals("what holds the highlight: $holders", 1, holders.size)
        assertEquals(tag, holders.single())
    }

    /**
     * One `Down`, both halves of it, sent through the root the focused node belongs to.
     *
     * **The node picks the root and not the receiver.** `performKeyPress` dispatches at the view
     * root and Android routes the event to whatever holds the highlight, which is the same shape
     * `LadderExitTest` uses.
     */
    private fun pressDownOnWhateverHoldsTheHighlight() {
        val at = android.os.SystemClock.uptimeMillis()
        compose.onNode(isFocused()).performKeyPress(
            KeyEvent(
                AndroidKeyEvent(at, at, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN, 0),
            ),
        )
        compose.onNode(isFocused()).performKeyPress(
            KeyEvent(
                AndroidKeyEvent(at, at, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, 0),
            ),
        )
    }

    /** One entry per node reporting that it holds the highlight, each its tag or null. */
    private fun holdersOfTheHighlight(): List<String?> =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.TestTag) }

    /**
     * Waits until the one node holding the highlight is a text field.
     *
     * A `BasicTextField` carries no test tag of its own on either of these screens, and it does
     * not need one: it is the only node on either screen that Compose gives `EditableText`, so
     * that property says which node it is better than a tag would.
     */
    private fun awaitFocusOnTheTextField() {
        compose.waitUntil(LADDER_TIMEOUT_MS) { focusedNodeIsATextField() }
    }

    private fun focusedNodeIsATextField(): Boolean {
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        return focused.size == 1 &&
            focused.single().config.getOrNull(SemanticsProperties.EditableText) != null
    }

    // ------------------------------------------------- what a text field really reports

    /**
     * **The one genuinely new question build ticket 48 had to answer**, measured rather than
     * assumed: a `BasicTextField` reports through the caller's own `Modifier.onFocusChanged`
     * exactly as a `focusable()` box does.
     *
     * The doubt was real. `BasicTextField` builds its own focusable node **inside**
     * `CoreTextField`, below whatever modifier the caller hands it, and `FocusState.isFocused`
     * is false on an ancestor of the node that holds the highlight while `hasFocus` is true. Had
     * that been the shape here, a record written from `isFocused` would never have been written
     * at all and every ladder on these two screens would have spent all four rungs and given up.
     *
     * What really happens, on `googletv1080p`, is three deliveries and no ambiguity:
     *
     * 1. one at composition, `isFocused=false hasFocus=false`, before anything has asked;
     * 2. the gain, `isFocused=true hasFocus=true`;
     * 3. the loss, `isFocused=false hasFocus=false`.
     *
     * The first of those is why every node in this app reports only a **change**: a record
     * written from the composition delivery would report a loss the field never had.
     */
    @Test
    fun aBasicTextFieldReportsThroughTheCallersOwnOnFocusChanged() {
        val reports = mutableListOf<String>()
        val fieldRequester = FocusRequester()
        val boxRequester = FocusRequester()

        compose.setContent {
            Column {
                BasicTextField(
                    value = "",
                    onValueChange = {},
                    singleLine = true,
                    modifier = Modifier
                        .focusRequester(fieldRequester)
                        .onFocusChanged {
                            reports += "isFocused=${it.isFocused} hasFocus=${it.hasFocus}"
                        },
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .focusRequester(boxRequester)
                        .focusable(),
                )
            }
        }

        compose.waitForIdle()
        assertEquals(listOf("isFocused=false hasFocus=false"), reports.toList())

        compose.runOnIdle { fieldRequester.requestFocus() }
        compose.waitForIdle()
        assertEquals(
            listOf("isFocused=false hasFocus=false", "isFocused=true hasFocus=true"),
            reports.toList(),
        )

        compose.runOnIdle { boxRequester.requestFocus() }
        compose.waitForIdle()
        assertEquals(
            listOf(
                "isFocused=false hasFocus=false",
                "isFocused=true hasFocus=true",
                "isFocused=false hasFocus=false",
            ),
            reports.toList(),
        )
    }

    // ------------------------------------------------------- the add screen's four ladders

    /**
     * The add screen opens with the highlight on its text field.
     *
     * That is what brings the television's own keyboard up with its microphone button, and the
     * keyboard's action key submits, so the common path never touches the `Find it` button at
     * all. A ladder that stopped early left a field with no ring, no cursor and no keyboard.
     */
    @Test
    fun theAddScreenLandsTheHighlightOnItsTextField() {
        compose.setContent { AddSourceScreen(state = AddSourceState.Typing()) }

        awaitFocusOnTheTextField()

        assertEquals(1, holdersOfTheHighlight().size)
    }

    /**
     * The results land the highlight on the first row and not on the second.
     *
     * Only the first row is given a requester, so a screen that highlighted the second would be
     * Compose's own focus search choosing rather than this ladder landing.
     */
    @Test
    fun theResultsLandTheHighlightOnTheFirstRow() {
        compose.setContent {
            AddSourceScreen(
                state = AddSourceState.Choosing(title = ADD_PICK_ONE, rows = rows),
            )
        }

        val firstRow = TestTags.candidateRow(candidateKey(rows.first()))
        awaitFocusOn(firstRow)

        assertOnlyHolderIs(firstRow)
    }

    /**
     * The last step lands the highlight on `Add` and not on a Direction chip.
     *
     * The chips are drawn above the button and are focusable, so a ladder that landed nothing
     * would leave Compose's own focus search to pick, and the chips are what it would reach
     * first.
     */
    @Test
    fun theConfirmStepLandsTheHighlightOnTheAddButton() {
        compose.setContent {
            AddSourceScreen(
                state = AddSourceState.Confirming(
                    candidate = rows.first(),
                    direction = Direction.NEWEST_FIRST,
                ),
            )
        }

        val addButton = TestTags.pageButton(ADD_CONFIRM)
        awaitFocusOn(addButton)

        assertOnlyHolderIs(addButton)
    }

    /**
     * The refusal page lands the highlight on the offer to open the Source already there.
     *
     * It is the first of two buttons, and it is the one the household nearly always wants: a
     * household that pasted a link it added a month ago is trying to reach that Source.
     */
    @Test
    fun theAlreadyAddedPageLandsTheHighlightOnTheOfferToOpenIt() {
        compose.setContent {
            AddSourceScreen(
                state = AddSourceState.AlreadyAdded(sourceId = "UUx", name = existingName),
            )
        }

        val openButton = TestTags.pageButton(openExisting(existingName))
        awaitFocusOn(openButton)

        assertOnlyHolderIs(openButton)
    }

    // ----------------------------------------------------- the rename screen's one ladder

    /**
     * The rename screen opens with the highlight on its text field.
     *
     * **This is the most exposed of build ticket 48's five ladders.** A `BasicTextField` eats
     * `Down`, so the two buttons below are reached only through the field's own
     * `onPreviewKeyEvent`, and that handler runs only while the field holds the highlight. With
     * no highlight on the field, `BACK` is the only key on the screen that does anything.
     */
    @Test
    fun theRenameScreenLandsTheHighlightOnItsTextField() {
        compose.setContent {
            RenameSourceScreen(
                state = RenameSourceState(
                    sourceId = "UUx",
                    typed = existingName,
                    fetchedName = existingName,
                    storedTypedName = null,
                ),
            )
        }

        awaitFocusOnTheTextField()

        assertEquals(1, holdersOfTheHighlight().size)
    }

    /**
     * `Down` out of the rename field reaches `Save this name`, and the record follows it.
     *
     * This is the walk that proves the field really reports its **loss** and the button really
     * reports its gain, which a landing test on its own cannot show. It is also the one key on
     * this screen that Compose's focus search never sees: the field's `onPreviewKeyEvent` takes
     * `Down` before Compose turns it into a cursor command and swallows it.
     */
    @Test
    fun downOutOfTheRenameFieldMovesTheHighlightToSaveThisName() {
        compose.setContent {
            RenameSourceScreen(
                state = RenameSourceState(
                    sourceId = "UUx",
                    typed = "",
                    fetchedName = existingName,
                    storedTypedName = null,
                ),
            )
        }
        awaitFocusOnTheTextField()

        pressDownOnWhateverHoldsTheHighlight()

        val saveButton = TestTags.pageButton(RENAME_SAVE)
        awaitFocusOn(saveButton)
        assertOnlyHolderIs(saveButton)
    }
}

/** Long enough for any of the five ladders, each of which gives up at about 484 ms. */
private const val LADDER_TIMEOUT_MS = 5_000L
