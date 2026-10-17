package local.youtubetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import local.youtubetv.APP_NAME
import local.youtubetv.data.Landing
import local.youtubetv.data.SourceCard

/**
 * The home screen: one rail of Source cards, most recently played first.
 *
 * A card answers the one question the household asks from the sofa, which is what to watch next
 * in this Source. Tapping the centre button opens the Source at its Next unwatched video, and
 * holding it opens a menu with the three landings and `Mark everything unwatched`. Spec
 * section 7.1.
 *
 * **A card says nothing about a single video's progress.** No `In progress` line, no bar and no
 * percentage. Decided on 2026-09-19: inside a Source the household is looking at one video, and
 * a position belongs there, on the red bar of the video card. On this rail the household is
 * choosing a Source, and the only useful number is how much is left, which the badge gives. The
 * in-progress video is one press away, through `Open at Last played`.
 *
 * **A card does carry one picture, and one only: the Next unwatched video's thumbnail.** Ticket
 * 10 built this card text-only and said nothing may put a picture on it without amending spec
 * section 7.1. The owner looked at that rail on the household television on 2026-09-20, chose to
 * add a picture, and chose that video's, because it is the video the card already names. Ticket
 * 21 added it and amended section 7.1 in the same commit. It is still not a picture about a
 * single video's progress: there is no bar on it and no wash.
 *
 * Every rule behind this is a pure function with a JVM test: `data/HomeRail.kt` decides what a
 * card says and what order the rail is in, and `ui/HomeScreenText.kt` holds the wording. What
 * is left here is drawing it and moving the highlight.
 *
 * The rail's own left and right moves are left to Compose, unlike the Source screen's grid. A
 * row of focusable cards is the case Compose's focus search already gets right, in the same way
 * a column of menu lines is, and there is no flat sequence hidden behind visual rows here to
 * get wrong.
 */
