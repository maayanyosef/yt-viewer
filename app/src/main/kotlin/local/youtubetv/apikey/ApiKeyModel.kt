package local.youtubetv.apikey

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the key screen shows right now. Build ticket 64.
 *
 * @property replacing true when a key is saved, which is the screen reached from the home
 *   screen's menu. `BACK` then leaves it, and `Remove the saved key` is offered. False when no
 *   key is saved, which is the screen the app opens on.
 * @property pageUrl the Key page's address, or null while the server is not running or when
 *   the television is on no home network.
 * @property typing true while the field for typing with the remote is shown.
 * @property typed what is in that field.
 * @property checking true while a key is being checked with YouTube.
 * @property lastResult the answer to the last key, from the phone or the remote, or null.
 * @property confirmingRemove true while the screen asks before removing the key.
 */
data class ApiKeyScreenState(
    val replacing: Boolean,
    val pageUrl: String? = null,
    val typing: Boolean = false,
    val typed: String = "",
    val checking: Boolean = false,
    val lastResult: KeyCheckResult? = null,
    val confirmingRemove: Boolean = false,
)

/**
 * Drives the key screen: check a key, save it, or remove the saved one. Build ticket 64.
 *
 * No Android import, the same shape as `RenameSourceModel` and `HomeModel`, so the whole flow is
 * a JVM test with no device and no quota. The Key page and the remote both end in [submit],
 * so a key is checked and saved the same way whichever way it arrived.
 *
 * @param onSaved called once a key is saved. It may be called from the Key page's own
 *   thread, so the caller moves to the main thread itself.
 * @param log one line per event. It is never handed a key.
 */
class ApiKeyModel(
    private val store: ApiKeyStore,
    private val checker: KeyChecker,
    private val onSaved: (KeyCheckResult) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ApiKeyScreenState(replacing = store.read() != null))
    val state: StateFlow<ApiKeyScreenState> = mutableState.asStateFlow()

    /**
     * Held for the whole of a check, so a key from the phone and a key from the remote that
     * arrive together are checked one after the other and never saved over each other.
     */
    private val checking = Mutex()

    /** Set when the screen closes. A key that arrives after that is not saved. */
    @Volatile
    private var closed = false

    fun pageReady(url: String?) = mutableState.update { it.copy(pageUrl = url) }

    fun startTyping() = mutableState.update { it.copy(typing = true, confirmingRemove = false) }

    fun typed(text: String) = mutableState.update { it.copy(typed = text) }

    /**
     * Checks one key and saves it when it works.
     *
     * One Data API call, 1 unit, unless nothing was entered. The key is never logged: the line
     * names where it came from and what the check said.
     */
    suspend fun submit(raw: String, from: KeyFrom): KeyCheckResult = checking.withLock {
        val key = normalizeKey(raw)
        if (closed) {
            // No call is spent on a key that could not be saved.
            log(keyCheckLogLine(from, KeyCheckResult.SCREEN_CLOSED))
            return@withLock KeyCheckResult.SCREEN_CLOSED
        }
        mutableState.update { it.copy(checking = true, confirmingRemove = false) }
        val checked = if (key.isEmpty()) KeyCheckResult.EMPTY else checker.check(key)
        // The screen can close while the check runs. A working key is then not saved, and the
        // answer says so rather than claiming it was.
        val result = if (checked.saves && closed) KeyCheckResult.SCREEN_CLOSED else checked
        log(keyCheckLogLine(from, result))
        if (result.saves) {
            store.write(key)
            closed = true
            mutableState.update { it.copy(checking = false, lastResult = result, typed = "") }
            onSaved(result)
        } else {
            mutableState.update { it.copy(checking = false, lastResult = result) }
        }
        result
    }

    fun askRemove() = mutableState.update { it.copy(confirmingRemove = true) }

    /** Removes the saved key. The screen stays, now as the screen with no key saved. */
    fun remove() {
        store.clear()
        log("The saved API key was removed.")
        mutableState.update {
            it.copy(replacing = false, confirmingRemove = false, lastResult = null)
        }
    }

    /**
     * `BACK` on the key screen. True when the screen handled it and stays open.
     *
     * It steps back out of the confirmation, then out of the typing field. When there is
     * nothing left to step out of it returns false, and the caller decides: leave the screen
     * when a key is saved, or leave the app when none is.
     */
    fun back(): Boolean {
        val now = state.value
        return when {
            now.confirmingRemove -> {
                mutableState.update { it.copy(confirmingRemove = false) }
                true
            }
            now.typing -> {
                mutableState.update { it.copy(typing = false) }
                true
            }
            else -> false
        }
    }

    /** The screen is closing. Nothing that arrives after this is saved. */
    fun close() {
        closed = true
    }
}
