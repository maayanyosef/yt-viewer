package local.youtubetv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * How an overlay panel names its own neighbours instead of asking Compose to find them.
 *
 * Both screens draw panels over something that is still focusable: the home rail's cards behind
 * the home overlays, and the Source screen's video grid behind the card menu and the Period
 * jump list. Compose's two-dimensional focus search looks for the nearest focusable node in the
 * direction asked for, and "nearest" is measured in pixels on the screen, not in lines of the
 * panel. On the household's rail the bottom edge of a card sat nearer to the menu's last line
 * than the line above it did, so one `Up` took the highlight out of the panel altogether and
 * scrolled the rail under the scrim. Reproduced on the television on 2026-09-20; build ticket
 * 31.
 *
 * So a panel says which line it means. This file is the Compose half of that; the rule itself
 * is [menuNeighbour] in `ui/CardMenu.kt`, which is pure and unit-tested on the JVM.
 *
 * **It lives in its own file because both screens need it.** Build ticket 31 wrote it private
 * inside `ui/HomeScreen.kt`, and build ticket 33 moved it here rather than copying it into
 * `ui/SourceScreen.kt`.
 */

/**
 * The requester [menuNeighbour]'s answer names, or [FocusRequester.Cancel] when there is none.
 *
 * `Cancel` is not the same as leaving the direction alone. Leaving it alone hands the key to
 * Compose's focus search, which is the whole defect. `Cancel` ends the search with no focus
 * change at all, so the highlight stays on the line it is on and nothing behind the scrim
 * moves.
 *
 * @param live one flag per drawn line, in the order the lines are drawn.
 * @param line the drawn index of the line this answer is for.
 * @param requesters one requester per drawn line, in the same order. Only a live line's
 *   requester is ever returned, and only a live line is given one to attach, so this never
 *   hands back a requester that reaches no focus target.
 */
fun neighbourRequester(
    live: List<Boolean>,
    line: Int,
    move: MenuMove,
    requesters: List<FocusRequester>,
): FocusRequester =
    menuNeighbour(live, line, move)?.let { requesters[it] } ?: FocusRequester.Cancel

/**
 * Points this line's `Up` and `Down` at the lines the panel says they mean.
 *
 * Put it on a panel line **before** `focusable()`, and only on a line that is live. A dim line
 * is not focusable, so it is never asked which way to go.
 *
 * **The parameters are called [above] and [below] on purpose, and renaming them would be a
 * defect.** The receiver inside `focusProperties { }` is a `FocusProperties`, which has members
 * of its own called `up` and `down`. A parameter called `up` would make `this.up = up` read as
 * correct while the compiler was free to resolve the right-hand side to the member, and the
 * statement would then be a self-assignment: the whole fix would compile and do nothing.
 * `/code-review` had to settle that with a compiler probe on build ticket 31. With these names
 * there is nothing to settle, and there is one copy of the statement in the app rather than
 * one per screen.
 *
 * @param above the line `Up` moves to, or [FocusRequester.Cancel] when there is none above.
 * @param below the same, downwards.
 */
fun Modifier.panelNeighbours(above: FocusRequester, below: FocusRequester): Modifier =
    this.focusProperties {
        this.up = above
        this.down = below
    }

/**
 * A bring-into-view rule that never scrolls.
 *
 * Give it to a lazy list, through `LocalBringIntoViewSpec`, when this app and not Android owns
 * where that list sits. Android's default is the pivot spec: a node that takes focus is dragged
 * to a place in the viewport of the spec's own choosing, and the list scrolls to put it there.
 * That is right when the household is walking the list and wrong when the list is not the thing
 * the household is looking at.
 *
 * Three lists use it, for two reasons that are really the same one:
 *
 * - the Source screen's grid, so that walking along a row never makes the view jump and the one
 *   scroll is the effect that goes to the first card of a row;
 * - the Period jump list, so that the panel moves once per press and `periodPanelScrollTo` is
 *   telling the whole truth. Build ticket 33;
 * - the home rail, but **only while a panel covers it**. A card behind the scrim can still be
 *   handed the highlight, and the rail used to scroll home to show it. Build ticket 37;
 *   [railIsCovered] is the rule and carries the measurement. The rail keeps the default spec
 *   when it is the thing the household is walking, because that default is what scrolls the
 *   next card into view.
 *
 * **It lives here because three lists on two screens need it.** Build ticket 33 wrote it
 * private inside `ui/SourceScreen.kt`, and build ticket 37 moved it here rather than writing a
 * second copy, which is the same move ticket 33 made for [neighbourRequester].
 */
@OptIn(ExperimentalFoundationApi::class)
val NoScrollOnFocus: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}