@Composable
fun HomeScreen(
    state: HomeScreenState,
    // Opening a Source is what walks it, so this is launched on the Activity's scope and never
    // on the composition's, exactly like a write to the log.
    onOpen: (SourceCard, Landing) -> Unit = { _, _ -> },
    // `Mark everything unwatched`, after the household confirmed it. One `range` entry.
    onMarkEverythingUnwatched: (SourceCard) -> Unit = {},
    // `+ Add a Source`, from the card pinned at the end of the rail or from the first-run page.
    onAddSource: () -> Unit = {},
    // `Remove`, after the household confirmed it. The Source's row and its cached videos go;
    // every Watch it earned stays. Spec section 6.6.
    onRemove: (SourceCard) -> Unit = {},
    // `Refresh`, straight away and with nothing to confirm. It spends a few units of the daily
    // ten thousand and changes nothing the household owns. Spec section 5.1.
    onRefresh: (SourceCard) -> Unit = {},
    // `Rename`, which opens a screen of its own. It asks nothing here, because the screen it
    // opens is itself the question and `BACK` out of it writes nothing. Build ticket 27.
    onRename: (SourceCard) -> Unit = {},
    // `API key: replace or remove`, the last line of the Source menu. Opens the key screen.
    // Build ticket 64.
    onApiKey: () -> Unit = {},
    // One logcat line per rail action. The rail's order and its landings are rules rather than
    // pictures, so this is what proves them without a screenshot.
    onAction: (String) -> Unit = {},
    // The Source the household just came back from, so the rail puts the highlight back on it
    // rather than throwing them to the first card.
    focusOnSourceId: String? = null,
    // Bumped by the Activity every time the household comes back from a Source. It is a counter
    // and not just the id above, because coming back to the same Source twice in a row must
    // still move the highlight, and two equal ids would be one ask silently dropped.
    returnedFromSource: Long = 0L,
    // Where a card's picture comes from. The same seam the Source screen's grid and the add
    // screen use, so the rail shares their one in-memory cache: a Next unwatched video whose
    // picture the grid has already decoded costs the rail nothing at all. It defaults to
    // [NoThumbnails] for Compose previews, exactly as `AddSourceScreen`'s does.
    thumbnails: ThumbnailLoader = NoThumbnails,
) {
    val ink = screenInk()
    val cards = (state as? HomeScreenState.Ready)?.cards.orEmpty()

    // The same message machinery the Source screen uses, and for the same reason: two presses
    // saying the same words must be two messages with two timers.
    var toasts by remember { mutableLongStateOf(0L) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun say(message: String) {
        toasts++
        toast = message
    }

    LaunchedEffect(toast, toasts) {
        if (toast != null) {
            delay(2_600)
            toast = null
        }
    }

    // Which Source's long-press menu is open, and which Source's confirmation is. They are
    // Source ids and not indexes, because the rail reorders itself under the household the
    // moment a play is written, and an index would then name a different card.
    var menuFor by remember { mutableStateOf<String?>(null) }

    // Which confirmation is up, and what it would do. There are two of them now, and both are
    // the same panel over the same scrim, so the kind travels with the Source id rather than
    // being guessed from which of two nullable fields happens to be set.
    var confirm by remember { mutableStateOf<Confirmation?>(null) }

    // Bumped whenever something that covered the rail goes away. Without it the highlight is
    // simply gone when a menu closes, because the card lost focus to the menu and nothing gave
    // it back. The same counter the Source screen's grid uses, for the same reason.
    var refocusRequests by remember { mutableLongStateOf(0L) }

    // Which card has the highlight right now, so the menu and the confirmation can give it
    // back to the right one. It is the **Source id** and never an index, because the rail
    // reorders itself the moment a play is written and an index would then name another card.
    val focusedSourceId = remember { mutableStateOf<String?>(null) }

    /**
     * The focus requester of every rail card composed right now, keyed by Source id.
     *
     * **It lives here and not inside [SourceRail] because the overlays need it**, and they need
     * it at a moment that only this function has: the instant before a panel is taken out of
     * composition. See [handHighlightBack]. `SourceRail` still fills it, one card at a time.
     */
    val railRequesters = remember { mutableMapOf<String, FocusRequester>() }

    /**
     * Gives the highlight back to a rail card **before** the panel over it leaves composition.
     *
     * **This is build ticket 38, and it works by removing the gap rather than by racing it.**
     * Compose moves focus by itself whenever the node holding it leaves composition, and the
     * node it chooses is the nearest focusable it can find, which behind a panel is the first
     * rail card the lazy row has placed. Measured on the household television on 2026-09-21,
     * closing a card menu: the panel's line lost the highlight, one frame later the rail's
     * first placed card had it, and the rail's own ladder only took it back 109 ms after that,
     * because a ladder cannot start until the composition that removed the panel has finished.
     * When the rail is scrolled that card is mostly off the left edge, so the household sees a
     * white sliver flash at the screen's edge.
     *
     * A panel that is not holding the highlight when it goes gives Compose nothing to choose,
     * so there is no frame to fix afterwards. The card is asked while the scrim is still up,
     * which is safe in both ways that matter: [railIsCovered] is still true, so the card
     * swallows every key but `BACK`, and [NoScrollOnFocus] is still in force, so the rail does
     * not scroll to the card it is handed. Neither of those is a new rule.
     *
     * **It records the card it chose, and that is not the same as believing Compose.** A
     * covered card's own focus gain is ignored, because Compose hands the highlight out behind
     * a scrim without being asked; see [SourceRail]. This is the app choosing, so
     * [focusedSourceId] is written to match. It is almost always the value that was already
     * there, and the case where it is not is the one that matters: the Source the highlight was
     * on has left the rail, [railHighlightTarget] falls back to the first card, and without
     * this write [focusedSourceId] would go on naming a Source that is gone. Found by
     * `/code-review`.
     *
     * **It promises nothing, and the caller must not treat it as done.**
     * `FocusRequester.requestFocus` throws when no node is attached to it and says nothing at
     * all about whether the highlight moved, so this is an ask and not an answer. `SourceRail`'s
     * retry ladder is what verifies, by asking the rail where the highlight actually is, and
     * every caller here arms it. Also found by `/code-review`.
     *
     * **The write above is not what that ladder tests, and build ticket 41 is the record of
     * what happens when it is.** [focusedSourceId] is the app's own choice, written here before
     * the ladder is armed, so a ladder reading it saw what it was waiting for on its first rung
     * and returned. It now tests `SourceRail`'s `highlightOn`, which only a card's own focus
     * report writes and this function cannot reach at all.
     */
    fun handHighlightBack() {
        val target = railHighlightTarget(
            preferred = listOf(focusedSourceId.value),
            cards = cards.map { it.sourceId },
        ) ?: return
        focusedSourceId.value = target
        railRequesters[target]?.let { runCatching { it.requestFocus() } }
    }

    fun closeMenu() {
        // The highlight goes home first, while the panel is still composed, which is what
        // stops Compose choosing a card of its own. The counter is still bumped, because an
        // ask is not an answer and the ladder is what checks. It no longer costs a scroll:
        // see [railMustScrollTo].
        handHighlightBack()
        menuFor = null
        confirm = null
        refocusRequests++
    }

    /**
     * The menu is replaced by one of its two confirmations.
     *
     * The highlight is handed to a card first, for the same reason [closeMenu] does it: the
     * menu's own line is about to leave composition holding it. Both panels are drawn over the
     * same scrim, so nothing the household can see moves, and the confirmation's own ladder
     * takes the highlight from the card a moment later.
     *
     * **The rail's ladder is not armed here, and it does not need to be.** The panel that
     * arrives has a focus ladder of its own, in [MenuPanel], and that is what checks the
     * highlight really landed somewhere. On [closeMenu]'s path no panel arrives, so the rail's
     * ladder is armed instead.
     */
    fun askFirst(sourceId: String, kind: ConfirmKind) {
        handHighlightBack()
        menuFor = null
        confirm = Confirmation(sourceId, kind)
    }

    /**
     * A confirmation is refused, and the menu it was reached from comes back.
     *
     * Spec section 7.3's shape: `BACK` inside a confirmation returns to the menu, never straight
     * to the rail. The one written path for it, because `BACK` and `No` must not differ.
     *
     * The same hand-back and the same reasoning as [askFirst]: the menu that comes back brings
     * its own ladder.
     */
    fun backToMenu(sourceId: String) {
        handHighlightBack()
        menuFor = sourceId
        confirm = null
    }

    // The Source an open overlay is about went away under it, which `Remove` is exactly what
    // does. It is cleared from an effect and never while composing: a composition can be run
    // again or thrown away, and writing state from inside one is how a screen ends up in a
    // state no press ever put it in.
    LaunchedEffect(menuFor, confirm, cards) {
        val open = confirm?.sourceId ?: menuFor ?: return@LaunchedEffect
        if (cards.none { it.sourceId == open }) closeMenu()
    }

    BackHandler(enabled = menuFor != null || confirm != null) {
        // `BACK` inside the confirmation returns to the menu, never straight to the rail, the
        // same way `BACK` inside a card submenu returns to its two top lines. Spec section 7.3
        // settled that shape and this menu follows it.
        val open = confirm
        if (open != null) backToMenu(open.sourceId) else closeMenu()
    }

    Box(modifier = Modifier.fillMaxSize().background(ink.surface)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp)) {
                Text(text = APP_NAME, color = ink.onSurface, style = Fonts.SourceTitle, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                // **The same two lines in all three states, and that is deliberate.** This
                // label read `opening` while storage was being read, and build ticket 36 is
                // the record of a small grey word in the corner not being enough. The loading
                // state now says so where the cards are, in [SOURCES_LOADING], and this header
                // is left alone so that nothing above the rail moves or changes when the real
                // cards arrive. Ticket 21's no-resize rule, applied to the swap itself.
                Text(
                    text = SOURCES_LABEL,
                    color = ink.onSurfaceMuted,
                    style = Fonts.Badge,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(18.dp))

            // Exactly one of three, and which one is [homeBody], which is a JVM test. The
            // condition used to be written here and asked for `Ready` before it would draw the
            // first-run page, so a rail still reading storage fell through to `SourceRail`
            // with no cards and `AddSourceCard` became the whole screen. Build ticket 36.
            when (homeBody(state)) {
                HomeBody.LOADING -> LoadingRail(ink = ink)

                // A household with no Source at all. Spec section 6.6 gives it a full-screen
                // page naming all three add paths, because none of them is discoverable on
                // its own. The rail, and the `+ Add a Source` card pinned at its end, replace
                // this page the moment there is one Source.
                HomeBody.FIRST_RUN -> FirstRunPage(
                    ink = ink,
                    onAddSource = onAddSource,
                    onApiKey = onApiKey,
                )

                HomeBody.RAIL -> SourceRail(
                    cards = cards,
                    ink = ink,
                    // One fact, and the rail draws both of its consequences from it: every
                    // card swallows every key but `BACK`, and the rail never scrolls itself to
                    // a card that takes the highlight. [railIsCovered] carries the reasons.
                    covered = railIsCovered(menuFor, confirm?.sourceId),
                    focusOnSourceId = focusOnSourceId,
                    returnedFromSource = returnedFromSource,
                    refocusRequests = refocusRequests,
                    focusedSourceId = focusedSourceId,
                    requesters = railRequesters,
                    // A plain press asks for no landing by name, so with Shuffle on the Source
                    // opens on its run's next video. Build ticket 69.
                    onSelect = { card ->
                        onAction(railOpenLine(card, Landing.DEFAULT))
                        onOpen(card, Landing.DEFAULT)
                    },
                    onHold = { card -> menuFor = card.sourceId },
                    onAddSource = onAddSource,
                    thumbnails = thumbnails,
                )
            }
        }

        // The menu and the confirmation are two states of one overlay, and only one is ever on
        // screen. They are looked up by Source id, so a rail that reordered itself underneath
        // cannot leave either one pointing at a different Source.
        val menuCard = menuFor?.let { id -> cards.firstOrNull { it.sourceId == id } }
        val open = confirm
        val confirmCard = open?.let { id -> cards.firstOrNull { it.sourceId == id.sourceId } }

        // Refusing a confirmation goes back to the menu it was reached from, never to the
        // rail. The same shape as `BACK` inside it, and now the same code.
        val refuse = { open?.sourceId?.let { backToMenu(it) } ?: closeMenu() }

        when {
            confirmCard != null && open?.kind == ConfirmKind.REMOVE -> ConfirmRemove(
                card = confirmCard,
                ink = ink,
                onYes = {
                    closeMenu()
                    say(removedLine(railCardName(confirmCard)))
                    // The Activity writes the logcat line, after the removal has happened.
                    // Saying it here as well printed it twice for one press.
                    onRemove(confirmCard)
                },
                onNo = refuse,
            )
            confirmCard != null -> ConfirmEverythingUnwatched(
                card = confirmCard,
                ink = ink,
                onYes = {
                    val count = confirmCard.everythingUnwatchedCount ?: 0
                    closeMenu()
                    say(markedRangeLine(count, watched = false))
                    onAction(
                        "Mark everything unwatched confirmed for ${confirmCard.sourceId}: " +
                            "$count listed videos.",
                    )
                    onMarkEverythingUnwatched(confirmCard)
                },
                onNo = refuse,
            )
            menuCard != null -> SourceMenuOverlay(
                card = menuCard,
                ink = ink,
                onChoose = { line ->
                    when (line.action) {
                        SourceMenuAction.OPEN_AT_NEXT_UNWATCHED -> {
                            closeMenu()
                            onAction(railOpenLine(menuCard, Landing.NEXT_UNWATCHED))
                            onOpen(menuCard, Landing.NEXT_UNWATCHED)
                        }
                        SourceMenuAction.OPEN_AT_LAST_PLAYED -> {
                            closeMenu()
                            onAction(railOpenLine(menuCard, Landing.LAST_PLAYED))
                            onOpen(menuCard, Landing.LAST_PLAYED)
                        }
                        SourceMenuAction.OPEN_AT_NEWEST -> {
                            closeMenu()
                            onAction(railOpenLine(menuCard, Landing.NEWEST))
                            onOpen(menuCard, Landing.NEWEST)
                        }
                        // The one manual trigger a refresh has. Spec section 5.1.
                        //
                        // It asks nothing first, unlike the two lines below it, because it
                        // changes nothing the household owns and cannot be got wrong: it
                        // spends a few units of the daily ten thousand and brings the Source
                        // up to date. The menu closes at once, so the household is back on
                        // the rail while the check runs.
                        SourceMenuAction.REFRESH -> {
                            closeMenu()
                            say(refreshingLine(railCardName(menuCard)))
                            onRefresh(menuCard)
                        }
                        // A screen of its own, opened at once and with nothing to confirm.
                        // Nothing is written until the household saves there, and `BACK`
                        // out of it writes nothing at all, so the screen is the question.
                        // Build ticket 27.
                        SourceMenuAction.RENAME -> {
                            closeMenu()
                            onRename(menuCard)
                        }
                        // The two lines that ask before they act. Neither does anything by
                        // itself, so the household always sees what it would do first.
                        SourceMenuAction.REMOVE ->
                            askFirst(menuCard.sourceId, ConfirmKind.REMOVE)
                        SourceMenuAction.MARK_EVERYTHING_UNWATCHED ->
                            askFirst(menuCard.sourceId, ConfirmKind.EVERYTHING_UNWATCHED)
                        SourceMenuAction.API_KEY -> {
                            closeMenu()
                            onApiKey()
                        }
                    }
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
                Text(text = said, color = ink.onSurface, style = Fonts.Meta, maxLines = 2)
            }
        }
    }
}

/** Which confirmation is up on the rail, and what pressing yes would do. */
private enum class ConfirmKind { EVERYTHING_UNWATCHED, REMOVE }

/**
 * One confirmation, by Source id and never by index.
 *
 * The rail reorders itself the moment a play is written, so an index would name a different
 * card a second later, and this panel is the one that removes a Source.
 */
private data class Confirmation(val sourceId: String, val kind: ConfirmKind)

/** The message shown for a second or two after a Source comes off the rail. */
private fun removedLine(sourceName: String): String = "$sourceName removed. Your watches are kept."

/** One logcat line saying which Source was opened and where it was asked to land. */
private fun railOpenLine(card: SourceCard, landing: Landing): String =
    "Home rail opened ${card.sourceId} at $landing. " +
        "Next unwatched is ${card.nextUnwatched?.videoId ?: "none"}, " +
        "Last played is ${card.lastPlayedVideoId ?: "none"}."

/**
 * How wide a rail card is, how tall, and how many of them reach the household's screen.
 *
 * The width and the height are both fixed, so the rail never changes shape as the household
 * works along it. The rest is the arithmetic behind "how many Sources fit across", which a
 * screenshot proves one screen at a time and `RailCardLayoutTest` pins for every screen.
 *
 * **The card was 352dp until 2026-09-20.** The owner looked at that rail on the household
 * television and counted two whole cards with the third running off the right edge, and asked
 * for more Sources across the screen. Ticket 10's note that "five fit across 1080p with room to
 * spare" was simply wrong: the TCL is 1920 physical pixels at two pixels to the dp, so the rail
 * has 960dp to spend and a 352dp card spends it on two.
 *
 * **280dp is the answer, and it is a compromise between this ticket's two halves.** The picture
 * added above the text wants width and the household wants narrowness. At 280dp three whole
 * cards reach the screen with a 34dp slice of the fourth still showing, and the text under the
 * picture still holds a Hebrew Source name on one line.
 */
internal object RailCardLayout {

    val WIDTH = 280.dp

    /** See [textBlockHeight] for what has to fit inside this, and at what font scale. */
    val HEIGHT = 324.dp

    /** The gap between two cards. It is `LazyRow`'s `horizontalArrangement`. */
    val GAP = 14.dp

    /** How far the first card's left edge sits in. It is `LazyRow`'s `contentPadding`. */
    val SIDE_PADDING = 44.dp

    /** The card's own padding, inside its border. */
    val PADDING_X = 16.dp
    val PADDING_Y = 14.dp

    /** The picture's shape, the same 16:9 a video card's thumbnail has. */
    const val PICTURE_ASPECT = 16f / 9f

    /** The gap under the picture, and the two gaps in the text below it. */
    val PICTURE_GAP = 10.dp
    val LABEL_TOP_GAP = 12.dp
    val LABEL_GAP = 3.dp

    /**
     * The Next unwatched title's own box, which keeps its height whether it holds a two-line
     * title, a one-line one, or the sentence a finished Source shows. Ticket 10's rule.
     */
    val TITLE_SLOT_HEIGHT = 40.dp

    /**
     * The household television, in dp: 1920 physical pixels at 320dpi, which is two pixels to
     * the dp. Every count below is about this screen unless a test says otherwise.
     */
    val HOUSEHOLD_SCREEN_WIDTH = 960.dp

    /**
     * The largest system text size this card is built to hold.
     *
     * Google TV lets the household raise the text size, and a card with a fixed height and a
     * picture that does not grow with it has only so much room. Above this the date under the
     * Next unwatched title is pushed past the card's own background, on every card of the rail
     * at once. `RailCardLayoutTest` keeps [HEIGHT] above the budget at this scale.
     *
     * **The picture is what made this tight.** Before ticket 21 this card was all text and held
     * about 1.2; the 139dp picture took that down to 1.08 at the height first chosen, which
     * `/code-review` found. [HEIGHT] was raised to 324dp to put it back.
     */
    const val MAX_FONT_SCALE = 1.3f

    /**
     * Everything under the picture, added up, at a given system text size.
     *
     * The text heights are the styles' own line heights read as dp, which is what they measure
     * at font scale 1, multiplied by the scale. The gaps and [TITLE_SLOT_HEIGHT] are dp and do
     * not grow.
     *
     * This is a budget check and not the layout: Compose measures the real text. What it
     * catches is a later session adding a line to this card, or making the picture taller, and
     * pushing the date off the bottom of a fixed height. No other test would see that, and a
     * screenshot only shows it on the card that happens to be longest.
     */
    fun textBlockHeight(fontScale: Float = 1f): Dp {
        val lines = Fonts.FocusedTitle.lineHeight.value +
            Fonts.Meta.lineHeight.value +
            Fonts.Badge.lineHeight.value +
            Fonts.Meta.lineHeight.value
        return PICTURE_GAP + LABEL_TOP_GAP + LABEL_GAP + TITLE_SLOT_HEIGHT +
            (lines * fontScale).dp
    }

    /** How tall the picture is on a card this wide. It spans the card inside its padding. */
    fun pictureHeight(width: Dp = WIDTH): Dp = (width - PADDING_X * 2) / PICTURE_ASPECT

    /** Where a card's left edge falls, measured from the screen's own left edge. */
    fun leftEdgeOf(index: Int, width: Dp = WIDTH): Dp = SIDE_PADDING + (width + GAP) * index

    /** How many cards fit whole across a screen this wide, before any scrolling. */
    fun fullyVisible(screenWidth: Dp, width: Dp = WIDTH): Int {
        val room = screenWidth - SIDE_PADDING + GAP
        if (room <= 0.dp) return 0
        return maxOf(0, (room / (width + GAP)).toInt())
    }

    /**
     * How much of the first card that does not fit whole is still on screen.
     *
     * It is what tells the household the rail carries on past the screen's edge. A television
     * has no scrollbar, so this slice is the only thing that says so.
     */
    fun peek(screenWidth: Dp, width: Dp = WIDTH): Dp =
        (screenWidth - leftEdgeOf(fullyVisible(screenWidth, width), width)).coerceIn(0.dp, width)

    /**
     * How many placeholder tiles [LoadingRail] draws on a screen this wide.
     *
     * It is every card the household would see if the rail were already there: the whole ones,
     * plus the one whose slice [peek] measures. Four on the household's screen. Drawing fewer
     * would leave bare surface where a card is about to appear, and drawing more would cost a
     * node nobody ever sees, because the row is clipped at the screen's edge.
     */
    fun placeholderTiles(screenWidth: Dp, width: Dp = WIDTH): Int {
        val whole = fullyVisible(screenWidth, width)
        return if (peek(screenWidth, width) > 0.dp) whole + 1 else whole
    }
}

/**
 * What the home screen draws while `HomeModel` is still reading storage. Build ticket 36.
 *
 * A row of card-sized placeholders where the cards are about to be, and one sentence across
 * them saying what is happening. The owner saw the old behaviour on the household television on
 * 2026-09-21: the loading state fell through to the rail with no cards, so `+ Add a Source`
 * was the whole screen and read as a household that had chosen no Source.
 *
 * Three rules hold this together, and each is another ticket's lesson:
 *
 * - **Nothing here is focusable.** Not the row, not a tile, not the sentence. Ticket 31's
 *   standing lesson is that a focusable thing the household cannot see steals a key and drops
 *   the highlight. A press during this second must move nothing, and the rail's own first-card
 *   focus request must land the moment [SourceRail] arrives with something to land on.
 * - **Nothing moves when the rail replaces this.** The tiles are [RailCardLayout]'s own width,
 *   height, gap and side padding, so a tile sits exactly where a card will sit. The header and
 *   the label above are not touched at all: they read the same in every state.
 * - **There is no delay and no blank.** Both hide the state rather than say it, and the whole
 *   window is about 1.3 seconds on the household's own library.
 *
 * The sentence is centred, so it falls on the empty lower half of the tiles rather than over
 * the picture slots, and it carries its own filled pill for the same reason the unwatched badge
 * does: it is drawn over something, so it cannot rely on what is behind it.
 */
@Composable
private fun LoadingRail(ink: Ink) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(RailCardLayout.HEIGHT)
            // The last tile runs past the screen's right edge, exactly as the rail's own last
            // card does, and this is what cuts it there. A `LazyRow` clips for itself; a plain
            // `Row` has to be told. It only works because `LoadingTile` asks for its width
            // with `requiredWidth`: without that the `Row` shrinks the last tile to fit and
            // there is nothing left to clip.
            .clipToBounds(),
    ) {
        // Read out here and not inside the `Row`: `maxWidth` belongs to this scope, and inside
        // the row the implicit receiver is the row's own.
        val tiles = RailCardLayout.placeholderTiles(maxWidth)
        Row(
            // Two things, and the rail's fourth card depends on both.
            //
            // **`start` only, and no `end`.** The rail is a `LazyRow` whose content padding
            // does not cut the screen short: its fourth card shows the 34dp slice
            // `RailCardLayout.peek` measures. An end padding here would stop the tiles short
            // of that slice.
            //
            // **`unbounded`, so the row is measured as if the screen went on.** Without it a
            // `Row` hands each child only the room it has left, `Modifier.width` is coerced
            // into that, and `Arrangement.spacedBy` then clamps a child that still does not
            // fit back on top of the one before it. Both were seen on the television: first a
            // last tile 34dp wide that drew its own rounded corners and a two-pixel picture
            // slot inside the screen, then, with `requiredWidth` instead, a fourth tile
            // overlapping the third with the gap between them gone. Measured unbounded, every
            // tile is a whole card and `clipToBounds` above cuts the last one at the screen's
            // edge, which is what the rail does. Found by `/code-review`.
            modifier = Modifier
                .padding(start = RailCardLayout.SIDE_PADDING)
                .wrapContentWidth(align = Alignment.Start, unbounded = true),
            horizontalArrangement = Arrangement.spacedBy(RailCardLayout.GAP),
        ) {
            repeat(tiles) { LoadingTile(ink = ink) }
        }
        Text(
            text = SOURCES_LOADING,
            color = ink.onSurface,
            style = Fonts.Body,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .background(ink.badgeScrim, RoundedCornerShape(10.dp))
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

/**
 * One placeholder, the size and shape of a Source card with nothing written on it.
 *
 * It draws the card's own box and the plain tile of its picture slot, and no text of any kind.
 * The plain tile is already this app's way of saying there is no picture here yet: spec section
 * 7.1 gives it to a finished Source and to a Source that has never been walked, and the Source
 * screen's grid draws it while a picture loads. Nothing else is drawn, because a placeholder
 * carrying shapes where words go invites the household to read them.
 *
 * **The size is [RailCardLayout]'s and not its own.** That is the whole point of the tile: when
 * the read finishes and [RailCard] takes this place, the swap changes what is in the card and
 * never where the card is.
 */
@Composable
private fun LoadingTile(ink: Ink) {
    Column(
        modifier = Modifier
            // A tile is always the card's full width, never the room left on the screen. The
            // row it sits in is measured unbounded so that this is true of the last one too.
            // See the `wrapContentWidth` in [LoadingRail].
            .width(RailCardLayout.WIDTH)
            .height(RailCardLayout.HEIGHT)
            .background(ink.surfaceRaised, RoundedCornerShape(14.dp))
            .padding(
                horizontal = RailCardLayout.PADDING_X,
                vertical = RailCardLayout.PADDING_Y,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(RailCardLayout.PICTURE_ASPECT)
                .clip(RoundedCornerShape(8.dp))
                .background(ink.thumbnailPlaceholder),
        )
    }
}

/**
 * The rail.
 *
 * The scroll and the left and right moves are Compose's own, which is the one difference from
 * the Source screen's grid. A rail is a single row of focusables with nothing hidden behind it,
 * so there is no flat sequence to walk and no row boundary to land on.
 *
 * **Compose's own scroll is kept only while the rail is the thing the household is walking.**
 * See [railIsCovered] and the provider around the lazy row below.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRail(
    cards: List<SourceCard>,
    ink: Ink,
    focusOnSourceId: String?,
    returnedFromSource: Long,
    /**
     * True while one of the home screen's panels is drawn over the rail. [railIsCovered] is the
     * rule, and it carries the reasons for both of the things that hang off it here.
     */
    covered: Boolean,
    refocusRequests: Long,
    focusedSourceId: MutableState<String?>,
    /**
     * The focus requester of every card composed right now.
     *
     * Each card puts itself in while it lives and takes itself out when the lazy row drops it,
     * which is the same arrangement the Source screen's grid uses. A card registers from an
     * effect and never while composing.
     *
     * **It is keyed by Source id and not by position**, unlike the grid's. The grid's items
     * never reorder; this rail's do, every time a video is played. A card that moves keeps its
     * composition, because the lazy row is keyed by Source id, so it never registers again and
     * a map keyed by position would hand out another card's requester. Found by
     * `/code-review`.
     *
     * **It is owned by `HomeScreen` and not by this function**, because closing a panel has to
     * reach a card before this rail is recomposed. Build ticket 38.
     */
    requesters: MutableMap<String, FocusRequester>,
    onSelect: (SourceCard) -> Unit,
    onHold: (SourceCard) -> Unit,
    onAddSource: () -> Unit,
    thumbnails: ThumbnailLoader,
) {
    val railState = rememberLazyListState()

    /** Which card to give the highlight to, and a counter so two equal asks are never one. */
    var moves by remember { mutableLongStateOf(0L) }
    var pending by remember { mutableStateOf<Pair<Long, String>?>(null) }

    /**
     * Which card reports holding the highlight right now, or null when no card does.
     *
     * **It is the retry ladder's exit test, and nothing but a card's own focus report writes
     * it.** That is the whole of build ticket 41. `focusedSourceId` is the card the app chose,
     * and `handHighlightBack` writes it before the ladder is even armed, so a ladder testing
     * that field read back the app's own write on its first rung and stopped there. A
     * `requestFocus` that quietly moved nothing then left the rail with no highlight at all and
     * no rung left to fix it, which is the one failure the ladder was written for.
     *
     * **It lives inside this function on purpose.** `handHighlightBack` is in `HomeScreen` and
     * cannot reach it, so the exit test cannot be satisfied by the app writing down what it
     * wanted. [railHighlightHolder] is the rule that keeps it true as the highlight moves from
     * card to card.
     */
    val highlightOn = remember { mutableStateOf<String?>(null) }

    /**
     * Asks for the highlight, taking the first of [preferred] that is still on the rail.
     *
     * [railHighlightTarget] is the rule, and it is the same one the close path in `HomeScreen`
     * uses, so the card this ladder walks to and the card a closing panel hands the highlight
     * to can never be two different cards.
     */
    fun ask(vararg preferred: String?) {
        val target = railHighlightTarget(preferred.toList(), cards.map { it.sourceId }) ?: return
        moves++
        pending = moves to target
    }

    // The household came back from a Source, so the highlight goes back to that Source's card,
    // wherever the rail's own order has since moved it. Keyed on the id and not on an index,
    // because playing a video is exactly what reorders the rail.
    //
    // **It is deliberately not keyed on the card list.** The list is built again after every
    // `Mark everything unwatched`, and an effect that restarted on it would drag the highlight
    // away from whatever the household was standing on, every time. `cards.size` is keyed on
    // instead, so the first load still lands somewhere once there is a card to land on, and so
    // that a Source coming off the rail is a moment the highlight is put somewhere real.
    LaunchedEffect(focusOnSourceId, returnedFromSource, cards.size) {
        if (cards.isEmpty()) return@LaunchedEffect
        ask(focusOnSourceId, focusedSourceId.value)
    }

    // Something that covered the rail went away. `handHighlightBack` has already asked the card
    // for the highlight, and this counter is bumped on every one of those closes, because an
    // ask is not an answer: see `closeMenu`, and the `/code-review` section of build ticket 38,
    // which put the ladder back on every close. Nothing scrolls: the card never left the
    // screen, unless it was removed, and then the first card takes the highlight.
    LaunchedEffect(refocusRequests) {
        if (refocusRequests == 0L) return@LaunchedEffect
        ask(focusedSourceId.value)
    }

    LaunchedEffect(pending) {
        val (_, sourceId) = pending ?: return@LaunchedEffect
        val index = cards.indexOfFirst { it.sourceId == sourceId }
        if (index < 0) return@LaunchedEffect
        // Only to reach a card that is not there yet. [railMustScrollTo] carries the
        // measurement and the reason; the short version is that `scrollToItem` aligns, so
        // asking it for a card already on the screen moves the rail for nothing.
        if (railMustScrollTo(sourceId, requesters.keys)) railState.scrollToItem(index)
        // The card may only just have been placed by the lazy row, and a card that does not
        // exist yet cannot take focus. These retries are the whole reason focus is asked for
        // here rather than inside the card.
        //
        // **A request that succeeded is not a highlight that stayed.** A ladder that runs
        // while a node is leaving composition can be undone by Compose tidying up after it,
        // which hands the highlight to the first focusable it finds. The old ladder stopped at
        // the first `requestFocus` that returned true, so it stopped just before the thing
        // that undid it. This asks the rail where the highlight actually is and keeps asking
        // until it is where it was asked to be. Found by ticket 11 on the television and
        // reproduced on the emulator.
        //
        // **Closing a panel arrives here on every close**, and the two moves do different
        // work. [handHighlightBack] hands the card the highlight while the panel is still
        // composed, which is what keeps Compose from choosing a card of its own; this ladder
        // is what finds out whether that ask did anything. The other cases it serves are a
        // card the lazy row has not placed yet, coming back from a Source, and a rail whose
        // cards have just changed.
        //
        // **The exit test is [highlightOn] and never `focusedSourceId`.** The second is the
        // card the app chose, and a panel closing writes it before this ladder starts, so a
        // ladder reading it stopped at the first rung whatever the ask had done. Build ticket
        // 41.
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { requesters[sourceId]?.let { runCatching { it.requestFocus() } } },
            holds = { highlightOn.value == sourceId },
        )
    }

    // **Who is allowed to scroll the rail, and when.**
    //
    // Walking the rail is the household's own scroll, and Android's default bring-into-view
    // rule is what performs it: the next card takes focus and the row moves to show it. So the
    // default is kept, read out of the composition here and handed straight back, while the
    // rail is the thing the household is on.
    //
    // While a panel covers the rail it is nobody's scroll, and the default was doing one
    // anyway. A card behind the scrim is still focusable, deliberately, and Compose hands it
    // the highlight in the gap where a panel swaps for its confirmation. [railIsCovered]
    // carries the measurement. [NoScrollOnFocus] is the same rule the Source screen's grid and
    // the Period jump list already use, for the same reason: this app owns where a list sits.
    val railWalksToFocus = LocalBringIntoViewSpec.current

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides if (covered) NoScrollOnFocus else railWalksToFocus,
    ) {
        LazyRow(
            state = railState,
            modifier = Modifier.fillMaxWidth(),
            // The two numbers `RailCardLayout` counts cards with. They are read from there rather
            // than written twice, so the count in `RailCardLayoutTest` is a count of this rail.
            contentPadding = PaddingValues(horizontal = RailCardLayout.SIDE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(RailCardLayout.GAP),
        ) {
            items(items = cards, key = { card -> card.sourceId }) { card ->
                RailCard(
                    card = card,
                    ink = ink,
                    thumbnails = thumbnails,
                    enabled = !covered,
                    register = { requester ->
                        requesters[card.sourceId] = requester
                        // Returned so the card can take itself out again on dispose.
                        { requesters.remove(card.sourceId) }
                    },
                    // **Only while the rail really has the keys.** A panel over the rail takes
                    // the highlight and gives it back, and in the gap between the two Compose
                    // hands it to the nearest focusable it can find, which is the first card
                    // the lazy row has placed. That card reported a focus gain, overwrote this,
                    // and `BACK` out of the menu put the household on the first Source instead
                    // of the one the menu was about. This is the defect ticket 11 found on the
                    // television and could not place. The cause is a focus move nobody asked
                    // for, so the answer is to not believe it: while an overlay is up, whatever
                    // held the highlight before it opened is still what the household is
                    // standing on.
                    //
                    // **The `covered` flip is not what causes that move, and an older version
                    // of this comment said it was.** Measured on the household television on
                    // 2026-09-21 by build ticket 38, with a log on every card's focus change:
                    // opening the menu flips `covered`, which does change every card's modifier
                    // chain, and the card kept the highlight across it for the whole 93 ms
                    // until the panel's own ladder asked for it. What moves the highlight is a
                    // node leaving composition while it holds it, and that is the panel's line
                    // and not a card. [handHighlightBack] closes the window on the way out.
                    //
                    // **The report itself is believed, and only the app's own record of it is
                    // not.** [highlightOn] is where the rail writes down what the cards say,
                    // covered or not, because the retry ladder's one honest question is where
                    // the highlight really is. `focusedSourceId` is the other answer: what the
                    // household is standing on, which a focus move nobody asked for must not
                    // change. Build ticket 41 pulled the two apart.
                    onHighlightChanged = { holdsIt ->
                        highlightOn.value =
                            railHighlightHolder(highlightOn.value, card.sourceId, holdsIt)
                        if (holdsIt && !covered) focusedSourceId.value = card.sourceId
                    },
                    onSelect = { onSelect(card) },
                    onHold = { onHold(card) },
                )
            }
            // Pinned at the end, which spec section 7.1 asks for: the household works along the
            // rail and adding is what is past the last Source, not in front of the first.
            //
            // Its key is a string no playlist id can be. YouTube ids are letters, digits, dashes
            // and underscores, so a key carrying a space and a `+` cannot collide with one, and a
            // collision here would make the lazy row reuse a Source's composition for this card.
            item(key = ADD_CARD_KEY) {
                AddSourceCard(ink = ink, enabled = !covered, onSelect = onAddSource)
            }
        }
    }
}

