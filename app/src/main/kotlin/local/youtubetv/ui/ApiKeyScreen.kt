package local.youtubetv.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.delay
import local.youtubetv.apikey.ApiKeyScreenState
import local.youtubetv.apikey.KEY_BACK_TO_QR
import local.youtubetv.apikey.KEY_CHECK_BUTTON
import local.youtubetv.apikey.KEY_FIELD_HINT
import local.youtubetv.apikey.KEY_REMOVE_BUTTON
import local.youtubetv.apikey.KEY_REMOVE_CONFIRM
import local.youtubetv.apikey.KEY_REMOVE_QUESTION
import local.youtubetv.apikey.KEY_REMOVE_REFUSE
import local.youtubetv.apikey.KEY_SCREEN_ADDRESS_INTRO
import local.youtubetv.apikey.KEY_SCREEN_CHECKING
import local.youtubetv.apikey.KEY_SCREEN_NO_NETWORK
import local.youtubetv.apikey.KEY_SCREEN_REPLACE_TITLE
import local.youtubetv.apikey.KEY_SCREEN_REPLACE_WHY
import local.youtubetv.apikey.KEY_SCREEN_SCAN
import local.youtubetv.apikey.KEY_SCREEN_TITLE
import local.youtubetv.apikey.KEY_SCREEN_WAITING
import local.youtubetv.apikey.KEY_SCREEN_WHY
import local.youtubetv.apikey.KEY_TYPE_BUTTON
import local.youtubetv.apikey.keyCheckMessage
import local.youtubetv.apikey.saves

/** The node names the key screen's focus ladder waits on. */
private const val KEY_FIELD = "key field"

/**
 * The key screen. Build ticket 64, spec section 7.5.
 *
 * Shown instead of the home screen when no key is saved, and reached from the home screen's
 * menu to replace or remove a saved key. Two ways in for a key: the QR code, which opens a page
 * the app serves on the home network, and a field typed with the remote.
 *
 * Every rule is in `apikey/ApiKeyModel.kt` with a JVM test, and every word is in
 * `apikey/ApiKeyText.kt`. What is left here is drawing it. The text field follows the rename
 * screen's shape, including the `Down` handling a `BasicTextField` needs.
 */
