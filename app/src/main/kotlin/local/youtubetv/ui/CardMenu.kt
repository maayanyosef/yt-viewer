package local.youtubetv.ui

/**
 * The menu the household opens by holding the centre button on a video card.
 *
 * Two levels, not one flat list of six lines. That was judged on the television: the first
 * level signposts that a second step is coming, and every line stays short enough to read
 * from the sofa. See spec section 7.3.
 *
 * The state is here, with no Compose in it, so every move is a JVM unit test. The screen only
 * draws whatever this says.
 */
sealed interface CardMenu {

    /** Nothing is open. The grid has the keys. */
    data object Closed : CardMenu

    /** The two top lines, `Mark as watched` and `Mark as unwatched`, for the card at [index]. */
    data class Top(val index: Int) : CardMenu

    /**
     * One submenu.
     *
     * @property markWatched true under `Mark as watched`, false under `Mark as unwatched`.
     *   Both submenus hold the same lines, and this is the only difference between them.
     */
    data class Sub(val index: Int, val markWatched: Boolean) : CardMenu
}

val CardMenu.isOpen: Boolean get() = this != CardMenu.Closed

/** The card the menu was opened on, in the listed videos. */
val CardMenu.videoIndex: Int?
    get() = when (this) {
        CardMenu.Closed -> null
        is CardMenu.Top -> index
        is CardMenu.Sub -> index
    }

/**
 * Where `Back` goes.
 *
 * From a submenu it returns to the two top lines, **never** straight to the grid. Spec section
 * 7.3 asks for that in so many words: a household that opened the wrong submenu would
 * otherwise have to hold the centre button again to get back where it was.
 */
fun CardMenu.back(): CardMenu = when (this) {
    CardMenu.Closed -> CardMenu.Closed
    is CardMenu.Top -> CardMenu.Closed
    is CardMenu.Sub -> CardMenu.Top(index)
}

/**
 * The card this menu is about moved to another index, so the menu moves with it.
 *
 * A refresh that inserts a newer video puts every later video one place along. This menu holds
 * an **index** into the listed videos, so one left alone would go on marking one card and
 * acting on another. Spec section 5.3 applies a change at once and keeps the focused video, and
 * this is the same rule applied to what is drawn over it.
 *
 * A closed menu is about no card, so it stays closed.
 */
fun CardMenu.movedTo(index: Int): CardMenu = when (this) {
    CardMenu.Closed -> CardMenu.Closed
    is CardMenu.Top -> CardMenu.Top(index)
    is CardMenu.Sub -> CardMenu.Sub(index = index, markWatched = markWatched)
}

/** Opening a submenu from the top lines. A closed menu opens nothing. */
fun CardMenu.openSub(markWatched: Boolean): CardMenu = when (this) {
    CardMenu.Closed -> CardMenu.Closed
    is CardMenu.Top -> CardMenu.Sub(index = index, markWatched = markWatched)
    is CardMenu.Sub -> CardMenu.Sub(index = index, markWatched = markWatched)
}

/** How far the household asked to move inside the open menu. */
enum class MenuMove {
    UP,
    DOWN,

    /** Left or Right, and the channel rocker. The menu is one column, so none of them mean anything. */
    SIDEWAYS,
}

/**
 * Whether the menu keeps a key rather than letting it reach the grid behind the scrim.
 *
 * The menu is drawn over the grid, and the cards behind it are still focusable. Compose's own
 * focus search looks for the nearest focusable in the direction asked for, and from the last
 * line of the menu that is a card the household cannot see. So the menu keeps every key that
 * would take focus out of it.
 *
 * **Which panels still leave `Up` and `Down` to Compose is now different per panel.** The
 * argument used to be that a column of focusable lines is the one case its focus search gets
 * right. Build ticket 31 disproved that on the television: from the home rail's menu the search
 * picked a rail card behind the scrim, because the card's bottom edge was nearer than the line
 * above.
 *
 * - `HomeScreen.MenuPanel`, which is all three home overlays, no longer asks. [menuNeighbour]
 *   says which line the key means and the panel wires it with a `FocusRequester`. There this
 *   function is only about the two ends and about sideways keys, and keeping the key at an end
 *   says the same thing as the cancelled search at that end, on purpose.
 * - `SourceScreen.CardMenuOverlay` and `SourceScreen.PeriodPanelOverlay` **still ask**. Neither
 *   showed the defect when ticket 31 looked: the card menu was checked on the household
 *   television on 2026-09-21, and the Period panel on the `googletv1080p` emulator, where the
 *   home defect does reproduce. Both are drawn over a grid, whose geometry is not the rail's.
 *   [periodPanelKeepsKey] is the panel's half of this.
 *
 * **[focusedLine] and [lineCount] count live lines, never drawn ones.** A dim line is not
 * focusable, so it is not an end of the column even when it is drawn at one. With `Just this`
 * dim, a three-line submenu is a column of two, and `Up` from the first of those two has to be
 * kept. [liveLineRank] is what turns a drawn line's own index into the number this wants.
 *
 * **[hasFocus] is the guard ticket 09 wrote up and did not own.** Until a line of the menu has
 * the highlight, the highlight is still on a card behind the scrim, and every key the menu does
 * not keep is acted on by the grid. The window is the retry ladder that asks the first line for
 * focus, which gives up at about 484 ms, and one slow frame on the household television is
 * already enough to fall inside it. See `HIGHLIGHT_LADDER_WAITS` for where that number comes
 * from: it is four rungs and a settle after each of them, not the 240 ms of the last rung.
 *
 * **This function states the rule. It is not where the rule is enforced.** Compose delivers a
 * preview key event only to the `onPreviewKeyEvent` modifiers on the ancestor chain of the
 * focused node, and while the grid still holds the highlight the menu is that card's sibling
 * rather than its ancestor, so the menu's own handler is never visited. The guard that does the
 * work is on the Source screen's outer `Box`, the nearest common ancestor of the menu and the
 * grid. [periodPanelKeepsKey] says the same thing about the Period panel and defers to this.
 *
 * @param hasFocus whether any line of the menu has reported focus yet.
 */
