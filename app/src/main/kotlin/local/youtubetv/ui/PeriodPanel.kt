package local.youtubetv.ui

/**
 * The Period jump list's own rules, with no Compose in them.
 *
 * The same shape as `ui/CardMenu.kt`: the panel is drawn from what this says, so every rule is
 * a JVM unit test. The Periods themselves are `data/Periods.kt`.
 */

/**
 * How many entries the panel shows before it stops growing and scrolls.
 *
 * Eight is the height that leaves the grid behind the panel still readable at 1080p. Past it
 * the panel scrolls, which is why the entry the household is standing on is scrolled to when
 * the panel opens rather than left off the bottom.
 */
const val PERIOD_PANEL_MAX_ROWS = 8

/**
 * How many rows tall the panel is drawn.
 *
 * **A panel with three entries is three entries tall.** That is the cosmetic item from spec
 * section 9.3, folded into this ticket: the panel was drawn full height however few entries it
 * held, which made a three-year Source look like a screen of empty box.
 *
 * It is an entry count rather than a `Dp`, so the rule is settled here on the JVM and the
 * composable only multiplies it by one row's height.
 */
fun periodPanelRows(entryCount: Int): Int =
    entryCount.coerceIn(0, PERIOD_PANEL_MAX_ROWS)

/**
 * Whether the panel keeps a key rather than letting it reach the grid behind the scrim.
 *
 * **A panel that has not got the highlight yet keeps every key there is.** The panel needs it
 * because its rows are placed by a `LazyColumn` and asked for focus from an effect that
 * retries. There is a moment after the panel appears when the grid behind it still holds the
 * highlight, and a key let through in that moment acts on the grid while the household is
 * looking at the panel.
 *
 * **The card menu has the same rule since ticket 19**, which is why this now defers to
 * [cardMenuKeepsKey] entirely rather than stating it again. The card menu was always open to
 * the same hole; it had simply never been seen to fire, because a hold costs several hundred
 * milliseconds and a plain `Column` is composed in the first frame.
 *
 * It is not a theory. On the emulator on 2026-09-20, `MENU` followed a beat later by `Down`
 * and the centre button **played a video from behind the open panel**.
 *
 * **This function states the rule. It is not where the rule is enforced.** Compose delivers a
 * preview key event only to the `onPreviewKeyEvent` modifiers on the ancestor chain of the
 * focused node, and while the grid still holds the highlight the panel is that card's sibling
 * rather than its ancestor, so the panel's own handler is never visited. The guard that does
 * the work is on the Source screen's outer `Box`, the nearest common ancestor of the panel and
 * the grid. The first version of this fix missed that and was dead code; `/code-review` caught
 * it, and no test here could have.
 *
 * Once a row has the highlight the rule is the card menu's own, unchanged, because the case is
 * then the same one: the cards behind the scrim are still focusable, and Compose's focus
 * search would find one from either end of the column.
 *
 * **`Up` and `Down` between the rows are no longer left to Compose either.** Build ticket 33
 * gave the panel [periodTarget], and the panel moves the highlight itself. So this function now
 * only answers for a key the panel has already declined to act on, and at an end of the list
 * the two say the same thing by different means: one consumes the key, the other simply found
 * no row to move to. That is on purpose, exactly as it is on the home screen.
 *
 * @param hasFocus whether any row of the panel has reported focus yet.
 */
fun periodPanelKeepsKey(
    move: MenuMove,
    focusedLine: Int,
    lineCount: Int,
    hasFocus: Boolean,
): Boolean = if (!hasFocus) true else cardMenuKeepsKey(move, focusedLine, lineCount)