@Composable
fun ApiKeyScreen(
    state: ApiKeyScreenState,
    onStartTyping: () -> Unit = {},
    onTyped: (String) -> Unit = {},
    onSubmitTyped: () -> Unit = {},
    onBackToQr: () -> Unit = {},
    onAskRemove: () -> Unit = {},
    onRemove: () -> Unit = {},
) {
    val ink = screenInk()
    val firstRequester = remember(state.typing, state.confirmingRemove) { FocusRequester() }
    val checkRequester = remember { FocusRequester() }
    val highlightOn = remember { HighlightRecord<String>() }
    var fieldFocused by remember { mutableStateOf(false) }

    // The first focusable thing of whichever view is up takes the highlight, through the same
    // retry ladder every screen of this app uses.
    val firstName = when {
        state.typing -> KEY_FIELD
        state.confirmingRemove -> KEY_REMOVE_REFUSE
        else -> KEY_TYPE_BUTTON
    }
    LaunchedEffect(firstName) {
        askUntilHighlightHolds(
            wait = { millis -> delay(millis) },
            ask = { runCatching { firstRequester.requestFocus() } },
            holds = { highlightOn.holds(firstName) },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(ink.surface)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state.replacing) KEY_SCREEN_REPLACE_TITLE else KEY_SCREEN_TITLE,
                color = ink.onSurface,
                style = Fonts.SourceTitle,
                maxLines = 1,
            )
            Text(
                text = if (state.replacing) KEY_SCREEN_REPLACE_WHY else KEY_SCREEN_WHY,
                color = ink.onSurfaceMuted,
                style = Fonts.FocusedTitle,
            )
            val url = state.pageUrl
            if (url != null) {
                Text(text = KEY_SCREEN_SCAN, color = ink.onSurface, style = Fonts.FocusedTitle)
                Text(text = KEY_SCREEN_ADDRESS_INTRO, color = ink.onSurfaceMuted, style = Fonts.FocusedTitle)
                // The address alone on its own line, so the sentence before it never pushes half
                // of it onto the next line. The longest address, `http://255.255.255.255:65535/`
                // and a 10-letter token, is 39 characters. The emulator's 33-character address
                // took about half the column at 1080p, so 39 fit with room for a larger font.
                // It is not clipped to one line: a wrapped address can still be typed, a clipped
                // one cannot. Build ticket 68.
                Text(text = url, color = ink.onSurfaceMuted, style = Fonts.FocusedTitle)
            } else {
                Text(text = KEY_SCREEN_NO_NETWORK, color = ink.onSurfaceMuted, style = Fonts.FocusedTitle)
            }

            // The status line: what the last key did, or what is happening now.
            val result = state.lastResult
            val status = when {
                state.checking -> KEY_SCREEN_CHECKING
                result != null -> keyCheckMessage(result)
                else -> KEY_SCREEN_WAITING
            }
            Text(
                text = status,
                color = when {
                    state.checking || result == null -> ink.onSurfaceMuted
                    result.saves -> ink.accent
                    else -> Color(0xFFFF8A80)
                },
                style = Fonts.FocusedTitle,
            )

            Spacer(Modifier.height(6.dp))

            when {
                state.typing -> {
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
                            Text(text = KEY_FIELD_HINT, color = ink.onSurfaceFaint, style = Fonts.Body, maxLines = 1)
                        }
                        BasicTextField(
                            value = state.typed,
                            onValueChange = onTyped,
                            singleLine = true,
                            textStyle = TextStyle(color = ink.onSurface, fontSize = Fonts.Body.fontSize),
                            cursorBrush = SolidColor(ink.accent),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            ),
                            keyboardActions = KeyboardActions(onDone = { onSubmitTyped() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                // A `BasicTextField` eats `Down`. See `RenameSourceScreen`.
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                                    runCatching { checkRequester.requestFocus() }
                                    true
                                }
                                .focusRequester(firstRequester)
                                .onFocusChanged { focusState ->
                                    val changed = focusState.isFocused != fieldFocused
                                    fieldFocused = focusState.isFocused
                                    if (changed) highlightOn.report(KEY_FIELD, focusState.isFocused)
                                },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PanelButton(
                            label = KEY_CHECK_BUTTON,
                            ink = ink,
                            onChoose = onSubmitTyped,
                            width = 300.dp,
                            enabled = !state.checking,
                            focusRequester = checkRequester,
                            onHighlightChanged = { highlightOn.report(KEY_CHECK_BUTTON, it) },
                        )
                        PanelButton(
                            label = KEY_BACK_TO_QR,
                            ink = ink,
                            onChoose = onBackToQr,
                            width = 240.dp,
                            onHighlightChanged = { highlightOn.report(KEY_BACK_TO_QR, it) },
                        )
                    }
                }
                state.confirmingRemove -> {
                    Text(text = KEY_REMOVE_QUESTION, color = ink.onSurface, style = Fonts.FocusedTitle)
                    // The refusing button is first and takes the highlight, the same order every
                    // confirmation on the home rail uses, so a press made without reading keeps
                    // the key.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PanelButton(
                            label = KEY_REMOVE_REFUSE,
                            ink = ink,
                            onChoose = onBackToQr,
                            width = 240.dp,
                            focusRequester = firstRequester,
                            onHighlightChanged = { highlightOn.report(KEY_REMOVE_REFUSE, it) },
                        )
                        PanelButton(
                            label = KEY_REMOVE_CONFIRM,
                            ink = ink,
                            onChoose = onRemove,
                            width = 240.dp,
                            onHighlightChanged = { highlightOn.report(KEY_REMOVE_CONFIRM, it) },
                        )
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PanelButton(
                            label = KEY_TYPE_BUTTON,
                            ink = ink,
                            onChoose = onStartTyping,
                            width = 320.dp,
                            focusRequester = firstRequester,
                            onHighlightChanged = { highlightOn.report(KEY_TYPE_BUTTON, it) },
                        )
                        if (state.replacing) {
                            PanelButton(
                                label = KEY_REMOVE_BUTTON,
                                ink = ink,
                                onChoose = onAskRemove,
                                width = 260.dp,
                                onHighlightChanged = { highlightOn.report(KEY_REMOVE_BUTTON, it) },
                            )
                        }
                    }
                }
            }
        }

        state.pageUrl?.let { url ->
            QrCodeImage(
                text = url,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(300.dp),
            )
        }
    }
}

/**
 * A QR code, drawn module by module on a white square with the standard four-module quiet zone.
 *
 * Black on white whatever the app's own colours are, because that is what every phone camera
 * reads best.
 */
@Composable
private fun QrCodeImage(text: String, modifier: Modifier = Modifier) {
    val code = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier = modifier.background(Color.White)) {
        val quiet = 4
        val modules = code.size + quiet * 2
        val cell = size.minDimension / modules
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code.getModule(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset((x + quiet) * cell, (y + quiet) * cell),
                        // A hair wider than one cell, so no thin line shows between neighbours
                        // when a cell lands between two pixels.
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}
