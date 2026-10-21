package local.youtubetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import local.youtubetv.APP_NAME
import local.youtubetv.data.Landing
import local.youtubetv.data.SourceCard
import local.youtubetv.player.VideoQuality
import local.youtubetv.player.videoQualitySetLine

/**
 * What a [MenuPanel] says under its title.
 *
 * It is one type with two cases rather than two parameters. Two parameters were the first
 * shape, and `/code-review` pointed out that nothing but a comment stopped a caller passing
 * both and drawing two subtitles. The two cases really are different things and are drawn
 * differently, so the type says which one the caller meant and the `when` below cannot miss
 * one.
 */
internal sealed interface MenuSubtitle {

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
internal fun MenuPanel(
    title: String,
    subtitle: MenuSubtitle?,
    lines: List<Pair<String, Boolean>>,
    ink: Ink,
    onChoose: (Int) -> Unit,
    // `Left` or `Right` on the drawn line at the index. True when the line used the key, which
    // a line that is a setting does. Every other line leaves it to the edge guard below.
    onSideways: (index: Int, right: Boolean) -> Boolean = { _, _ -> false },
) {
    // One flag per drawn line, in the order the lines are drawn. This is what [menuNeighbour]
    // reads, and it is the only thing about the panel the rule needs.
    val live = remember(lines) { lines.map { it.second } }

    // One requester per drawn line, so a line can name the line it means. Only a live line is
    // ever given one: a dim line is not focusable, and a requester that reaches no focus target
    // throws when anything asks it for focus.
    //
    // Keyed on the live flags and not on the labels. A line that is a setting changes its own
    // label when `Left` or `Right` moves it, and a label change must not throw the highlight
    // back to the first line. A change in which lines are live still starts again from the top.
    val requesters = remember(title, live) { List(lines.size) { FocusRequester() } }
    val focusedLine = remember(title, live) { mutableIntStateOf(0) }

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
    LaunchedEffect(title, live) {
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
                if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                    val right = event.key == Key.DirectionRight
                    if (onSideways(focusedLine.intValue, right)) return@onPreviewKeyEvent true
                }
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
internal fun liveLine(lines: List<Pair<String, Boolean>>, drawnIndex: Int): Int =
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
internal fun PanelLine(
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
