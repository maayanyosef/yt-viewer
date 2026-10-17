package local.youtubetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
// Aliased, because the lazy grid exports a function of the same name and this file uses both.
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import local.youtubetv.data.Direction
import local.youtubetv.data.Landing
import local.youtubetv.data.Period
import local.youtubetv.data.RangeSide
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchState
import local.youtubetv.data.lastPlayedIndex
import local.youtubetv.data.nextUnwatchedIndex
import local.youtubetv.data.openingLanding
import local.youtubetv.data.shuffleNextIndex
import local.youtubetv.data.periodIndexFor
import local.youtubetv.data.periodsOf
import local.youtubetv.refresh.HighlightAfterRefresh
import local.youtubetv.refresh.highlightAfterRefresh
import local.youtubetv.refresh.refocusIndexAfterRefresh

/**
 * The Source screen: one Source browsed as a four-column grid of video cards.
 *
 * Top to bottom:
 * - the Source name, with the quiet item count and `(updated ...)` beside it. The name is live:
 *   see [SourceScreenModel.name], which build ticket 29 added
 * - one line carrying the **full title of the focused card**, at a fixed height
 * - one chip row holding every control
 * - the videos
 *
 * The grid shows one flat chronological list wrapped into rows, and the D-pad walks that list
 * rather than the visual rows. See [gridTarget] and spec section 7.2.
 *
 * **A chip holding the highlight means no video is selected.** The header line goes empty, the
 * `Period` chip opens the jump list on the first Period, `Order` puts the grid at the top of the
 * new order, and `Down` goes to the first card. The rules are in `ui/SourceSelection.kt` and the
 * reason is written there. Spec section 7.2, amended by ticket 16.
 *
 * Every rule behind them is a pure function with a JVM test: `data/Landings.kt` decides where
 * a landing goes, `data/VideoOrder.kt` turns the list around, and `ui/SourceScreenText.kt`
 * holds the wording. What is left here is which of them to call.
 *
 * **Everything here is written to recompose as little as possible.** A key press on a
 * television has to land inside one frame, and the grid was missing that badly. What keeps it
 * cheap is written at each place it matters, but the two rules are: colours are looked up once
 * for the whole screen rather than once per card, and moving the highlight touches only the
 * two cards whose focus actually changed.
 */

/**
 * The height of the fade over the top edge of the grid.
 *
 * The grid is padded by this much as well, so a settled row starts below the fade and only a
 * stray sliver is ever painted over. Spec section 9.3, folded into this ticket.
 *
 * It was 34 dp while the grid had five columns. At four the cards are half as tall again and
 * two rows all but fill the screen, so this band of empty air was the difference between a
 * third row peeking and the grid looking as though it ended at the bottom of the second.
 * Nothing is lost by shortening it: with the pivot scroll turned off there is no sliver left
 * to hide, and the fade is only insurance.
 */
private val TOP_FADE = 16.dp

/**
 * One move of the highlight: which card takes focus, and whether the grid moves with it.
 *
 * [scroll] is false when the target card is already wholly on screen, so walking along a row
 * never makes the view jump.
 *
 * [takeFocus] is false for the one move that must not steal focus: a Direction flip, which
 * happens while the household is standing on the `Order` chip. The grid goes to the top of the
 * new order, but focus stays on the chip, so a second flip is one press rather than three. Spec
 * section 7.2, amended by ticket 16.
 *
 * [seq] counts moves, and exists only so that no two are ever equal. The effect that carries a
 * move out restarts when this value changes, and two equal values in a row would be one move
 * silently dropped, leaving the highlight where it was.
 */
private data class FocusMove(
    val seq: Long,
    val index: Int,
    val scroll: Boolean,
    val takeFocus: Boolean = true,
)

/**
 * One move of the highlight inside the Period jump list.
 *
 * The panel's own [FocusMove], and it is a separate type because the two carry different
 * things. A card is scrolled to by its row's first card, which the grid works out from the
 * index itself. A Period row is scrolled to by a row that is usually **not** the target: the
 * list scrolls one row under the highlight rather than jumping the target to the top. So the
 * row to scroll to travels with the move, and [scrollTo] is null when nothing needs to scroll.
 *
 * [seq] counts moves, for the same reason [FocusMove.seq] does.
 */
private data class PeriodMove(val seq: Long, val index: Int, val scrollTo: Int?)

/**
 * Where a chip asked the grid to go.
 *
 * It travels as one object rather than as a counter beside an index, for the same reason
 * [FocusMove] does: two values that must agree, kept apart, fall out of step when a second
 * press arrives before the first has been carried out.
 *
 * [takeFocus] is false for a Direction flip and true for `Next unwatched` and
 * `Jump to last played`, which are the household asking to be taken there.
 */
private data class GridLanding(val seq: Long, val index: Int, val takeFocus: Boolean)

/**
 * A request to put the highlight back on the card it was taken from, without scrolling.
 *
 * It is what the card menu, the Period jump list and a return from the player all send. The
 * card travels **with** the request rather than being read again when it is carried out, for
 * the same reason [FocusMove.scroll] does: the two fall out of step in between. Here the step
 * is Compose handing the highlight to the first chip the moment the panel above the grid goes
 * away, which clears the selection.
 *
 * [seq] counts requests, and exists only so that no two are ever equal, exactly as
 * [FocusMove.seq] does.
 */
private data class Refocus(val seq: Long, val index: Int)

/**
 * One short message at the bottom of the screen, and how many have been said before it.
 *
 * [seq] is there for the same reason [FocusMove.seq] is. The effect that takes the message
 * away again is keyed on what is being shown, so two presses saying the **same** words would
 * be one message with one timer, and the second would vanish almost at once.
 */
private data class Toast(val seq: Long, val message: String)

