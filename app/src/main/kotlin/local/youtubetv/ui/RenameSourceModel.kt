package local.youtubetv.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.youtubetv.data.SourceStore
import local.youtubetv.data.StoredSource

/**
 * What the rename screen shows right now.
 *
 * @property sourceId the Source being renamed, which the preview needs for its own fallback.
 * @property typed what is in the field. It starts at [renameFieldStart].
 * @property fetchedName what YouTube calls this Source, or null when it has never been named.
 *   It is what the card falls back to when the typed name is cleared, so the preview needs it.
 * @property storedTypedName the typed name as storage holds it. It does **not** follow the
 *   field: it is what [RENAME_CLEAR] would take away, and it changes only when something is
 *   written, which is the moment this screen closes.
 */
data class RenameSourceState(
    val sourceId: String,
    val typed: String,
    val fetchedName: String?,
    val storedTypedName: String?,
)

/**
 * What one rename wrote, and what the household will see because of it.
 *
 * **The two are different facts and the caller needs both.** [typedName] is what went into
 * `sources.typed_name`, and it is null when the name was cleared. [shown] is what the Source's
 * own row says the card is headed by now, which for a cleared name is the title YouTube gave
 * it. A caller told only [shown] could not patch the rail's card, and a caller told only
 * [typedName] could not say in the log what the household is about to read.
 */
data class RenamedSource(val typedName: String?, val shown: String)

/**
 * Drives renaming one Source: hold what is typed, and write it to the Source's own row.
 *
 * It has no Android import, in the same shape as [AddSourceModel], [HomeModel] and
 * [SourceScreenModel], so the whole flow is a JVM test with no device and no quota. Unlike
 * [AddSourceModel] it touches no network at all: a rename costs nothing of the daily 10,000 and
 * nothing of the `search.list` hundred, and clearing a name brings YouTube's back out of the
 * column the change check already filled for free.
 *
 * **Both names it starts with are handed in rather than read.** The household reaches this
 * screen from a rail card's long-press menu, and that card is already holding both. Reading the
 * row here would be SQLite on the way to the first frame, and the field would sit empty until
 * it came back, which on a field is worse than on a header: text arriving under a household
 * that has started typing would take away what they typed. The card is current for the one
 * column that matters, because nothing but this screen ever writes `typed_name`.
 *
 * @param fetchedName what YouTube calls the Source. Only the preview and [renameFieldStart]
 *   read it; it is never written back.
 * @param typedName what the household has already typed for this Source, or null.
 * @param log where a real build passes `Log.i`.
 */
class RenameSourceModel(
    private val sourceId: String,
    fetchedName: String?,
    typedName: String?,
    private val store: SourceStore,
    private val log: (String) -> Unit = {},
) {

    private val mutableState = MutableStateFlow(
        RenameSourceState(
            sourceId = sourceId,
            typed = renameFieldStart(typedName, fetchedName, sourceId),
            fetchedName = fetchedName,
            storedTypedName = StoredSource.typedNameOfInput(typedName),
        ),
    )
    val state: StateFlow<RenameSourceState> = mutableState.asStateFlow()

    /**
     * Held for the whole of a write, for the same reason [AddSourceModel] holds one: the
     * television's keyboard action key and the `Save this name` button are two ways to the same
     * write, and a household can reach both before the first one has answered.
     */
    private val writing = Mutex()

    /** The household typed. Nothing is written per keystroke. */
    fun typed(text: String) {
        mutableState.value = mutableState.value.copy(typed = text)
    }

    /**
     * Writes what is in the field.
     *
     * **An empty field clears the name rather than storing an empty one**, which is the whole
     * of the owner's decision of 2026-09-20: the card then shows what YouTube calls the Source
     * again, with no API call. [StoredSource.typedNameOfInput] is the one rule that says so and
     * the store applies it as well, so the two cannot drift apart.
     */
    suspend fun save(): RenamedSource? = writing.withLock { write(mutableState.value.typed) }

    /**
     * Takes the typed name away, so the card falls back to the name YouTube has.
     *
     * It is the same write [save] makes on an empty field, reached in one press instead of by
     * emptying the field with a remote. See [RENAME_CLEAR].
     */
    suspend fun clear(): RenamedSource? = writing.withLock { write("") }

    /**
     * **Call it off the main thread.** It is one `UPDATE` by primary key and one row read.
     *
     * The row is read back rather than worked out, so the answer is what the rail will really
     * draw. Three things could make a guess wrong: the store's own blank rule, a Source removed
     * between the menu and this press, and a change check that filled `title` while this screen
     * was open. It is the same lesson `SourceStore.fillMissingTitle` carries, which
     * `/code-review` wrote into ticket 20: a caller that logs the name it meant to write will
     * one day log a name the Source is not called.
     *
     * @return what was written and what the card is headed by now, or null when there is no
     *   such Source. The screen closes either way; the rail is what says whether anything
     *   changed.
     */
    private fun write(text: String): RenamedSource? {
        val wanted = StoredSource.typedNameOfInput(text)
        if (!store.setTypedName(sourceId, wanted)) {
            log(renameRefusedLine(sourceId))
            return null
        }
        val row = store.source(sourceId)
        val shown = sourceNameOrId(row?.typedName, row?.title, sourceId)
        log(renameLogLine(sourceId, wanted, shown))
        // The row's own value and not `wanted`, for the reason this whole function reads the
        // row back: the store owns the blank rule, and what it stored is what the rail must be
        // told.
        return RenamedSource(typedName = row?.typedName, shown = shown)
    }
}
