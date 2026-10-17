package local.youtubetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import local.youtubetv.data.Direction
import local.youtubetv.youtube.SourceCandidate

/**
 * The screen that adds a Source: type or paste, pick, choose a Direction, add.
 *
 * Every rule behind it is somewhere else with a JVM test: [AddSourceModel] decides what state
 * follows what, `ui/AddSourceText.kt` holds every word, and `youtube/SourceInput.kt` reads
 * what was typed. What is left here is drawing it and moving the highlight.
 *
 * **`BACK` is not handled here.** `MainActivity` owns every `BACK` in this app, on purpose: a
 * Compose `BackHandler` is added during composition and the dispatcher asks the last-added
 * enabled callback first, so one added here would silently take the key from the player. The
 * Activity calls [AddSourceModel.back], which walks the steps back and says when there is
 * nothing left to go back to.
 */
@Composable
fun AddSourceScreen(
    state: AddSourceState,
    thumbnails: ThumbnailLoader = NoThumbnails,
    onTyped: (String) -> Unit = {},
    onFind: () -> Unit = {},
    onChoose: (SourceCandidate) -> Unit = {},
    onDirection: (Direction) -> Unit = {},
    onAdd: () -> Unit = {},
    onOpenExisting: (String) -> Unit = {},
    // The same move `BACK` makes: one step back, which from a refusal is the field. It is
    // **not** `onFind`: `AddSourceModel.find` only acts on a `Typing` state, so wiring this to
    // it made the line do nothing at all. Found by `/code-review`.
    onBack: () -> Unit = {},
) {
    val ink = screenInk()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ink.surface)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = headingOf(state),
            color = ink.onSurface,
            style = Fonts.SourceTitle,
            maxLines = 1,
        )

        when (state) {
            is AddSourceState.Typing -> TypingStep(state, ink, onTyped, onFind)
            AddSourceState.Looking -> Text(
                text = ADD_LOOKING,
                color = ink.onSurfaceMuted,
                style = Fonts.Body,
                maxLines = 1,
            )
            is AddSourceState.Choosing -> ChoosingStep(state, ink, thumbnails, onChoose)
            is AddSourceState.Confirming ->
                ConfirmingStep(state, ink, thumbnails, onDirection, onAdd)
            is AddSourceState.AlreadyAdded -> AlreadyAddedStep(state, ink, onOpenExisting, onBack)
        }
    }
}

/** The heading, which says which question is being asked rather than naming the screen twice. */
private fun headingOf(state: AddSourceState): String = when (state) {
    is AddSourceState.Typing, AddSourceState.Looking -> ADD_TITLE
    is AddSourceState.Choosing -> state.title
    is AddSourceState.Confirming -> ADD_CONFIRM_TITLE
    is AddSourceState.AlreadyAdded -> ALREADY_ADDED_TITLE
}

/**
 * The field, the button, and the three paths written under them.
 *
 * The paths are on this screen as well as on the first-run page, because a household that
 * already has one Source never sees that page again and still has to be able to learn that a
 * handle works. They are the same three strings, from the same place.
 *
 * **The field takes the highlight when the screen opens**, which is what brings the
 * television's own keyboard up with its microphone button. The keyboard's own action key
 * submits, so the common path never needs the button below at all.
 */