/** The lazy row's key for the `+ Add a Source` card. No YouTube id can be this string. */
private const val ADD_CARD_KEY = "+ add a source"

/**
 * How long a rail card waits before asking for its picture again, in seconds.
 *
 * The first entry is zero, so the first attempt is immediate. The ladder then reaches about
 * three minutes, which covers a television switched on before its router has finished starting.
 * It is **bounded on purpose**: a card that has given up is not a poll, and a longer outage is
 * the designed offline behaviour of spec section 5.4, which the owner accepted on 2026-09-20 under
 * ticket 13. Opening a Source and coming back builds these cards again and starts the ladder
 * over.
 *
 * The same shape as the focus ladders in this file, and for the same reason: the app asks a few
 * times with growing patience rather than once or forever.
 */
private val RETRY_WAITS_SECONDS = intArrayOf(0, 2, 5, 15, 45, 120)

/**
 * One Source card.
 *
 * Six things and no more, in the order spec section 7.1 lists them: the Next unwatched video's
 * picture, the unwatched badge in its corner, the Source name, the Direction and the video
 * count, and the Next unwatched title with its exact date. Nothing here is about how far into a
 * video the household got.
 *
 * **The picture is the sixth, and it arrived on 2026-09-20 with ticket 21.** The badge moved
 * onto its top corner in the same change, which is where the video card has always put a badge.
 * It is still the first thing in reading order and still a filled pill, and putting it on the
 * picture rather than above it is what kept a card with a picture from growing half as tall
 * again as one without.
 *
 * **Nothing about this card's size depends on what is in it.** The width and the height are
 * fixed in [RailCardLayout], the picture slot is drawn whether or not there is a picture, and
 * the Next unwatched slot keeps its room whether or not there is a video. So a finished Source,
 * a Source that has never been walked and a Source in the middle of its backlog are all exactly
 * the same size, and the rail does not change shape as the household works along it. That is
 * ticket 10's rule, and the picture was built not to break it.
 */