@Composable
fun SourceScreen(
    sourceId: String,
    // What the header calls this Source. It is `SourceScreenModel.name` collected by the
    // Activity, and **not** a name read once at navigation time, so a name that changes while
    // this screen is open reaches the header without the household going back to the rail.
    // The fallback to the raw playlist id is `sourceNameOrId`, the rail's own rule. Ticket 29.
    sourceName: String,
    state: SourceScreenState,
    thumbnails: ThumbnailLoader,
    // Not a suspend function, and not run on a scope this composition owns. Writing an entry
    // to the household's log must not be cancelled because the screen went away mid-write, so
    // the Activity launches it on its own lifecycle scope.
    onMarkJustThis: (StoredVideo, Boolean) -> Unit = { _, _ -> },
    // A Bulk mark: the chosen video and every listed video older, or newer, in one press. It
    // writes one log entry however many videos it covers, and it is launched on the Activity's
    // scope for the same reason a single mark is.
    onMarkRange: (StoredVideo, RangeSide, Boolean) -> Unit = { _, _, _ -> },
    // The player is not a screen this one navigates to. It is drawn over the whole app by the
    // Activity, because the Activity is what takes every key while a video is on. Spec 8.5.
    //
    // The second flag is play from the start. The third is whether Shuffle chose this play:
    // true only for one press of OK on the card Shuffle put the highlight on. Build ticket 69.
    onPlay: (StoredVideo, Boolean, Boolean) -> Unit = { _, _, _ -> },
    // The `Order` chip. It writes the Source's row, so it is launched on the Activity's scope
    // and off the main thread, exactly like a mark.
    onFlipDirection: () -> Unit = {},
    // The `Autoplay` chip. It writes the Source's row, so it is launched on the Activity's
    // scope and off the main thread, exactly like a flip.
    onToggleAutoplay: () -> Unit = {},
    // The `Shuffle` chip, and `Start a new run` behind its long press. Both write the Source's
    // row, so both are launched on the Activity's scope, exactly like `Autoplay`. Build
    // ticket 69.
    onToggleShuffle: () -> Unit = {},
    onStartNewShuffleRun: () -> Unit = {},
    // One logcat line per chip press. Direction, Next unwatched and Last played are rules and
    // not pictures, so this is what proves them on the television.
    onLanding: (String) -> Unit = {},
    // Bumped by the Activity when the household comes back from the player. The grid lost its
    // highlight to whatever had focus while the video was on, so it asks for it again on the
    // card it left, which is the video just played.
    returnedFromPlayer: Long = 0L,
    // Where this Source opens, chosen on the home rail's long-press menu. Spec section 7.2
    // makes Next unwatched the default, which is also what a plain press on a rail card means.
    //
    // It is `openAt` and not `landing`, because this screen already has a `landing`: the state
    // holding where a **chip** has asked the grid to go. The two are different things and one
    // name for both would be a bug waiting for whoever reads this next.
    openAt: Landing = Landing.DEFAULT,
    // Whether a video is on. The player is drawn over this screen by the Activity rather than
    // navigated to, so this screen stays composed underneath it and still sees keys while the
    // player is loading and has not taken focus yet. Without this, `MENU` in that gap opens
    // the Period jump list behind the video, and the household's next `BACK` closes a panel
    // they cannot see instead of leaving the player.
    playing: Boolean = false,
    // Which video the player asked the grid to land on when it handed back, or null to put
    // the highlight back on the card the household left. A finished video with autoplay-next
    // off lands on the **next** video in Direction, and that is a landing, so it takes focus.
    // Spec section 8.6, and ticket 06's note about `FocusMove.takeFocus`.
    landOnVideoId: String? = null,
) {
    val ink = screenInk()
    // The message carries a count, so that showing the **same** message twice is still two
    // toasts. Without it the effect below, which is keyed on what it is showing, never
    // restarts: press `Next unwatched` on a fully watched Source, wait two and a half
    // seconds, press it again, and the second toast is on screen for a tenth of a second.
    // Found by `/code-review`.
    var toasts by remember { mutableLongStateOf(0L) }
    var toast by remember { mutableStateOf<Toast?>(null) }

    /** Says it, and starts its two and a half seconds again even if it is the same words. */
    fun say(message: String) {
        toasts++
        toast = Toast(seq = toasts, message = message)
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_600)
            toast = null
        }
    }

    val ready = state as? SourceScreenState.Ready
    val videos = ready?.videos.orEmpty()
    val watches = ready?.watches.orEmpty()
    val direction = ready?.direction ?: Direction.NEWEST_FIRST

    // Shuffle. Build ticket 69. The run's next video, as an index into the grid, or null when
    // Shuffle is off or has nothing to play. It is where the highlight rests whenever Shuffle
    // is what put it there: see [shuffleArmed].
    val shuffleOn = ready?.shuffle == true
    // Remembered, because it hashes every playable video of the Source, and a toast or a menu
    // recomposes this screen without changing the list or the run. Found by `/code-review`.
    val shuffleRun = ready?.shuffleRun
    val shuffleNext = remember(videos, shuffleRun, shuffleOn) {
        if (shuffleOn) shuffleNextIndex(videos, shuffleRun) else null
    }

    // Where `Jump to last played` would go, and null when it has nowhere to go at all. Null is
    // what greys the chip out: no `played` entry carrying a position, a range that cleared
    // Last played, or a video the grid no longer shows. See `data/Landings.kt`.
    val lastPlayedTarget = lastPlayedIndex(videos, ready?.lastPlayedVideoId)

    // Keyed on the Source, never on the number of videos. Two Sources can list the same count,
    // and then the highlight would stay on an index that names a different video. A count is
    // also not stable within one Source: a refresh changes it, and resetting the highlight in
    // the middle of browsing would throw the household back to the first card in the list.
    //
    // It is passed down as the state object rather than as an Int, so that reading it does not
    // make this whole screen recompose every time the highlight moves. Only the one line that
    // shows the focused title reads the value.
    //
    // **It is `NO_SELECTION` whenever a chip holds the highlight**, and not only before the
    // first card has taken focus. See `ui/SourceSelection.kt`, which holds that rule and the
    // reason for it. What still reads it while a card has the highlight is unchanged: the
    // header line, the play action, the card menu and the hamburger's Period.
    val focusedIndex = remember(sourceId) { mutableIntStateOf(NO_SELECTION) }

    // The card menu, and the request that puts focus back on the card when it closes. Both
    // are keyed on the Source for the same reason the highlight is: an index means a
    // different video in a different Source.
    var menu by remember(sourceId) { mutableStateOf<CardMenu>(CardMenu.Closed) }
    var refocusRequests by remember(sourceId) { mutableLongStateOf(0L) }
    var refocus by remember(sourceId) { mutableStateOf<Refocus?>(null) }

    /**
     * The focus requester of every grid card composed right now, keyed by its index.
     *
     * **It lives here and not inside [VideoGrid] because the panels need it**, and they need it
     * at a moment only this function has: the instant before a panel is taken out of
     * composition. See [handHighlightBack]. `VideoGrid` still fills it, one card at a time.
     * Build ticket 39, and the same move build ticket 38 made on the home rail.
     */
    val gridRequesters = remember(sourceId) { mutableMapOf<Int, FocusRequester>() }

    /**
     * Something that covered the grid has gone, so the highlight goes back on its card.
     *
     * **It is the ladder and no longer the first move.** [handHighlightBack] hands the
     * highlight over while the panel is still composed, and this asks again, over four frames,
     * for the one card a hand-back cannot reach: one the lazy grid has not placed. Build
     * ticket 39 put it in that order; before it, this was the whole of the close and the
     * `Order` chip held the highlight for about 80 ms of every one.
     *
     * **It is the same net as the home rail's, since build ticket 47.** The ladder it arms, in
     * `VideoGrid`, used to stop at the first `requestFocus` that did not throw, which says a
     * node was attached and nothing at all about where the highlight went. It now asks the grid
     * which card reports holding the highlight, which is `VideoGrid`'s own `highlightOn`, and
     * keeps asking until the answer is the card it named. Build ticket 39 wrote this weakness
     * up and correctly refused to fix another ticket's code.
     *
     * **The card is read here, at the moment the request is made, and never in the effect that
     * carries it out.** Between the two, Compose gives the highlight to the first focusable on
     * the screen, which is the `Order` chip, and a chip taking the highlight clears the
     * selection. An effect that read the selection then would ask for card 0, which is not
     * composed when the grid is scrolled anywhere else, and the retry ladder would give up with
     * the highlight left on a chip and the header line empty. Seen on the television: `MENU`
     * from a card in 2017, then `BACK`.
     *
     * @param index the card to go back to. It defaults to whatever is selected right now, which
     *   is right for everything that closes straight back onto the grid. The Period jump list
     *   passes its own, because by the time it closes the selection has already been cleared by
     *   the chip: the card menu's `Jump to a Period` closes the menu on the way in.
     */
    fun askRefocus(index: Int = focusedIndex.intValue) {
        refocusRequests++
        refocus = Refocus(seq = refocusRequests, index = index)
    }

    // Where a chip has asked the grid to go, and the counter that keeps two equal asks apart.
    var landings by remember(sourceId) { mutableLongStateOf(0L) }
    var landing by remember(sourceId) { mutableStateOf<GridLanding?>(null) }

    // The Period jump list: whether it is open, and which entry it opened highlighted on.
    //
    // The Periods themselves are **derived, never cached**. Keying on the list and the
    // Direction is what keeps them honest across a flip: a flipped Source hands in a
    // turned-around list, so which Period comes first turns around with it. A bucket list
    // remembered past a flip would open the panel on the wrong end of the Source.
    val periods = remember(videos, direction) { periodsOf(videos) }
    var periodsOpen by remember(sourceId) { mutableStateOf(false) }
    var periodsOpenOn by remember(sourceId) { mutableIntStateOf(0) }

    // Where `BACK` puts the highlight when the panel goes away again: the card to hand it back
    // to, or `NO_SELECTION` for the chip row.
    //
    // **It is worked out when the panel opens, never when `BACK` is pressed.** The panel has
    // three doors since ticket 19, and one of them, the card menu's `Jump to a Period`, closes
    // the card menu on its way in. That hands the highlight to the first chip, which clears the
    // selection, so reading the selection later would answer the chip row for a household that
    // came from a card. The rule itself is `periodPanelReturnsTo` in `ui/PeriodPanel.kt`, and
    // it is the same trap [Refocus] exists for.
    var periodsReturnTo by remember(sourceId) { mutableIntStateOf(NO_SELECTION) }

    // Whether a row of the open panel has the highlight yet.
    //
    // **It lives here, on the screen, and not inside the panel.** Compose delivers a preview
    // key event only to the `onPreviewKeyEvent` modifiers on the **ancestor chain of the
    // focused node**. While the grid still holds the highlight, the panel is a *sibling* of
    // the grid under the outer `Box`, not an ancestor of it, so a handler inside the panel is
    // never visited and cannot keep anything. The outer `Box` is the ancestor of both, so the
    // guard has to be there. Found by `/code-review`, which caught that the first version of
    // this fix was dead code.
    //
    // It is read from key handlers and never while composing, so nothing recomposes on it.
    val periodPanelHasFocus = remember(sourceId) { mutableStateOf(false) }

    /**
     * Whether a line of the open card menu has the highlight yet.
     *
     * The same hole, the same fix and the same placement as [periodPanelHasFocus]. It closes
     * the leak ticket 09 found in `cardMenuKeepsKey` and did not own: until a line of the menu
     * takes focus, the highlight is still on a card behind the scrim, and every key the menu
     * does not keep is acted on by the grid. A `Down` scrolls the grid the household cannot
     * see, and the centre button plays a video from behind the menu.
     *
     * **It is cleared on every change of level, not only when the menu opens.** The top level
     * has four lines and a submenu has at most three, so choosing `Mark as unwatched`, which is
     * drawn fourth, always removes the very node that holds the highlight. Focus leaves the
     * menu, and the window this guards is open again until the retry ladder lands. Found by
     * `/code-review`, which also caught that a wholly dim submenu never lands at all.
     *
     * What sets it true is the line reporting focus **and** the retry ladder's `requestFocus`
     * coming back successful. The second is not belt and braces: Compose reuses the line nodes
     * between the two levels, so a node that already has the highlight reports no change, and
     * on that path nothing else would ever set this true again.
     */
    val cardMenuHasFocus = remember(sourceId) { mutableStateOf(false) }

    val periodChipFocus = remember { FocusRequester() }
    var periodChipRefocus by remember(sourceId) { mutableLongStateOf(0L) }

    /**
     * Which chip of the row above the grid reports holding the highlight.
     *
     * **Only the `Period` chip's own focus report writes it, and the retry ladder below reads
     * it and nothing else.** Build ticket 47. The ladder used to exit on
     * `runCatching { periodChipFocus.requestFocus() }.isSuccess`, which says only that the call
     * did not throw, so its first rung read success whatever the ask had done and the 60, 120
     * and 240 ms rungs never ran.
     *
     * **It names the chip rather than holding a bare flag**, so that it keeps the shape every
     * other record on this screen has, and so that a second chip given a requester later cannot
     * make this one read as held. `PERIOD_CHIP` is the only chip that reports into it today,
     * because it is the only chip that is ever asked for the highlight: `BACK` out of the
     * Period jump list is the one door that puts the highlight back on a chip by name.
     */
    val chipHighlight = remember(sourceId) { HighlightRecord<String>() }

    // The chip may only just have been drawn again with the panel gone, so this retries the
    // same way every other focus move on this screen does.
    LaunchedEffect(periodChipRefocus) {
        if (periodChipRefocus == 0L) return@LaunchedEffect
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { periodChipFocus.requestFocus() } },
            holds = { chipHighlight.holds(PERIOD_CHIP) },
        )
    }

    // Whether `Order` has been pressed at all in this Source.
    //
    // The effect below is keyed on the Direction, which also arrives once when the Source
    // finishes loading and its stored Direction replaces the default. Without this flag an
    // oldest-first Source would be scrolled to its first card on opening instead of to its
    // Next unwatched.
    //
    // **It is never cleared**, and nothing goes stale in it, because the flip no longer keeps
    // anything: every flip sends the grid to the same place, which is the top. That is what
    // makes two quick presses of `Order` safe. The value it replaced was the video to keep,
    // which the second press could find already gone. Found by `/code-review` on ticket 06.
    var orderPressed by remember(sourceId) { mutableStateOf(false) }

    /**
     * The video Shuffle put the highlight on, or null. Build ticket 69, rule 7.
     *
     * **One press of OK on this card is a Shuffle play**, and a press on any other card is an
     * ordinary one. So it is cleared by the household's very next key that is not OK: a card
     * the household moves to and presses is an ordinary play, even when it is this same card
     * after a walk away and back. See `shuffleArmedAfterKey` in `ui/SourceSelection.kt`.
     *
     * Shuffle puts the highlight here in four places, and each of them sets this: a plain
     * opening of the Source, turning the chip on, playback handing back on the run's next video
     * (a finished video with autoplay-next off, and the two-hour idle stop), and a refresh that
     * re-lands an untouched opening.
     */
    var shuffleArmed by remember(sourceId) { mutableStateOf<String?>(null) }

    // The long press on the `Shuffle` chip asks a question. Build ticket 69, rule 10. These
    // three are the same shape as the card menu's: whether it is open, whether a line of it
    // holds the highlight yet, and which of its lines does.
    var shuffleQuestionOpen by remember(sourceId) { mutableStateOf(false) }
    val shuffleQuestionHasFocus = remember(sourceId) { mutableStateOf(false) }
    // Whether the `Shuffle` chip holds the highlight, which is what puts the hint on the line
    // under the Source's name. Rule 11.
    val shuffleChipFocused = remember(sourceId) { mutableStateOf(false) }
    val shuffleChipFocus = remember { FocusRequester() }
    var shuffleChipRefocus by remember(sourceId) { mutableLongStateOf(0L) }
    // The chip was pressed while Shuffle was off, so when the Source's row says Shuffle is on
    // the highlight moves to the run's next video. Rule 7. A flag and not a direct move,
    // because the row is written on another thread and the run may only exist after it.
    var shuffleTurnedOn by remember(sourceId) { mutableStateOf(false) }

    // The question closed, so the highlight goes back to the chip that asked it. The same
    // ladder the `Period` chip uses, over the same record.
    LaunchedEffect(shuffleChipRefocus) {
        if (shuffleChipRefocus == 0L) return@LaunchedEffect
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { shuffleChipFocus.requestFocus() } },
            holds = { chipHighlight.holds(SHUFFLE_CHIP) },
        )
    }

    /** Closes the question and gives the highlight back to the `Shuffle` chip. */
    fun closeShuffleQuestion() {
        // Asked while the question is still composed, for the reason [handHighlightBack] gives:
        // a panel that is not holding the highlight when it goes gives Compose nothing to choose.
        runCatching { shuffleChipFocus.requestFocus() }
        shuffleQuestionOpen = false
        shuffleChipRefocus++
    }

    fun land(index: Int, takeFocus: Boolean) {
        landings++
        landing = GridLanding(seq = landings, index = index, takeFocus = takeFocus)
        // Said now, and not when the card reports focus. The next key press is measured from
        // here. A Direction flip does not move focus at all, so it selects nothing: the
        // highlight is still on the `Order` chip. See `ui/SourceSelection.kt`.
        focusedIndex.intValue = selectionAfterLanding(index, takeFocus)
    }

    /** Puts the highlight on the Shuffle run's next video, as Shuffle's own choice. Rule 7. */
    fun landOnShuffleNext(index: Int) {
        shuffleArmed = videos.getOrNull(index)?.videoId
        land(index, takeFocus = true)
    }

    // The chip was turned on and the row now says so, so the highlight goes to the run's next
    // video. Rule 7. Keyed on the run as well, because a Source turned on for the first time
    // has no run until the same write lands.
    LaunchedEffect(shuffleOn, shuffleNext) {
        if (!shuffleTurnedOn || !shuffleOn) return@LaunchedEffect
        shuffleTurnedOn = false
        shuffleNext?.let { landOnShuffleNext(it) }
    }

    // The list turned around, so the grid goes to the top of the new order. Keyed on the
    // Direction rather than on the list: the videos change on a mark as well, and only a flip
    // should move the grid.
    //
    // **The scroll happens now, not at the next key press.** The household presses `Order` and
    // looks at the screen without touching anything, and it is showing the first cards of the
    // new order. The owner was explicit about that on 2026-09-20.
    LaunchedEffect(direction) {
        if (!orderPressed) return@LaunchedEffect
        val top = firstCardIndex(videos.size) ?: return@LaunchedEffect
        // Focus is on the `Order` chip and stays there. Only the grid moves.
        land(top, takeFocus = false)
    }

    // The list the grid was last drawing, so a refresh can be told which video the highlight
    // was on. It is written from an effect and never while composing.
    val drawn = remember(sourceId) { mutableStateOf(videos to direction) }

    /**
     * Whether the household has pressed any key since the grid made its opening landing. Build
     * ticket 55. The grid clears it when it first appears, beside `VideoGrid` below.
     *
     * While it is false, nobody has chosen the video the highlight is on: the screen chose it,
     * from the list it had when it landed. So a refresh that lands then moves the highlight to
     * the landing the screen would choose now. See `highlightAfterRefresh` in
     * `refresh/RefreshMerge.kt`.
     *
     * **The outer `Box` below writes it, on the key down of every key, before anything else
     * looks at the key.** That `Box` is the ancestor of every focusable node on this screen, so
     * it sees every key that reaches the screen at all, whichever node holds the highlight and
     * whether or not something later consumes the key. A key up is not counted, because the
     * key up of the press that opened this Source can arrive here. A modifier key alone is not
     * counted either: see `countsAsHouseholdKey` in `ui/SourceSelection.kt`, which says how
     * the television keepalive's `SHIFT` press defeated the first verification run.
     *
     * Nothing composes it, so writing it recomposes nothing. Only the refresh effect reads it.
     */
    val keyPressed = remember(sourceId) { mutableStateOf(false) }

    // A refresh arrived while this Source was on screen. Spec section 5.3: the change is
    // applied at once and the focused video keeps focus, so the list moves around it.
    //
    // **The one exception is a screen nobody has touched yet.** Build ticket 55. When no key
    // has been pressed since the grid landed, the highlight goes to the landing the screen
    // would choose now, and that **does** scroll and take focus, exactly as opening does.
    // The owner's report of 2026-10-03 is the reason: the household opened a Source a few seconds
    // after launch, the screen landed on the old Next unwatched, and the new video arrived at
    // the top of the grid out of sight. With no key pressed, no overlay can be open and the
    // highlight is not on a chip by the household's choice, so nothing below can be in the way.
    //
    // **Nothing here asks for focus, and nothing scrolls.** The grid's items are keyed by video
    // id, so a card that moves down a place keeps its composition and keeps the highlight by
    // itself: Compose moves the node rather than rebuilding it. What does **not** follow on its
    // own is this screen's own idea of where the highlight is, which is an index, and which the
    // header line, the play action and the card menu all read. Re-anchoring that index is the
    // whole of what this effect does.
    //
    // An earlier version bumped `refocusRequests` here as well, which asks a grid card for
    // focus. That pulled the household into the grid when a background refresh landed while
    // the card menu or the Period panel was open, or while they were standing on a chip. A
    // refresh is not something the household did, so it may not move the highlight at all.
    // Found by `/code-review`.
    LaunchedEffect(videos, direction) {
        val (before, beforeDirection) = drawn.value
        drawn.value = videos to direction
        // A flip changes the list too, and has its own effect above, which does move the grid
        // because the household asked it to.
        if (beforeDirection != direction) return@LaunchedEffect
        // The open Period panel holds the card it will hand the highlight back to, and it holds
        // it as an **index**, exactly as the card menu holds one. So it moves with the list for
        // the same reason. Without this, a refresh landing while the panel is up sends `BACK`
        // to the wrong video, or to none at all when the index is now past the end. It is done
        // before the highlight's own move below, because that one can return early. Found by
        // `/code-review`.
        if (periodsOpen && periodsReturnTo != NO_SELECTION) {
            refocusIndexAfterRefresh(before, videos, periodsReturnTo)?.let { periodsReturnTo = it }
        }
        val decision = highlightAfterRefresh(
            before = before,
            after = videos,
            focusedIndex = focusedIndex.intValue,
            keyPressed = keyPressed.value,
            // Worked out only while it can matter. Once a key is pressed the rule ignores it, and
            // on a Source of thousands of videos it is a scan of the whole list on every mark.
            landingAfter = if (keyPressed.value) {
                NO_SELECTION
            } else {
                openingLanding(openAt, videos, watches, direction, ready?.lastPlayedVideoId, shuffleNext)
            },
        ) ?: return@LaunchedEffect
        val to = when (decision) {
            is HighlightAfterRefresh.Land -> {
                onLanding(refreshLandingLogLine(videos[decision.index].videoId, decision.index))
                // The same opening worked out again, so Shuffle is what chose it whenever it
                // did the first time. Build ticket 69.
                shuffleArmed = shuffleArmedOnOpening(openAt, decision.index, shuffleNext, videos)
                land(decision.index, takeFocus = true)
                return@LaunchedEffect
            }
            is HighlightAfterRefresh.Follow -> decision.index
        }
        focusedIndex.intValue = to
        // The card menu was opened on a card and holds that card's **index**, so it moves with
        // it. Without this the menu would mark one card and act on another.
        if (menu.isOpen) menu = menu.movedTo(to)
    }

    /**
     * Gives the highlight away **before** the panel holding it leaves composition.
     *
     * **This is build ticket 39, and it works by removing the gap rather than by racing it.**
     * Compose moves focus by itself whenever the node holding it leaves composition, and the
     * node it chooses is the nearest focusable it can find, which on this screen is the `Order`
     * chip above the grid. Ticket 19 found that chip taking the highlight; this closes it.
     * Measured on the household television on 2026-09-21: `BACK` from the card menu, the chip
     * had the highlight 52 ms later, and the card got it back at 128 ms. A 60 fps recording
     * coded three frames for the close, and the middle one shows the chip ringed with no card
     * highlighted.
     *
     * A panel that is not holding the highlight when it goes gives Compose nothing to choose.
     * Asking while the scrim is still up is safe in both ways that matter: every key but `BACK`
     * is still kept by the guard on the outer `Box`, and the grid's [NoScrollOnFocus] means a
     * card that takes the highlight does not move the grid.
     *
     * **It promises nothing, and no caller may treat it as done.**
     * `FocusRequester.requestFocus` throws when no node is attached and says nothing at all
     * about whether the highlight moved, so this is an ask and not an answer. Every door arms
     * the screen's own ladder as well, whatever this does, and the ladder asks again four
     * times. Build ticket 38's `/code-review` found that hole on the home rail.
     *
     * @param returnTo the card the panel is about, or [NO_SELECTION] for the chip row.
     *   [panelHandBack] is the rule that turns it into a target, and it answers null for a card
     *   the lazy grid is not holding, which is the one case only the ladder can serve.
     */
    fun handHighlightBack(returnTo: Int) {
        when (val target = panelHandBack(returnTo, gridRequesters.keys)) {
            is HandBack.Card -> {
                // The app chose this card, so the screen's own selection is written to match
                // rather than waiting for Compose to report the card's focus gain. They agree
                // on every door but one: the Period jump list opened from the card menu, where
                // the selection was cleared by the chip on the way in and the panel is the only
                // thing that still knows which card the household came from.
                focusedIndex.intValue = target.index
                gridRequesters[target.index]?.let { runCatching { it.requestFocus() } }
            }
            HandBack.PeriodChip -> runCatching { periodChipFocus.requestFocus() }
            null -> Unit
        }
    }

    /**
     * Moves the card menu to another level, with no line of the new level holding the
     * highlight yet. Every open, every submenu and every `BACK` between levels goes through it.
     *
     * A refresh does not: `movedTo` renumbers the same lines, and the highlight never leaves.
     *
     * **The card is handed the highlight first, for the same reason a close does it.** The top
     * level draws four lines and a submenu at most three, so choosing `Mark as unwatched`, the
     * fourth, destroys the very node holding the highlight while the panel stays composed. It
     * is the move `HomeScreen.askFirst` makes between two home panels. Found by `/code-review`
     * on build ticket 39.
     *
     * **It is removing a trap and not fixing a defect, and that was measured, not assumed.**
     * Both builds were recorded on the household television on 2026-09-21, opening that very
     * submenu and coming back: each level change codes two frames either way, and the `Order`
     * chip's own pixels are **identical** in them, YAVG 32.4015 going down and 34.786 coming
     * back on both. So Compose never showed the chip here, unlike on a close, where it showed
     * it for 78 ms. The panel's own ladder is quick enough, and this makes it not a race at
     * all. Ticket 33 made the same kind of change to this panel for the same kind of reason.
     *
     * On the open, where the card already holds the highlight, it asks the card that has it and
     * nothing happens.
     */
    fun setMenu(next: CardMenu) {
        handHighlightBack(next.videoIndex ?: focusedIndex.intValue)
        cardMenuHasFocus.value = false
        menu = next
    }

    fun closeMenu() {
        // The highlight goes back to the card first, while the menu is still composed, which
        // is what stops Compose handing it to the `Order` chip. The ladder is still armed,
        // because an ask is not an answer.
        //
        // **The card is the menu's own card and not the selection.** They are the same on
        // every ordinary close, and they come apart on two paths: a refresh that drops the
        // video re-anchors neither, and a Direction flip clears the selection while the menu
        // is up. `menu.videoIndex` is what the refresh effect keeps re-anchored, so it is the
        // one that still names the right card. Found by `/code-review`.
        val card = menu.videoIndex ?: focusedIndex.intValue
        handHighlightBack(card)
        menu = CardMenu.Closed
        askRefocus(card)
    }

    /**
     * Opens the Period jump list, from any of its three doors.
     *
     * - The `Period` chip, which is on the chip row of every Source.
     * - The card menu's `Jump to a Period`, added by ticket 19 because the household remote's
     *   hamburger cannot reach this app at all. Spec sections 7.3 and 8.5.
     * - `MENU`, which works on any set whose firmware does not eat the key, and not on the TCL.
     *
     * The entry it opens on is worked out **here**, not while composing. Reading the
     * highlight's index during composition would make this whole screen recompose on every
     * key press of the grid, which is what `focusedIndex` exists to avoid.
     *
     * **The panel opens on the Period holding the video it will hand the highlight back to**,
     * which is one rule covering all three doors. From the `Period` chip nothing is selected,
     * and `periodIndexFor` answers the first Period in the current Direction for that. From a
     * card, by either of the other two doors, a video really is focused and the panel opens on
     * its Period. Spec section 7.4, amended by ticket 16 and again by ticket 19.
     *
     * @param fromChip true when the `Period` chip was the door.
     * @param cardIndex the card the menu was opened on, when the card menu was the door. It is
     *   passed rather than read, because opening this panel closes the card menu, and that
     *   hands the highlight to the first chip and clears the selection.
     * @return false when the Source has no Period to jump to, so the caller can put the
     *   highlight back itself. Nothing is drawn over the grid in that case, and the household
     *   is told why instead.
     */
    fun openPeriods(fromChip: Boolean, cardIndex: Int? = null): Boolean {
        if (periods.isEmpty()) {
            say(NO_PERIODS)
            return false
        }
        onLanding(periodListLogLine(periods.first().unit, periods.size, videos.size))
        val returnTo = periodPanelReturnsTo(fromChip, cardIndex, focusedIndex.intValue)
        periodsReturnTo = returnTo
        periodsOpenOn = periodIndexFor(periods, returnTo)
        // No row has the highlight yet, and will not have until the `LazyColumn` has placed
        // one. Every key belongs to this screen until then.
        periodPanelHasFocus.value = false
        periodsOpen = true
        return true
    }

    /**
     * Closes the Period jump list without a Period having been chosen, and puts the highlight
     * somewhere the household can use.
     *
     * **Both doors out of this panel that are not a chosen Period go through here**, `BACK` and
     * the self-close a refresh forces, so the household cannot tell them apart by where the
     * highlight lands. Build ticket 52 pulled the body out of the `BackHandler` below to make
     * that true; before it, `BACK` was the only close and wrote these three lines itself.
     *
     * **No landing is sent.** A landing always scrolls, so closing the panel through one would
     * move the grid the household never asked to move. `refocusRequests` is the counter for
     * "something covered the grid, took focus, and went away", which is this case and the card
     * menu's, and it puts the highlight back without scrolling at all. Spec section 7.4 asks
     * that `BACK` leave the grid exactly where it was.
     *
     * @param returnTo the card to go back to, or [NO_SELECTION] for the `Period` chip on the
     *   chip row. **Every caller works it out with `periodPanelClosesOnto`** in
     *   `ui/PeriodPanel.kt` rather than passing `periodsReturnTo` straight in, because that
     *   field can name a card a refresh has taken out of the Source. `NO_SELECTION` is not a
     *   fallback there: sending the household into the grid would drop the highlight on a card
     *   they never chose, and a refocus never scrolls, so the card it asks for may not be
     *   composed at all and the screen would be left with no highlight anywhere.
     */
    fun closePeriods(returnTo: Int) {
        // The highlight is handed over while the panel is still composed; see
        // [handHighlightBack]. The two doors of this panel are two different targets, and the
        // rule that tells them apart is [panelHandBack].
        handHighlightBack(returnTo)
        periodsOpen = false
        if (returnTo == NO_SELECTION) periodChipRefocus++ else askRefocus(returnTo)
    }

    // `BACK` from the player brings the household here, on the video just played. The
    // highlight is asked for again by the same counter the card menu uses, because the two
    // cases are the same one: something covered the grid, took focus, and went away.
    //
    // A finished video is the other case. The player names the video to land on, and that is
    // a landing rather than a refocus: it takes focus and it scrolls. Spec section 8.6.
    LaunchedEffect(returnedFromPlayer) {
        if (returnedFromPlayer <= 0L) return@LaunchedEffect
        val target = landOnVideoId?.let { id -> videos.indexOfFirst { it.videoId == id } } ?: -1
        // With Shuffle on, a finished video with autoplay-next off and the two-hour idle stop
        // both hand back on the run's next video, and Shuffle chose it. Build ticket 69.
        shuffleArmed = if (target >= 0 && target == shuffleNext) videos[target].videoId else null
        if (target >= 0) land(target, takeFocus = true) else askRefocus()
    }

    // The video the card menu is about went away under it, which a refresh that drops a video
    // does. It is closed from an effect and never while composing: a composition can be run
    // again or thrown away, and it is no place to ask for the highlight, which is what
    // [closeMenu] now does. Build ticket 39 moved it out; before that it was a bare call in
    // the middle of the overlay's own `let`.
    //
    // **This is the one close that still shows the frame the ticket is about, and it cannot
    // be helped from here.** The overlay stops being drawn the moment its video is gone,
    // which is a composition earlier than this effect, so the menu's line is already out and
    // Compose has already handed the highlight to the `Order` chip by the time [closeMenu]
    // hands it to the card. Handing it back sooner would mean drawing a menu about a video
    // that no longer exists. The household never asks for this close: a background refresh
    // does. Found by `/code-review` on build ticket 39.
    LaunchedEffect(menu, videos) {
        val index = menu.videoIndex ?: return@LaunchedEffect
        if (videos.getOrNull(index) == null) closeMenu()
    }

    // **The Period jump list has nothing left in it, so it closes itself.** Build ticket 52,
    // and the same event as the card menu's close directly above: a refresh took away the
    // thing the panel is about. `periodPanelCloseAfterRefresh` in `ui/PeriodPanel.kt` is the
    // rule and is unit-tested on the JVM.
    //
    // `openPeriods` above refuses to **open** this panel for a Source with no Period, and
    // nothing closed an open one. `periods` is derived and never cached,
    // `remember(videos, direction) { periodsOf(videos) }`, so a refresh that empties the Source
    // rebuilds it empty under a panel that is already up. Every row is then destroyed, no row
    // can take the highlight, and build ticket 51's recovery correctly asks for nothing,
    // because putting the highlight back needs a row. The household was left looking at the
    // panel's title over an empty box, answering nothing but `BACK`.
    //
    // **It is an effect and never a bare call while composing**, for the reason written on the
    // card menu's close above: a composition can be run again or thrown away, and it is no
    // place to ask for the highlight. The frame that close is honest about is here too. The
    // panel's rows stop being drawn a composition before this effect runs, so Compose has
    // already handed the highlight to whatever it found behind the scrim by the time
    // [closePeriods] hands it to the `Period` chip.
    //
    // **Keyed on whether the panel lists anything, and not on the Periods themselves.** The
    // list is rebuilt on every refresh, so keying on it would restart this effect for refreshes
    // that changed nothing this rule cares about. Build ticket 51's recovery effect is keyed
    // narrowly for the same reason, and its comment in `PeriodPanelOverlay` says why.
    //
    // **`periodsOpen` is a key as well as a test, and that is a belt rather than a fix for
    // something reachable now.** `openPeriods` cannot open a panel with no Period, so the two
    // keys can only go true in one order today. Keying on it means the body is never deciding
    // from a `periodsOpen` older than the composition it was launched from, whatever a later
    // ticket adds above.
    LaunchedEffect(periodsOpen, periods.isEmpty()) {
        if (!periodsOpen) return@LaunchedEffect
        val returnTo = periodPanelCloseAfterRefresh(
            periodCount = periods.size,
            returnTo = periodsReturnTo,
            videoCount = videos.size,
        ) ?: return@LaunchedEffect
        closePeriods(returnTo)
    }

    // BACK inside a submenu returns to the two top lines, and never straight to the grid.
    // Spec section 7.3. With the menu closed there is no handler at all, so BACK leaves the
    // app as it always did.
    BackHandler(enabled = menu.isOpen) {
        val next = menu.back()
        if (next.isOpen) setMenu(next) else closeMenu()
    }

    // `BACK` leaves the grid exactly where it was. Spec section 7.4. [closePeriods] is the
    // whole of the close and says why nothing scrolls.
    //
    // **Where to go back to was settled when the panel opened**, because by now the selection
    // has been cleared by whatever chip Compose handed the highlight to. `periodsReturnTo` is
    // that answer, re-anchored by the refresh effect above whenever the list moved under it.
    //
    // **`periodPanelClosesOnto` is what checks it against the list the Source has now**, and it
    // is build ticket 52's second half. The refresh effect answers null for a Source left
    // listing no video at all, so `periodsReturnTo` goes on naming a card that is gone, and
    // `BACK` on that panel asked the retry ladder for it: four rungs, about 484 ms, every one
    // failing, and no ring anywhere the app put there. It now goes to the `Period` chip
    // instead, which is drawn on every Source and never dim.
    BackHandler(enabled = periodsOpen) {
        closePeriods(periodPanelClosesOnto(periodsReturnTo, videos.size))
    }

    // `BACK` on the Shuffle question keeps the run, exactly as `Keep this run` does.
    BackHandler(enabled = shuffleQuestionOpen) { closeShuffleQuestion() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ink.surface)
            // `MENU` opens the Period jump list from anywhere on this screen, chips included.
            // It is caught here rather than in the grid, because the household may be standing
            // on a chip. `GUIDE` is never used: on this television the TCL live-TV guide takes
            // it and the app never sees it.
            //
            // Every delivery of the key is consumed, the key up included, so nothing else on
            // the system can act on a press this screen has already answered.
            .onPreviewKeyEvent { event ->
                // First, before any test can return: the household has touched this screen,
                // so a refresh may no longer move the highlight for them. See [keyPressed].
                if (event.type == KeyEventType.KeyDown && countsAsHouseholdKey(event.nativeKeyEvent.keyCode)) {
                    keyPressed.value = true
                }
                // Any key but OK means the household is moving, so the card Shuffle chose is
                // no longer Shuffle's choice. Build ticket 69, rule 7.
                if (event.type == KeyEventType.KeyDown) {
                    shuffleArmed = shuffleArmedAfterKey(
                        armed = shuffleArmed,
                        isSelect = isSelectKey(event),
                        counts = countsAsHouseholdKey(event.nativeKeyEvent.keyCode),
                    )
                }
                // While a video is on, this screen is not the one the household is looking at
                // and must not answer `MENU` at all. The key is passed on rather than eaten,
                // so the player owns it.
                if (playing) return@onPreviewKeyEvent false
                // **The open panel's keys are kept here, not in the panel.** Compose delivers
                // a preview key event only to the `onPreviewKeyEvent` modifiers on the
                // ancestor chain of the focused node. Until a row of the panel has the
                // highlight, the focused node is still a card in the grid, and the panel is
                // that card's *sibling* rather than its ancestor, so a handler inside the
                // panel is never visited. This `Box` is the ancestor of both, so it is the
                // only place the guard can work from.
                //
                // Without it, `MENU` and then the centre button plays a video from behind the
                // open panel, a `Down` scrolls the grid behind the scrim, and a hold opens the
                // card menu on top of the panel with both `BackHandler`s live.
                //
                // **The rule itself is `overlayKeepsEveryKey` in `ui/CardMenu.kt`**, and it
                // carries the rest of the reasoning: why the test is never a bare `periodsOpen`,
                // and why `BACK` is let through whatever else is kept. It was written out twice
                // inline here until build ticket 52, once for each overlay, and two copies of
                // one sentence drift. **The placement is the half no JVM test can see**, which
                // is why it stays written here and why `SourceKeyGuardTest` in
                // `app/src/androidTest/` presses a real key against it.
                if (overlayKeepsEveryKey(
                        overlayOpen = periodsOpen,
                        overlayHasFocus = periodPanelHasFocus.value,
                        isBack = event.key == Key.Back,
                    )
                ) {
                    return@onPreviewKeyEvent true
                }
                // The same guard for the card menu, which has the same hole. It was written up
                // by ticket 09, which found it and did not own the file; ticket 19 edits that
                // file and closes it. The menu has never been seen to leak, because a hold
                // already costs several hundred milliseconds and a plain `Column` of four lines
                // is composed in the first frame, so its `FocusRequester` normally succeeds at
                // 0 ms. One slow frame is enough to fall in the gap, and the television draws
                // one of those about every 113 ms under a burst.
                //
                // It reads the menu's own `hasFocus` rather than a bare `menu.isOpen`, and it is
                // above any `KeyDown` test, and it lets `BACK` through, for the three reasons
                // written on `overlayKeepsEveryKey`.
                //
                // **This is the one of the two that stands still long enough to press a key
                // at.** A wholly dim submenu never lands the highlight at all, so it holds this
                // state for as long as the household leaves the menu up, and that is what
                // `SourceKeyGuardTest` drives. See `firstLiveLine` in `ui/CardMenu.kt`.
                if (overlayKeepsEveryKey(
                        overlayOpen = menu.isOpen,
                        overlayHasFocus = cardMenuHasFocus.value,
                        isBack = event.key == Key.Back,
                    )
                ) {
                    return@onPreviewKeyEvent true
                }
                // The same guard for the Shuffle question, which has the same hole: until one
                // of its two lines has the highlight, the `Shuffle` chip still has it, and the
                // release of the long press that opened the question would toggle the chip.
                // Build ticket 69.
                if (overlayKeepsEveryKey(
                        overlayOpen = shuffleQuestionOpen,
                        overlayHasFocus = shuffleQuestionHasFocus.value,
                        isBack = event.key == Key.Back,
                    )
                ) {
                    return@onPreviewKeyEvent true
                }
                if (event.key != Key.Menu) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                // A second press while the panel is already open does nothing, and MENU
                // means nothing at all while the card menu is up.
                if (!menu.isOpen && !periodsOpen && !shuffleQuestionOpen) openPeriods(fromChip = false)
                true
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val header = headerLine(state)
            SourceHeader(
                ink = ink,
                sourceName = sourceName,
                header = header,
                // Empty while a chip holds the highlight, because then no video is selected.
                // Spec section 7.2, amended by ticket 16.
                // The one chip with words of its own on this line. Build ticket 69, rule 11.
                focusedTitle = {
                    if (shuffleChipFocused.value) {
                        shuffleHintLine(ready?.shufflePlayedCount() ?: 0)
                    } else {
                        selectedVideo(videos, focusedIndex.intValue)?.title
                    }
                },
                // The one place a watch date appears. Spec section 7.3 took the text line off
                // the card itself, because a label under one card's date made that whole grid
                // row taller and left a gap under the other three.
                focusedWatchDate = {
                    val video = selectedVideo(videos, focusedIndex.intValue)
                    watchDateLine(video?.let { watches[it.videoId] })
                },
                direction = direction,
                // Greyed out and skipped by the D-pad when it has nowhere to land.
                lastPlayedEnabled = lastPlayedTarget != null,
                onOrder = {
                    // **Nothing at all until the Source is `Ready`.** The chip row is drawn
                    // over a first walk as well, and while that walk runs it is the only thing
                    // on the screen that can hold the highlight, so this chip is easy to press.
                    // `SourceScreenModel.flip` does nothing then, but arming `orderPressed`
                    // would make the **first** real Direction, the one that arrives with the
                    // finished walk, look like a flip. The grid would be sent to its first card
                    // instead of to the Source's opening landing, and with focus taken by
                    // nobody. Found by `/code-review`.
                    if (ready != null) {
                        // Set before the flip is asked for. The flip is a write to storage and
                        // comes back on another thread, so the effect that puts the grid at
                        // the top of the new order runs long after this returns.
                        orderPressed = true
                        onFlipDirection()
                    }
                },
                onJumpToLastPlayed = {
                    onLanding(
                        landingLogLine(
                            chip = JUMP_TO_LAST_PLAYED_CHIP,
                            videoId = lastPlayedTarget?.let { videos[it].videoId },
                            index = lastPlayedTarget,
                            count = videos.size,
                        ),
                    )
                    lastPlayedTarget?.let { land(it, takeFocus = true) }
                },
                onNextUnwatched = {
                    val target = nextUnwatchedIndex(videos, watches, direction)
                    onLanding(
                        landingLogLine(
                            chip = NEXT_UNWATCHED_CHIP,
                            videoId = target?.let { videos[it].videoId },
                            index = target,
                            count = videos.size,
                        ),
                    )
                    if (target == null) say(NO_NEXT_UNWATCHED) else land(target, takeFocus = true)
                },
                // Unavailable until the first walk finishes, because Next unwatched is read
                // over the **whole** Source: half a list has the wrong high-water mark as well
                // as the wrong first gap. Spec section 6.5.
                nextUnwatchedEnabled = ready?.walking == null,
                // The same door as `MENU`, and in the same place on the row it has always
                // been, so the household never has to learn it twice. Spec section 7.4.
                onPeriod = { openPeriods(fromChip = true) },
                periodChipFocus = periodChipFocus,
                // The record the `Period` chip's ladder reads. Only the chip itself writes it.
                onPeriodChipHighlightChanged = { holdsIt ->
                    chipHighlight.report(PERIOD_CHIP, holdsIt)
                },
                // A chip has taken the highlight, so no video is selected any more. **Unless
                // something is drawn over the grid**, in which case the household did not move
                // to the chip row at all: Compose did, because the node holding the highlight
                // left composition. See `selectionAfterChipFocus`.
                onChipFocused = {
                    focusedIndex.intValue = selectionAfterChipFocus(
                        selection = focusedIndex.intValue,
                        overlayOpen = menu.isOpen || periodsOpen || shuffleQuestionOpen,
                    )
                },
                // `Down` from any chip goes to the first card of the grid, and scrolls there
                // when the grid is not already at the top. Spec section 7.2, ticket 16.
                onDownToGrid = { firstCardIndex(videos.size)?.let { land(it, takeFocus = true) } },
                autoplayNext = ready?.autoplayNext ?: StoredSource.AUTOPLAY_NEXT_DEFAULT,
                onAutoplay = onToggleAutoplay,
                shuffle = shuffleOn,
                onShuffle = {
                    // Nothing until the Source is `Ready`, for the reason `Order` gives.
                    if (ready != null) {
                        shuffleTurnedOn = !shuffleOn
                        onToggleShuffle()
                    }
                },
                onShuffleHold = {
                    if (ready != null) {
                        shuffleQuestionHasFocus.value = false
                        shuffleQuestionOpen = true
                    }
                },
                shuffleChipFocus = shuffleChipFocus,
                onShuffleChipHighlightChanged = { holdsIt ->
                    shuffleChipFocused.value = holdsIt
                    chipHighlight.report(SHUFFLE_CHIP, holdsIt)
                },
            )

            when (state) {
                is SourceScreenState.Ready ->
                    if (videos.isEmpty()) {
                        Message(NO_VIDEOS_MESSAGE, ink)
                    } else {
                        // Keyed on the Source, so the scroll position and the focus target are
                        // rebuilt together when the household opens a different one. They are
                        // two pieces of state that must never disagree about which list they
                        // describe.
                        key(sourceId) {
                            // Worked out once, when this Source's grid first appears, and
                            // never again here. Recomputing it would send the household back to
                            // the top the moment they marked the video they were standing on.
                            // The one later move to a new landing is the refresh effect's, and
                            // only before any key is pressed. Build ticket 55.
                            //
                            // Which of the three landings this is was chosen on the home rail.
                            // `openingLanding` holds the rule and its fallbacks.
                            val opening = remember {
                                openingLanding(
                                    openAt,
                                    videos,
                                    watches,
                                    direction,
                                    ready?.lastPlayedVideoId,
                                    shuffleNext,
                                )
                            }
                            // A plain opening with Shuffle on rests on the run's next video,
                            // and one press of OK there is a Shuffle play. Build ticket 69.
                            LaunchedEffect(Unit) {
                                shuffleArmed = shuffleArmedOnOpening(openAt, opening, shuffleNext, videos)
                            }
                            // **The grid has landed, so "since the screen landed" starts now.**
                            // Build ticket 55's rule counts keys from the landing, and its
                            // `/code-review` caught it counting from the opening instead. A key
                            // pressed before this moment is already overridden: the opening
                            // landing takes the highlight whatever the key did. Counting it would
                            // only keep the highlight on a landing worked out from an old list.
                            LaunchedEffect(Unit) { keyPressed.value = false }
                            VideoGrid(
                                videos = videos,
                                watches = watches,
                                thumbnails = thumbnails,
                                ink = ink,
                                focusedIndex = focusedIndex,
                                refocus = refocus,
                                // A Source opens on its Next unwatched video, in its
                                // Direction. Spec section 7.2.
                                openOnIndex = opening,
                                landing = landing,
                                requesters = gridRequesters,
                                // A tap of the centre button plays. An In progress video
                                // resumes; `Play from start` in the card menu is the only
                                // way to ask for the beginning. Spec section 8.6.
                                onSelect = { video ->
                                    onPlay(video, false, shuffleOn && video.videoId == shuffleArmed)
                                },
                                onHold = { index -> setMenu(CardMenu.Top(index)) },
                            )
                        }
                    }
                is SourceScreenState.Failed -> Message(state.message, ink)
                // Nothing is drawn while the first walk runs, beyond the quiet
                // `loading 400 of 5,813` line above. Spec section 6.5 also wants the videos
                // themselves to appear as pages arrive, and they do not. That needs the walk
                // to hand back finished videos batch by batch, and it is only reachable again
                // once a Source can be added, so it is left to ticket 11.
                SourceScreenState.Starting, is SourceScreenState.Walking -> Unit
            }
        }

        menu.videoIndex?.let { index ->
            videos.getOrNull(index)?.let { video ->
                CardMenuOverlay(
                    menu = menu,
                    video = video,
                    listed = videos,
                    ink = ink,
                    // Which `Just this` does nothing on this card, and so which one is dim.
                    // Spec section 7.3.
                    watched = watches[video.videoId]?.watched == true,
                    // False while the first walk runs, which leaves `Just this` as the only
                    // line of either submenu. Spec section 6.5.
                    walkFinished = ready?.walking == null,
                    hasFocus = cardMenuHasFocus,
                    onOpenSub = { markWatched -> setMenu(menu.openSub(markWatched)) },
                    // The panel opens on this card's Period, and `BACK` from it comes back to
                    // this card. The index travels with the request, because closing the menu
                    // is what gives the chip row the highlight. Spec sections 7.3 and 7.4.
                    //
                    // A Source with no dated video opens no panel and says so, exactly as the
                    // `Period` chip does. The highlight then goes back to the card, because
                    // nothing was drawn over the grid to take it.
                    onJumpToPeriod = {
                        // One panel replaces another, and the menu's line is leaving
                        // composition holding the highlight exactly as it does on a close. The
                        // card takes it for the two frames until the Period panel's own ladder
                        // asks, and the card is the right place for it to be in the meantime.
                        handHighlightBack(index)
                        menu = CardMenu.Closed
                        if (!openPeriods(fromChip = false, cardIndex = index)) askRefocus(index)
                    },
                    onMark = { line, markWatched ->
                        closeMenu()
                        when (line.scope) {
                            MarkScope.JUST_THIS -> {
                                say(markedLine(markWatched))
                                onMarkJustThis(video, markWatched)
                            }
                            MarkScope.ALL_OLDER -> {
                                say(markedRangeLine(line.count, markWatched))
                                onMarkRange(video, RangeSide.OLDER, markWatched)
                            }
                            MarkScope.ALL_NEWER -> {
                                say(markedRangeLine(line.count, markWatched))
                                onMarkRange(video, RangeSide.NEWER, markWatched)
                            }
                        }
                    },
                    onPlayFromStart = {
                        closeMenu()
                        // Chosen from a menu, so never a Shuffle play.
                        onPlay(video, true, false)
                    },
                )
            }
        }

        if (periodsOpen) {
            PeriodPanelOverlay(
                periods = periods,
                openOnEntry = periodsOpenOn,
                hasFocus = periodPanelHasFocus,
                ink = ink,
                onChoose = { period ->
                    // The chosen Period's first card is normally nowhere near the screen, so
                    // this hands the highlight over only when the lazy grid happens to be
                    // holding that card already. When it is not, [panelHandBack] answers null
                    // and the landing below is what puts the highlight there, after a scroll.
                    handHighlightBack(period.firstIndex)
                    // Closed first, so the panel is gone before the grid takes focus back.
                    periodsOpen = false
                    videos.getOrNull(period.firstIndex)?.let { video ->
                        onLanding(periodLandingLogLine(period, video.videoId, videos.size))
                    }
                    // The same seam `Next unwatched` and `Jump to last played` use. It
                    // scrolls and takes focus in one move, which is what spec section 7.4
                    // asks a chosen Period to do.
                    land(period.firstIndex, takeFocus = true)
                },
            )
        }

        if (shuffleQuestionOpen) {
            ShuffleQuestionOverlay(
                question = shuffleQuestion(ready?.shufflePlayedCount() ?: 0),
                ink = ink,
                hasFocus = shuffleQuestionHasFocus,
                onKeep = { closeShuffleQuestion() },
                onStartNew = {
                    closeShuffleQuestion()
                    say(NEW_SHUFFLE_RUN_STARTED)
                    onStartNewShuffleRun()
                },
            )
        }

        toast?.let { said ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .background(ink.surfaceRaised, RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = said.message,
                    color = ink.onSurface,
                    style = Fonts.Meta,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * The header.
 *
 * The line carrying the focused card's title has a **fixed height**, so nothing below it moves
 * as focus travels across the grid. An empty title still takes the same room, which is what
 * lets it go empty the moment a chip takes the highlight.
 */
@Composable
private fun SourceHeader(
    ink: Ink,
    sourceName: String,
    header: HeaderLine,
    focusedTitle: () -> String?,
    focusedWatchDate: () -> String?,
    direction: Direction,
    lastPlayedEnabled: Boolean,
    nextUnwatchedEnabled: Boolean,
    autoplayNext: Boolean,
    onOrder: () -> Unit,
    onJumpToLastPlayed: () -> Unit,
    onNextUnwatched: () -> Unit,
    onPeriod: () -> Unit,
    // Held by the caller, so `BACK` out of a panel the `Period` chip opened can put the
    // highlight back on that chip rather than dropping it into the grid.
    periodChipFocus: FocusRequester,
    // Said on every change of the `Period` chip's own highlight, the gains and the losses
    // both, so that the caller's retry ladder has something to read that the caller did not
    // write itself. Build ticket 47. It is the `Period` chip alone, because that is the only
    // chip this screen ever asks for the highlight by name.
    onPeriodChipHighlightChanged: (Boolean) -> Unit,
    onAutoplay: () -> Unit,
    // The `Shuffle` chip. A tap toggles it and a long press asks to start a new run. The long
    // press never toggles. Build ticket 69.
    shuffle: Boolean,
    onShuffle: () -> Unit,
    onShuffleHold: () -> Unit,
    shuffleChipFocus: FocusRequester,
    onShuffleChipHighlightChanged: (Boolean) -> Unit,
    // Any chip has taken the highlight, so the caller clears the selection. Every chip reports
    // it, because the rule is about the row and not about which chip on it.
    onChipFocused: () -> Unit,
    // `Down` was pressed while a chip held the highlight.
    onDownToGrid: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // TV panels overscan. Keep everything away from the edges.
            .padding(start = 48.dp, end = 48.dp, top = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = sourceName,
                color = ink.onSurface,
                style = Fonts.SourceTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            // Pinned to the top of the Source name rather than its baseline. Sitting low, it
            // came within a few pixels of a long focused title in the line below, which the owner
            // saw on the television on 2026-09-19.
            Row(
                modifier = Modifier.align(Alignment.Top),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = header.count,
                    color = ink.onSurfaceMuted,
                    style = Fonts.Meta,
                    maxLines = 1,
                )
                header.updated?.let { updated ->
                    // Dimmer than the count: it is the least urgent thing on the screen.
                    Text(text = updated, color = ink.onSurfaceFaint, style = Fonts.Meta, maxLines = 1)
                }
            }
        }
        // Clear air under the count, so a long title cannot reach up and touch it.
        Spacer(Modifier.height(8.dp))
        FocusedTitleLine(ink = ink, title = focusedTitle, watchDate = focusedWatchDate)
        Spacer(Modifier.height(10.dp))
        Row(
            // **`Down` is answered here, not left to Compose.** Compose's focus search would
            // hand the highlight to whichever card happens to be under the chip, which is a
            // card from wherever the grid is scrolled to. The household is always taken to the
            // first card of the grid instead. Spec section 7.2, amended by ticket 16.
            //
            // A preview handler reaches this row because the focused chip is its child, and
            // preview events are delivered down the ancestor chain of the focused node. The
            // grid's own handler never sees this key: the grid is not on that chain while a
            // chip holds the highlight.
            //
            // Every delivery is consumed, the key up included, so nothing else can act on a
            // press this row has already answered.
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) onDownToGrid()
                true
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The `Order` chip names the Direction the Source is in now, not the one pressing
            // it would move to. Direction is per Source and is stored with the Source.
            Chip(orderChipLabel(direction), ink, onSelect = onOrder, onFocused = onChipFocused)
            // Two chips on purpose, because they mean different things and usually point at
            // different videos. Spec section 7.2.
            Chip(
                JUMP_TO_LAST_PLAYED_CHIP,
                ink,
                enabled = lastPlayedEnabled,
                onSelect = onJumpToLastPlayed,
                onFocused = onChipFocused,
            )
            Chip(
                NEXT_UNWATCHED_CHIP,
                ink,
                enabled = nextUnwatchedEnabled,
                onSelect = onNextUnwatched,
                onFocused = onChipFocused,
            )
            Chip(
                PERIOD_CHIP,
                ink,
                focusRequester = periodChipFocus,
                onSelect = onPeriod,
                onFocused = onChipFocused,
                onHighlightChanged = onPeriodChipHighlightChanged,
            )
            // Last on the row, after the four that were settled on the television on
            // 2026-09-19. A new chip among them would move one the household has already
            // learnt the place of. Spec section 7.2, amended by tickets 08, 18 and 26.
            //
            // The only chip on the row that carries a state, and it says that state twice:
            // once in its word and once in its fill. Ticket 18 left it to the fill alone,
            // and the owner could not read the two fills apart from the sofa, so ticket 26 put
            // the word back and kept the fill. One boolean feeds both, so they cannot
            // disagree.
            Chip(
                autoplayChipLabel(autoplayNext),
                ink,
                fill = autoplayChipFill(autoplayNext),
                onSelect = onAutoplay,
                onFocused = onChipFocused,
            )
            // After `Autoplay`, at the end of the row, so no chip the household has learnt the
            // place of moves. It follows `Autoplay`'s rule for its word and its fill, and one
            // boolean feeds both. Build ticket 69, rule 1.
            Chip(
                shuffleChipLabel(shuffle),
                ink,
                fill = autoplayChipFill(shuffle),
                focusRequester = shuffleChipFocus,
                onSelect = onShuffle,
                onHold = onShuffleHold,
                onFocused = onChipFocused,
                onHighlightChanged = onShuffleChipHighlightChanged,
                testTag = TestTags.chip(SHUFFLE_CHIP),
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

/**
 * The full title of the focused card, and its watch date in green when it has one.
 *
 * Both are read here and nowhere else, so moving the highlight recomposes this one line rather
 * than the whole screen. A grid card fits only part of a title, so this is the only place a
 * whole one can be read, and spec section 7.3 made it the only place a watch date appears at
 * all. See spec section 7.2.
 *
 * The height is fixed, so nothing below moves as focus travels. A title long enough to need
 * the ellipsis still leaves the date its room, because the date is laid out first.
 */
@Composable
private fun FocusedTitleLine(ink: Ink, title: () -> String?, watchDate: () -> String?) {
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title().orEmpty(),
            color = ink.onSurfaceMuted,
            style = Fonts.FocusedTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        watchDate()?.let { date ->
            Spacer(Modifier.width(14.dp))
            Text(
                text = date,
                color = ink.watchedTick,
                style = Fonts.Meta,
                maxLines = 1,
            )
        }
    }
}

/**
 * One chip of the row above the grid.
 *
 * A disabled chip is **not focusable**, so the D-pad walks straight past it rather than
 * stopping on something that cannot act. It is still drawn, in the faintest ink on the screen,
 * because a chip that came and went would be a chip whose place on the row could never be
 * learnt. Spec section 7.2 asks for `Jump to last played` to be disabled, and that is the only
 * chip that ever is.
 *
 * **Which colour goes where is not decided here.** [chipPaint] holds that rule, with no
 * Compose in it, so every case of it is a JVM unit test. This reads [Ink] for the colour each
 * role names and nothing more.
 */
@Composable
private fun Chip(
    label: String,
    ink: Ink,
    // What the chip's fill says about it. Only `Autoplay` is ever anything but quiet, and
    // since ticket 26 the fill is **half** of what says its state: the word says it too.
    // The caller feeds one boolean to `autoplayChipLabel` and `autoplayChipFill`, so the two
    // cannot drift apart. Tickets 18 and 26.
    fill: ChipFill = ChipFill.QUIET,
    enabled: Boolean = true,
    // Only the `Period` chip has one, so that `BACK` out of the panel it opened can put the
    // highlight back on it. Every other chip is reached by the D-pad alone.
    focusRequester: FocusRequester? = null,
    onSelect: () -> Unit,
    // Said once, when this chip takes the highlight, and never on the delivery that takes it
    // away again. It is what clears the video selection. Ticket 16.
    onFocused: () -> Unit = {},
    // Said on every change, the gains and the losses both, so that a caller can keep a record
    // of what really holds the highlight rather than of what it asked for. Only the `Period`
    // chip passes one, because it is the only chip a retry ladder ever asks for. Build ticket
    // 47.
    onHighlightChanged: (Boolean) -> Unit = {},
    // A long press of OK. Only the `Shuffle` chip has one, and with it the chip acts on a tap
    // and a hold through [onSelectOrHoldKey], so a hold never also toggles it. Build ticket 69.
    onHold: (() -> Unit)? = null,
    // The chip's test tag. It is its label for every chip but `Shuffle`, whose label changes
    // with its state while a test needs to find the same node either way.
    testTag: String = TestTags.chip(label),
) {
    // **Not keyed on `enabled`, since build ticket 47, and it used to be.** The reason it was
    // keyed is real and is now answered somewhere better. A chip disabled while it holds the
    // highlight was never told it lost it, because `onFocusChanged` sat inside the `enabled`
    // branch of the modifier chain below and went away with it, so the chip went on being
    // painted as focused and its next real focus looked like no change at all. Rebuilding this
    // on `enabled` hid the painting half of that and left the report missing.
    //
    // `onFocusChanged` is outside that branch now, so the loss really is reported, and this may
    // no longer be rebuilt: resetting it to false while the node still holds the highlight
    // would make the very next delivery look like a gain the chip never had, and would write a
    // record saying so. Found by `/code-review`, twice: once on ticket 16 for the painting and
    // once on build ticket 47 for the report.
    var focused by remember { mutableStateOf(false) }
    val paint = chipPaint(fill, focused = focused, enabled = enabled)
    Box(
        modifier = Modifier
            .height(40.dp)
            // The chip that took the highlight is what build ticket 39's instrumentation test
            // is watching for, and its words are on a child node rather than on the focusable
            // one, so they cannot say which node holds the highlight. See [TestTags].
            .testTag(testTag)
            .let { if (focusRequester == null) it else it.focusRequester(focusRequester) }
            .background(
                color = when (paint.body) {
                    ChipBody.SURFACE_RAISED -> ink.surfaceRaised
                    ChipBody.ACCENT -> ink.accent
                    ChipBody.FOCUS_FILL -> ink.focusFill
                },
                shape = RoundedCornerShape(20.dp),
            )
            .border(
                // The focus ring is the thicker one. It is drawn only on an accent chip,
                // whose body cannot change on focus because its body is its state, so that
                // ring is carrying the highlight by itself and a thin one was too quiet.
                width = if (paint.ring == ChipRing.FOCUS_FILL) 4.dp else 2.dp,
                color = when (paint.ring) {
                    ChipRing.NONE -> Color.Transparent
                    ChipRing.ACCENT -> ink.accent
                    ChipRing.FOCUS_FILL -> ink.focusFill
                },
                shape = RoundedCornerShape(20.dp),
            )
            .then(
                when {
                    !enabled -> Modifier
                    onHold != null -> Modifier.onSelectOrHoldKey(onSelect = onSelect, onHold = onHold)
                    else -> Modifier.onSelectKey { onSelect() }
                },
            )
            // **Outside the `enabled` branch, and `MenuLine` and `PanelLine` are the same shape
            // for the same reason.** A chip disabled while it holds the highlight has to be
            // able to say that it lost it. With this modifier inside the branch, going disabled
            // took the modifier away before Compose reported anything, so the chip went on
            // being painted as focused and, since build ticket 47, the screen's record of what
            // holds the highlight went on naming it. The `Period` chip's ladder would then read
            // its exit test as satisfied on the first rung with the highlight somewhere else.
            //
            // Only the `Period` chip writes a record and it is never disabled, and
            // `Jump to last played` is the only chip that is ever disabled at all, so this is a
            // trap removed and not a defect fixed. Found by `/code-review` on build ticket 47.
            // An older version of this comment said the answer was to rebuild `focused` on
            // `enabled`; that answered the painting and left the report missing altogether.
            .onFocusChanged { state ->
                val changed = state.isFocused != focused
                val gained = state.isFocused && !focused
                focused = state.isFocused
                if (gained) onFocused()
                // Every change, and only a real change. Compose delivers `onFocusChanged` on
                // other things than a gain or a loss, and a record written from a delivery
                // that changed nothing would report a loss the chip never had.
                if (changed) onHighlightChanged(state.isFocused)
            }
            // A disabled chip is not focusable, so the D-pad walks straight past it rather
            // than stopping on something that cannot act.
            .focusable(enabled = enabled)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when (paint.label) {
                ChipLabel.ON_SURFACE -> ink.onSurface
                ChipLabel.ON_SURFACE_FAINT -> ink.onSurfaceFaint
                ChipLabel.ON_FOCUS -> ink.onFocus
            },
            style = Fonts.Chip,
            maxLines = 1,
        )
    }
}

/**
 * The videos.
 *
 * Focus, scrolling and the D-pad are driven from here rather than left to Compose, because the
 * household walks a flat chronological list and Compose's focus search walks visual rows. The
 * rules themselves are in [gridTarget], so they are unit-tested with no device.
 *
 * **No card knows whether it is the focus target.** The grid keeps one [FocusRequester] per
 * index and asks the right one directly. A card that read the target would have to recompose
 * every time the target changed, which meant every visible card recomposing on every key
 * press, twice. Measured on the television on 2026-09-19.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGrid(
    videos: List<StoredVideo>,
    watches: Map<String, WatchState>,
    thumbnails: ThumbnailLoader,
    ink: Ink,
    focusedIndex: MutableIntState,
    refocus: Refocus?,
    openOnIndex: Int,
    landing: GridLanding?,
    /**
     * The focus requester of every card that is composed right now.
     *
     * Each card puts itself in while it lives and takes itself out when the lazy grid drops
     * it, so this holds the dozen or so on screen rather than one entry per video ever seen.
     * A card registers from an effect, never while composing, because composition can be run
     * again or thrown away and must leave nothing behind.
     *
     * **It is owned by `SourceScreen` and not by this function**, because a closing panel has
     * to reach a card before this grid is recomposed. Build ticket 39.
     */
    requesters: MutableMap<Int, FocusRequester>,
    onSelect: (StoredVideo) -> Unit,
    onHold: (Int) -> Unit,
) {
    val gridState = rememberLazyGridState()

    /** The move waiting to be carried out. Nothing composes this, so nothing recomposes on it. */
    var moves by remember { mutableLongStateOf(0L) }
    // The Source opens on its Next unwatched video, so the first move of all is to it. It is
    // read once, here, and the grid is inside a `key(sourceId)`, so opening a different Source
    // builds this again and opening the same one never lands twice.
    var pending by remember {
        mutableStateOf<FocusMove?>(FocusMove(seq = 0, index = openOnIndex, scroll = true))
    }

    /**
     * Which video's card reports holding the highlight right now.
     *
     * **It is the retry ladder's exit test, and nothing but a card's own focus report writes
     * it.** Build ticket 47, which is the Source screen's reading of what build ticket 41 did
     * to the home rail. The ladder used to exit on
     * `runCatching { requestFocus() }.isSuccess`, which says only that the call did not throw:
     * a card that is attached and quietly refuses the highlight throws nothing, so the first
     * rung read success and the later rungs never ran.
     *
     * **It lives inside this function on purpose.** `handHighlightBack` and `move` are in
     * `SourceScreen` and cannot reach it, so the exit test cannot be satisfied by the screen
     * writing down what it wanted. [focusedIndex] is the other answer, the card the app chose,
     * and it stays exactly where it was.
     *
     * **It names the video and not the card's place in the grid, and the first version of this
     * named the place.** Everything else about a move is counted in places: [requesters] is
     * keyed by one, [FocusMove] names one, and the key handler measures from one. The record
     * cannot be, because a place moves under it and a video id does not.
     *
     * A refresh that inserts three videos moves the card the household is standing on down
     * three places, and Compose reports no focus change at all, because that node is reused and
     * only the number it is drawn at changed. A record naming places would go on naming the old
     * one. Worse, the card's next real loss would arrive carrying its **new** place, which
     * [highlightHolder] rightly refuses to apply to a record naming a different number, so the
     * record would then name a place nothing holds and would never go empty again by itself. A
     * later ladder asking for that place would read its own exit test as already satisfied on
     * the first rung, and if the lazy grid had not placed that card there would be nothing to
     * ask either, so the screen would be left with no highlight anywhere. That is the exact
     * failure this ticket exists to make detectable. Found by `/code-review` on build ticket
     * 47.
     *
     * A video id is what the lazy grid itself is keyed by, so this record survives a
     * renumbering exactly as the node holding the highlight does.
     */
    val highlightOn = remember { HighlightRecord<String>() }

    /**
     * The video list as it is **now**, for the retry ladder to read while it is running.
     *
     * A `LaunchedEffect` closes over the values its composition had when it started, and the
     * ladder below is keyed on the move alone, so a refresh landing inside its 484 ms does not
     * restart it and does not reach it. The ladder has to turn a video back into a place in the
     * grid on every rung, and it has to do that against the list the cards are really drawn
     * from, not the one that existed when the household pressed the key.
     */
    val liveVideos = rememberUpdatedState(videos)

    /**
     * How many key downs this grid has seen. The retry ladder below stops asking once it moves.
     * Build ticket 55. Nothing composes it, so counting recomposes nothing.
     */
    val keysInGrid = remember { mutableLongStateOf(0L) }

    LaunchedEffect(pending) {
        val move = pending ?: return@LaunchedEffect
        if (move.scroll) {
            // The row's first card, never the card itself, so a row always lines up with the
            // top of the viewport.
            gridState.scrollToItem(rowStart(move.index))
        }
        // A Direction flip scrolls and stops. Focus is on the `Order` chip, and taking it
        // away would cost the household two presses to flip back.
        if (!move.takeFocus) return@LaunchedEffect
        // Which video this move is really about. A move names a place in the grid, and this is
        // the one point where that place is turned into the video standing at it. A move naming
        // a place the list does not hold has nothing to ask for and nothing to wait for, so it
        // stops here rather than spending every rung.
        val card = videos.getOrNull(move.index)?.videoId ?: return@LaunchedEffect
        // The card may only just have been placed by the lazy grid, and a card that does not
        // exist yet cannot take focus, nor has it registered itself yet. These retries are the
        // whole reason focus is asked for here rather than in the card.
        //
        // **The exit test is [highlightOn] and never a `requestFocus` that did not throw.** A
        // card the lazy grid has not placed has no requester here at all, and a card that is
        // placed and quietly refuses the highlight throws nothing, so the old test read
        // success on the first rung in the one case the ladder exists for. Build ticket 47.
        //
        // **The ask and the exit test are both about the video, and the place is worked out
        // again on every rung.** [requesters] is keyed by place, so the two have to meet
        // somewhere, and the honest place for that is inside the ask, against the list as it is
        // at that moment. This effect is keyed on the move alone, so it is not restarted by a
        // refresh, and it runs for up to 484 ms: long enough for a background refresh to land
        // inside it and move this video to another place. An ask that went on naming the place
        // the move was armed with would then drag the highlight onto a different video on every
        // rung, and the exit test, which names the video, could never become true. Found by
        // `/code-review` on build ticket 47, which had left exactly that.
        //
        // **A key the household presses ends the ladder.** Build ticket 55. Its `/code-review`
        // asked why `RefreshLandingTest` saw the highlight jump back after a `Right`, and a trace
        // on `emulator-5554` answered it. `Right` inside a row is not one of [gridTarget]'s
        // moves, so Compose's own focus search carries it and no new move restarts this effect.
        // The ladder was still waiting between rungs, found its card no longer holding the
        // highlight, and asked for it again, taking the highlight back from the household. Any
        // landing could do this to a quick press. A refresh landing makes it likelier, because
        // the household did not start it and cannot know it is under way.
        val keysAtStart = keysInGrid.longValue
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = {
                if (keysInGrid.longValue == keysAtStart) {
                    val at = liveVideos.value.indexOfFirst { it.videoId == card }
                    if (at >= 0) requesters[at]?.let { runCatching { it.requestFocus() } }
                }
            },
            holds = { keysInGrid.longValue != keysAtStart || highlightOn.holds(card) },
        )
    }

    // A chip asked the grid to go somewhere: `Next unwatched`, `Jump to last played`, or the
    // video kept across a Direction flip. It always scrolls, because the target is normally
    // nowhere near the card the household was standing on.
    LaunchedEffect(landing) {
        val asked = landing ?: return@LaunchedEffect
        moves++
        pending = FocusMove(
            seq = moves,
            index = asked.index,
            scroll = true,
            takeFocus = asked.takeFocus,
        )
    }

    // Focus comes back to the card the menu was opened on. Without this the highlight is
    // simply gone when the menu closes, because the card lost focus to the menu and nothing
    // gave it back. Nothing scrolls: the card never left the screen.
    //
    // **The card comes with the request.** Reading the selection here instead would read it
    // after Compose had handed the highlight to the first chip, which clears it. See
    // [Refocus].
    //
    // Every caller has a card selected: the card menu is opened by holding the centre button
    // on a card, the player is reached from a card, and the one path that can start on a chip,
    // `MENU` from the chip row, sends the highlight back to that chip instead. Nothing
    // selected is still refused rather than clamped to card 0, because this move never scrolls
    // and so could not bring card 0 into view anyway.
    LaunchedEffect(refocus) {
        val asked = refocus ?: return@LaunchedEffect
        if (asked.index < 0) return@LaunchedEffect
        moves++
        pending = FocusMove(seq = moves, index = asked.index, scroll = false)
    }

    fun move(to: Int?): Boolean {
        if (to == null) return false
        // Whether to scroll travels with the target rather than beside it. Kept apart, the
        // two can fall out of step when a second key arrives before the first has been
        // carried out, and the grid then skips a scroll it needed.
        moves++
        pending = FocusMove(seq = moves, index = to, scroll = !gridState.isWhollyVisible(to))
        // Said now, not when the card reports focus. A card that is asked for focus and does
        // not get it would otherwise leave the next key press measured from the card the
        // household left, which is no longer on screen.
        focusedIndex.intValue = to
        return true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // First, before any key can start a move of its own. See [keysInGrid].
                if (countsAsHouseholdKey(event.nativeKeyEvent.keyCode)) keysInGrid.longValue++
                val held = isHeld(event.nativeKeyEvent.repeatCount)
                val requested = when (event.key) {
                    Key.DirectionLeft -> GridMove.LEFT
                    Key.DirectionRight -> GridMove.RIGHT
                    Key.DirectionDown -> GridMove.DOWN
                    // Holding Up jumps to the first card in the list instead of walking back
                    // row by row. From deep in a long Source that walk was 25 presses, and the
                    // chips sit above the first row. Asked for on the TCL, 2026-09-19. Which
                    // video that card is now depends on the Source's Direction.
                    Key.DirectionUp -> if (held) GridMove.UP_HELD else GridMove.UP
                    // The channel rocker pages one screen. PageUp and PageDown are mapped
                    // too, because not every remote sends the channel keys.
                    Key.ChannelUp, Key.PageUp -> GridMove.PAGE_UP
                    Key.ChannelDown, Key.PageDown -> GridMove.PAGE_DOWN
                    else -> return@onPreviewKeyEvent false
                }
                val from = focusedIndex.intValue
                // A held Up is swallowed even when the highlight is already on the newest
                // video. Passing it on would let the rest of the hold carry focus out of the
                // grid and onto the chips, which is what went wrong on the television.
                move(gridTarget(requested, from = from, count = videos.size)) ||
                    gridKeepsKey(requested, from = from, count = videos.size)
            },
    ) {
        // This app owns every scroll of the grid. Android's default is the pivot spec, which
        // drags a newly focused card to about a third of the way down the viewport, and that
        // is what left most of the row above showing at the top edge. Seen on the TCL on
        // 2026-09-19, and the cause of the cosmetic item in spec section 9.3. With it off,
        // the scroll in the effect above is the only one, so a row always lines up.
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollOnFocus) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 44.dp,
                    end = 44.dp,
                    top = TOP_FADE,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(items = videos, key = { _, video -> video.videoId }) { index, video ->
                    VideoCard(
                        video = video,
                        watched = watches[video.videoId]?.watched == true,
                        inProgressFraction = inProgressFraction(
                            state = watches[video.videoId],
                            durationSeconds = video.durationSeconds,
                        ),
                        thumbnails = thumbnails,
                        ink = ink,
                        register = { requester ->
                            requesters[index] = requester
                            // Returned so the card can take itself out again on dispose.
                            //
                            // **Only if the entry is still this card's.** The grid is keyed by
                            // video id, so a refresh that inserts a video moves every later
                            // card down one index, and each of them registers again under its
                            // new one. A bare `remove(index)` would then let one card's
                            // disposal take out the entry another card had just written, and
                            // the grid would hand out a requester for a card that is no longer
                            // there. Found while acting on `/code-review`.
                            {
                                if (requesters[index] === requester) requesters.remove(index)
                            }
                        },
                        // **Two answers, kept apart, and counted in two different things.**
                        // [highlightOn] is what this card reports and is the ladder's exit
                        // test, and it names the **video**, because a refresh moves a card's
                        // place in the grid without the card ever reporting anything.
                        // [focusedIndex] is the card the app is standing on, which the header
                        // line reads and which `move` and `handHighlightBack` write before any
                        // card has answered; it is a **place**, and the screen's own
                        // `refocusIndexAfterRefresh` is what moves it when the list does.
                        // Writing one field for both is the defect build ticket 41 found on
                        // the home rail and build ticket 47 fixed here.
                        onHighlightChanged = { holdsIt ->
                            highlightOn.report(video.videoId, holdsIt)
                            if (holdsIt) focusedIndex.intValue = index
                        },
                        onSelect = { onSelect(video) },
                        onHold = { onHold(index) },
                    )
                }
            }
        }

        // Painted over the top edge, so a sliver of the row above never shows. Remembered,
        // because building a gradient on every frame is work for nothing.
        val fade = remember(ink.surface) {
            Brush.verticalGradient(
                0f to ink.surface,
                0.6f to ink.surface,
                1f to ink.surface.copy(alpha = 0f),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(TOP_FADE)
                .background(fade),
        )
    }
}

