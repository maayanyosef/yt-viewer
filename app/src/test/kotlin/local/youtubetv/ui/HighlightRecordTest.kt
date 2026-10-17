package local.youtubetv.ui

import kotlinx.coroutines.runBlocking
import local.youtubetv.youtube.CandidateKind
import local.youtubetv.youtube.SourceCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record of what really holds the highlight, and the six retry ladders that read it.
 *
 * **Build ticket 47.** Six ladders across the two screens exited on
 * `runCatching { requestFocus() }.isSuccess`, which says only that the call did not throw. A
 * `requestFocus` on a node that is attached and quietly refuses the highlight throws nothing,
 * so the first rung read success, the ladder returned, and the 60, 120 and 240 ms rungs never
 * ran. The one case a retry ladder exists for was the one case it could not detect. Build
 * ticket 41 fixed the home rail first, and `RailHighlightLadderTest` covers that one.
 *
 * **Every test here that matters is a node that refuses the highlight and says nothing about
 * it, and that case cannot be produced on a device.** Build ticket 41 tried three ways on the
 * `googletv1080p` emulator, including a rail of twelve Sources with the target card far outside
 * what the lazy row held, and a paused test clock stepped frame by frame. The ask landed every
 * time. So these tests are what stands for the day a node really does refuse, and no
 * instrumentation test can stand for it. The instrumentation test in
 * `app/src/androidTest/kotlin/local/youtubetv/ui/LadderExitTest.kt` covers the other half: that
 * the record really is written by the nodes, on a real screen, on every door this app has.
 *
 * The six ladders are modelled here one test each, because each one passes a different `ask`
 * and a different `holds`, and what this file proves is that the pair works. The wiring itself,
 * which composable holds which record, is what the instrumentation test is for.
 */
class HighlightRecordTest {

    // ------------------------------------------------- which node holds the highlight

    @Test
    fun `a node taking the highlight becomes the holder`() {
        assertEquals(2, highlightHolder(held = null, reported = 2, holdsIt = true))
    }

    @Test
    fun `a node taking the highlight from another node replaces it`() {
        assertEquals(5, highlightHolder(held = 2, reported = 5, holdsIt = true))
    }

    /** Opening the card menu: the grid card loses the highlight to the menu's first line. */
    @Test
    fun `the holder losing the highlight leaves no holder at all`() {
        assertNull(highlightHolder(held = 2, reported = 2, holdsIt = false))
    }

    /**
     * Compose may report the gain before the loss when the highlight walks from one card to the
     * next. A loss applied without asking which node it is about would throw away the gain that
     * had just arrived, and the grid would read as holding the highlight nowhere.
     */
    @Test
    fun `a node that was not the holder losing the highlight changes nothing`() {
        assertEquals(5, highlightHolder(held = 5, reported = 2, holdsIt = false))
    }

    @Test
    fun `a loss reported while no node holds it leaves no holder`() {
        assertNull(highlightHolder(held = null, reported = 2, holdsIt = false))
    }

    /** The `Period` chip names its node by the words on it, not by a number. */
    @Test
    fun `the rule reads the same for a node named by its words`() {
        assertEquals(PERIOD_CHIP, highlightHolder(held = null, reported = PERIOD_CHIP, holdsIt = true))
        assertNull(highlightHolder(held = PERIOD_CHIP, reported = PERIOD_CHIP, holdsIt = false))
    }

    /**
     * `railHighlightHolder` in `ui/HomeModel.kt` is the home rail's reading of the same rule.
     * Build ticket 47 made it delegate rather than keep a second copy, so this is the test that
     * the two have not drifted apart.
     */
    @Test
    fun `the home rail's rule is this rule`() {
        val curated = "PLmadeUpPlaylistForTestsZ000000001"
        val ted = "UUAuUUnT6oDeKwE6v1NGQxug"
        assertEquals(
            highlightHolder(held = ted, reported = curated, holdsIt = false),
            railHighlightHolder(held = ted, reported = curated, holdsIt = false),
        )
        assertEquals(
            highlightHolder(held = ted, reported = curated, holdsIt = true),
            railHighlightHolder(held = ted, reported = curated, holdsIt = true),
        )
        assertEquals(
            highlightHolder(held = ted, reported = ted, holdsIt = false),
            railHighlightHolder(held = ted, reported = ted, holdsIt = false),
        )
    }

    // ------------------------------------------------------------------- the record itself

    @Test
    fun `a new record holds nothing`() {
        val record = HighlightRecord<Int>()
        assertNull(record.holder)
        assertFalse(record.holdsAnything)
        assertFalse(record.holds(0))
    }