@Composable
private fun RailCard(
    card: SourceCard,
    ink: Ink,
    thumbnails: ThumbnailLoader,
    /**
     * False while an overlay covers the rail. The card then swallows every key it is given.
     *
     * A panel that has not yet been handed the highlight does not receive keys, so whatever
     * still has it acts on them: a centre press in that window opened the Source from behind
     * the open menu. The card keeps its place in the focus order and simply does nothing with
     * a key, which closes that window without disturbing focus at all.
     *
     * **The guard sits on the card and not on the overlay, and that is the whole point.**
     * Compose hands a preview key event only to the `onPreviewKeyEvent` modifiers on the
     * **focused node's ancestor chain**. While a card still holds the highlight, the overlay is
     * that card's sibling and not its ancestor, so a guard written on the overlay is never
     * visited and is dead code that reads as correct. This guard is on the focused node itself,
     * so it always runs. Ticket 09 hit the dead-code version of this on its own panel.
     *
     * **Taking the card out of the focus order instead does not work**, and was tried first.
     * Removing `focusable` while the overlay is up leaves the highlight nowhere, and giving it
     * back afterwards through the card's own [FocusRequester] did not land: Compose had already
     * chosen a card of its own, so closing the menu on the first Source left the highlight on
     * the second. Seen on the emulator on 2026-09-20.
     */
    enabled: Boolean,
    register: (FocusRequester) -> (() -> Unit),
    /**
     * Reports every change to this card's own highlight: true when it takes it, false when it
     * loses it.
     *
     * **The loss is reported as well, and that is build ticket 41.** The rail's retry ladder
     * asks a card for the highlight and then asks the rail whether any card is holding it. A
     * card that only ever reported taking the highlight would leave that answer naming the card
     * the household stood on before a panel opened, and the ladder would read it as a success
     * on its first rung, however little the ask had done.
     */
    onHighlightChanged: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onHold: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    DisposableEffect(Unit) {
        val unregister = register(requester)
        onDispose { unregister() }
    }

    val slot = nextUnwatchedSlot(card)
    val titleColour = if (focused) ink.onFocus else ink.onSurface
    val metaColour = if (focused) ink.onFocusMuted else ink.onSurfaceMuted

    Column(
        modifier = Modifier
            .width(RailCardLayout.WIDTH)
            .height(RailCardLayout.HEIGHT)
            // How `RailCardFontScaleTest` finds this card's own edges. See `ui/TestTags.kt`.
            .testTag(TestTags.railCard(card.sourceId))
            .background(
                color = if (focused) ink.focusFill else ink.surfaceRaised,
                shape = RoundedCornerShape(14.dp),
            )
            .border(
                width = 3.dp,
                color = if (focused) ink.accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .focusRequester(requester)
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                onHighlightChanged(focusState.isFocused)
            }
            .then(
                if (enabled) {
                    Modifier.onSelectOrHoldKey(onSelect = onSelect, onHold = onHold)
                } else {
                    // Every key but `BACK`, and swallowed rather than acted on. See [enabled].
                    //
                    // **`BACK` has to get through.** The overlay's focus ladder gives up at
                    // about 484 ms, and a household that pressed `BACK` inside that window would
                    // otherwise be left on an overlay with no way off it but `HOME`. Returning
                    // false lets the event reach the `BackHandler` that closes the overlay.
                    Modifier.onKeyEvent { event -> event.key != Key.Back }
                },
            )
            .focusable()
            .padding(
                horizontal = RailCardLayout.PADDING_X,
                vertical = RailCardLayout.PADDING_Y,
            ),
    ) {
        RailCardPicture(card = card, thumbnails = thumbnails, ink = ink)
        Spacer(Modifier.height(RailCardLayout.PICTURE_GAP))
        Text(
            text = railCardName(card),
            color = titleColour,
            style = Fonts.FocusedTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        val meta = railCardMeta(card)
        MetaLine(parts = meta.parts, shrinkingPart = meta.shrinkingPart, color = metaColour)
        Spacer(Modifier.height(RailCardLayout.LABEL_TOP_GAP))
        Text(
            text = NEXT_UNWATCHED_LABEL,
            color = if (focused) ink.onFocusMuted else ink.onSurfaceFaint,
            style = Fonts.Badge,
            maxLines = 1,
        )
        Spacer(Modifier.height(RailCardLayout.LABEL_GAP))
        // A fixed height, so a one-line title and a two-line title leave the date in the same
        // place on every card of the rail.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RailCardLayout.TITLE_SLOT_HEIGHT),
        ) {
            Text(
                text = slot.title,
                color = titleColour,
                style = Fonts.CardTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = slot.date.orEmpty(),
            color = metaColour,
            style = Fonts.Meta,
            maxLines = 1,
        )
    }
}

/**
 * The picture at the top of a Source card, with the unwatched badge in its corner.
 *
 * Which picture, and when there is none, is [railCardPicture] and is a JVM test. All that is
 * here is drawing it.
 *
 * **The slot is the same size whether or not it holds a picture.** The [Box] is measured from
 * the card's width and its 16:9 shape alone, so a finished Source and a Source that has never
 * been walked draw the plain tile at exactly the size a loaded picture would have had. That is
 * how this keeps ticket 10's rule that the rail does not change shape along its length.
 *
 * **Nothing here delays the first frame.** The cache is asked while composing, so a picture the
 * app already holds is drawn in this very frame; anything else is fetched by an effect on a
 * background dispatcher and arrives when it arrives. The rail is the first screen, so on a
 * cold start these tiles are plain until the network answers. `ThumbnailLoader` writes nothing
 * to disk, and ticket 13 put that to the owner on 2026-09-20 with the offline grid in front of him:
 * he chose to leave it. The rail is the same behaviour on a screen that is seen sooner.
 *
 * It is the same arrangement `CardThumbnail` uses in `ui/VideoCard.kt`, and deliberately not
 * the same Composable: that one carries the watched wash, the duration badge, the availability
 * badge and the in-progress bar, none of which belongs on a screen about Sources.
 */
@Composable
private fun RailCardPicture(card: SourceCard, thumbnails: ThumbnailLoader, ink: Ink) {
    val picture = railCardPicture(card)

    // The cache is asked here, while composing, so a picture already decoded for the Source
    // screen's grid is on this card in the very frame the household comes back to the rail.
    //
    // **`remember` is keyed on the video, and `produceState` is deliberately not used.**
    // `produceState` remembers its state with no key at all, so when its key changes it keeps
    // the value it already had until the new one is produced. On this rail the key really does
    // change under a card: playing a video moves that Source's Next unwatched, and the lazy row
    // is keyed by Source id, so the card keeps its composition and is simply recomposed with
    // another video. The card would then draw the video it has just watched underneath the
    // title of the video it has not. Offline it would draw it for good.
    val videoId = picture?.videoId
    val bitmap = remember(videoId) { mutableStateOf(videoId?.let(thumbnails::cached)) }
    LaunchedEffect(videoId) {
        if (picture == null) return@LaunchedEffect
        // **A rail card asks more than once, and the grid's cards do not have to.**
        // A grid card is disposed and built again every time it scrolls past, so a fetch that
        // failed while the television was offline is tried again the moment the household
        // scrolls back over it. This rail holds four cards, none of them ever scrolls out of
        // composition, and the rail's state does not change when a refresh finds nothing new,
        // so one attempt per card would be the only attempt the app ever made. A television
        // started before its router is ready would then show plain tiles until the household
        // opened a Source and came back. Found by `/code-review`.
        //
        // The ladder costs nothing in the two cases that are not an outage: a picture already
        // in hand leaves on the first line below, and a video YouTube really has no picture
        // for is remembered by `HttpThumbnailLoader`, which answers from memory with no
        // request at all. Only an unreachable television actually asks again.
        for (wait in RETRY_WAITS_SECONDS) {
            if (bitmap.value != null) return@LaunchedEffect
            if (wait > 0) delay(wait * 1000L)
            bitmap.value = thumbnails.load(picture.videoId, picture.storedUrl)
        }
    }

    val accented = card.unwatchedCount > 0 && card.walked

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(RailCardLayout.PICTURE_ASPECT)
            .clip(RoundedCornerShape(8.dp))
            .background(ink.thumbnailPlaceholder),
    ) {
        bitmap.value?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // How much of this Source is left. It is the one thing on the card that is read from
        // across the room, so it is drawn as a filled pill and sits in the corner the eye
        // reaches first. It is over the picture, which is why it keeps its own fill: a badge
        // with no fill would be unreadable on a bright thumbnail.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(CardOverlays.inset)
                .background(
                    color = if (accented) ink.accent else ink.badgeScrim,
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = unwatchedBadge(card),
                color = if (accented) ink.onFocus else ink.onSurface,
                style = Fonts.Badge,
                maxLines = 1,
            )
        }
    }
}

