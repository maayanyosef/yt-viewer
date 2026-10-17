package local.youtubetv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import local.youtubetv.data.Direction
import local.youtubetv.data.Landing
import local.youtubetv.data.StoredVideo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Period jump list row destroyed by a refresh, and what the panel does about it.
 *
 * **Three tickets are pinned here.** Build ticket 49 asked that the Source screen's key guard be
 * shut in that moment, which it is. Build ticket 51 asked that the panel not be left holding
 * nothing, so a row that is still there is asked for the highlight back. Build ticket 52 asked
 * what happens when the refresh leaves no row at all, and the answer is that the panel closes.
 *
 * **Build ticket 52.** A refresh can empty a Source outright, because its videos were made
 * private, deleted, or taken out of the playlist. The panel then lists no Period, so no row can
 * take the highlight and build ticket 51's recovery correctly asks for nothing. `openPeriods` in
 * `ui/SourceScreen.kt` refuses to **open** the panel for a Source with no Period and nothing
 * closed an open one, so the household was left looking at an empty box answering nothing but
 * `BACK`. `periodPanelCloseAfterRefresh` in `ui/PeriodPanel.kt` is the rule that closes it and
 * is unit-tested on the JVM; `closePeriods` in `ui/SourceScreen.kt` carries it out.
 *
 * **Build ticket 51.** The rows of this panel are keyed by their place in it, so a refresh that
 * empties the last Period bucket destroys the last row. Compose then takes the highlight out of
 * the panel, and until build ticket 51 nothing asked for it back: the household was left looking
 * at an open panel that answered nothing but `BACK`. `periodRowAfterRefresh` in
 * `ui/PeriodPanel.kt` is the rule that says which row to ask for, and it is unit-tested on the
 * JVM in `app/src/test/kotlin/local/youtubetv/ui/PeriodPanelTest.kt`. The effect that carries it
 * out is in `PeriodPanelOverlay` in `ui/SourceScreen.kt`, below that panel's own `move`.
 *
 * **Build ticket 49.** The Source screen keeps every key but `BACK` while no row of an open
 * panel holds the highlight, and since build ticket 47 it decides that from a `HighlightRecord`
 * the rows themselves write. The worry build ticket 49 was raised on is that a row leaving
 * composition **while holding the highlight** would run no `onFocusChanged`, so it would never
 * report the loss: the record would go on naming it, `holdsAnything` would read true, and the
 * guard would read as open while Compose had already handed the highlight to something behind
 * the dimmed panel. The next `Down` would then move something the household cannot see, and the
 * centre button would act on it.
 *
 * **That premise turned out to be false on the Compose version this app is built against, and
 * it was measured rather than assumed.** A throwaway probe on the `googletv1080p` emulator on
 * 2026-09-21 removed the focused item from a `LazyColumn` and from a plain `Column`, and both
 * delivered the loss to the node being removed before disposing it: `focus 2 = false`, then the
 * gain on whatever Compose moved the highlight to, then `dispose 2`. So `highlightHolder`
 * already empties the record on its own today, and both tests here pass on a build with no
 * `HighlightRecord.reportDestroyed` at all. What the record's own disposal report buys is that
 * the key guard no longer rests on that Compose behaviour staying as it is, and what this file
 * pins is the household-visible property either way.
 *
 * **This is composition identity and key handling, which is two of the four things
 * `app/src/androidTest/` exists for.** `docs/agents/testing.md` has the list. The rule itself is
 * `highlightAfterDestruction` in `ui/HighlightRecord.kt` and is unit-tested on the JVM in
 * `app/src/test/kotlin/local/youtubetv/ui/HighlightRecordTest.kt`.
 *
 * **The Period jump list's rows are keyed by their place in the panel, on purpose.**
 * `PeriodPanelOverlay` carries build ticket 33's reason. Keying by place survives a
 * renumbering, which is what that ticket needed, and does not survive the list getting shorter,
 * which is the case here.
 */