    @Test
    fun `a record names the node that reported the gain`() {
        val record = HighlightRecord<Int>()
        record.report(3, holdsIt = true)
        assertTrue(record.holds(3))
        assertFalse(record.holds(4))
        assertTrue(record.holdsAnything)
    }

    /** The walk from one grid card to the next, with Compose reporting the gain first. */
    @Test
    fun `a record follows the highlight along a walk`() {
        val record = HighlightRecord<Int>()
        record.report(3, holdsIt = true)
        record.report(4, holdsIt = true)
        record.report(3, holdsIt = false)
        assertTrue(record.holds(4))
    }

    /** The card menu opening: the card that held the highlight reports the loss. */
    @Test
    fun `a record holds nothing once its own node reports the loss`() {
        val record = HighlightRecord<Int>()
        record.report(3, holdsIt = true)
        record.report(3, holdsIt = false)
        assertNull(record.holder)
        assertFalse(record.holdsAnything)
    }

    // ------------------------------------------ a refresh renumbers the Source screen's grid

    /**
     * **Why the grid's record names the video and not the card's place in the grid.**
     *
     * A refresh that lists three new videos moves the card the household is standing on down
     * three places. Compose reports no focus change at all, because that node is reused and only
     * the number it is drawn at changed, so nothing corrects a record that names places. Then
     * the card's next real loss, when a panel takes the highlight, arrives carrying its **new**
     * place, and [highlightHolder] rightly refuses to apply a loss to a record naming a
     * different node. The record is left naming a place nothing holds, and it can never go empty
     * again by itself.
     *
     * This is that sequence. `/code-review` found it on build ticket 47, whose first version of
     * the grid's record named places.
     */
    @Test
    fun `a record named by a place is left naming a place nothing holds after a refresh`() {
        val byPlace = HighlightRecord<Int>()
        byPlace.report(9, holdsIt = true)
        // The refresh. Nothing is reported at all, because the node is reused. Then the Period
        // jump list opens and the card reports its loss under its new place.
        byPlace.report(12, holdsIt = false)

        assertTrue("the stale place still reads as held", byPlace.holds(9))
        assertTrue(byPlace.holdsAnything)
    }

    /**
     * And what that costs. A ladder asking for the stale place reads its own exit test as
     * already satisfied on the first rung, so it stops after one rung. When the lazy grid holds
     * no card at that place there is nothing to ask either, so the screen is left with no
     * highlight anywhere, which is the exact failure this whole ticket exists to make
     * detectable.
     */
    @Test
    fun `a ladder on a stale place stops after one rung with nothing asked`() {
        val byPlace = HighlightRecord<Int>()
        byPlace.report(9, holdsIt = true)
        byPlace.report(12, holdsIt = false)

        var asks = 0
        // The lazy grid holds no card at place 9 any more, so the ask reaches nothing at all.
        // That is the real call site's `requesters[move.index]?.let { ... }`, with nothing
        // under the key.
        val requesters = mapOf<Int, String>()
        val run = climb(ask = { requesters[9]?.let { asks++ } }, holds = { byPlace.holds(9) })

        assertEquals(0, asks)
        assertEquals(listOf(16L), run.waits)
    }

    /**
     * Naming the video instead answers correctly through the same refresh. A video id is what
     * the lazy grid is keyed by, so the record survives a renumbering exactly as the node
     * holding the highlight does.
     */
    @Test
    fun `a record named by the video goes empty on the loss the same refresh delivers`() {
        val byVideo = HighlightRecord<String>()
        byVideo.report("v9", holdsIt = true)
        // The refresh moved this card from place 9 to place 12. Its id did not move, so the
        // loss the Period jump list causes is reported under the id the record already names.
        byVideo.report("v9", holdsIt = false)

        assertNull(byVideo.holder)
        assertFalse(byVideo.holdsAnything)
    }

    // ------------------------------------------- a node destroyed while it holds the highlight

    @Test
    fun `destroying the node that holds the highlight leaves no holder`() {
        assertNull(highlightAfterDestruction(held = 3, destroyed = 3, replaced = false))
    }

    @Test
    fun `destroying a node that does not hold the highlight changes nothing`() {
        assertEquals(3, highlightAfterDestruction(held = 3, destroyed = 4, replaced = false))
    }

    @Test
    fun `a destruction reported while no node holds the highlight leaves no holder`() {
        assertNull(highlightAfterDestruction(held = null, destroyed = 3, replaced = false))
    }