/**
 * The page a household with no Source at all sees. Spec section 6.6.
 *
 * It names **all three** add paths, because none of them is discoverable on its own: a
 * household shown only a field never learns that pasting a link works, and one that has only
 * pasted a link never learns it can say a name. The search path is named although ticket 12
 * builds it, for the same reason: this page is about what the household can do.
 *
 * It is a page and not an overlay, so nothing sits behind it that could hold the highlight.
 * The one line asks for focus with the same retry ladder every panel in this app uses.
 *
 * **Its ladder reads what the line reports, since build ticket 47, and that matters more here
 * than anywhere else in the app.** `Add a Source` is the only focusable node on the whole
 * screen, and the home screen is the screen `BACK` has nowhere to go from. A ladder that read
 * `runCatching { requestFocus() }.isSuccess` stopped on its first rung whatever the ask had
 * done, so a line that was attached and quietly refused the highlight left a household with a
 * page they could read and no key they could press. Nothing recovers that but the `HOME`
 * button.
 */
@Composable
private fun FirstRunPage(ink: Ink, onAddSource: () -> Unit, onApiKey: () -> Unit) {
    val requester = remember { FocusRequester() }
    val keyRequester = remember { FocusRequester() }

    /** Which line of this page reports holding the highlight. */
    val highlightOn = remember { HighlightRecord<String>() }

    LaunchedEffect(Unit) {
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { requester.requestFocus() } },
            holds = { highlightOn.holds(ADD_A_SOURCE) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp)
            .widthIn(max = 1100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = FIRST_RUN_TITLE, color = ink.onSurface, style = Fonts.SourceTitle, maxLines = 1)
        Text(text = FIRST_RUN_LEAD, color = ink.onSurfaceMuted, style = Fonts.Body, maxLines = 3)
        Spacer(Modifier.height(4.dp))
        FIRST_RUN_PATHS.forEach { path ->
            Text(text = path, color = ink.onSurface, style = Fonts.Body, maxLines = 2)
        }
        Spacer(Modifier.height(14.dp))
        PanelLine(
            label = ADD_A_SOURCE,
            enabled = true,
            ink = ink,
            focusRequester = requester,
            // Two lines, each naming the other, so `Down` and `Up` move between them and never
            // ask the focus search, which has nothing else on this page to find.
            up = FocusRequester.Default,
            down = keyRequester,
            // The record the ladder above reads. Only the line itself writes it.
            onHighlightChanged = { holdsIt -> highlightOn.report(ADD_A_SOURCE, holdsIt) },
            onChoose = onAddSource,
            modifier = Modifier.width(360.dp),
        )
        // The key screen, reachable with no Source at all. The Source menu is the other way in,
        // and a household with no Source has no menu. Found by `/code-review` on build
        // ticket 64.
        PanelLine(
            label = API_KEY_LINE,
            enabled = true,
            ink = ink,
            focusRequester = keyRequester,
            up = requester,
            down = FocusRequester.Default,
            onHighlightChanged = { holdsIt -> highlightOn.report(API_KEY_LINE, holdsIt) },
            onChoose = onApiKey,
            modifier = Modifier.width(360.dp),
        )
    }
}