/**
 * Whether the card at [index] is **wholly** on screen.
 *
 * Counting a half-visible card as visible is what left a row of titles with their thumbnails
 * cut off above the top edge, seen on the TCL on 2026-09-19.
 */
private fun LazyGridState.isWhollyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset.y >= info.viewportStartOffset &&
        item.offset.y + item.size.height <= info.viewportEndOffset
}

/** The empty state and the failure message. Both are read, not scanned. */
@Composable
private fun Message(text: String, ink: Ink) {
    Text(
        text = text,
        color = ink.onSurface,
        style = Fonts.Body,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
    )
}

/**
 * The card menu, drawn over the grid.
 *
 * Two levels, one at a time, in the same place on the screen. Spec section 7.3 chose two
 * levels over one flat list of six lines, judged on the television: the first level says
 * "three choices" so the second step is signposted, and every line stays short.
 *
 * It is a panel over a scrim rather than a window of its own. A `Dialog` brings a second
 * window, and a second window on Android TV brings its own focus rules, which is a fight for
 * nothing when the panel never needs to outlive the screen behind it.
 *
 * `BACK` is handled by the caller, which owns the menu state. **`Up` and `Down` are not left to
 * Compose.** Each line names the line its neighbour is, through a `FocusRequester`, and a line
 * with no neighbour cancels the search outright. See [panelNeighbours] and [menuNeighbour].
 *
 * That is build ticket 33, and it is removing a trap rather than fixing a defect. This menu was
 * checked on the household television on 2026-09-21 and did **not** lose the highlight, because
 * it is drawn over the grid, whose rows run the full width and are spaced differently from the
 * home rail's cards. The home rail's own menu did lose it; see build ticket 31. The geometry is
 * the only thing that separated the two, so a change to this panel's height, to the grid's row
 * height, or to the number of lines could have turned this into that at any time, and nothing
 * in the build would have caught it.
 *
 * **One line of a submenu can be dim.** `cardMenuSubLinesEnabled` says which, and everything
 * the column counts is then counted in live lines rather than drawn ones. Where the highlight
 * opens is [firstLiveLine] and the end-of-column rule reads [liveLineRank]. Counting drawn
 * lines instead would leave `Up` from the first live line unguarded, and Compose would find a
 * card behind the scrim.
 *
 * @param watched whether the card this menu is about is watched right now. It decides which
 *   `Just this` does nothing and so which one is dim. Spec section 7.3.
 * @param hasFocus set as soon as a line takes the highlight, and read by the guard on the
 *   screen's outermost `Box`. See the comment on `cardMenuHasFocus`.
 */