fun cardMenuKeepsKey(
    move: MenuMove,
    focusedLine: Int,
    lineCount: Int,
    hasFocus: Boolean = true,
): Boolean = if (!hasFocus) {
    true
} else {
    when (move) {
        MenuMove.SIDEWAYS -> true
        MenuMove.UP -> focusedLine <= 0
        MenuMove.DOWN -> focusedLine >= lineCount - 1
    }
}

/**
 * Whether the Source screen keeps a key from everything behind an overlay drawn over the grid.
 *
 * **This is the Source screen's outer key guard, and it is one sentence for both overlays.**
 * While the card menu or the Period jump list is drawn and no line of it holds the highlight,
 * every key but `BACK` belongs to the screen. Without it a `Down` scrolls the grid the household
 * cannot see and the centre button plays a video from behind the overlay. Reproduced on the
 * emulator on 2026-09-20: `MENU`, then a beat later `Down` and the centre button, played a video
 * from behind an open Period jump list.
 *
 * **It was written twice inline until build ticket 52 and is one function now.** Two copies of
 * one sentence drift, and the copy that drifts is the one nobody has a test for.
 *
 * **Stating the rule is not enforcing it, and the placement is the half that matters.** Compose
 * delivers a preview key event only to the `onPreviewKeyEvent` modifiers on the **ancestor chain
 * of the focused node**. While a card behind the scrim holds the highlight, the overlay is that
 * card's sibling rather than its ancestor, so a handler written inside the overlay is never
 * visited. The one place this can work from is `SourceScreen`'s outer `Box`, the nearest common
 * ancestor of the overlay and the grid. The first version of the fix was written inside the
 * overlay and was dead code that read as correct; `/code-review` caught it and no JVM test could
 * have. `SourceKeyGuardTest` in `app/src/androidTest/` is what proves the placement, on a wholly
 * dim submenu, which is the one state in which this stands still long enough to press a key at.
 *
 * **[isBack] is let through whatever else is kept, and that is not a detail.** The retry ladder
 * that puts the highlight on a line gives up at about 484 ms, and a wholly dim submenu never
 * lands at all, so an overlay can hold no highlight for as long as it is open. Without this
 * term the guard swallows `BACK`, and the household has no way off that overlay but the `HOME`
 * button.
 *
 * **Measured on the household TCL on 2026-10-03, by build ticket 54.** The TCL is API 34 and the
 * app does not opt in to `OnBackInvokedCallback`, so `BACK` is only a key event there. It goes
 * through this guard like any other key, and `Activity.onKeyUp` turns it into `onBackPressed`
 * only if nothing consumed it. One press of `BACK` on a wholly dim submenu gave:
 *
 * - With this term, both halves of the press passed the guard. The card menu's `BackHandler`
 *   ran 1 ms after the key up.
 * - With this term removed by hand, the guard consumed both halves. No `BackHandler` ran, and
 *   the submenu stayed up.
 *
 * **The `googletv1080p` emulator cannot show this on its own.** It is API 36 and the app targets
 * 36. The same diagnostic build logged `BACK` arriving at this guard there too, as a key down
 * and a key up. But the system also called `OnBackInvokedCallback` on the key down, and that
 * second route ran `BackHandler` 6 ms later with this term or without it. An earlier note here
 * read the close as `BACK` not reaching the guard at all, which was wrong. `SourceKeyGuardTest`
 * turns the second route off with `KeyEventBackActivity`, and its `BACK` case fails when this
 * term is removed. Only API 34 and API 36 were measured. Android documents the second route as
 * the default from Android 16, API 36, for an app that targets 36.
 *
 * **[overlayHasFocus] is tested, never a bare [overlayOpen].** The outer `Box` is an ancestor of
 * the overlay as well, and preview runs from the top down, so keeping every key while the
 * overlay *does* hold the highlight would eat the overlay's own `Up` and `Down`. Found by
 * `/code-review`.
 *
 * @param overlayOpen whether that overlay is drawn over the grid right now.
 * @param overlayHasFocus whether any line of it has reported holding the highlight.
 * @param isBack whether the key is `BACK`.
 */