/**
 * The `+ Add a Source` card, pinned at the end of the rail.
 *
 * It is the same size as a Source card, so the rail keeps one shape, and it is drawn in the
 * quieter ink because it is not a Source. A hold on it does nothing: there is no Source behind
 * it to open a menu about, and a hold that fell through to the release would open the add
 * screen from a press the household meant as a hold.
 */
@Composable
private fun AddSourceCard(ink: Ink, enabled: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(RailCardLayout.WIDTH)
            .height(RailCardLayout.HEIGHT)
            .background(
                color = if (focused) ink.focusFill else ink.surfaceRaised,
                shape = RoundedCornerShape(14.dp),
            )
            .border(
                width = 3.dp,
                color = if (focused) ink.accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (enabled) {
                    Modifier.onSelectOrHoldKey(onSelect = onSelect, onHold = {})
                } else {
                    // Every key but `BACK`, swallowed. The same guard the Source cards carry
                    // while an overlay is up, and for the same reason. See [RailCard.enabled].
                    Modifier.onKeyEvent { event -> event.key != Key.Back }
                },
            )
            .focusable()
            .padding(
                horizontal = RailCardLayout.PADDING_X,
                vertical = RailCardLayout.PADDING_Y,
            ),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ADD_A_SOURCE,
            color = if (focused) ink.onFocus else ink.onSurface,
            style = Fonts.FocusedTitle,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = ADD_A_SOURCE_META,
            color = if (focused) ink.onFocusMuted else ink.onSurfaceMuted,
            style = Fonts.Meta,
            maxLines = 1,
        )
    }
}