/**
 * The row `Up` or `Down` moves to, or null when the key moves the highlight nowhere.
 *
 * **This is the panel's answer to the same question the card menu answers with [menuNeighbour],
 * and it has to be a different answer.** The card menu is a plain `Column`, so every line of it
 * exists and can be handed a `FocusRequester` to be named by. The Period panel is a
 * `LazyColumn`, because a Source spanning fifteen years has fifteen entries and a busy one has
 * up to 36, while only [PERIOD_PANEL_MAX_ROWS] of them are drawn. A row scrolled out of view is
 * **not composed**, so there is no requester to name it by, and naming an unattached requester
 * throws the moment the focus search resolves it. Build ticket 31 raised this and build ticket
 * 33 built it.
 *
 * So the panel does what the video grid does instead: work out the target index, scroll to it
 * if it is not already drawn, then ask it for focus. This is that first step, and it is the
 * same shape as [gridTarget] on purpose. Compose's focus search is then never consulted inside
 * the panel at all, so it can never find a video card behind the scrim.
 *
 * No row of this panel is ever dim, so there is nothing here to walk past.
 *
 * @param from the row the highlight is on.
 * @param count how many Periods the panel lists.
 */
fun periodTarget(move: MenuMove, from: Int, count: Int): Int? {
    if (count <= 0 || from < 0 || from >= count) return null
    return when (move) {
        // The panel is one column, so neither Left nor Right nor the channel rocker means
        // anything in it. [periodPanelKeepsKey] is what swallows them.
        MenuMove.SIDEWAYS -> null
        MenuMove.UP -> (from - 1).takeIf { it >= 0 }
        MenuMove.DOWN -> (from + 1).takeIf { it < count }
    }
}

/**
 * The row of the Period jump list to ask for the highlight after a refresh, or null to ask for
 * nothing at all.
 *
 * **Build ticket 51.** The Period jump list's rows are keyed by their place in the panel, for
 * the reason build ticket 33 wrote at `PeriodPanelOverlay`'s `lazyItemsIndexed` call. Keying by
 * place survives a renumbering and does not survive the list getting shorter. So a refresh that
 * empties the last Period bucket destroys the last row of the panel, and if the household was
 * standing on that row the highlight leaves the panel altogether. Measured on the
 * `googletv1080p` emulator on 2026-09-21: Compose hands it to the `Order` chip above the grid.
 *
 * The Source screen's key guard then keeps every key but `BACK`, which is right and is build
 * ticket 49's work. What was missing is anything that asks a surviving row for the highlight
 * back, so the household was left looking at an open panel that answered nothing but `BACK`.
 *
 * **This says which row, and it is a clamp rather than a guess.** The panel's own key handler
 * already clamps [focusedRow] the same way before working out a target, for the same reason.
 *
 * **It answers null whenever the refresh took nothing away from under the household, and that
 * is the important half.** A refresh is not something the household did, so it may not move the
 * highlight. The Source screen's refresh effect in `ui/SourceScreen.kt` deliberately asks for no
 * focus at all, and this must not undo that. The only case it answers a row for is [focusedRow]
 * being outside a list that still has rows, which means the **row** the household was standing
 * on is gone. A refresh that destroys no row leaves [focusedRow] inside the list and gets null.
 *
 * **It is the row and not the Period, and the two are not the same thing.** The rows are keyed
 * by place, so a refresh that drops one Period and adds another leaves the same rows standing
 * and the household's row now naming a different Period. Build ticket 33 chose that on purpose,
 * because the alternative destroyed the node holding the highlight on almost every row, and its
 * comment at `PeriodPanelOverlay`'s `lazyItemsIndexed` call says so. This rule says nothing
 * about that case and must not: nothing was destroyed, so nothing is stranded. Build ticket 53
 * is where it is written up. Found by `/code-review` on build ticket 51.
 *
 * **A panel with no rows left gets null too, and it really is reachable.** There is nothing to
 * land on, so no recovery can answer it: putting the highlight back needs a row and there is
 * none. It takes a refresh that empties a Source whose panel is already open, because
 * `openPeriods` in `ui/SourceScreen.kt` refuses to **open** the panel for a Source with no
 * Period. Found by `/code-review` on build ticket 51, which corrected an earlier version of this
 * paragraph that called the state unreachable. **Build ticket 52 answered it by closing the
 * panel instead**, which is [periodPanelCloseAfterRefresh], so the panel is gone by the time
 * this rule's null would have mattered. This rule is still the one that says nothing about it.
 *
 * @param focusedRow the row the panel is measuring its next key from, which is `focusedLine` in
 *   `PeriodPanelOverlay`. A row's own focus report and the panel's own `move` are the only two
 *   things that write it, so after a refresh destroyed that row it still names it.
 * @param rowCount how many Periods the panel lists **after** the refresh.
 */