@Composable
private fun CardMenuOverlay(
    menu: CardMenu,
    video: StoredVideo,
    listed: List<StoredVideo>,
    ink: Ink,
    watched: Boolean,
    walkFinished: Boolean,
    hasFocus: MutableState<Boolean>,
    onOpenSub: (Boolean) -> Unit,
    onJumpToPeriod: () -> Unit,
    onMark: (CardMenuSubLine, Boolean) -> Unit,
    onPlayFromStart: () -> Unit,
) {
    // Counted once per opening of a submenu, not once per recomposition. It is one pass over
    // the Source's videos, and a Source can hold several thousand.
    val subLines = remember(video, listed, walkFinished) {
        cardMenuSubLines(video, listed, walkFinished)
    }
    val labels = when (menu) {
        is CardMenu.Top -> cardMenuTopLines()
        is CardMenu.Sub -> subLines.map { it.label }
        CardMenu.Closed -> emptyList()
    }

    // Every top line is live on every card. Only a submenu ever draws a dim line, and only its
    // `Just this`, on the card whose state already says what that line would write.
    val live = when (menu) {
        is CardMenu.Sub ->
            cardMenuSubLinesEnabled(subLines, markWatched = menu.markWatched, pivotWatched = watched)
        else -> List(labels.size) { true }
    }

    val openOn = firstLiveLine(live)

    // One requester per drawn line, so a line can name the line it means. Only a live line is
    // ever given one to attach: a dim line is not focusable, and a requester that reaches no
    // focus target throws when anything asks it for focus. [neighbourRequester] never answers
    // with a dim line's requester, so no unattached one is ever named.
    val requesters = remember(menu, labels.size) { List(labels.size) { FocusRequester() } }

    /**
     * Which drawn line of this menu reports holding the highlight.
     *
     * **It is the retry ladder's exit test, and nothing but a line's own focus report writes
     * it.** Build ticket 47.
     *
     * **It is remembered for the whole time the menu is open, across every level change.** A
     * level change swaps four lines for at most three and Compose reuses the same nodes, so a
     * node that already holds the highlight reports nothing at all; a record rebuilt on the
     * level change would read as holding the highlight nowhere and the ladder would give up
     * about 484 ms later on a menu that was fine. Drawn line 1 of a submenu is literally the same
     * node as drawn line 1 of the top level, so naming the drawn index is naming the node.
     */
    val highlightOn = remember { HighlightRecord<Int>() }

    // The menu is useless until something in it has the highlight, and the lines have only
    // just been placed. The same retry the grid uses, for the same reason.
    //
    // **It asks the first line that is not dim**, never line 0. A dim line is not focusable, so
    // asking it would fail on all four attempts and leave the menu with no highlight at all.
    //
    // **Keyed on `openOn` as well as on the menu.** A submenu opened while the first walk was
    // still running holds one line, and that line can be the dead one, so there is nothing to
    // ask. When the walk finishes under it, the submenu grows two live lines and `openOn` turns
    // from null into a real line. The menu state did not change, so an effect keyed on that
    // alone would never ask, and the menu would sit there with no highlight until `BACK`.
    //
    // **Keyed on `requesters` as well, since build ticket 33.** The retry ladder runs for up to
    // about 484 ms and holds the requester list from the composition that started it. If the first
    // walk finishes inside that window, the submenu grows from one line to three, a new list is
    // built, and every remaining attempt would be asking a requester attached to nothing. Those
    // failures are swallowed by `runCatching`, the ladder gives up, and the menu sits there with
    // no highlight until `BACK`. Found by `/code-review`.
    //
    // **The exit test is [highlightOn] and never a `requestFocus` that did not throw.** Build
    // ticket 47. The old ladder stopped on the first call that threw nothing, which is true of
    // an attached line that quietly refuses the highlight, and that line is the one case a
    // ladder exists for. Worse, the old ladder then wrote `hasFocus.value = true` itself, so
    // the screen's own key guard opened on the strength of an ask that may have moved nothing,
    // and every key the menu does not keep reached the grid behind the scrim.
    //
    // **[hasFocus] is settled from the record when the ladder ends, and that is not the same
    // as the line reporting.** Compose reuses the line nodes between the two menu levels, so a
    // node that already holds the highlight reports no focus change at all, and on that path
    // no report would ever arrive. [highlightOn] is deliberately not rebuilt on a level change,
    // so in exactly that case it still names the drawn line that holds the highlight, the first
    // rung reads true, and this line says so. Found by `/code-review` on build ticket 19 and
    // answered differently by build ticket 47.
    LaunchedEffect(menu, openOn, requesters) {
        val line = openOn ?: return@LaunchedEffect
        val target = requesters.getOrNull(line) ?: return@LaunchedEffect
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { target.requestFocus() } },
            holds = { highlightOn.holds(line) },
        )
        hasFocus.value = highlightOn.holdsAnything
    }

    // Which line has the highlight, **counted in live lines**. The menu keeps any key that
    // would take focus off the ends of the column, because the cards behind the scrim are
    // still focusable and Compose's focus search would happily find one.
    val focusedLine = remember(menu) { mutableIntStateOf(liveLineRank(live, openOn ?: 0)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ink.menuScrim)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val move = when (event.key) {
                    Key.DirectionUp -> MenuMove.UP
                    Key.DirectionDown -> MenuMove.DOWN
                    Key.DirectionLeft, Key.DirectionRight,
                    Key.ChannelUp, Key.ChannelDown, Key.PageUp, Key.PageDown,
                    -> MenuMove.SIDEWAYS
                    else -> return@onPreviewKeyEvent false
                }
                cardMenuKeepsKey(
                    move = move,
                    focusedLine = focusedLine.intValue,
                    lineCount = live.count { it },
                    hasFocus = hasFocus.value,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 620.dp)
                .background(ink.surfaceRaised, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Which video this is about. A menu with no title is a menu that can be used on
            // the wrong card without the household ever noticing.
            Text(
                text = video.title.ifBlank { video.videoId },
                color = ink.onSurface,
                style = Fonts.FocusedTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (menu is CardMenu.Sub) {
                Text(
                    text = cardMenuSubTitle(menu.markWatched),
                    color = ink.onSurfaceMuted,
                    style = Fonts.Meta,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))

            labels.forEachIndexed { index, label ->
                MenuLine(
                    label = label,
                    ink = ink,
                    line = index,
                    enabled = live.getOrElse(index) { true },
                    focusRequester = requesters[index].takeIf { live.getOrElse(index) { true } },
                    // The neighbour, named rather than searched for. `FocusRequester.Cancel`
                    // stops the search dead at an end of the column, which is what keeps the
                    // grid behind the scrim from taking the highlight and scrolling.
                    up = neighbourRequester(live, index, MenuMove.UP, requesters),
                    down = neighbourRequester(live, index, MenuMove.DOWN, requesters),
                    // **Two answers, kept apart.** [highlightOn] is what this line reports and
                    // is the ladder's exit test; [focusedLine] and [hasFocus] are what the
                    // menu's key guards read. A loss is recorded as well as a gain, because a
                    // record that only ever goes true is a record that cannot say the
                    // highlight has left the menu. Build ticket 47.
                    onHighlightChanged = { holdsIt ->
                        highlightOn.report(index, holdsIt)
                        if (holdsIt) focusedLine.intValue = liveLineRank(live, index)
                        hasFocus.value = highlightOn.holdsAnything
                    },
                    // **A line destroyed while it holds the highlight tells the record so.**
                    // Build ticket 49. The top level draws four lines and a submenu at most
                    // three, so a level change destroys drawn line 3. `setMenu` hands the
                    // highlight to a grid card before that happens, but `panelHandBack`
                    // answers null for a card the lazy grid is not holding, and on that path
                    // the only thing that clears the record is the destroyed line's own loss
                    // report. Were that report ever not delivered, the record would go on
                    // naming drawn line 3, and if the new level's ladder also failed every
                    // rung the line below it would read `holdsAnything` as true and open the
                    // screen's key guard on a menu that holds nothing.
                    //
                    // **Compose does deliver it today**, measured on the `googletv1080p`
                    // emulator on 2026-09-21 for a plain `Column` as well as for a
                    // `LazyColumn`, so this is the belt to `setMenu`'s brace and not a fix
                    // for something reachable now. `highlightAfterDestruction` carries the
                    // measurement.
                    //
                    // **`replaced` is always false here**, and that is a property of this
                    // `Column` rather than a guess. The lines are drawn by `forEachIndexed`
                    // into positional slots, so a level change only ever removes slots from
                    // the end and nothing can have taken a destroyed line's place. The Period
                    // jump list's rows are in a `LazyColumn` and have to answer it from their
                    // requester's identity.
                    onDestroyed = {
                        highlightOn.reportDestroyed(node = index, replaced = false)
                        hasFocus.value = highlightOn.holdsAnything
                    },
                    onChoose = {
                        when (menu) {
                            // Two of the four top lines act at once. The two marking lines
                            // open a submenu instead, so the household always sees what it is
                            // about to do before it happens.
                            is CardMenu.Top -> when (label) {
                                PLAY_FROM_START -> onPlayFromStart()
                                JUMP_TO_A_PERIOD -> onJumpToPeriod()
                                else -> onOpenSub(label == MARK_AS_WATCHED)
                            }
                            // By position, not by label. The label carries a count, so
                            // matching on its text would break the first time a count changed.
                            is CardMenu.Sub ->
                                subLines.getOrNull(index)?.let { onMark(it, menu.markWatched) }
                            CardMenu.Closed -> Unit
                        }
                    },
                )
            }
        }
    }
}

