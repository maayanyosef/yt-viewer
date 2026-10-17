package local.youtubetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * The screen that gives a Source the household's own name, or takes that name away again.
 *
 * Reached from `Rename` in a rail card's long-press menu. Build ticket 27, and spec section 7.1
 * holds the behaviour.
 *
 * **It is the add screen's way of taking text, and deliberately not a second way.** A
 * [BasicTextField] that asks for focus as the screen opens is what brings the television's own
 * keyboard up with its microphone button, and the keyboard's action key writes the name, so the
 * common path never touches a button. `AddSourceScreen.TypingStep` is where that shape was
 * settled and it is copied here rather than reinvented.
 *
 * **It is a page and not a panel over the rail.** The keyboard covers the bottom half of the
 * screen while it is up, so a panel would be a panel nobody could see what was behind. It is
 * also the shape that keeps `BACK` in `MainActivity`, which owns every `BACK` in this app.
 *
 * **Every rule behind it is somewhere else with a JVM test.** [RenameSourceModel] holds the
 * state and makes the write, and `ui/RenameSourceText.kt` holds every word, the starting value
 * of the field, the preview and the dim rule. What is left here is drawing it.
 */
@Composable
fun RenameSourceScreen(
    state: RenameSourceState,
    onTyped: (String) -> Unit = {},
    // The keyboard's action key and the `Save this name` button, which are one action.
    onSave: () -> Unit = {},
    // `Use the name YouTube gives it`. It writes nothing into the field first: the write is
    // what matters and the screen closes on it.
    onClear: () -> Unit = {},
) {
    val ink = screenInk()
    val requester = remember { FocusRequester() }

    /**
     * The first button, named so that `Down` out of the field can reach it.
     *
     * **A `BasicTextField` eats `Down`.** Compose Foundation maps `Up` and `Down` inside a text
     * field to its own cursor commands and consumes them, on a single-line field as well as on
     * a many-line one, so Compose's focus search is never asked and the highlight cannot leave
     * the field at all. Seen on the household television on 2026-09-21: from the field, three
     * presses of `Down` moved nothing, and the two buttons below were unreachable with the
     * remote. It is the same shape of defect ticket 31 fixed on the rail's menu panel, and the
     * same answer: name the neighbour rather than search for one.
     */
    val saveRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }

    /**
     * Which node of this screen reports holding the highlight.
     *
     * **Only a node's own focus report writes it, and the ladder below reads it and nothing
     * else.** Build ticket 48. The ladder used to exit on
     * `runCatching { requester.requestFocus() }.isSuccess`, which says only that the call did
     * not throw, so its first rung read success whatever the ask had done and the 60, 120 and
     * 240 ms rungs never ran. The comment that used to sit on the ladder said it was the same
     * one the rail's menu panel uses, and that stopped being true when build ticket 47 fixed
     * the rail's; it is true again now.
     *
     * All three nodes report: the field, named [RENAME_FIELD], and the two buttons, each named
     * by the words on it. `Use the name YouTube gives it` is the one button in the app that can
     * be dim, which is why [PanelButton] reports from outside its own `enabled` branch.
     *
     * **A `BasicTextField` reports through the caller's own `Modifier.onFocusChanged` exactly
     * as a `focusable()` box does**, which build ticket 48 measured on `googletv1080p` rather
     * than assuming. `AddSourceScreen.TypingStep` carries the measurement.
     */
    val highlightOn = remember { HighlightRecord<String>() }

    // The retry ladder the add screen's field and every panel in this app now use, for the same
    // reason: the field has only just been placed, and a single request can land before there
    // is anything to give focus to. It gives up at about 484 ms.
    LaunchedEffect(Unit) {
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { requester.requestFocus() } },
            holds = { highlightOn.holds(RENAME_FIELD) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ink.surface)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = RENAME_TITLE,
            color = ink.onSurface,
            style = Fonts.SourceTitle,
            maxLines = 1,
        )

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
                        text = RENAME_FIELD_HINT,
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // The television keyboard's own action key, which is the path the
                    // household actually uses: the keyboard covers the buttons below while it
                    // is up. `Done` and not `Search`, because nothing is looked up here.
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        // **Before the text field's own key handling, which is the point.** A
                        // preview handler is visited on the way down the tree, so this sees
                        // `Down` before Compose turns it into a cursor command and swallows
                        // it. See [saveRequester]. Only `Down` is taken: every other key,
                        // including `Up`, `Left`, `Right` and every character, goes on to the
                        // field, because that is what typing is.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            // Kept whether or not the request lands. A `Down` handed back to
                            // the field after a failed request would move the cursor nowhere
                            // and look to the household exactly like this defect.
                            runCatching { saveRequester.requestFocus() }
                            true
                        }
                        .focusRequester(requester)
                        // Two things are written from one delivery: `fieldFocused`, which draws
                        // the ring, and the record, which the ladder waits on. Only a real
                        // change is reported on, because a node is told `isFocused=false` once
                        // at composition, before anything has asked it for anything.
                        .onFocusChanged { focusState ->
                            val changed = focusState.isFocused != fieldFocused
                            fieldFocused = focusState.isFocused
                            if (changed) highlightOn.report(RENAME_FIELD, focusState.isFocused)
                        },
                )
            }

            // What the card will be headed by if this is saved now. **The empty field is the
            // case this line exists for**: an empty field does not leave the card blank, it
            // gives back the name YouTube has, and nothing else on this screen says so.
            //
            // Drawn through [MetaLine], so the label and the name are two pieces of text and
            // never one. Two of the household's four Sources are named in Hebrew, and one
            // `Text` lays its whole content out as one bidirectional run. Ticket 24's rule and
            // spec section 6.2.
            MetaLine(
                parts = listOf(
                    RENAME_PREVIEW_LABEL,
                    renamePreviewName(state.typed, state.fetchedName, state.sourceId),
                ),
                // The name gives way when the line is too wide, never the label, because a
                // label cut short stops saying what the line is about.
                shrinkingPart = 1,
                color = ink.onSurfaceMuted,
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PanelButton(
                    label = RENAME_SAVE,
                    ink = ink,
                    onChoose = onSave,
                    width = 260.dp,
                    // Where `Down` out of the field lands. `Up` back to the field needs no
                    // wiring: nothing up there eats the key, so Compose's own search finds it.
                    focusRequester = saveRequester,
                    onHighlightChanged = { holdsIt -> highlightOn.report(RENAME_SAVE, holdsIt) },
                )
                PanelButton(
                    label = RENAME_CLEAR,
                    ink = ink,
                    onChoose = onClear,
                    width = 380.dp,
                    // Dim on a Source the household has never renamed, where it would do
                    // nothing at all. See [renameClearEnabled].
                    enabled = renameClearEnabled(state.storedTypedName),
                    onHighlightChanged = { holdsIt -> highlightOn.report(RENAME_CLEAR, holdsIt) },
                )
            }
        }
    }
}