    /**
     * **The ordering, which is the trap this rule exists to walk around.**
     *
     * A disposal is delivered *after* the node that took the destroyed node's place has already
     * registered, and that replacement may already have taken the highlight and reported it. So
     * a clear that only asked "does the record name this node" would throw away the
     * replacement's own report. The Source screen's key guard would then be shut on a panel that
     * is perfectly fine for the 484 ms its ladder takes to give up, which is a defect of its own.
     */
    @Test
    fun `a destroyed node that has been replaced leaves its successor's record alone`() {
        assertEquals(3, highlightAfterDestruction(held = 3, destroyed = 3, replaced = true))
    }

    @Test
    fun `a record holds nothing once the node holding it is destroyed`() {
        val record = HighlightRecord<Int>()
        record.report(3, holdsIt = true)
        record.reportDestroyed(3, replaced = false)

        assertNull(record.holder)
        assertFalse(record.holdsAnything)
    }

    @Test
    fun `a record keeps its holder when some other node is destroyed`() {
        val record = HighlightRecord<Int>()
        record.report(3, holdsIt = true)
        record.reportDestroyed(4, replaced = false)

        assertTrue(record.holds(3))
    }

    /**
     * The same ordering again, at the record rather than at the rule.
     *
     * The replacement registered under place 3, reported that it holds the highlight, and only
     * then did the node it replaced run its disposal. The panel answers `replaced` from the same
     * identity test that guards its `requesters` removal, `requesters[index] === requester`, so
     * the disposal says so and the record is left naming the replacement.
     */
    @Test
    fun `a replaced node's disposal does not throw away its successor's record`() {
        val record = HighlightRecord<Int>()
        record.report(3, holdsIt = true)
        // The successor at the same place takes the highlight and says so. Nothing changes,
        // because the record names that place either way.
        record.report(3, holdsIt = true)
        // Now the node it replaced is disposed.
        record.reportDestroyed(3, replaced = true)

        assertTrue("the successor still holds the highlight", record.holds(3))
        assertTrue(record.holdsAnything)
    }

    /**
     * **The Period jump list's own case, end to end, and the reason build ticket 49 exists.**
     *
     * The household is standing on the last row of the Period jump list. A background refresh
     * lands, `periodsOf` is derived again, and the Period that row was for now holds no videos
     * at all, so the list is one row shorter. The rows are keyed by their place in the panel, on
     * purpose, so a place past the end of the shorter list is a node that leaves composition.
     *
     * Suppose that node reports no loss on its way out. The record goes on naming it,
     * `holdsAnything` reads true, the screen's outer key guard reads as open, and the next key
     * reaches whatever Compose has handed the highlight to behind the dimmed panel.
     *
     * **Compose does report the loss on the version this app is built against**, measured on the
     * `googletv1080p` emulator on 2026-09-21, so this is the case the record answers for itself
     * rather than one the household can reach today. `highlightAfterDestruction` carries the
     * measurement and why it is still worth answering.
     */
    @Test
    fun `the last row of the Period jump list destroyed by a refresh shuts the key guard`() {
        val withoutTheReport = HighlightRecord<Int>()
        withoutTheReport.report(4, holdsIt = true)
        // The refresh. Row 4 is gone and says nothing about it.
        assertTrue("the guard reads as open", withoutTheReport.holdsAnything)

        val record = HighlightRecord<Int>()
        record.report(4, holdsIt = true)
        record.reportDestroyed(4, replaced = false)

        assertFalse("the guard is shut", record.holdsAnything)
    }

    /**
     * **The card menu's narrower version of the same case.**
     *
     * The top level draws four lines and a submenu draws at most three, so choosing `Mark as
     * unwatched`, which is drawn fourth, destroys the very node holding the highlight. `setMenu`
     * hands the highlight to a grid card first and that produces the loss report, but
     * `panelHandBack` answers null for a card the lazy grid is not holding, and on that path the
     * only report left is the destroyed line's own.
     *
     * The lines are drawn into the positional slots of a `Column`, so a level change only ever
     * removes slots from the end and nothing can have taken a destroyed line's place. That is
     * why the menu answers `replaced = false` without having to test anything.
     */
    @Test
    fun `a card menu line destroyed by a level change shuts the key guard`() {
        val record = HighlightRecord<Int>()
        record.report(3, holdsIt = true)
        record.reportDestroyed(3, replaced = false)

        assertFalse(record.holdsAnything)

        // And the new level's ladder is then free to land on its own first line.
        record.report(0, holdsIt = true)
        assertTrue(record.holds(0))
    }

