package local.youtubetv.ui

import local.youtubetv.data.Landing
import local.youtubetv.data.StoredVideo

/**
 * Which video the Source screen has selected, and where a chip sends the grid.
 *
 * The rules are here, with no Compose and no Android in them, so every one is a JVM unit test.
 * The screen only decides when to apply them.
 *
 * **The selection follows the highlight.** A video is selected while a card holds the
 * highlight, and nothing is selected while a chip holds it. Spec section 7.2, amended by
 * ticket 16. The chip row can only be reached by pressing `Up` from the first row of the grid,
 * so the card the screen was standing on when focus reached a chip is whatever the household
 * walked through on the way up, never a card they chose to keep. Keeping it was the app
 * preserving a choice nobody made, and it showed: the header line went on naming that card, and
 * a Direction flip turned the list around it.
 */

/**
 * Nothing is selected.
 *
 * It is not an index into the listed videos, so [selectedVideo] answers null for it and every
 * reader of the selection gets the same answer. `data/Periods.kt` reads it too: with no video
 * selected the Period jump list opens on the first Period.
 */
const val NO_SELECTION = -1

/** The video the highlight is on, or null when a chip holds the highlight. */
fun selectedVideo(videos: List<StoredVideo>, selection: Int): StoredVideo? =
    videos.getOrNull(selection)

/**
 * What is selected after the grid has been sent somewhere.
 *
 * @param takeFocus true when the household asked to be taken there, which is `Next unwatched`,
 *   `Jump to last played`, a chosen Period and `Down` from a chip. The card takes the highlight,
 *   so it becomes the selection.
 *
 *   False for the one move that leaves the highlight where it is: a Direction flip, which
 *   happens while the household is standing on the `Order` chip. The grid moves to the top of
 *   the new order and the chip keeps the highlight, so nothing is selected.
 */
fun selectionAfterLanding(index: Int, takeFocus: Boolean): Int =
    if (takeFocus) index else NO_SELECTION

/**
 * What is selected after a chip reported the highlight.
 *
 * **A chip taking the highlight means nothing at all while something is drawn over the grid.**
 * The selection follows the highlight only when the highlight follows the household, and with
 * the card menu or the Period jump list on screen it does not: Compose moves focus on its own
 * whenever the node holding it leaves composition, and the nearest focusable it then finds is a
 * chip on the row above the panel.
 *
 * It is not a theory, and ticket 19 is what made it reachable. The card menu's top level now
 * has **four** lines and a submenu has at most three, so choosing `Mark as unwatched`, which is
 * drawn fourth, always destroys the node holding the highlight. Focus landed on the `Order`
 * chip behind the scrim, the selection was cleared, and the menu then took focus back from its
 * own retry ladder, so nothing on screen said anything had gone wrong. Two presses of `BACK`
 * later, `askRefocus` asked for `NO_SELECTION`, the grid refused it, and the household was left
 * standing on a chip with the header line empty. Seen on the household television, three times
 * out of three, on 2026-09-20.
 *
 * @param overlayOpen whether anything is drawn over the grid. There are three such panels: the
 *   card menu, the Period jump list and, since build ticket 69, the Shuffle question. Ticket 25
 *   removed a fourth, the one-time mark-everything-watched offer, and took it out of this test
 *   at the same time. **A panel added later must be added here too**, because a stale name and
 *   a missing one are the same defect.
 */
fun selectionAfterChipFocus(selection: Int, overlayOpen: Boolean): Int =
    if (overlayOpen) selection else NO_SELECTION

/**
 * What a panel hands the highlight to on its way out, while it is still composed.
 *
 * **This is build ticket 39, and it is [selectionAfterChipFocus]'s defect closed from the other
 * end.** That rule stops the `Order` chip's stray focus gain clearing the selection; this one
 * stops the stray focus gain happening at all. A panel that is not holding the highlight when
 * it leaves composition gives Compose nothing to choose, so there is no frame to fix
 * afterwards.
 *
 * Measured on the household television on 2026-09-21, with a log on every focus change.
 * Closing the card menu with `BACK`: the `Order` chip took the highlight 52 ms later and the
 * card only got it back at 128 ms. The same close of the Period jump list: the chip at 53 ms,
 * the card at 131 ms. The 60 fps recording coded three frames for each close, and the middle
 * one shows the `Order` chip ringed, no card highlighted, and the focused-title line under the
 * Source name empty.
 */
sealed interface HandBack {

    /** The card at [index] in the listed videos. */
    data class Card(val index: Int) : HandBack

    /**
     * The `Period` chip.
     *
     * The chip row, and this chip on it, because the panel the household is leaving is the one
     * that chip opens. It is the only answer when nothing is selected: a hand-back never
     * scrolls, so a card is reachable only while the lazy grid is holding it, and card 0 is
     * not a card the household chose.
     */
    data object PeriodChip : HandBack
}