fun overlayKeepsEveryKey(
    overlayOpen: Boolean,
    overlayHasFocus: Boolean,
    isBack: Boolean,
): Boolean = overlayOpen && !overlayHasFocus && !isBack

/**
 * The drawn line `Up` or `Down` moves to, or null when the key moves the highlight nowhere.
 *
 * **This is what replaces Compose's focus search inside a panel.** The comment on
 * [cardMenuKeepsKey] used to say that a column of focusable lines is the one case the search
 * already gets right. That is false, and the reason is geometry, not the column. The cards
 * behind the scrim stay focusable on purpose, and the search looks for the nearest focusable
 * node in the direction asked for. On the household's rail the bottom edge of a rail card is
 * nearer to the last menu line than the menu line above it is, so the search picks the card,
 * the highlight leaves the panel, and the rail scrolls under the scrim to show a card nobody
 * can see. Reproduced on the television on 2026-09-20; build ticket 31.
 *
 * So the panel stops asking and says which line it means. Each line points at its neighbour
 * through a `FocusRequester`, and a line with no neighbour cancels the search outright.
 *
 * A dim line is not focusable, so it is never a neighbour and is walked straight past. A dim
 * line at either end therefore shortens the column, which is the case that costs the most: a
 * Source that has never been walked draws both `Refresh this Source` and
 * `Mark everything unwatched` dim, and the second of those is the **last** drawn line.
 *
 * @param live one flag per drawn line, in the order the lines are drawn.
 * @param line the drawn index of the line the highlight is on.
 * @param move which key was pressed. [MenuMove.SIDEWAYS] moves nowhere by definition: the panel
 *   is one column.
 * @return the drawn index of the line to move to, or null.
 */
fun menuNeighbour(live: List<Boolean>, line: Int, move: MenuMove): Int? = when (move) {
    MenuMove.SIDEWAYS -> null
    MenuMove.UP -> (line - 1 downTo 0).firstOrNull { it < live.size && live[it] }
    MenuMove.DOWN -> ((line + 1).coerceAtLeast(0) until live.size).firstOrNull { live[it] }
}

/**
 * Where the highlight opens: the first line that is not dim, or null when every line is.
 *
 * The first drawn line is not always the first live one. `Mark as watched` opened on a card
 * that is already watched draws `Just this` dim, so the highlight belongs on the line below it.
 * Asking a line that is not focusable for focus fails silently, and the whole menu would then
 * sit there with no highlight at all until the retry ladder gave up.
 *
 * **A submenu can be wholly dim, and then there is honestly nowhere to put the highlight.** It
 * happens whenever the only line on offer is the dead one, which is two cases and not one:
 *
 * - a private or deleted video, which has no publish date and so is offered no range;
 * - **any card at all while the Source's first walk is still running**, because a Bulk mark
 *   needs the whole list and there is not one yet. So during a first walk, every unwatched
 *   card's `Mark as unwatched` is a wholly dim submenu. `/code-review` caught this being
 *   written up as the rare case; it is not.
 *
 * The menu is still drawn, so the household sees why. `BACK` is the way out, and the guard on
 * the Source screen's outer `Box` lets `BACK` through whatever else it keeps. That guard is
 * what keeps a wholly dim submenu from leaking its keys to the grid, and it works only because
 * `cardMenuHasFocus` is cleared on every change of level.
 *
 * @param live one flag per drawn line, in the order the lines are drawn.
 */
fun firstLiveLine(live: List<Boolean>): Int? = live.indexOfFirst { it }.takeIf { it >= 0 }

/**
 * A drawn line's place among the live lines, which is what [cardMenuKeepsKey] counts in.
 *
 * A dim line ranks as the live line before it, which is harmless: nothing ever reports focus
 * from a line that cannot take it.
 *
 * @param live one flag per drawn line, in the order the lines are drawn.
 * @param line the drawn line's own index.
 */
fun liveLineRank(live: List<Boolean>, line: Int): Int =
    live.take(line.coerceIn(0, live.size)).count { it }