    /**
     * A level change that keeps the node holding the highlight still ends its ladder at once.
     *
     * Drawn line 1 of a submenu is literally the same node as drawn line 1 of the top level, so
     * that node reports no focus change and the record has to go on naming it. Build ticket 47
     * wrote that; the disposal report must not undo it. Drawn line 3 is the one that goes, and a
     * record naming line 1 is untouched by line 3's disposal.
     */
    @Test
    fun `a level change that destroys another line leaves the reused node's record standing`() {
        val record = HighlightRecord<Int>()
        record.report(1, holdsIt = true)
        record.reportDestroyed(3, replaced = false)

        assertTrue(record.holds(1))
        assertTrue(record.holdsAnything)
    }

    // ------------------------------------------------------------------- the six ladders

    /**
     * One node a ladder can ask for the highlight.
     *
     * [takesIt] is the whole point. A real `FocusRequester.requestFocus` throws nothing either
     * way, so a node that refuses the highlight and a node that takes it are the same call with
     * the same result, and the only difference between them is whether the node then reports
     * that it holds it. That is what this models, and it is the case no device run produced.
     */
    private class Node<T : Any>(
        private val name: T,
        private val record: HighlightRecord<T>,
        private val takesIt: Boolean,
    ) {
        var asks = 0
            private set

        /** Exactly what `runCatching { it.requestFocus() }` does at a real call site. */
        fun ask() {
            asks++
            if (takesIt) record.report(name, holdsIt = true)
        }
    }

    /** One run of a ladder: every wait it asked for, and how many asks it made. */
    private class LadderRun(val waits: MutableList<Long> = mutableListOf())

    /**
     * Climbs [askUntilHighlightHolds] with the [ask] and [holds] a real call site passes,
     * recording every wait.
     */
    private fun climb(ask: () -> Unit, holds: () -> Boolean): LadderRun {
        val run = LadderRun()
        runBlocking {
            askUntilHighlightHolds(wait = { millis -> run.waits += millis }, ask = ask, holds = holds)
        }
        return run
    }

    /**
     * The four rungs, each one an ask and a settle, when nothing ever takes the highlight.
     *
     * `HIGHLIGHT_LADDER_WAITS` is `0, 60, 120, 240` and the settle is one frame, so the waits a
     * ladder that never succeeds asks for are 16, 60, 16, 120, 16, 240, 16. The leading zero is
     * not waited for at all, which is what makes an ask that works cost no delay.
     *
     * **They add up to 484 ms, which is when a ladder really gives up, and 240 is only the last
     * rung's own wait.** Several comments in the app said 240; `/code-review` on build ticket 47
     * caught it, and `HIGHLIGHT_LADDER_WAITS` now carries the real number.
     */
    private val everyRung = listOf(16L, 60L, 16L, 120L, 16L, 240L, 16L)

    /**
     * The `Period` chip's ladder, `LaunchedEffect(periodChipRefocus)` in
     * `ui/SourceScreen.kt`. It is armed by `BACK` out of the Period jump list, which is the one
     * door that puts the highlight back on a chip by name.
     */
    @Test
    fun `the Period chip ladder runs every rung when the chip never takes the highlight`() {
        val record = HighlightRecord<String>()
        val chip = Node(PERIOD_CHIP, record, takesIt = false)
        val run = climb(ask = { chip.ask() }, holds = { record.holds(PERIOD_CHIP) })
        assertEquals(4, chip.asks)
        assertEquals(everyRung, run.waits)
    }

    @Test
    fun `the Period chip ladder stops the moment the chip reports holding the highlight`() {
        val record = HighlightRecord<String>()
        val chip = Node(PERIOD_CHIP, record, takesIt = true)
        val run = climb(ask = { chip.ask() }, holds = { record.holds(PERIOD_CHIP) })
        assertEquals(1, chip.asks)
        assertEquals(listOf(16L), run.waits)
    }

