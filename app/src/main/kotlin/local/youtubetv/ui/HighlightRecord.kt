package local.youtubetv.ui

/**
 * What really holds the highlight, written down by the nodes themselves.
 *
 * "The highlight" is the ring and fill this app draws on the one thing the D-pad is standing
 * on. A card, a chip, a card menu line or a Period jump list row can hold it, and exactly one
 * of them does at a time.
 *
 * **This file exists because a retry ladder has to be able to fail.** Every panel and every
 * list in this app asks for the highlight through a `FocusRequester` and then retries, because
 * a node a lazy list has not placed yet cannot take the highlight. Until build ticket 41 every
 * one of those ladders exited on `runCatching { requestFocus() }.isSuccess`, which says only
 * that the call did not throw. A `requestFocus` on a node that is attached and quietly refuses
 * the highlight throws nothing, so the first rung read success and the later rungs never ran.
 * The one case a retry ladder exists for was the one case it could not detect. Build ticket 41
 * fixed the home rail; build ticket 47 fixed the four ladders on the Source screen and the two
 * on the home screen's panels, and pulled the rule out here so there is one copy of it.
 *
 * There is no Compose and no Android import in this file, so every rule in it is a JVM unit
 * test with no device. See `app/src/test/kotlin/local/youtubetv/ui/HighlightRecordTest.kt`.
 */

/**
 * Which node really holds the highlight, after one node reports its own focus changing.
 *
 * **This is the fact, and it is not the same thing as the node the app asked for.** The app's
 * own request is written down separately on every screen: `focusedSourceId` on the home rail,
 * `focusedIndex` on the Source screen's grid. A ladder that read one of those fields would be
 * reading back the app's own write and would stop on its first rung whatever the ask had done.
 *
 * **A node reporting that it lost the highlight only clears the answer when the answer is that
 * node.** Compose can report the new node's gain before the old node's loss when the highlight
 * walks from one to the next, and a loss applied without that check would throw away the gain
 * that had just arrived, leaving the record reading as if nothing held the highlight at all.
 * Build ticket 41 found that on the home rail.
 *
 * [railHighlightHolder] in `ui/HomeModel.kt` is this same rule, read for a Source id.
 *
 * @param held the node that held the highlight until now, or null when none reported holding
 *   it.
 * @param reported the node whose focus has just changed.
 * @param holdsIt what that node now reports: true when it has the highlight, false when it has
 *   lost it.
 */
fun <T : Any> highlightHolder(held: T?, reported: T, holdsIt: Boolean): T? = when {
    holdsIt -> reported
    held == reported -> null
    else -> held
}

/**
 * Which node holds the highlight after one node is destroyed, meaning it left composition.
 *
 * **Whether a node that leaves composition reports its loss first is Compose's business, and
 * this makes the record's answer not depend on it.** Build ticket 49 was raised on the belief
 * that it does not report, which would leave [highlightHolder] naming that node for ever:
 * nothing reports under its name again, and Compose has meanwhile handed the highlight to the
 * nearest focusable it can find, behind whatever panel is open. `holdsAnything` reading true
 * there is what opens the key guard on the Source screen's outer `Box`, and the next `Down`
 * would then move something the household cannot see while the centre button acted on it.
 *
 * **Measured on the `googletv1080p` emulator on 2026-09-21, the loss is reported.** A probe
 * removed the focused item from a `LazyColumn` and from a plain `Column`, and both delivered
 * `isFocused = false` to the node being removed, then the gain on whatever Compose moved the
 * highlight to, and only then the disposal. So the defect is not reachable on the Compose
 * version this app is built against, and this function is what keeps the record right if that
 * ever changes. Compose's behaviour here has changed between versions before, and the only
 * thing that would report it changing again is the household finding a video playing from
 * behind an open panel.
 *
 * **A destroyed node that was replaced clears nothing, and that is the whole difficulty.** A
 * disposal is delivered *after* the node that took the destroyed node's place has already
 * registered, and that replacement may already have taken the highlight and reported it. A
 * clear written without this test would throw away the replacement's own report, and the key
 * guard would then be shut on a panel that is perfectly fine for the 484 ms its retry ladder
 * takes to give up. That is the same ordering the Source screen's grid and Period jump list
 * already guard their `requesters` removal with, `if (requesters[index] === requester)`, and
 * the caller answers [replaced] from exactly that test.
 *
 * @param held the node that held the highlight until now, or null when none reported holding
 *   it.
 * @param destroyed the node that has just left composition.
 * @param replaced true when another node has already taken [destroyed]'s place in the panel, so
 *   whatever the record names now belongs to that replacement and not to the node being
 *   destroyed.
 */