@Composable
private fun TypingStep(
    state: AddSourceState.Typing,
    ink: Ink,
    onTyped: (String) -> Unit,
    onFind: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }

    /**
     * Which node of this step reports holding the highlight.
     *
     * **Only a node's own focus report writes it, and the ladder below reads it and nothing
     * else.** Build ticket 48. The ladder used to exit on
     * `runCatching { requester.requestFocus() }.isSuccess`, which says only that the call did
     * not throw, so its first rung read success whatever the ask had done and the 60, 120 and
     * 240 ms rungs never ran.
     *
     * Two nodes report into it: the field, named [ADD_FIELD], and `Find it`, named by its own
     * label. Nothing asks for `Find it`, but a record that hears only about one of the two
     * nodes on a step would say the field still held the highlight after the household had
     * walked off it.
     *
     * **A `BasicTextField` reports through the caller's own `Modifier.onFocusChanged` exactly
     * as a `focusable()` box does**, which build ticket 48 measured on `googletv1080p` rather
     * than assuming: one delivery of `isFocused=false` at composition, then `isFocused=true`
     * on the gain and `isFocused=false` on the loss, with `hasFocus` agreeing every time. The
     * field's own focusable node is a descendant, so `isFocused` could in principle have stayed
     * false while `hasFocus` went true, and it does not.
     */
    val highlightOn = remember { HighlightRecord<String>() }

    LaunchedEffect(Unit) {
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { requester.requestFocus() } },
            holds = { highlightOn.holds(ADD_FIELD) },
        )
    }

    Column(
        modifier = Modifier.widthIn(max = 1100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(ink.surfaceRaised, RoundedCornerShape(10.dp))
                .border(
                    width = 3.dp,
                    color = if (fieldFocused) ink.accent else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (state.typed.isEmpty()) {
                Text(
                    text = ADD_FIELD_HINT,
                    color = ink.onSurfaceFaint,
                    style = Fonts.Body,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = state.typed,
                onValueChange = onTyped,
                singleLine = true,
                textStyle = TextStyle(color = ink.onSurface, fontSize = Fonts.Body.fontSize),
                cursorBrush = SolidColor(ink.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // The television keyboard's own action key. It is the path a household
                // actually uses, because the keyboard covers the button below while it is up.
                keyboardActions = KeyboardActions(onSearch = { onFind() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    // Two things are written from one delivery: `fieldFocused`, which draws the
                    // ring, and the record, which the ladder waits on. They are separate
                    // because only the first has to make this step recompose; see the note on
                    // `HighlightRecord` about why a record is a plain field.
                    .onFocusChanged { focusState ->
                        val changed = focusState.isFocused != fieldFocused
                        fieldFocused = focusState.isFocused
                        if (changed) highlightOn.report(ADD_FIELD, focusState.isFocused)
                    },
            )
        }

        // The refusal from the last try, under the field that caused it and with the text
        // still in the field, so one mistyped character is one character to fix.
        state.message?.let {
            Text(text = it, color = ink.accent, style = Fonts.Body, maxLines = 3)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelButton(
                label = ADD_FIND,
                ink = ink,
                onChoose = onFind,
                width = 240.dp,
                onHighlightChanged = { holdsIt -> highlightOn.report(ADD_FIND, holdsIt) },
            )
        }

        Spacer(Modifier.height(8.dp))
        FIRST_RUN_PATHS.forEach { path ->
            Text(text = path, color = ink.onSurfaceMuted, style = Fonts.Meta, maxLines = 2)
        }
    }
}

/** One row per thing the household could mean, as a column it walks with `Up` and `Down`. */
@Composable
private fun ChoosingStep(
    state: AddSourceState.Choosing,
    ink: Ink,
    thumbnails: ThumbnailLoader,
    onChoose: (SourceCandidate) -> Unit,
) {
    val first = remember(state.rows) { FocusRequester() }

    /**
     * The row this step opens on, or null when YouTube gave nothing back.
     *
     * **`firstOrNull` and not `first`.** The ladder below names this row, so it reads the list
     * outside the `items` block, where an empty list is not already ruled out by there being
     * nothing to draw.
     */
    val firstRow = state.rows.firstOrNull()

    /**
     * Which row of this step reports holding the highlight.
     *
     * **It names the row and never the row's place in the list**, which is the rule
     * `/code-review` settled on build ticket 47's grid: a place can move under a record while
     * Compose reports nothing, and the record is then left naming a place nothing holds. The
     * name is [candidateKey], which is the same expression the `LazyColumn` is keyed by, so a
     * row survives in this record exactly as long as its node survives.
     *
     * **It is not rebuilt when the rows change**, for the reason build ticket 47 found on the
     * card menu: rebuilding it would throw away a report the surviving node had already made
     * and can never make again. A record naming a row that is gone does no harm, because the
     * ladder below asks whether it names the **new** first row, which it does not.
     */
    val highlightOn = remember { HighlightRecord<String>() }

    LaunchedEffect(state.rows) {
        val row = firstRow ?: return@LaunchedEffect
        // **The ask and the exit test are about the same row.** `first` is the requester the
        // first row and only the first row is given, and both it and this effect are rebuilt
        // when the rows change, so the two can never end up naming different rows. That is the
        // trap `/code-review` caught on build ticket 47's grid, where the ask named a place and
        // the exit test named a video.
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { first.requestFocus() } },
            holds = { highlightOn.holds(candidateKey(row)) },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // [candidateKey] is the key, and it is the same expression the highlight record above
        // names a row by. It carries the channel id as well as the playlist id; its own KDoc
        // says why.
        items(items = state.rows, key = { candidateKey(it) }) { row ->
            CandidateRow(
                candidate = row,
                ink = ink,
                thumbnails = thumbnails,
                focusRequester = if (row == firstRow) first else null,
                onChoose = { onChoose(row) },
                onHighlightChanged = { holdsIt -> highlightOn.report(candidateKey(row), holdsIt) },
            )
        }
    }
}

/**
 * One row: the picture, the title, the badge, the channel or handle under it, and the count.
 *
 * The first row of a channel's second step is its whole uploads playlist, and it is named
 * `All uploads` rather than by the channel's own title, so the two rows of a channel that also
 * has a playlist of the same name are told apart.
 */
@Composable
private fun CandidateRow(
    candidate: SourceCandidate,
    ink: Ink,
    thumbnails: ThumbnailLoader,
    focusRequester: FocusRequester?,
    onChoose: () -> Unit,
    onHighlightChanged: (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            // The row that took the highlight is what build ticket 48's instrumentation test
            // watches for, and its title is on a child node rather than on the focusable one,
            // so it cannot say which node holds the highlight. See [TestTags].
            .testTag(TestTags.candidateRow(candidateKey(candidate)))
            .background(
                color = if (focused) ink.focusFill else ink.surfaceRaised,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 3.dp,
                color = if (focused) ink.accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .let { if (focusRequester == null) it else it.focusRequester(focusRequester) }
            // Only a real change is reported on, for the reason written on [PanelButton]: a
            // node is told `isFocused=false` once at composition, before anything has asked it.
            .onFocusChanged { focusState ->
                val changed = focusState.isFocused != focused
                focused = focusState.isFocused
                if (changed) onHighlightChanged(focusState.isFocused)
            }
            .onSelectOrHoldKey(onSelect = onChoose, onHold = {})
            .focusable()
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CandidatePicture(candidate, ink, thumbnails)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                color = if (focused) ink.onFocus else ink.onSurface,
                style = Fonts.FocusedTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CandidateMetaLine(
                candidate = candidate,
                color = if (focused) ink.onFocusMuted else ink.onSurfaceMuted,
            )
        }
    }
}

/**
 * The badge, the channel and the count, each drawn by its own [Text].
 *
 * It is [MetaLine], which is where the rule and the reason live. This wrapper exists because
 * the result row and the add screen both draw the same candidate, so neither can be fixed
 * without the other.
 *
 * **It walks [CandidateMeta.parts] rather than reading the three fields by name.** That is
 * deliberate: the list `AddSourceTextTest` pins is then the same list that reaches the screen,
 * which is as close as a JVM test can get to a layout no JVM test can see. This project has no
 * instrumentation tests, so the last step is a screenshot on the household television. Found
 * by `/code-review`.
 */
@Composable
private fun CandidateMetaLine(
    candidate: SourceCandidate,
    color: Color,
) {
    val meta = candidateMeta(candidate)
    MetaLine(parts = meta.parts, shrinkingPart = meta.shrinkingPart, color = color)
}

/** A candidate's picture, or the dark tile a card keeps when there is none. */
@Composable
private fun CandidatePicture(
    candidate: SourceCandidate,
    ink: Ink,
    thumbnails: ThumbnailLoader,
) {
    // Keyed on the playlist id, which is this app's key for everything to do with a Source.
    // The loader's cache is keyed by whatever it is handed, and a playlist id can never be a
    // video id, so the two cannot collide.
    var picture by remember(candidate.playlistId) {
        mutableStateOf<ImageBitmap?>(thumbnails.cached(candidate.playlistId))
    }
    LaunchedEffect(candidate.playlistId) {
        // **Only when YouTube gave a URL.** The loader falls back on
        // `i.ytimg.com/vi/<id>/maxresdefault.jpg`, which is built from the first argument, and
        // the first argument here is a playlist id. Those two requests can never succeed. A
        // channel or playlist with no picture keeps its dark tile instead. Found by
        // `/code-review`.
        val url = candidate.thumbnailUrl ?: return@LaunchedEffect
        if (picture == null) picture = thumbnails.load(candidate.playlistId, url)
    }

    Box(
        modifier = Modifier
            .width(124.dp)
            .height(70.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ink.thumbnailPlaceholder),
    ) {
        picture?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The add screen of spec section 6.3.
 *
 * Six things and no more: the picture, the title, the `@handle`, the video count, the
 * Direction choice and `Add`. **Nothing about Shorts**, because spec section 4.3 hides them
 * always and there is no toggle to put here.
 *
 * The Direction choice starts on newest-first, which is also the first chip, so the
 * preselected choice is where the highlight starts.
 */
@Composable
private fun ConfirmingStep(
    state: AddSourceState.Confirming,
    ink: Ink,
    thumbnails: ThumbnailLoader,
    onDirection: (Direction) -> Unit,
    onAdd: () -> Unit,
) {
    val addButton = remember(state.candidate) { FocusRequester() }

    /**
     * Whether the `Add` button reports holding the highlight.
     *
     * **Only the button's own focus report writes it, and the ladder below reads it and
     * nothing else.** Build ticket 48.
     *
     * The two Direction chips are the other focusable nodes on this step and they report into
     * nothing, because nothing ever asks them for the highlight and the button's own loss is
     * already enough: when the household walks off `Add` onto a chip, `Add` reports the loss
     * and this record goes empty. That is the same choice build ticket 47 made for the Source
     * screen's `Period` chip.
     *
     * **It is not rebuilt when the candidate changes**, although the requester above is. A
     * button that already holds the highlight reports no focus change when a new requester is
     * attached to it, so a record rebuilt there would be empty with nothing able to fill it.
     * Build ticket 47 found that on the card menu's level change.
     */
    val highlightOn = remember { HighlightRecord<String>() }

    LaunchedEffect(state.candidate) {
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { addButton.requestFocus() } },
            holds = { highlightOn.holds(ADD_CONFIRM) },
        )
    }

    Column(
        modifier = Modifier.widthIn(max = 1100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CandidatePicture(state.candidate, ink, thumbnails)
            Column {
                Text(
                    text = state.candidate.title,
                    color = ink.onSurface,
                    style = Fonts.FocusedTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                CandidateMetaLine(candidate = state.candidate, color = ink.onSurfaceMuted)
            }
        }

        Text(
            text = ADD_DIRECTION_LABEL,
            color = ink.onSurfaceFaint,
            style = Fonts.Badge,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ADD_DIRECTIONS.forEach { direction ->
                DirectionChip(
                    direction = direction,
                    chosen = direction == state.direction,
                    ink = ink,
                    onChoose = { onDirection(direction) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        PanelButton(
            label = ADD_CONFIRM,
            ink = ink,
            onChoose = onAdd,
            width = 240.dp,
            focusRequester = addButton,
            onHighlightChanged = { holdsIt -> highlightOn.report(ADD_CONFIRM, holdsIt) },
        )
        Text(text = ADD_CONFIRM_DETAIL, color = ink.onSurfaceMuted, style = Fonts.Meta, maxLines = 1)
    }
}

/**
 * One of the two Direction choices.
 *
 * The chosen one is drawn filled even when it does not have the highlight, so the household
 * can see what it has picked while standing on `Add`. `CONTEXT.md` fixes the two words:
 * newest-first and oldest-first, never ascending and never sort order.
 */
@Composable
private fun DirectionChip(
    direction: Direction,
    chosen: Boolean,
    ink: Ink,
    onChoose: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(46.dp)
            .background(
                color = when {
                    focused -> ink.focusFill
                    chosen -> ink.surfaceRaised
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 2.dp,
                color = when {
                    focused -> ink.accent
                    chosen -> ink.onSurfaceMuted
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .onSelectOrHoldKey(onSelect = onChoose, onHold = {})
            .focusable()
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = directionLabel(direction),
            color = if (focused) ink.onFocus else ink.onSurface,
            style = Fonts.Chip,
            maxLines = 1,
        )
    }
}

/**
 * The refusal of spec section 6.4, with the one thing the household probably wanted.
 *
 * It offers to open the Source it already has, because a household that pasted a link it had
 * added a month ago is trying to get to that Source.
 */
@Composable
private fun AlreadyAddedStep(
    state: AddSourceState.AlreadyAdded,
    ink: Ink,
    onOpenExisting: (String) -> Unit,
    onTrySomethingElse: () -> Unit,
) {
    val first = remember(state) { FocusRequester() }

    /** The words on the first button, which are also its name to [highlightOn]. */
    val openLabel = openExisting(state.name)

    /**
     * Which of the two buttons reports holding the highlight.
     *
     * **Only a button's own focus report writes it, and the ladder below reads it and nothing
     * else.** Build ticket 48. Both buttons report, each named by the words on it, because a
     * record that heard only from the first would say the first still held the highlight after
     * the household had walked down to the second.
     *
     * The two labels can never be the same words: one of them always starts with `Open `.
     */
    val highlightOn = remember { HighlightRecord<String>() }

    LaunchedEffect(state) {
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { first.requestFocus() } },
            holds = { highlightOn.holds(openLabel) },
        )
    }

    Column(
        modifier = Modifier.widthIn(max = 1100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = alreadyAddedDetail(state.name),
            color = ink.onSurfaceMuted,
            style = Fonts.Body,
            maxLines = 3,
        )
        PanelButton(
            label = openLabel,
            ink = ink,
            onChoose = { onOpenExisting(state.sourceId) },
            width = 420.dp,
            focusRequester = first,
            onHighlightChanged = { holdsIt -> highlightOn.report(openLabel, holdsIt) },
        )
        PanelButton(
            label = ADD_TRY_SOMETHING_ELSE,
            ink = ink,
            // The field is one `BACK` away, and this is the same move written as a line, so
            // the household never has to know that.
            onChoose = onTrySomethingElse,
            width = 420.dp,
            onHighlightChanged = { holdsIt ->
                highlightOn.report(ADD_TRY_SOMETHING_ELSE, holdsIt)
            },
        )
    }
}

/**
 * One button of a page.
 *
 * The same shape as a menu line on the home rail, written here rather than shared with it
 * because that one lives inside a panel over a scrim and this one sits on a page. It **is**
 * shared with `RenameSourceScreen`, which is the other page in this app with buttons on it, so
 * that the two pages cannot drift apart.
 *
 * @param enabled false draws the button dim and takes it out of the D-pad's walk, the same rule
 *   `PanelLine` on the home rail follows: a button that took focus and did nothing would be
 *   worse than a dim one. The add screen's own buttons are all always live; the rename screen's
 *   `Use the name YouTube gives it` is the one button in the app that can be dim.
 * @param onHighlightChanged what the button tells its page's highlight record when it gains or
 *   loses the highlight. The page's retry ladder reads that record and nothing else, so a
 *   button that is never given this is a button no ladder can wait for. Build ticket 48.
 */
@Composable
internal fun PanelButton(
    label: String,
    ink: Ink,
    onChoose: () -> Unit,
    width: androidx.compose.ui.unit.Dp,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onHighlightChanged: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(width)
            .height(46.dp)
            // The words are on a child node, so only a tag can say which button holds the
            // highlight. See [TestTags].
            .testTag(TestTags.pageButton(label))
            .background(
                color = if (focused) ink.focusFill else ink.surfaceRaised,
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
                        .onSelectOrHoldKey(onSelect = onChoose, onHold = {})
                } else {
                    Modifier
                },
            )
            // **Outside the `enabled` branch, and `PanelLine` on the home rail has the same
            // shape for the same reason.** A button that goes dim while it holds the highlight
            // has to be able to say that it lost it. With this modifier inside the branch,
            // going dim takes the modifier away before Compose reports anything, so the page's
            // record would go on naming a button that holds nothing and this button would go on
            // being drawn with the focus fill and the ring. Found by `/code-review` on build
            // ticket 47, on `PanelLine`; carried here by build ticket 48.
            .onFocusChanged { focusState ->
                // Only a real change is reported on. Compose delivers `onFocusChanged` on other
                // things than a gain or a loss, and a record written from a delivery that
                // changed nothing would report a loss this button never had. Measured on
                // `googletv1080p`: a node is told `isFocused=false` once at composition, before
                // anything has asked it for anything.
                val changed = focusState.isFocused != focused
                focused = focusState.isFocused
                if (changed) onHighlightChanged(focusState.isFocused)
            }
            // A dim button is not focusable, so the D-pad walks straight past it and it can
            // never be chosen.
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
            overflow = TextOverflow.Ellipsis,
        )
    }
}