fun periodRowAfterRefresh(focusedRow: Int, rowCount: Int): Int? = when {
    rowCount <= 0 -> null
    focusedRow >= 0 && focusedRow < rowCount -> null
    else -> focusedRow.coerceIn(0, rowCount - 1)
}

/**
 * The row to put at the top of the panel so that [target] is wholly drawn, or null for no scroll.
 *
 * **The point of it is that the list scrolls one row under the highlight, and not a screenful.**
 * `scrollToItem` always puts the row it is given at the top, so scrolling straight to the target
 * at the bottom edge would jump the list by a whole panel and lose the household's place in it.
 * Walking off the bottom scrolls by exactly one row, and so does walking off the top.
 *
 * It is arithmetic rather than a reading of the layout, and that is what makes it a JVM test.
 * It is allowed to be, because every row of this panel is the same height and the panel is
 * drawn exactly [periodPanelRows] rows tall, so the rows wholly on screen are always
 * [firstVisible] to `firstVisible + visibleRows - 1`.
 *
 * @param target the row the highlight is moving to.
 * @param firstVisible the row currently at the top of the panel.
 * @param visibleRows how many rows the panel draws, which is [periodPanelRows].
 */
fun periodPanelScrollTo(target: Int, firstVisible: Int, visibleRows: Int): Int? = when {
    visibleRows <= 0 -> null
    target < firstVisible -> target
    target > firstVisible + visibleRows - 1 -> target - visibleRows + 1
    else -> null
}

/**
 * Where the highlight belongs when the panel closes without a Period being chosen.
 *
 * The panel has three doors since ticket 19, and the answer is one number: the card to hand the
 * highlight back to, or [NO_SELECTION] for the chip row.
 *
 * - The `Period` chip. The household was on the chip row, so they go back to that chip.
 * - The card menu's `Jump to a Period`. The menu was opened by holding the centre button on a
 *   card, so that card is where the highlight goes.
 * - `MENU`, which the household TCL's firmware eats but other sets deliver. It opens the panel
 *   from wherever the highlight was, so the answer is whatever was selected.
 *
 * **The answer is worked out when the panel opens, never when `BACK` is pressed.** Between the
 * two, Compose hands the highlight to the first focusable on the screen, which is the `Order`
 * chip, and a chip taking the highlight clears the selection. That is the same trap `Refocus`
 * in `ui/SourceScreen.kt` exists for, and the card menu walks straight into it: closing the
 * menu is exactly what gives the chip the highlight.
 *
 * Nothing selected is answered as the chip row and never as card 0. A refocus never scrolls, so
 * a card it asks for may not be composed at all, and the screen would be left with no highlight
 * anywhere. See spec section 7.4 and `ui/SourceSelection.kt`.
 *
 * @param fromChip whether the `Period` chip was the door.
 * @param cardIndex the card the menu was opened on, or null when the door was not the card menu.
 * @param focused what was selected when the panel opened, which is [NO_SELECTION] on the chip row.
 */
fun periodPanelReturnsTo(fromChip: Boolean, cardIndex: Int?, focused: Int): Int = when {
    fromChip -> NO_SELECTION
    cardIndex != null -> cardIndex
    else -> focused
}