@RunWith(AndroidJUnit4::class)
class PanelRowDestroyedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Twelve videos across three calendar months, newest first, four in each month.
     *
     * Three distinct months in one calendar year is what `periodUnit` answers `MONTH` for, so
     * the panel lists three Periods: March 2026, February 2026 and January 2026, in that order,
     * because the Source's Direction is newest first. Every date is mid-month, so the
     * emulator's own time zone cannot move a video into a neighbouring Period.
     */
    private val threeMonths = sourceScreenOf(
        monthVideos(days = listOf(75, 72, 69, 66), from = 0) +
            monthVideos(days = listOf(50, 47, 44, 41), from = 4) +
            monthVideos(days = listOf(20, 17, 14, 11), from = 8),
    )

    /**
     * The same Source after a refresh in which every January video has gone.
     *
     * A video can leave a Source's listed videos: it is made private, it is deleted, or the
     * owner takes it out of the playlist. That empties the last Period bucket, the panel lists
     * two Periods instead of three, and the row at place 2 leaves composition.
     */
    private val twoMonths = sourceScreenOf(threeMonths.videos.take(8))

    /**
     * And a refresh that leaves all three Periods standing, with one more video in the newest.
     *
     * Nothing is destroyed here, so the panel must go on answering keys exactly as it did
     * before. That is the trap build ticket 49 had to walk around: a row's `DisposableEffect`
     * now clears the panel's highlight record, so an effect that restarted on an ordinary
     * recomposition would empty the record under a row that is still on the screen, and the key
     * guard would shut on a panel that is perfectly fine for the 484 ms its retry ladder takes
     * to give up. `PeriodLine` keys that effect on the row's own place for exactly this reason.
     *
     * **This test does not prove that keying, and the comment there says why.** Keyed on the
     * `register` lambda instead, both cases in this file still pass, measured on the
     * `googletv1080p` emulator on 2026-09-21: the Compose compiler's strong skipping memoises
     * that lambda, so the effect does not restart. What this test does pin is the panel still
     * answering `Up` after a refresh, whichever way that comes about.
     */
    private val threeMonthsPlusOne = sourceScreenOf(
        monthVideos(days = listOf(78), from = 12) + threeMonths.videos,
    )

    /**
     * And a refresh in which every video of the Source has gone.
     *
     * The panel then lists no Period at all, so there is no row to ask for the highlight and
     * `periodRowAfterRefresh` answers null. Build ticket 51's recovery does nothing here and was
     * right not to try: putting the highlight back needs a row and there is none.
     *
     * **Build ticket 52 answers it by closing the panel**, which is `periodPanelCloseAfterRefresh`
     * in `ui/PeriodPanel.kt` and `closePeriods` in `ui/SourceScreen.kt`. Until it, the household
     * was left looking at the panel's title over an empty box that answered nothing but `BACK`.
     */
    private val noVideos = sourceScreenOf(emptyList())

    private val state = mutableStateOf<SourceScreenState>(threeMonths)

    /** The `Order` chip above the grid, which is drawn whether or not the Source lists videos. */
    private val orderChip = TestTags.chip(orderChipLabel(Direction.NEWEST_FIRST))

    /**
     * The `Period` chip above the grid, which is the chip that opens the Period jump list.
     *
     * It is where build ticket 52's close puts the highlight when the Source is left listing no
     * video, because the grid is replaced by a message then and holds nothing focusable at all.
     * It is drawn on every Source and it is never dim.
     */
    private val periodChip = TestTags.chip(PERIOD_CHIP)

    /** How many times the `Order` chip behind the panel has been pressed. It must stay at 0. */
    private var flips = 0

    /**
     * Opens the Source on its first card, then opens the Period jump list with `MENU`.
     *
     * Nothing is watched, so Next unwatched is the newest video, the Source opens on card 0,
     * and the panel opens on the Period holding it, which is row 0.
     */
    private fun openThePeriodJumpList() {
        compose.setContent {
            SourceScreen(
                sourceId = "UUx",
                sourceName = "A Source",
                state = state.value,
                thumbnails = NoThumbnails,
                onFlipDirection = { flips++ },
                openAt = Landing.NEXT_UNWATCHED,
            )
        }
        awaitFocusOn(TestTags.videoCard(threeMonths.videos[0].videoId))

        press(AndroidKeyEvent.KEYCODE_MENU)
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()
        awaitFocusOn(TestTags.periodRow(0))
    }

    /** Walks down to the last of the three rows, which is the January 2026 Period. */
    private fun walkToTheLastRow() {
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag(TestTags.periodRow(2)).assertIsFocused()
    }

    /**
     * The whole household-visible case, in one run.
     *
     * The household walks down to the last row of the Period jump list, a refresh empties that
     * Period, and the row holding the highlight is destroyed. A row that is still there takes
     * the highlight back, and no key reaches anything behind the dimmed panel.
     *
     * **Build ticket 49 wrote this case the other way round, and build ticket 51 rewrote it.**
     * It used to read the highlight after the refresh, press `Down` and the centre button, and
     * assert that the highlight had not moved at all. That was the right assertion for the code
     * build ticket 49 left: nothing asked a surviving row for the highlight, so the household
     * was stranded on an open panel answering nothing but `BACK`. Build ticket 51 is the repair,
     * so the assertion is now that the highlight **does** move, back onto a row of the panel.
     * What build ticket 49 really guarded is kept: the set of nodes holding the highlight is
     * asserted to be exactly one row of the panel, before and after a `Down`, which rules out
     * the `Order` chip and every video card behind the dimmed panel by naming what is there
     * rather than only what is not.
     *
     * **The centre button's half of build ticket 49's case is not here, and [flips] cannot fail
     * in this test.** Found by `/code-review` on build ticket 51: this case presses `MENU` and
     * three `Down`s and nothing else, and only the `Order` chip's select action writes [flips],
     * so the two counts below are a belt and not the brace. The brace is
     * `SourceKeyGuardTest`, where the highlight really is behind an overlay when the centre
     * button is pressed. Build ticket 51 put it in this file instead, in a case build ticket 52
     * had to rewrite; that ticket's closing notes say why it had to move out.
     *
     * **Compose hands the highlight to the `Order` chip in the moment between the destruction
     * and the recovery**, which the run of 2026-09-21 measured. That is not a card in the video
     * grid, which is what build ticket 49 expected, and it is the same chip the closing panels
     * of build tickets 19, 33 and 37 kept losing the highlight to. It is not pinned here: the
     * recovery closes that moment too quickly to press a key inside it, and pinning Compose's
     * choice would turn a change in it into a red test reading like a real defect.
     */
    @Test
    fun aRowOfThePanelTakesTheHighlightBackAfterTheHighlightedRowIsDestroyed() {
        openThePeriodJumpList()
        walkToTheLastRow()

        // The refresh. Every January video is gone, so row 2 leaves composition while it holds
        // the highlight.
        compose.runOnUiThread { state.value = twoMonths }
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.periodRow(2)).assertDoesNotExist()

        // Row 1 is the last row the shorter list still has. `periodRowAfterRefresh` in
        // `ui/PeriodPanel.kt` is the rule that says so, and the wait is long enough for the
        // panel's own retry ladder, which gives up at about 484 ms.
        awaitFocusOn(TestTags.periodRow(1))
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()

        // And that row is the **only** thing holding the highlight, so neither the `Order` chip
        // nor a video card behind the dimmed panel is left holding it.
        assertEquals(
            "something other than a row of the panel holds the highlight",
            listOf(TestTags.periodRow(1)),
            focusedTags(),
        )
        assertEquals("the Order chip behind the panel was acted on", 0, flips)

        // `Down` from the last row of the panel moves nothing. The panel keeps the key rather
        // than letting Compose's focus search find a video card behind the scrim.
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitForIdle()
        assertEquals(
            "a key moved the highlight behind the panel",
            listOf(TestTags.periodRow(1)),
            focusedTags(),
        )
        assertEquals("the Order chip behind the panel was acted on", 0, flips)
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertExists()
    }

    /**
     * A refresh that leaves the panel no row at all closes it, onto the `Period` chip.
     *
     * **Build ticket 52, and this case is build ticket 51's fourth one rewritten.** That case
     * was `noKeyReachesTheOrderChipWhenARefreshLeavesThePanelNoRowAtAll`, and it asserted the
     * panel was **still open** after this very refresh, which is now the defect rather than the
     * behaviour. Its own subject, the Source screen's outer key guard, moved into
     * `SourceKeyGuardTest`, because closing the panel leaves that guard no state here to press a
     * key against: measured on `emulator-5554` frame by frame, exactly one frame draws the panel
     * after this refresh and the highlight is already on the `Period` chip in it, with
     * `periodsOpen` already false. That file's own KDoc carries the measurement.
     *
     * **Why the `Period` chip and not a card.** Every video of the Source has gone, so the grid
     * is replaced by `This Source lists no videos.`, which holds nothing focusable. The card the
     * panel was opened about is gone with the rest. `periodPanelClosesOnto` in
     * `ui/PeriodPanel.kt` answers `NO_SELECTION` for that, which `panelHandBack` turns into the
     * `Period` chip: the chip that opens this panel, drawn on every Source and never dim.
     *
     * **The highlight is asserted to be on that chip and on nothing else**, so a close that left
     * it on the `Order` chip, on a card, or nowhere at all fails here.
     */
    @Test
    fun aRefreshThatLeavesThePanelNoRowAtAllClosesItOntoThePeriodChip() {
        openThePeriodJumpList()
        walkToTheLastRow()

        // The refresh. Every video of the Source is gone, so the panel lists no Period.
        compose.runOnUiThread { state.value = noVideos }
        compose.waitForIdle()

        // The wait is long enough for the `Period` chip's own retry ladder, which gives up at
        // about 484 ms.
        awaitFocusOn(periodChip)
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertDoesNotExist()
        compose.onNodeWithText(NO_VIDEOS_MESSAGE).assertExists()
        assertEquals(
            "something other than the Period chip holds the highlight",
            listOf(periodChip),
            focusedTags(),
        )
        assertEquals("the Order chip behind the panel was acted on", 0, flips)
    }

    /**
     * A refresh that destroys no row leaves the panel answering keys exactly as before.
     *
     * One video is added to the newest Period, so all three rows stay and every one of them is
     * recomposed with a new count. The household is standing on row 2 and presses `Up`: the
     * panel must move them to row 1.
     */
    @Test
    fun aRefreshThatDestroysNoRowLeavesThePanelWorking() {
        openThePeriodJumpList()
        walkToTheLastRow()

        compose.runOnUiThread { state.value = threeMonthsPlusOne }
        compose.waitForIdle()

        compose.onNodeWithTag(TestTags.periodRow(2)).assertIsFocused()

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        awaitFocusOn(TestTags.periodRow(1))
    }

    /**
     * The panel answers `Up`, `Down` and the centre button again after the refresh.
     *
     * **Build ticket 51's first acceptance criterion, and the household's own complaint.** The
     * Source screen's outer `Box` keeps every key but `BACK` while no row of an open panel holds
     * the highlight, so a panel left holding nothing is a panel with one door out of it. Getting
     * a row to hold the highlight again is what opens that guard, and these three presses are
     * what says the whole path is live rather than only the record being right.
     *
     * The shorter list is March 2026 at row 0 and February 2026 at row 1. February's first video
     * is `v4`, so the centre button on row 1 closes the panel and lands the grid on that card.
     * Spec section 7.4.
     */
    @Test
    fun thePanelAnswersKeysAgainAfterTheHighlightedRowIsDestroyed() {
        openThePeriodJumpList()
        walkToTheLastRow()

        compose.runOnUiThread { state.value = twoMonths }
        compose.waitForIdle()
        awaitFocusOn(TestTags.periodRow(1))

        press(AndroidKeyEvent.KEYCODE_DPAD_UP)
        awaitFocusOn(TestTags.periodRow(0))
        press(AndroidKeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocusOn(TestTags.periodRow(1))

        press(AndroidKeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        compose.onNodeWithText(PERIOD_PANEL_TITLE).assertDoesNotExist()
        awaitFocusOn(TestTags.videoCard("v4"))
        assertEquals("the Order chip behind the panel was acted on", 0, flips)
    }

    /** The test tag of every node reporting that it holds the highlight, in order. */
    private fun focusedTags(): List<String> = compose
        .onAllNodes(isFocused())
        .fetchSemanticsNodes()
        .map { it.config.getOrNull(SemanticsProperties.TestTag) ?: "untagged:${it.id}" }

    /** Waits for the tagged node to hold the highlight, and fails the test when it never does. */
    private fun awaitFocusOn(tag: String) {
        compose.waitUntil(FOCUS_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * One press and release of a key, sent through the view root.
     *
     * `performKeyPress` dispatches at the **view root** and Android then routes the event to
     * whatever holds the highlight, so naming a node delivers nothing to that node. The node
     * named here is the grid's first video card, which is drawn in every state this test uses,
     * and it is named only to pick the root. `OverlayFocusTest` says the same thing at more
     * length.
     */
    private fun press(keyCode: Int) =
        pressFrom(TestTags.videoCard(threeMonths.videos[0].videoId), keyCode)

    /**
     * The same press, through a different node's root.
     *
     * A Source that lists no videos draws a message where the grid was, so the card [press]
     * names is not on the screen in every state this file uses. The `Order` chip is.
     */
    private fun pressFrom(tag: String, keyCode: Int) {
        val root = compose.onNodeWithTag(tag)
        root.performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, keyCode)))
        root.performKeyPress(KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_UP, keyCode)))
    }
}

/** Long enough for the panel's own retry ladder, which gives up at about 484 ms. */
private const val FOCUS_TIMEOUT_MS = 5_000L

/**
 * Videos in one calendar month of 2026, newest first, one per day in [days].
 *
 * [from] keeps every video id distinct across the months, so the grid's own keying by video id
 * is never the thing under test here.
 */
private fun monthVideos(days: List<Int>, from: Int): List<StoredVideo> =
    days.mapIndexed { offset, day ->
        testVideo(
            videoId = "v${from + offset}",
            day = day,
            title = "Video number ${from + offset}",
        )
    }

/** A ready Source screen over [videos], newest first, with nothing watched. */
private fun sourceScreenOf(videos: List<StoredVideo>): SourceScreenState.Ready =
    SourceScreenState.Ready(
        videos = videos,
        updatedAt = Instant.parse("2026-04-01T00:00:00Z"),
        fromStorage = true,
        watches = emptyMap(),
        direction = Direction.NEWEST_FIRST,
    )