fun <T : Any> highlightAfterDestruction(held: T?, destroyed: T, replaced: Boolean): T? = when {
    replaced -> held
    held == destroyed -> null
    else -> held
}

/**
 * One screen's or one panel's record of which of its nodes holds the highlight.
 *
 * **Only a node's own focus report may call [report], and nothing else may write the record at
 * all.** That placement is the whole point: a record the app can write is a record that says
 * what the app wanted rather than what happened, and a ladder reading it could never see a
 * failed ask. So this class has no setter, and a screen keeps its record inside the composable
 * whose nodes report into it, out of reach of the code that asks for the highlight. Build
 * ticket 41 put `highlightOn` inside `SourceRail` for exactly that reason, where
 * `handHighlightBack` in `HomeScreen` cannot touch it.
 *
 * **[reportDestroyed] is the second thing a node says about itself, and it does not weaken
 * that.** It is called from the node's own `DisposableEffect`, which is the only place that
 * knows the node has gone, and it can only ever take a name **out** of this record. Nothing in
 * this class can put a name in but [report], so no caller can make a ladder read a success that
 * did not happen, which is the property build ticket 41 and build ticket 47 were protecting.
 * The worst a misplaced [reportDestroyed] could do is make a ladder retry and a key guard shut,
 * which is the safe direction. Build ticket 49.
 *
 * **It is a plain field and not Compose state on purpose.** Nothing draws from it, so nothing
 * has to recompose when it changes. It is written from `onFocusChanged`, which Compose runs on
 * the main thread, and read from the ladders, which run on the main dispatcher in a
 * `LaunchedEffect`, so both sides are the same thread.
 *
 * @param T what names one node to this record. The Source screen's grid, card menu and Period
 *   jump list each name a node by its index; the `Period` chip names it by the words on the
 *   chip.
 */
class HighlightRecord<T : Any> {

    /** The node that reports holding the highlight, or null when no node reports holding it. */
    var holder: T? = null
        private set

    /** True when any node of this record reports holding the highlight. */
    val holdsAnything: Boolean get() = holder != null

    /**
     * Writes down what one node has just reported about itself.
     *
     * [highlightHolder] is the rule, including the part about a loss reported by a node the
     * record does not name.
     */
    fun report(node: T, holdsIt: Boolean) {
        holder = highlightHolder(holder, node, holdsIt)
    }

    /**
     * Writes down that one node has left composition, so it will never report anything again.
     *
     * Call it from that node's own `DisposableEffect`, and from nowhere else.
     * [highlightAfterDestruction] is the rule, including the part about a node that has already
     * been replaced.
     *
     * @param replaced true when another node has already taken this node's place in the panel.
     *   The caller answers it from the same identity test that guards its `requesters` removal.
     */
    fun reportDestroyed(node: T, replaced: Boolean) {
        holder = highlightAfterDestruction(holder, node, replaced)
    }

    /**
     * Whether [node] is the one reporting that it holds the highlight.
     *
     * This is what a retry ladder passes as its `holds` argument. See [askUntilHighlightHolds].
     */
    fun holds(node: T): Boolean = holder == node
}