/**
 * One line of the card menu. Filled when focused, like every other focusable on this screen.
 *
 * **A line that is not [enabled] is drawn dim and is not focusable at all**, so the D-pad walks
 * straight past it and it can never be chosen. It is the same treatment the `Jump to last
 * played` chip gets when it has nowhere to land, down to the ink: `onSurfaceFaint`, no fill and
 * no ring. The owner chose dim over hiding the line, so its place on the menu stays learnable and
 * the card's own state is visible from inside the menu. Spec section 7.3, ticket 19.
 *
 * @param line this line's drawn place in the menu's `Column`, which is this node's whole
 *   identity: the lines are drawn into positional slots, so a node keeps its drawn place for as
 *   long as it lives. It is what the disposal effect below is keyed on.
 * @param onDestroyed called once, when this node leaves composition. It is what tells the
 *   menu's record that a line holding the highlight has gone, without the record having to
 *   depend on Compose delivering that line's loss first. Build ticket 49.
 * @param up the line `Up` moves to, or [FocusRequester.Cancel] when there is none above. The
 *   menu works this out with [neighbourRequester]; the line is only told the answer.
 * @param down the same, downwards.
 */
@Composable
private fun MenuLine(
    label: String,
    ink: Ink,
    line: Int,
    focusRequester: FocusRequester?,
    up: FocusRequester,
    down: FocusRequester,
    onHighlightChanged: (Boolean) -> Unit,
    onDestroyed: () -> Unit,
    onChoose: () -> Unit,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }

    // **Keyed on [line], so it is torn down only when this node really leaves composition.**
    // A cleared record shuts the screen's key guard, so this effect must never restart merely
    // because a lambda changed identity: each restart would read as a destroyed line and empty
    // the menu's record under a line that is still on the screen. [line] is this node's whole
    // identity, because the lines are drawn into the positional slots of a `Column`. A level
    // change reuses drawn line 1 of the top level as drawn line 1 of the submenu, so [line]
    // does not change there; what it does is destroy the slots past the shorter level's end,
    // which is the case this exists for. `PeriodLine` keys its own effect the same way and
    // carries the measurement behind it. Build ticket 49.
    DisposableEffect(line) {
        onDispose { onDestroyed() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(
                color = if (focused) ink.focusFill else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 2.dp,
                color = if (focused) ink.accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .let { if (focusRequester == null) it else it.focusRequester(focusRequester) }
            // Named neighbours, so no key inside this menu ever reaches Compose's
            // two-dimensional focus search. `Left` and `Right` are not named here: the menu's
            // own guard keeps them, and naming `Cancel` for them as well would say the same
            // thing in a second place.
            //
            // Only on a live line. A dim line is not focusable, so it is never asked which way
            // to go, and naming its neighbours would be one more thing to keep true for
            // nothing.
            .let { if (enabled) it.panelNeighbours(above = up, below = down) else it }
            .onFocusChanged { focusState ->
                // Only a real change is reported on. Compose delivers `onFocusChanged` on
                // other things than a gain or a loss, and a record written from a delivery
                // that changed nothing would report a loss this line never had.
                val changed = focusState.isFocused != focused
                focused = focusState.isFocused
                if (changed) onHighlightChanged(focusState.isFocused)
            }
            // A tap chooses the line. A hold does nothing here, and must not fall through to
            // the release that opened this menu in the first place.
            //
            // A dim line takes neither, and is not focusable, so nothing about it can act. The
            // key handler goes too rather than being left behind a test: a modifier that is
            // never reached is a modifier nobody can be sure of.
            .let { if (enabled) it.onSelectOrHoldKey(onSelect = onChoose, onHold = {}) else it }
            .focusable(enabled = enabled)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> ink.onSurfaceFaint
                focused -> ink.onFocus
                else -> ink.onSurface
            },
            style = Fonts.Chip,
            maxLines = 1,
        )
    }
}

