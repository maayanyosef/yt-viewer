package local.youtubetv.ui

/**
 * The names an instrumentation test finds a card by.
 *
 * They are here, in the app and not in the test, so the screen and the test cannot drift apart:
 * a tag is written once and read once, and renaming it is one edit. `Modifier.testTag` adds a
 * semantics property and nothing else, so a tag costs a card no measuring, no drawing and no
 * recomposition.
 *
 * **A tag is used only where finding the node by what it says would be ambiguous or brittle.**
 * A video's title is on its card **and** on the Source screen's focused-title line, so
 * `onNodeWithText(title)` finds two nodes and neither of them says which is which. The two
 * things the tests in `app/src/androidTest` ask of a card, which node holds the highlight and
 * where the card's own edges are, are both about the card and not about its text.
 *
 * Everything else is found by the words on the screen, which is what the household reads.
 *
 * See `docs/agents/testing.md`.
 */
object TestTags {

    /** One Source's card on the home rail. */
    fun railCard(sourceId: String): String = "railCard:$sourceId"

    /** One video's card in the Source screen's grid. */
    fun videoCard(videoId: String): String = "videoCard:$videoId"

    /**
     * The view count on one card's date line: the separator, the eye and the number. Build
     * ticket 59. The eye is drawn and has no text, so the count cannot be found by its words alone.
     */
    fun viewCount(videoId: String): String = "viewCount:$videoId"

    /**
     * One chip of the Source screen's row above the grid, named by the words on it.
     *
     * The chip is the node a closing panel used to lose the highlight to, so build ticket 39's
     * test has to ask which chip holds it. The words are on a `Text` **inside** the focusable
     * box rather than on the box itself, so `onNodeWithText` finds a node that never reports
     * focus, and the ring the household sees is drawn by the box.
     */
    fun chip(label: String): String = "chip:$label"

    /**
     * One button of the add screen or the rename screen, named by the words on it.
     *
     * The same case as [chip], and here for the same reason: the words are on a `Text` **inside**
     * the focusable box rather than on the box itself, so `onNodeWithText` finds a node that
     * never reports focus, and build ticket 48's tests have to ask which button holds the
     * highlight. Every `PanelButton` carries one.
     */
    fun pageButton(label: String): String = "pageButton:$label"

    /**
     * One row of the Period jump list, named by its place in the panel.
     *
     * The same case as [chip]: the row's words are on a `Text` inside the focusable `Row`, so
     * `onNodeWithText` finds a node that never reports focus. Build ticket 49's test has to ask
     * which row holds the highlight, and then ask again after a refresh has destroyed one.
     *
     * **It is named by the row's place and not by the Period's own year or month**, because the
     * place is what the panel's `LazyColumn` is keyed by and what its highlight record names, so
     * all three say the same thing about which row is which. `PeriodPanelOverlay` carries the
     * reason keying by place is right for this panel.
     */
    fun periodRow(row: Int): String = "$PERIOD_ROW_PREFIX$row"

    /**
     * What every [periodRow] tag starts with, for a test that asks only whether a node is one.
     *
     * **A test must not build the whole set of them from `PERIOD_PANEL_MAX_ROWS`.** That
     * constant is how many rows the panel is drawn **tall**, and `PeriodPanelOverlay` composes
     * one row per Period, so a Source cut into month Periods has up to `PERIOD_MONTHS_MAX` of
     * them, which is 36. A test bounded at 8 would call `periodRow(11)` something other than a
     * panel row and then fail at its own timeout for a screen that is behaving correctly. Found
     * by `/code-review` on build ticket 49.
     */
    const val PERIOD_ROW_PREFIX = "periodRow:"

    /**
     * One row of the add screen's results, named by [candidateKey].
     *
     * The same case again: a row's title is on a `Text` inside the focusable `Row`. It is named
     * by the same expression the `LazyColumn` is keyed by and the screen's highlight record
     * names the row by, so all three say the same thing about which row is which.
     */
    fun candidateRow(key: String): String = "candidateRow:$key"
}