    /**
     * The grid's ladder, `LaunchedEffect(pending)` in `VideoGrid` in `ui/SourceScreen.kt`. It
     * serves every move of the highlight across the grid, the Source's opening landing, a chip
     * landing, and `BACK` out of a panel through `askRefocus`.
     */
    @Test
    fun `the grid ladder runs every rung when the card never takes the highlight`() {
        val record = HighlightRecord<String>()
        val card = Node("v17", record, takesIt = false)
        val run = climb(ask = { card.ask() }, holds = { record.holds("v17") })
        assertEquals(4, card.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The other card the grid's ladder was written for: one the lazy grid has not placed, which
     * has no requester in the map at all, so the ask is not made and there is nothing to throw.
     * Every rung still has to run, because the lazy grid may place the card at any of them.
     */
    @Test
    fun `the grid ladder runs every rung for a card the lazy grid is not holding`() {
        val record = HighlightRecord<String>()
        val requesters = mutableMapOf<Int, Node<String>>()
        var asks = 0
        val run = climb(
            ask = { requesters[17]?.let { asks++ } },
            holds = { record.holds("v17") },
        )
        assertEquals(0, asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The same card, placed by the lazy grid between the second rung and the third. This is the
     * case the ladder was written for in the first place, and it still works.
     */
    @Test
    fun `the grid ladder lands on the rung where the card is finally placed`() {
        val record = HighlightRecord<String>()
        val requesters = mutableMapOf<Int, Node<String>>()
        var asks = 0
        val run = climb(
            ask = {
                asks++
                if (asks == 2) requesters[17] = Node("v17", record, takesIt = true)
                requesters[17]?.ask()
            },
            holds = { record.holds("v17") },
        )
        assertEquals(2, asks)
        assertEquals(listOf(16L, 60L, 16L), run.waits)
    }

    /**
     * The card menu's ladder, `LaunchedEffect(menu, openOn, requesters)` in `CardMenuOverlay`
     * in `ui/SourceScreen.kt`. It asks the first line that is not dim, never line 0.
     */
    @Test
    fun `the card menu ladder runs every rung when the line never takes the highlight`() {
        val record = HighlightRecord<Int>()
        val line = Node(1, record, takesIt = false)
        val run = climb(ask = { line.ask() }, holds = { record.holds(1) })
        assertEquals(4, line.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The menu's key guard is settled from the record after the ladder, and not from the ask.
     * Until build ticket 47 the ladder wrote `hasFocus.value = true` on the first
     * `requestFocus` that did not throw, so the screen's outer guard opened on the strength of
     * an ask that may have moved nothing, and every key the menu does not keep reached the grid
     * behind the scrim.
     */
    @Test
    fun `the card menu's key guard stays shut when no line takes the highlight`() {
        val record = HighlightRecord<Int>()
        val line = Node(1, record, takesIt = false)
        climb(ask = { line.ask() }, holds = { record.holds(1) })
        assertFalse(record.holdsAnything)
    }

    /**
     * A level change reuses the line nodes, so a node that already holds the highlight reports
     * no focus change at all. The record is deliberately not rebuilt on a level change, so it
     * still names that node, the first rung reads true, and the menu's key guard is opened by
     * the line after the ladder.
     */
    @Test
    fun `the card menu ladder ends at once when the reused node still holds the highlight`() {
        val record = HighlightRecord<Int>()
        record.report(1, holdsIt = true)
        val line = Node(1, record, takesIt = false)
        val run = climb(ask = { line.ask() }, holds = { record.holds(1) })
        assertEquals(1, line.asks)
        assertEquals(listOf(16L), run.waits)
        assertTrue(record.holdsAnything)
    }

    /**
     * The Period jump list's ladder, `LaunchedEffect(pending)` in `PeriodPanelOverlay` in
     * `ui/SourceScreen.kt`. A row past `PERIOD_PANEL_MAX_ROWS` is not composed until the panel
     * has scrolled to it, which is why this ladder exists at all.
     */
    @Test
    fun `the Period jump list ladder runs every rung when the row never takes the highlight`() {
        val record = HighlightRecord<Int>()
        val row = Node(4, record, takesIt = false)
        val run = climb(ask = { row.ask() }, holds = { record.holds(4) })
        assertEquals(4, row.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The first-run page's ladder, `LaunchedEffect(Unit)` in `FirstRunPage` in
     * `ui/HomeScreen.kt`. `Add a Source` is the only focusable node on that whole screen and
     * the home screen is the screen `BACK` has nowhere to go from, so a ladder that stopped
     * early left a household with a page they could read and no key they could press.
     */
    @Test
    fun `the first-run page ladder runs every rung when the line never takes the highlight`() {
        val record = HighlightRecord<String>()
        val line = Node(ADD_A_SOURCE, record, takesIt = false)
        val run = climb(ask = { line.ask() }, holds = { record.holds(ADD_A_SOURCE) })
        assertEquals(4, line.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The home panel's ladder, `LaunchedEffect(title, lines)` in `MenuPanel` in
     * `ui/HomeScreen.kt`. That panel has no key guard of its own, so a ladder that stopped
     * early left the highlight on a rail card behind the scrim, and a covered rail card
     * swallows every key but `BACK`.
     */
    @Test
    fun `the home panel ladder runs every rung when the line never takes the highlight`() {
        val record = HighlightRecord<Int>()
        val line = Node(0, record, takesIt = false)
        val run = climb(ask = { line.ask() }, holds = { record.holds(0) })
        assertEquals(4, line.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * What every one of the six ladders did before build ticket 47, kept as one test so the
     * defect is written down in code and not only in a comment.
     *
     * A ladder whose exit test is "the call did not throw" makes exactly one ask, because the
     * call never throws. A ladder whose exit test is the record makes four. The difference is
     * the whole ticket.
     */
    @Test
    fun `the old exit test stopped after one ask where the new one makes four`() {
        val record = HighlightRecord<String>()
        val card = Node("v17", record, takesIt = false)
        val old = climb(ask = { card.ask() }, holds = { true })
        assertEquals(1, card.asks)
        assertEquals(listOf(16L), old.waits)

        val again = Node("v17", record, takesIt = false)
        val new = climb(ask = { again.ask() }, holds = { record.holds("v17") })
        assertEquals(4, again.asks)
        assertEquals(everyRung, new.waits)
    }

    // --------------------------------------- the five ladders of the add and rename pages

    /**
     * One candidate row of the add screen's results, named the way the screen names it.
     *
     * [candidateKey] is the name, and it is also what the `LazyColumn` is keyed by, so the two
     * cannot drift apart. A place in the list is deliberately not used; the KDoc on
     * `ChoosingStep`'s record says why.
     */
    private fun candidateRow(playlistId: String): SourceCandidate = SourceCandidate(
        kind = CandidateKind.PLAYLIST,
        playlistId = playlistId,
        channelId = "UCsomething",
        title = "Something",
        handle = null,
        thumbnailUrl = null,
        videoCount = null,
    )

    /**
     * The add screen's text field, `LaunchedEffect(Unit)` in `TypingStep` in
     * `ui/AddSourceScreen.kt`. Build ticket 48.
     *
     * **The field is the screen.** Nothing else on the typing step is asked for the highlight,
     * and the household reaches this screen to type. A ladder that gave up early left a field
     * with no ring, no cursor and no television keyboard, so the only key that does anything is
     * `BACK`. That is the whole exposure: the household presses `BACK` and tries again, rather
     * than being stuck, because `MainActivity` owns `BACK` on this screen and it always works.
     */
    @Test
    fun `the add screen's field ladder runs every rung when the field never takes the highlight`() {
        val record = HighlightRecord<String>()
        val field = Node(ADD_FIELD, record, takesIt = false)
        val run = climb(ask = { field.ask() }, holds = { record.holds(ADD_FIELD) })
        assertEquals(4, field.asks)
        assertEquals(everyRung, run.waits)
    }

    @Test
    fun `the add screen's field ladder stops the moment the field reports holding it`() {
        val record = HighlightRecord<String>()
        val field = Node(ADD_FIELD, record, takesIt = true)
        val run = climb(ask = { field.ask() }, holds = { record.holds(ADD_FIELD) })
        assertEquals(1, field.asks)
        assertEquals(listOf(16L), run.waits)
    }

    /**
     * `Find it` taking the highlight does **not** end the field's ladder.
     *
     * The two share one record, because a record that heard from only one of them would say the
     * field still held the highlight after the household had walked off it. So the exit test
     * has to name the field and not merely ask whether anything at all holds the highlight.
     */
    @Test
    fun `the add screen's field ladder is not ended by the Find it button taking the highlight`() {
        val record = HighlightRecord<String>()
        val field = Node(ADD_FIELD, record, takesIt = false)
        record.report(ADD_FIND, holdsIt = true)
        val run = climb(ask = { field.ask() }, holds = { record.holds(ADD_FIELD) })
        assertEquals(4, field.asks)
        assertEquals(everyRung, run.waits)
        assertEquals(ADD_FIND, record.holder)
    }

    /**
     * The results ladder, `LaunchedEffect(state.rows)` in `ChoosingStep` in
     * `ui/AddSourceScreen.kt`. Build ticket 48.
     *
     * **This is the least exposed of the five.** Every row of the results is focusable, so a
     * ladder that gave up early leaves a screen the D-pad can still reach: Compose's own focus
     * search finds a row on the first `Down`. The household would see a list with nothing
     * ringed and would have to press a key twice.
     */
    @Test
    fun `the results ladder runs every rung when the first row never takes the highlight`() {
        val record = HighlightRecord<String>()
        val row = candidateRow("PLfirst")
        val node = Node(candidateKey(row), record, takesIt = false)
        val run = climb(ask = { node.ask() }, holds = { record.holds(candidateKey(row)) })
        assertEquals(4, node.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The row the `LazyColumn` has not composed yet, which is why this ladder exists at all.
     *
     * A row that does not exist has nothing attached to its requester, so `requestFocus` throws
     * and `runCatching` swallows it. Every rung still has to run, because the list may place
     * the row at any of them.
     */
    @Test
    fun `the results ladder lands on the rung where the LazyColumn finally places the row`() {
        val record = HighlightRecord<String>()
        val row = candidateRow("PLfirst")
        var placed: Node<String>? = null
        var asks = 0
        val run = climb(
            ask = {
                asks++
                if (asks == 3) placed = Node(candidateKey(row), record, takesIt = true)
                placed?.ask()
            },
            holds = { record.holds(candidateKey(row)) },
        )
        assertEquals(3, asks)
        assertEquals(listOf(16L, 60L, 16L, 120L, 16L), run.waits)
    }

    /**
     * A second row taking the highlight does not end the first row's ladder.
     *
     * Every row reports into the one record, so this is the case that makes naming the row
     * rather than asking `holdsAnything` load-bearing.
     */
    @Test
    fun `the results ladder is not ended by a different row taking the highlight`() {
        val record = HighlightRecord<String>()
        val first = candidateRow("PLfirst")
        val second = candidateRow("PLsecond")
        val node = Node(candidateKey(first), record, takesIt = false)
        record.report(candidateKey(second), holdsIt = true)
        val run = climb(ask = { node.ask() }, holds = { record.holds(candidateKey(first)) })
        assertEquals(4, node.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The `Add` button's ladder, `LaunchedEffect(state.candidate)` in `ConfirmingStep` in
     * `ui/AddSourceScreen.kt`. Build ticket 48.
     *
     * **The two Direction chips are the safety net here.** They are focusable, so a ladder that
     * gave up early leaves a screen the D-pad can reach, and nothing is stored until `Add` is
     * pressed, so nothing wrong can happen by accident. The cost is a household that has to
     * find the button with the D-pad rather than pressing the centre button once.
     */
    @Test
    fun `the Add button ladder runs every rung when the button never takes the highlight`() {
        val record = HighlightRecord<String>()
        val button = Node(ADD_CONFIRM, record, takesIt = false)
        val run = climb(ask = { button.ask() }, holds = { record.holds(ADD_CONFIRM) })
        assertEquals(4, button.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The button that already holds the highlight when a new candidate arrives.
     *
     * The requester is rebuilt on a candidate change and the record deliberately is not, for
     * the reason build ticket 47 found on the card menu's level change: a node that already
     * holds the highlight reports no focus change when a new requester is attached to it, so a
     * record rebuilt there would be empty with nothing able to fill it and the ladder would
     * spend all four rungs for nothing.
     */
    @Test
    fun `the Add button ladder ends at once when the button already holds the highlight`() {
        val record = HighlightRecord<String>()
        record.report(ADD_CONFIRM, holdsIt = true)
        val button = Node(ADD_CONFIRM, record, takesIt = false)
        val run = climb(ask = { button.ask() }, holds = { record.holds(ADD_CONFIRM) })
        assertEquals(1, button.asks)
        assertEquals(listOf(16L), run.waits)
    }

    /**
     * The refusal page's ladder, `LaunchedEffect(state)` in `AlreadyAddedStep` in
     * `ui/AddSourceScreen.kt`. Build ticket 48.
     *
     * **Two buttons and nothing else**, so a ladder that gave up early leaves a page with two
     * reachable buttons and no ring. `Type something else` is the second of them, and it does
     * the same thing `BACK` does, so the household is never stuck here either.
     */
    @Test
    fun `the already-added ladder runs every rung when the first button never takes it`() {
        val record = HighlightRecord<String>()
        val label = openExisting("ערוץ לדוגמה - כל הסרטונים")
        val button = Node(label, record, takesIt = false)
        val run = climb(ask = { button.ask() }, holds = { record.holds(label) })
        assertEquals(4, button.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The second button of the refusal page cannot end the first button's ladder.
     *
     * Both report into one record, named by the words on each, and the two labels can never be
     * the same words because one of them always starts with `Open `.
     */
    @Test
    fun `the already-added ladder is not ended by Type something else taking the highlight`() {
        val record = HighlightRecord<String>()
        val label = openExisting("TED")
        val button = Node(label, record, takesIt = false)
        record.report(ADD_TRY_SOMETHING_ELSE, holdsIt = true)
        val run = climb(ask = { button.ask() }, holds = { record.holds(label) })
        assertEquals(4, button.asks)
        assertEquals(everyRung, run.waits)
    }

    /**
     * The rename screen's text field, `LaunchedEffect(Unit)` in `RenameSourceScreen` in
     * `ui/RenameSourceScreen.kt`. Build ticket 48.
     *
     * **It is the most exposed of the five.** The field takes the highlight as the screen opens,
     * which is what brings the television's own keyboard up, and a `BasicTextField` eats `Down`,
     * so the two buttons below are reached only by the field's own `onPreviewKeyEvent`. With no
     * highlight on the field that preview handler never runs, and Compose's focus search has
     * nothing to start from, so `BACK` really is the only key that does anything. The screen
     * still has `BACK`, which `MainActivity` owns, so the worst case is a rename the household
     * has to start again.
     */
    @Test
    fun `the rename field ladder runs every rung when the field never takes the highlight`() {
        val record = HighlightRecord<String>()
        val field = Node(RENAME_FIELD, record, takesIt = false)
        val run = climb(ask = { field.ask() }, holds = { record.holds(RENAME_FIELD) })
        assertEquals(4, field.asks)
        assertEquals(everyRung, run.waits)
    }

    @Test
    fun `the rename field ladder stops the moment the field reports holding it`() {
        val record = HighlightRecord<String>()
        val field = Node(RENAME_FIELD, record, takesIt = true)
        val run = climb(ask = { field.ask() }, holds = { record.holds(RENAME_FIELD) })
        assertEquals(1, field.asks)
        assertEquals(listOf(16L), run.waits)
    }

    /**
     * The rename screen's three nodes share one record and the field's ladder names the field.
     *
     * `Use the name YouTube gives it` is the one button in the app that can be dim, and
     * `PanelButton` reports from outside its own `enabled` branch so that a button going dim
     * while it holds the highlight can still say it lost it. This is that loss, read back.
     */
    @Test
    fun `a rename button going dim while it holds the highlight empties the record`() {
        val record = HighlightRecord<String>()
        record.report(RENAME_CLEAR, holdsIt = true)
        assertEquals(RENAME_CLEAR, record.holder)

        record.report(RENAME_CLEAR, holdsIt = false)

        assertNull(record.holder)
        assertFalse(record.holdsAnything)
    }

    /**
     * What all five of these ladders did before build ticket 48, kept as one test so the defect
     * is written down in code and not only in a comment.
     *
     * A ladder whose exit test is "the call did not throw" makes exactly one ask, because the
     * call never throws. A ladder whose exit test is the record makes four.
     */
    @Test
    fun `the old exit test stopped the rename field ladder after one ask`() {
        val record = HighlightRecord<String>()
        val field = Node(RENAME_FIELD, record, takesIt = false)
        val old = climb(ask = { field.ask() }, holds = { true })
        assertEquals(1, field.asks)
        assertEquals(listOf(16L), old.waits)

        val again = Node(RENAME_FIELD, record, takesIt = false)
        val new = climb(ask = { again.ask() }, holds = { record.holds(RENAME_FIELD) })
        assertEquals(4, again.asks)
        assertEquals(everyRung, new.waits)
    }

    // -------------------------------------------------- what names a row of the results

    /**
     * [candidateKey] tells a channel row from its own uploads playlist, which share both ids.
     *
     * Two rows with one name would be a repeated `LazyColumn` key, which throws, and two rows
     * the highlight record could not tell apart.
     */
    @Test
    fun `a channel row and its uploads playlist are two different names`() {
        val channel = candidateRow("PLuploads").copy(kind = CandidateKind.CHANNEL)
        val playlist = candidateRow("PLuploads")
        assertTrue(candidateKey(channel) != candidateKey(playlist))
    }

    /**
     * Two channel rows whose uploads playlist YouTube declined to price are still two names.
     *
     * Both carry a blank playlist id, so a name built from the playlist id alone would repeat
     * and the `LazyColumn` would throw on a screen the household reached by typing a name.
     */
    @Test
    fun `two rows with no playlist id at all are still told apart by the channel id`() {
        val one = candidateRow("").copy(channelId = "UCone")
        val two = candidateRow("").copy(channelId = "UCtwo")
        assertTrue(candidateKey(one) != candidateKey(two))
    }

    /**
     * The NUL between the two ids is what stops one pair of ids being split two ways.
     *
     * Without it, channel `UCa` with playlist `bPL` and channel `UCab` with playlist `PL` would
     * both be named `UCabPL`.
     */
    @Test
    fun `the two ids cannot run together into one name`() {
        val one = candidateRow("bPL").copy(channelId = "UCa")
        val two = candidateRow("PL").copy(channelId = "UCab")
        assertTrue(candidateKey(one) != candidateKey(two))
    }
}