/**
 * One row of the Period jump list, and the gap under it.
 *
 * The panel's height is [periodPanelRows] of these, so the rule that a three-entry panel is
 * three entries tall is settled on the JVM and only multiplied out here.
 */
private val PERIOD_ROW_HEIGHT = 46.dp
private val PERIOD_ROW_GAP = 6.dp

/**
 * The Period jump list, drawn over the grid.
 *
 * A panel over a scrim, exactly like the card menu, and for the same reason: a `Dialog` brings
 * a second window, and a second window on Android TV brings its own focus rules.
 *
 * **It is sized to its contents.** Three entries is three entries tall. That is the cosmetic
 * item spec section 9.3 left for this ticket, and the rule itself is [periodPanelRows]. Past
 * [PERIOD_PANEL_MAX_ROWS] the list scrolls instead of growing, which is why this is a
 * `LazyColumn` and not a plain `Column`: a Source spanning fifteen years has fifteen entries,
 * and a busy one can have up to 36.
 *
 * `BACK` is handled by the caller, which owns whether the panel is open.
 *
 * **The panel walks its own rows, and never asks Compose to find the next one.** That is build
 * ticket 33, and it is not the fix the card menu next door took. A `FocusRequester` cannot name
 * a row that is not composed, and past [PERIOD_PANEL_MAX_ROWS] rows most of this list is not.
 * So the shape is the grid's: [periodTarget] says which row the key means,
 * [periodPanelScrollTo] says what to scroll so that row is drawn, and only then is it asked for
 * focus. Both are pure and unit-tested on the JVM.
 *
 * **A refresh that destroys the row holding the highlight is asked back onto a row that is
 * still there.** Build ticket 51. The rows are keyed by their place in the panel, so a shorter
 * list of Periods destroys the rows past its end, and the household standing on one of them
 * would otherwise be left on an open panel answering nothing but `BACK`. [periodRowAfterRefresh]
 * is the rule; the effect that carries it out is below [move] and says why it sits there.
 *
 * **A refresh that leaves no Period at all closes this panel instead.** Build ticket 52. There
 * is no row to ask for the highlight then, so the recovery above cannot answer it and the
 * household would be left looking at an empty box. The close is the caller's, because the
 * caller owns whether the panel is open; `periodPanelCloseAfterRefresh` in `ui/PeriodPanel.kt`
 * is that rule and `closePeriods` in [SourceScreen] is what carries it out.
 *
 * @param openOnEntry the entry holding the focused video, worked out by the caller before the
 *   panel was opened. The list scrolls to it and it takes the highlight.
 * @param hasFocus set to true here, the moment a row reports focus, and **read by the screen**.
 *   It is the screen's state and not this panel's, because until a row has the highlight this
 *   panel is off the key path entirely. See the outer `Box`'s key handler.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeriodPanelOverlay(
    periods: List<Period>,
    openOnEntry: Int,
    hasFocus: MutableState<Boolean>,
    ink: Ink,
    onChoose: (Period) -> Unit,
) {
    val listState = rememberLazyListState()

    /** The requester of every row composed right now, the same way the grid keeps its cards. */
    val requesters = remember { mutableMapOf<Int, FocusRequester>() }

    /** The row the panel opens on. Never negative: the caller answers -1 for nothing selected. */
    val openOnRow = openOnEntry.coerceAtLeast(0)

    // Which row has the highlight, so the panel knows when a key would take focus out of it.
    val focusedLine = remember { mutableIntStateOf(openOnRow) }

    /** How many rows the panel draws. Never more than [PERIOD_PANEL_MAX_ROWS]. */
    val rows = periodPanelRows(periods.size)
    val listHeight = PERIOD_ROW_HEIGHT * rows + PERIOD_ROW_GAP * (rows - 1).coerceAtLeast(0)

    /** The move waiting to be carried out, exactly as the grid keeps one. */
    var moves by remember { mutableLongStateOf(0L) }

    // The first move of all is the one that opens the panel on the Period holding the focused
    // video. Spec section 7.4. It scrolls **to that row**, because the entry is normally
    // nowhere near the top of the list, and it scrolls whether or not the row would already be
    // drawn, which is the behaviour the panel has had since ticket 09.
    var pending by remember {
        mutableStateOf(PeriodMove(seq = 0, index = openOnRow, scrollTo = openOnRow))
    }

    /**
     * Which row of this panel reports holding the highlight.
     *
     * **It is the retry ladder's exit test, and nothing but a row's own focus report writes
     * it.** Build ticket 47. The ladder used to exit on
     * `runCatching { requestFocus() }.isSuccess`, which says only that the call did not throw,
     * so its first rung read success whatever the ask had done. That mattered more here than
     * anywhere else on the screen: the panel's own key guard is `hasFocus`, and while no row
     * holds the highlight the screen's outer `Box` keeps every key but `BACK`, so a ladder that
     * gave up early left the household on a panel that answers nothing.
     *
     * It is remembered for as long as the panel is open, which is the whole life of this
     * composable.
     */
    val highlightOn = remember { HighlightRecord<Int>() }

    // The row has to be drawn before it can be asked for focus: a `LazyColumn` has not composed
    // a row that is off the bottom, and a row that does not exist cannot take focus, nor has it
    // registered itself yet. The retries are the grid's own, for the same reason.
    LaunchedEffect(pending) {
        // `scrollToItem` puts the row it is given at the top of the panel, which is why the
        // row to scroll to is worked out by `periodPanelScrollTo` and is not the target itself.
        // On the opening move it is the target, because the panel opens with that entry at the
        // top whether or not it would fit anywhere else.
        pending.scrollTo?.let { listState.scrollToItem(it) }
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { requesters[pending.index]?.let { runCatching { it.requestFocus() } } },
            holds = { highlightOn.holds(pending.index) },
        )
    }

    /**
     * Carries the highlight to [to], scrolling the list under it when it is not drawn.
     *
     * The same shape as the grid's own `move`, and answering the same way: true when the panel
     * took the key, false when it did not.
     */
    fun move(to: Int?): Boolean {
        if (to == null) return false
        moves++
        pending = PeriodMove(
            seq = moves,
            index = to,
            // Whether to scroll travels with the target rather than beside it, for the reason
            // written on [FocusMove]: kept apart, the two fall out of step when a second key
            // arrives before the first has been carried out.
            scrollTo = periodPanelScrollTo(to, listState.firstVisibleItemIndex, rows),
        )
        // Said now, not when the row reports focus. A row that is asked for focus and does not
        // get it would otherwise leave the next key press measured from a row the household
        // can no longer see.
        focusedLine.intValue = to
        return true
    }

    // **Asks a surviving row for the highlight when a refresh destroyed the row holding it.**
    // Build ticket 51. [periodRowAfterRefresh] is the rule and is unit-tested on the JVM.
    //
    // `periods` is derived on the screen and never cached, `remember(videos, direction) {
    // periodsOf(videos) }`, so a refresh landing while this panel is open rebuilds it. These
    // rows are keyed by their place in the panel, so a shorter list destroys the rows past its
    // end. If the household was standing on one of those, Compose takes the highlight out of
    // the panel altogether: it goes to the `Order` chip above the grid, measured on the
    // `googletv1080p` emulator on 2026-09-21. The screen's outer key guard is then shut, which
    // is right and is build ticket 49's work, but before this effect nothing asked for the
    // highlight back, so the household was left looking at an open panel that answered nothing
    // but `BACK`.
    //
    // **Keyed on the row count, which is exactly the thing that can destroy a row.** The rows
    // are `0 until periods.size`, so a rebuilt list of the same length destroys nothing however
    // much its contents changed, and this effect does not restart for it at all. That is what
    // keeps `aRefreshThatDestroysNoRowLeavesThePanelWorking` in
    // `app/src/androidTest/kotlin/local/youtubetv/ui/PanelRowDestroyedTest.kt` true.
    //
    // **It is an effect and not the row's own disposal, and that is the whole placement.** A
    // row's `DisposableEffect` runs during the apply of the composition that removed it, and
    // writing [pending] from there would start a composition from inside one. An effect runs
    // after the composition is applied, which is where every other write of [pending] on this
    // panel comes from.
    //
    // **It asks for nothing on the composition that opens the panel.** [focusedLine] starts at
    // [openOnRow], which `openPeriods` in [SourceScreen] works out from the live list, so the
    // rule answers null and the opening move already in [pending] is left to do its work.
    //
    // **And it asks for nothing when the refresh leaves no Period at all**, because there is no
    // row to ask. No recovery can reach that panel: it needs the panel to close itself, which
    // is what build ticket 52 built. The close is an effect in [SourceScreen], beside the card
    // menu's own close on the same event, and the rule is
    // `periodPanelCloseAfterRefresh`. So this effect and that one never both act on one
    // refresh: this one needs a row and that one needs there to be none. Found by
    // `/code-review` on build ticket 51.
    LaunchedEffect(periods.size) {
        move(periodRowAfterRefresh(focusedRow = focusedLine.intValue, rowCount = periods.size))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ink.menuScrim)
            .onPreviewKeyEvent { event ->
                // The same guard as the screen's, and it is not the one that does the work.
                // While no row has the highlight this handler is off the key path altogether,
                // so the screen's outer `Box` is what keeps those keys. This is here because
                // the rule belongs to the panel and reads the same either way.
                if (!hasFocus.value) return@onPreviewKeyEvent true
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Named `requested` and not `move`, because `move` is the panel's own function
                // for carrying the highlight, exactly as it is in the grid.
                val requested = when (event.key) {
                    Key.DirectionUp -> MenuMove.UP
                    Key.DirectionDown -> MenuMove.DOWN
                    Key.DirectionLeft, Key.DirectionRight,
                    Key.ChannelUp, Key.ChannelDown, Key.PageUp, Key.PageDown,
                    -> MenuMove.SIDEWAYS
                    else -> return@onPreviewKeyEvent false
                }
                // **Clamped into the list, and that is not defensive tidiness.** A refresh
                // that lands while the panel is open rebuilds `periods` under it, and a
                // shorter list leaves this pointing past the end. [periodTarget] answers null
                // for a row outside the list, so without the clamp the key would fall through
                // to the guard, which reads `focusedLine <= 0` for `Up` and would hand `Up`
                // straight to Compose's focus search: the one thing this ticket exists to
                // stop. Clamping both moves the highlight back into the panel and keeps the
                // guard's two ends meaningful. Found by `/code-review`.
                val from = focusedLine.intValue.coerceIn(0, (periods.size - 1).coerceAtLeast(0))
                // The panel moves its own highlight. Nothing inside it ever reaches Compose's
                // two-dimensional focus search, so the search can never find a video card
                // behind the scrim. Build ticket 33; the rule is [periodTarget].
                move(periodTarget(requested, from, periods.size)) ||
                    periodPanelKeepsKey(
                        move = requested,
                        focusedLine = from,
                        lineCount = periods.size,
                        hasFocus = hasFocus.value,
                    )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 620.dp)
                .background(ink.surfaceRaised, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            // What this panel is. Without it a column of bare years over a dimmed grid could
            // be read as the card menu, which opens in the same place with the same shape.
            Text(
                text = PERIOD_PANEL_TITLE,
                color = ink.onSurfaceMuted,
                style = Fonts.Meta,
                maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))
            // This app owns every scroll of the panel, the same way it owns every scroll of
            // the grid. Left to Android, a row that takes focus is dragged to a pivot of the
            // viewport's own choosing, and that scroll and the one above would fight: the
            // panel would move twice for one press and `periodPanelScrollTo` would be telling
            // the truth about only the first half of it.
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoScrollOnFocus) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = listHeight),
                    verticalArrangement = Arrangement.spacedBy(PERIOD_ROW_GAP),
                ) {
                    // **Keyed on the row's own place in the panel, and never on anything the
                    // Period carries.** Build ticket 33 changed this, and the reason is focus.
                    //
                    // `periods` is rebuilt whenever the videos change, so a refresh landing
                    // while the panel is open rebuilds it under the household. In a
                    // newest-first Source one added video moves `firstIndex` along for every
                    // Period but the newest, so a key made of `firstIndex` changes for almost
                    // every row. `LazyColumn` then disposes each of those nodes and composes
                    // new ones, **including the node holding the highlight**. Compose does not
                    // hand the highlight to the replacement, `LaunchedEffect(pending)` does not
                    // re-run because no move was asked for, and `hasFocus` is still true, so
                    // the screen's outer guard has stopped protecting: the next key reaches the
                    // grid behind the scrim. Found by `/code-review`.
                    //
                    // The row index is unique by construction and does not move, so the node
                    // holding the highlight survives and simply redraws with its new year.
                    //
                    // **The calendar block was never a candidate.** Two Periods carry different
                    // blocks only while the list handed in is in date order, which is true of
                    // everything that reaches here today and is not a property this row can
                    // check. A list that was not would give two rows the same key, and a
                    // `LazyColumn` throws on a repeated key and takes the whole screen down.
                    // Found by `/code-review` on build ticket 09.
                    lazyItemsIndexed(
                        items = periods,
                        key = { index, _ -> index },
                    ) { index, period ->
                        PeriodLine(
                            row = index,
                            period = period,
                            ink = ink,
                            register = { requester ->
                                requesters[index] = requester
                                // Taken out again on dispose, but **only if the entry is still
                                // this row's**. A disposal is delivered after the node that
                                // replaced it has already registered, so a bare
                                // `remove(index)` can take out an entry the new node has just
                                // written, and the panel is then holding no requester for a row
                                // that is on the screen. The grid carries the same fix, found
                                // by `/code-review` there; build ticket 33 brought it here,
                                // because the D-pad walk reads this map on every press rather
                                // than only when the panel opens.
                                {
                                    val registered = requesters[index]
                                    if (registered === requester) requesters.remove(index)
                                    // **And [highlightOn] with it, which is build ticket 49.**
                                    // A refresh that empties the last Period bucket destroys
                                    // the last row, because these rows are keyed by their
                                    // place and a place past the end of a shorter list is
                                    // gone. If that row went without reporting its loss, the
                                    // record would go on naming it, `hasFocus` would stay
                                    // true, and the screen's outer key guard would read as
                                    // open while Compose had already handed the highlight to
                                    // something behind the scrim.
                                    //
                                    // **Compose does report that loss today**, measured on the
                                    // `googletv1080p` emulator on 2026-09-21, so this line is
                                    // what keeps the guard right if that ever changes rather
                                    // than a fix for something reachable now. `PeriodLine`
                                    // and `highlightAfterDestruction` carry the measurement.
                                    //
                                    // **`replaced` means another node has taken this place,
                                    // and it is not simply the opposite of the test above.**
                                    // The entry can also be missing altogether, and that is a
                                    // row that has gone with nothing behind it, which is the
                                    // case this whole block exists for: it has to clear the
                                    // record, not leave it standing. Answering `replaced` as
                                    // `!(registered === requester)` would read a missing
                                    // entry as a replacement and walk straight into the hole
                                    // build ticket 49 was raised to close. Found by
                                    // `/code-review` on build ticket 49.
                                    //
                                    // The replacement case itself has to be kept, because
                                    // clearing a record the replacement node has just written
                                    // would shut the guard on a panel that is perfectly fine,
                                    // for the 484 ms its ladder takes to give up.
                                    // [highlightAfterDestruction] is the rule.
                                    highlightOn.reportDestroyed(
                                        node = index,
                                        replaced = registered != null && registered !== requester,
                                    )
                                    hasFocus.value = highlightOn.holdsAnything
                                }
                            },
                            // **Two answers, kept apart.** [highlightOn] is what this row
                            // reports and is the ladder's exit test; [focusedLine] and
                            // [hasFocus] are what the panel's and the screen's key guards
                            // read. A loss is recorded as well as a gain, because a record
                            // that only ever goes true is a record that cannot say the
                            // highlight has left the panel. Build ticket 47.
                            onHighlightChanged = { holdsIt ->
                                highlightOn.report(index, holdsIt)
                                if (holdsIt) focusedLine.intValue = index
                                hasFocus.value = highlightOn.holdsAnything
                            },
                            onChoose = { onChoose(period) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One Period on the panel: the calendar block, and how many videos it holds.
 *
 * The count is on the same row, in the quieter ink, because it is what tells a year worth
 * jumping into from one holding three videos. Hidden Shorts are in none of these numbers.
 *
 * It owns its own [FocusRequester] and hands it to the panel through [register], exactly the
 * way a video card does, so the panel can put the highlight on any row without a row ever
 * having to know whether it is the target.
 *
 * @param row this row's place in the panel, which is also the key `PeriodPanelOverlay`'s
 *   `LazyColumn` gives it, and so is this node's whole identity. It is what the registration
 *   effect below is keyed on.
 */
@Composable
private fun PeriodLine(
    row: Int,
    period: Period,
    ink: Ink,
    register: (FocusRequester) -> () -> Unit,
    onHighlightChanged: (Boolean) -> Unit,
    onChoose: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    // Registered from an effect and never while composing. Composition can be run again or
    // thrown away, and must leave nothing behind in the panel's map.
    //
    // **Keyed on [row] and not on [register], and that is what makes the disposal mean
    // something.** Build ticket 49 made the unregister clear the panel's highlight record as
    // well, and a cleared record shuts the screen's key guard, so this effect must be torn down
    // only when the node really leaves composition, never merely because a lambda changed
    // identity. [row] is this node's whole identity, since `PeriodPanelOverlay`'s `LazyColumn`
    // is keyed by `key = { index, _ -> index }`, so for a given node it never changes at all.
    //
    // **Keyed on `register` it happens to behave the same today, and that is not a reason to
    // leave it there.** Both of `PanelRowDestroyedTest`'s cases still pass with `register` as
    // the key, measured on the `googletv1080p` emulator on 2026-09-21, because the Compose
    // compiler's strong skipping memoises the panel's `register` lambda so the effect never
    // restarts. That is a property of the compiler and not of this file, and nothing here would
    // notice it changing except the household, who would find the panel answering no key but
    // `BACK` for the 484 ms after every refresh.
    DisposableEffect(row) {
        val unregister = register(requester)
        onDispose { unregister() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PERIOD_ROW_HEIGHT)
            // How build ticket 49's test says which row holds the highlight. See [TestTags].
            .testTag(TestTags.periodRow(row))
            .background(
                color = if (focused) ink.focusFill else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 2.dp,
                color = if (focused) ink.accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .focusRequester(requester)
            .onFocusChanged { focusState ->
                // Only a real change is reported on. Compose delivers `onFocusChanged` on
                // other things than a gain or a loss, and a record written from a delivery
                // that changed nothing would report a loss this row never had.
                val changed = focusState.isFocused != focused
                focused = focusState.isFocused
                if (changed) onHighlightChanged(focusState.isFocused)
            }
            // A tap chooses the Period. A hold does nothing here, and must not fall through to
            // the release of whatever press opened this panel.
            .onSelectOrHoldKey(onSelect = onChoose, onHold = {})
            .focusable()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = periodLabel(period),
            color = if (focused) ink.onFocus else ink.onSurface,
            style = Fonts.Chip,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = periodVideoCount(period),
            color = if (focused) ink.onFocus else ink.onSurfaceMuted,
            style = Fonts.Meta,
            maxLines = 1,
        )
    }
}

/**
 * The question a long press on the `Shuffle` chip asks. Build ticket 69, rule 10.
 *
 * It follows the confirmation pattern of `Remove the saved key` on the Key screen: the question,
 * then the answer that changes nothing, focused first, then the answer that acts. A press made
 * without reading keeps the run.
 *
 * It is drawn the way the card menu is, over a scrim and with the same [MenuLine]s, and for the
 * same reasons: a `Dialog` brings a second window with focus rules of its own, and the card
 * menu's lines already carry every focus lesson this screen has learnt. The guard on the
 * screen's outer `Box` keeps every key until a line holds the highlight, which is what stops
 * the release of the long press from reaching the chip behind the scrim and toggling it.
 *
 * `BACK` is the caller's, which owns whether the question is open, and it keeps the run.
 *
 * @param hasFocus set as soon as a line takes the highlight, and read by that guard.
 */
@Composable
private fun ShuffleQuestionOverlay(
    question: String,
    ink: Ink,
    hasFocus: MutableState<Boolean>,
    onKeep: () -> Unit,
    onStartNew: () -> Unit,
) {
    val labels = listOf(KEEP_THIS_RUN, START_A_NEW_RUN)
    val requesters = remember { List(labels.size) { FocusRequester() } }
    val highlightOn = remember { HighlightRecord<Int>() }
    val focusedLine = remember { mutableIntStateOf(0) }
    val live = List(labels.size) { true }

    // `Keep this run` takes the highlight first, by the same ladder the card menu uses.
    LaunchedEffect(Unit) {
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { requesters[0].requestFocus() } },
            holds = { highlightOn.holds(0) },
        )
        hasFocus.value = highlightOn.holdsAnything
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ink.menuScrim)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val move = when (event.key) {
                    Key.DirectionUp -> MenuMove.UP
                    Key.DirectionDown -> MenuMove.DOWN
                    Key.DirectionLeft, Key.DirectionRight,
                    Key.ChannelUp, Key.ChannelDown, Key.PageUp, Key.PageDown,
                    -> MenuMove.SIDEWAYS
                    else -> return@onPreviewKeyEvent false
                }
                cardMenuKeepsKey(
                    move = move,
                    focusedLine = focusedLine.intValue,
                    lineCount = labels.size,
                    hasFocus = hasFocus.value,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 620.dp)
                .background(ink.surfaceRaised, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = question,
                color = ink.onSurface,
                style = Fonts.FocusedTitle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            labels.forEachIndexed { index, label ->
                MenuLine(
                    label = label,
                    ink = ink,
                    line = index,
                    focusRequester = requesters[index],
                    up = neighbourRequester(live, index, MenuMove.UP, requesters),
                    down = neighbourRequester(live, index, MenuMove.DOWN, requesters),
                    onHighlightChanged = { holdsIt ->
                        highlightOn.report(index, holdsIt)
                        if (holdsIt) focusedLine.intValue = index
                        hasFocus.value = highlightOn.holdsAnything
                    },
                    onDestroyed = {
                        highlightOn.reportDestroyed(node = index, replaced = false)
                        hasFocus.value = highlightOn.holdsAnything
                    },
                    onChoose = { if (index == 0) onKeep() else onStartNew() },
                )
            }
        }
    }
}