/**
 * Which of them, or null when nothing on the screen can be handed the highlight now.
 *
 * Null is not a failure. It means the card this panel is about is not composed, which a
 * hand-back cannot fix, because it must not scroll. The screen's own retry ladder is what
 * answers that case, and it is armed on every door whatever this returns: asking for the
 * highlight is not the same as getting it, and `FocusRequester.requestFocus` reports only
 * whether a node was attached. Build ticket 38 learned that on the home rail.
 *
 * @param returnTo the card the panel is about, or [NO_SELECTION] when the panel was opened
 *   from the chip row. Every door works it out when the panel **opens**; see
 *   `periodPanelReturnsTo` in `ui/PeriodPanel.kt` and the reason it cannot be read on the way
 *   out.
 * @param placed every index the lazy grid is holding a composed card for, which is exactly the
 *   grid's focus requester map: a card registers when it is composed and takes itself out when
 *   it is dropped. Naming a requester no node is attached to throws.
 */
fun panelHandBack(returnTo: Int, placed: Set<Int>): HandBack? = when {
    returnTo == NO_SELECTION -> HandBack.PeriodChip
    returnTo in placed -> HandBack.Card(returnTo)
    else -> null
}

/**
 * The first card of the grid, or null when the Source lists no videos.
 *
 * Two rules land here, and they are the same place on purpose. `Order` puts the grid at the
 * top of the new order as part of the flip, and `Down` from any chip takes the highlight to the
 * first card whatever the grid was scrolled to. The owner chose the simpler rule over returning to
 * the card he came from, knowing that walking up to read the chips costs him his place.
 */
fun firstCardIndex(count: Int): Int? = if (count > 0) 0 else null

/**
 * Whether a key down counts as the household touching the Source screen. Build ticket 55.
 *
 * Two things read the answer: the screen's record of whether a key has been pressed since the
 * grid landed, which decides whether a refresh may move the highlight, and the grid's retry
 * ladder, which stops at a key the household pressed.
 *
 * **A modifier key pressed alone does not count.** It moves nothing on any screen of this app,
 * and the household's remote has none. Something else does send one: the television keepalive
 * in `tools/tv/keepalive.sh` presses `KEYCODE_SHIFT_LEFT` every 240 seconds to hold off
 * standby. On 2026-10-03 one of those presses reached the TED Source screen during build
 * ticket 55's own verification run, and the screen then kept the highlight on the old landing
 * as if the household had moved it.
 *
 * @param keyCode Android's `KeyEvent` key code, as a plain number so this stays a JVM test.
 */
fun countsAsHouseholdKey(keyCode: Int): Boolean = keyCode !in MODIFIER_KEY_CODES

/**
 * Android's modifier keys: `SHIFT`, `ALT`, `CTRL` and `META` on either side, `SYM`, `FUNCTION`,
 * and the three lock keys. The same set `KeyEvent.isModifierKey` names, plus the locks, written
 * out so this file needs no Android import.
 */
private val MODIFIER_KEY_CODES = setOf(
    57, 58, // ALT_LEFT, ALT_RIGHT
    59, 60, // SHIFT_LEFT, SHIFT_RIGHT
    63, // SYM
    113, 114, // CTRL_LEFT, CTRL_RIGHT
    115, 116, 143, // CAPS_LOCK, SCROLL_LOCK, NUM_LOCK
    117, 118, // META_LEFT, META_RIGHT
    119, // FUNCTION
)

/**
 * The video Shuffle chose for the highlight when the Source screen opens, or null. Build ticket
 * 69, rule 7.
 *
 * Only a plain opening, [Landing.DEFAULT], lets Shuffle choose. `Open at Next unwatched`,
 * `Open at Last played` and `Open at newest` are the household asking for a place by name, so
 * they win, and OK on the card they land on is an ordinary play.
 *
 * @param opening where the screen landed.
 * @param shuffleNext where the run's next video sits, or null when Shuffle is off.
 */
fun shuffleArmedOnOpening(
    openAt: Landing,
    opening: Int,
    shuffleNext: Int?,
    videos: List<StoredVideo>,
): String? =
    if (openAt == Landing.DEFAULT && shuffleNext != null && opening == shuffleNext) {
        videos.getOrNull(opening)?.videoId
    } else {
        null
    }

/**
 * What is left of Shuffle's choice after one key goes down. Build ticket 69, rule 7.
 *
 * OK keeps it, because one press of OK on that card is the Shuffle play. Every other key the
 * household presses clears it: a card the household moves to and presses is an ordinary play,
 * and that holds even for the same card after a walk away and back. A modifier key alone, which
 * the television keepalive sends, is not the household and clears nothing. See
 * [countsAsHouseholdKey].
 */
fun shuffleArmedAfterKey(armed: String?, isSelect: Boolean, counts: Boolean): String? =
    if (isSelect || !counts) armed else null