/**
 * The confirmation in front of `Remove`. Spec section 6.6.
 *
 * It names the Source and says the watch history is kept, in that order, because a household
 * that believes removing loses its history will never use the one answer this app has to a
 * cluttered rail. The refusing line is **first**, so the highlight lands on it.
 */
@Composable
private fun ConfirmRemove(
    card: SourceCard,
    ink: Ink,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    MenuPanel(
        title = REMOVE_QUESTION,
        subtitle = MenuSubtitle.Sentence(removeSourceDetail(railCardName(card))),
        lines = listOf(REMOVE_NO to true, REMOVE_YES to true),
        ink = ink,
        onChoose = { index -> if (index == 0) onNo() else onYes() },
    )
}

/**
 * The long-press menu on a Source card, drawn over the rail.
 *
 * It is the same shape as the Source screen's card menu, and it is written here rather than
 * shared with it for one reason: a line of this menu can be **dim**, and a dim line is not
 * focusable, so the D-pad walks past it. The card menu has no such line.
 *
 * It is a panel over a scrim and not a `Dialog`, for the reason spec section 7.3 gives: a
 * second window on Android TV brings its own focus rules, which is a fight for nothing.
 */
@Composable
private fun SourceMenuOverlay(
    card: SourceCard,
    ink: Ink,
    onChoose: (SourceMenuLine) -> Unit,
) {
    val lines = remember(card) { sourceMenuLines(card, card.everythingUnwatchedCount) }
    MenuPanel(
        title = railCardName(card),
        subtitle = MenuSubtitle.Meta(railCardMeta(card)),
        lines = lines.map { it.label to it.enabled },
        ink = ink,
        onChoose = { index -> onChoose(lines[index]) },
    )
}

/**
 * The confirmation in front of `Mark everything unwatched`.
 *
 * It names the count, because this is the one action on the rail whose effect runs off the edge
 * of the picture, and there is no undo anywhere in this app. The line that acts states the
 * count a second time, so what is promised and what is chosen can be compared.
 *
 * The refusing line is **first**, so the highlight lands on it when the panel opens and a
 * household that holds the centre button a moment too long does not mark four hundred videos.
 */
@Composable
private fun ConfirmEverythingUnwatched(
    card: SourceCard,
    ink: Ink,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    val count = card.everythingUnwatchedCount ?: 0
    MenuPanel(
        title = MARK_EVERYTHING_UNWATCHED_QUESTION,
        subtitle = MenuSubtitle.Sentence(markEverythingUnwatchedDetail(railCardName(card), count)),
        lines = listOf(
            MARK_EVERYTHING_UNWATCHED_NO to true,
            markEverythingUnwatchedYes(count) to true,
        ),
        ink = ink,
        onChoose = { index -> if (index == 0) onNo() else onYes() },
    )
}

/**
 * What a [MenuPanel] says under its title.
 *
 * It is one type with two cases rather than two parameters. Two parameters were the first
 * shape, and `/code-review` pointed out that nothing but a comment stopped a caller passing
 * both and drawing two subtitles. The two cases really are different things and are drawn
 * differently, so the type says which one the caller meant and the `when` below cannot miss
 * one.
 */
private sealed interface MenuSubtitle {

    /**
     * A sentence, such as a confirmation's detail. It wraps over up to three lines.
     *
     * It is one string and not a list of parts, which is not the defect of spec section 6.2: a
     * sentence is one bidirectional run by its nature, and a Hebrew Source name inside one is
     * bounded by whole English words on both sides, so nothing around it is reordered. Checked
     * on the household television by ticket 20.
     */
    data class Sentence(val text: String) : MenuSubtitle

    /** A meta line, drawn as separate pieces of text. See [RailCardMeta] and [MetaLine]. */
    data class Meta(val meta: RailCardMeta) : MenuSubtitle
}