/**
 * The card the Period jump list really closes onto, or [NO_SELECTION] for the chip row.
 *
 * **Build ticket 52.** [periodPanelReturnsTo] answers this question when the panel **opens**,
 * and it has to, for the reason written on it. This is the same answer checked again against
 * the list the Source has **now**, at the moment the panel goes away, and the two differ on one
 * path only: a refresh landed while the panel was open and took that card out of the Source.
 *
 * **The Source screen's refresh effect cannot keep [returnTo] honest on its own, and that is
 * why this exists.** `refocusIndexAfterRefresh` in `refresh/RefreshMerge.kt` answers null for a
 * refresh that leaves the Source listing no video at all, because there is no card to move an
 * index onto. So [returnTo] goes on naming a card that is gone, and the close asks the screen's
 * retry ladder for it: four rungs, about 484 ms, every one of them failing, and the highlight
 * left wherever Compose happened to drop it when the panel left composition. The household then
 * has no ring on the screen that the app put there.
 *
 * [NO_SELECTION] is the answer for that case, and it means the `Period` chip, which is the chip
 * that opens this panel. It is drawn on every Source and it is never dim, while the video grid
 * is replaced by `This Source lists no videos.` and holds nothing focusable at all. So it is the
 * one place on that screen the highlight can be put on purpose.
 *
 * **Both doors out of the panel go through it**, `BACK` and the self-close of
 * [periodPanelCloseAfterRefresh], because they are the same close of the same panel and the
 * household cannot tell them apart by where the highlight lands.
 *
 * @param returnTo what [periodPanelReturnsTo] answered when the panel opened, kept re-anchored
 *   since by the Source screen's refresh effect.
 * @param videoCount how many videos the Source lists now.
 */
fun periodPanelClosesOnto(returnTo: Int, videoCount: Int): Int =
    if (returnTo >= 0 && returnTo < videoCount) returnTo else NO_SELECTION

/**
 * Whether a refresh has left the Period jump list with nothing in it, and where to close it to.
 *
 * **Build ticket 52.** `openPeriods` in `ui/SourceScreen.kt` refuses to **open** this panel for
 * a Source with no Period, and until this rule nothing closed an open one. A refresh that takes
 * the last Period away leaves the panel drawn over the grid with no row in it: no row can take
 * the highlight, [periodRowAfterRefresh] has none to ask for, and the household is left looking
 * at an empty box that answers nothing but `BACK`.
 *
 * **It answers a close rather than a recovery, and that is the whole difference from
 * [periodRowAfterRefresh].** Putting the highlight back needs a row of the panel. With no row
 * there is nothing to put it on, so the panel itself has to go.
 *
 * **Closing moves the highlight, and a refresh is not something the household did.** That is a
 * real cost and it is paid on purpose, the same way the card menu next door pays it: a panel
 * about something that no longer exists is worse than a highlight the household did not move.
 * The card menu's own close is `LaunchedEffect(menu, videos)` in `ui/SourceScreen.kt`.
 *
 * **It says nothing about a panel that still lists a Period**, so build ticket 51's recovery is
 * the only thing that answers a refresh which merely destroyed the row the household was
 * standing on, and a refresh that destroyed no row is left alone by both rules.
 *
 * **The two Sources it fires for are not the same Source**, and neither is a special case.
 * `periodsOf` in `data/Periods.kt` gives a Period only to a video that is listed and carries a
 * publish date, so a Source can lose its last Period either by losing its last video or by
 * having every video it still lists made private. The first leaves [videoCount] at zero and the
 * second does not, which is exactly the difference [periodPanelClosesOnto] reads.
 *
 * @param periodCount how many Periods the panel lists **after** the refresh.
 * @param returnTo what the panel would hand the highlight back to; see [periodPanelClosesOnto].
 * @param videoCount how many videos the Source lists now.
 * @return the card to close onto, or [NO_SELECTION] for the chip row, or null to leave the
 *   panel open and untouched.
 */
fun periodPanelCloseAfterRefresh(periodCount: Int, returnTo: Int, videoCount: Int): Int? =
    if (periodCount > 0) null else periodPanelClosesOnto(returnTo, videoCount)