/**
 * A column of choices over a scrim, which all three overlays of this screen are.
 *
 * **Nothing inside this panel consults Compose's focus search.** Every line points at its own
 * neighbour through a [FocusRequester], and a line with no neighbour in a direction cancels the
 * search for that direction outright. [menuNeighbour] is the rule, and it is a JVM test.
 *
 * That is build ticket 31's fix, and the reason is geometry. The rail cards behind the scrim
 * stay focusable on purpose; see [RailCard]'s `enabled`. Compose's search looks for the nearest
 * focusable node in the direction asked for, and on the household's rail the bottom edge of a
 * card sat nearer to the menu's last line than the menu line above it did. One `Up` from
 * `Mark everything unwatched` took the highlight out of the menu altogether and scrolled the
 * rail under the scrim. Reproduced on the television on 2026-09-20.
 *
 * [cardMenuKeepsKey] still guards the two ends and the sideways keys. It and the cancelled
 * search now say the same thing at an end, which is deliberate: whichever runs first, the
 * highlight stays where it is.
 */
@Composable
private fun MenuPanel(
    title: String,
    subtitle: MenuSubtitle?,
    lines: List<Pair<String, Boolean>>,
    ink: Ink,
    onChoose: (Int) -> Unit,
) {
    // One requester per drawn line, so a line can name the line it means. Only a live line is
    // ever given one: a dim line is not focusable, and a requester that reaches no focus target
    // throws when anything asks it for focus.
    val requesters = remember(title, lines) { List(lines.size) { FocusRequester() } }
    val focusedLine = remember(title, lines) { mutableIntStateOf(0) }

    // One flag per drawn line, in the order the lines are drawn. This is what [menuNeighbour]
    // reads, and it is the only thing about the panel the rule needs.
    val live = remember(lines) { lines.map { it.second } }

    // The first line that can actually take focus. A dim line is skipped, so the retry below
    // does not ask a line that will never answer.
    val firstLive = firstLiveLine(live)

    /**
     * Which drawn line of this panel reports holding the highlight.
     *
     * **It is the retry ladder's exit test, and nothing but a line's own focus report writes
     * it.** Build ticket 47. The ladder used to exit on
     * `runCatching { requestFocus() }.isSuccess`, which says only that the call did not throw,
     * so its first rung read success whatever the ask had done.
     *
     * **This panel has no key guard of its own**, unlike the Source screen's two, so a ladder
     * that gave up early left the highlight on a rail card behind the scrim. That card swallows
     * every key but `BACK` while the rail is covered, so the household saw a menu that answered
     * nothing and could only leave it. [railIsCovered] carries that rule.
     *
     * It is remembered across a change of [lines], because a refresh renumbers the same lines
     * and Compose reuses their nodes: a node that already holds the highlight reports no focus
     * change, and a record rebuilt under it would read as holding the highlight nowhere.
     */
    val highlightOn = remember { HighlightRecord<Int>() }

    // The panel is useless until something in it has the highlight, and its lines have only
    // just been placed. The same retry the rail uses, for the same reason.
    LaunchedEffect(title, lines) {
        val line = firstLive ?: return@LaunchedEffect
        val target = requesters[line]
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { target.requestFocus() } },
            holds = { highlightOn.holds(line) },
        )
    }

    // How many lines the D-pad can actually stop on. The edge guard counts these and not the
    // drawn lines, because a dim line is walked past.
    val liveCount = lines.count { it.second }

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
                // The **live** lines, not every line. A dim line is not focusable, so the
                // last line the D-pad can reach is the last live one, and counting the dim
                // ones would leave `Down` unguarded on a menu whose last line is dim. That is
                // exactly the menu of a Source that has never been walked, which is what a
                // fresh install opens on. Found by `/code-review`.
                cardMenuKeepsKey(move, liveLine(lines, focusedLine.intValue), liveCount)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 460.dp, max = 680.dp)
                .background(ink.surfaceRaised, RoundedCornerShape(14.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                color = ink.onSurface,
                style = Fonts.FocusedTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when (subtitle) {
                null -> Unit
                is MenuSubtitle.Sentence -> Text(
                    text = subtitle.text,
                    color = ink.onSurfaceMuted,
                    style = Fonts.Meta,
                    maxLines = 3,
                )
                is MenuSubtitle.Meta -> MetaLine(
                    parts = subtitle.meta.parts,
                    shrinkingPart = subtitle.meta.shrinkingPart,
                    color = ink.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(6.dp))

            lines.forEachIndexed { index, (label, enabled) ->
                PanelLine(
                    label = label,
                    enabled = enabled,
                    ink = ink,
                    focusRequester = requesters[index].takeIf { enabled },
                    // The neighbour, named rather than searched for. `FocusRequester.Cancel`
                    // stops the search dead at an end of the column, which is what keeps the
                    // rail behind the scrim from taking the highlight and scrolling.
                    up = neighbourRequester(live, index, MenuMove.UP, requesters),
                    down = neighbourRequester(live, index, MenuMove.DOWN, requesters),
                    // **Two answers, kept apart.** [highlightOn] is what this line reports and
                    // is the ladder's exit test; [focusedLine] is what the panel's edge guard
                    // counts from. A loss is recorded as well as a gain, because a record that
                    // only ever goes true cannot say the highlight has left the panel. Build
                    // ticket 47.
                    onHighlightChanged = { holdsIt ->
                        highlightOn.report(index, holdsIt)
                        if (holdsIt) focusedLine.intValue = index
                    },
                    onChoose = { onChoose(index) },
                )
            }
        }
    }
}

/**
 * Which live line the highlight is on, counting only the lines the D-pad can stop on.
 *
 * [cardMenuKeepsKey] asks "am I at the top or the bottom of the column", and its answer is only
 * right when both its numbers count the same lines. The drawn index and the live index are the
 * same number until a line above the highlight is dim, and they differ the moment one is.
 */
private fun liveLine(lines: List<Pair<String, Boolean>>, drawnIndex: Int): Int =
    lines.take(drawnIndex.coerceAtLeast(0)).count { it.second }

/**
 * One line of a panel.
 *
 * A dim line is **not focusable**, so the D-pad walks straight past it, which is the same rule
 * a disabled chip on the Source screen follows. It is still drawn, in the faintest ink on the
 * screen, because a line that came and went would be a line whose place could never be learnt.
 *
 * @param up the line `Up` moves to, or [FocusRequester.Cancel] when there is none above. The
 *   panel works this out with [menuNeighbour]; the line is only told the answer.
 * @param down the same, downwards.
 */
@Composable
private fun PanelLine(
    label: String,
    enabled: Boolean,
    ink: Ink,
    focusRequester: FocusRequester?,
    up: FocusRequester,
    down: FocusRequester,
    onHighlightChanged: (Boolean) -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
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
            .then(
                if (enabled) {
                    Modifier
                        // Named neighbours, so no key inside this panel ever reaches Compose's
                        // two-dimensional focus search. `Left` and `Right` are not named here:
                        // the panel's own guard keeps them, and naming `Cancel` for them as
                        // well would say the same thing in a second place.
                        .panelNeighbours(above = up, below = down)
                        // A tap chooses the line. A hold does nothing here, and must not fall
                        // through to the release that opened this menu in the first place.
                        .onSelectOrHoldKey(onSelect = onChoose, onHold = {})
                } else {
                    Modifier
                },
            )
            // **Outside the `enabled` branch, and `MenuLine` on the Source screen has the same
            // shape for the same reason.** A line that goes dim while it holds the highlight
            // has to be able to say that it lost it. With this modifier inside the branch,
            // going dim takes the modifier away before Compose reports anything, so the
            // panel's record would go on naming a line that holds nothing and this line would
            // go on being drawn with the focus fill and the ring. No line of a home panel
            // flips from live to dim today, so this is a trap removed and not a defect fixed.
            // Found by `/code-review` on build ticket 47.
            .onFocusChanged { focusState ->
                // Only a real change is reported on. Compose delivers `onFocusChanged` on
                // other things than a gain or a loss, and a record written from a delivery
                // that changed nothing would report a loss this line never had.
                val changed = focusState.isFocused != focused
                focused = focusState.isFocused
                if (changed) onHighlightChanged(focusState.isFocused)
            }
            // A dim line is not focusable, so the D-pad walks straight past it and it can
            // never be chosen.
            .focusable(enabled = enabled)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
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
