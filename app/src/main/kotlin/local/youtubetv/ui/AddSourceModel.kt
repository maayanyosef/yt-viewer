package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.youtubetv.data.Direction
import local.youtubetv.data.SourceStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.WatchLog
import local.youtubetv.youtube.ChannelDetails
import local.youtubetv.youtube.Resolved
import local.youtubetv.youtube.SourceCandidate
import local.youtubetv.youtube.SearchOutcome
import local.youtubetv.youtube.SourceInput
import local.youtubetv.youtube.SourceResolver
import local.youtubetv.youtube.SourceSearch
import local.youtubetv.youtube.YouTubeApiException
import local.youtubetv.youtube.firstStepRows
import local.youtubetv.youtube.parseSourceInput

/** What the add screen shows right now. Spec sections 6.1 to 6.4. */
sealed interface AddSourceState {

    /**
     * The household is typing, or has just been told why the last try did not work.
     *
     * @property typed what is in the field.
     * @property message the refusal from the last try, or null on a fresh field. It sits under
     *   the field rather than on a screen of its own, so the household can correct what it
     *   typed instead of starting again.
     */
    data class Typing(val typed: String = "", val message: String? = null) : AddSourceState

    /** Waiting for YouTube, which is one call and usually well under a second. */
    data object Looking : AddSourceState

    /**
     * Rows to pick from: either the first step, or a channel's own playlists.
     *
     * @property title the heading over them, which says which question is being asked.
     */
    data class Choosing(val title: String, val rows: List<SourceCandidate>) : AddSourceState

    /** The add screen of spec section 6.3: the last step before anything is stored. */
    data class Confirming(
        val candidate: SourceCandidate,
        val direction: Direction,
    ) : AddSourceState

    /**
     * The household already has this Source. Spec section 6.4.
     *
     * @property sourceId the Source it already has, so the offer to open it can be taken.
     * @property name what that Source is called on the rail.
     */
    data class AlreadyAdded(val sourceId: String, val name: String) : AddSourceState
}

/** What [AddSourceModel.add] stored, so the Activity can open it. */
data class AddedSource(val sourceId: String, val title: String)

/**
 * Drives adding a Source: read what was typed, ask YouTube, and store the answer.
 *
 * It has no Android import on purpose, in the same shape as [SourceScreenModel] and
 * [HomeModel], so the whole flow is a JVM test with no device. The one call it makes costs
 * quota, so the tests run against a fake [SourceResolver] and cost none.
 *
 * **`search.list` is reachable from exactly one place here**: a typed name comes back from the
 * resolver as [Resolved.NeedsSearch] and is handed to [SourceSearch]. Nothing else in this
 * file, and nothing at all outside the `Find it` press, can spend one. That endpoint has a
 * bucket of 100 calls a day and is the tightest limit in the project, so the rules that
 * protect it live in [SourceSearch] and are tested there.
 *
 * @param search the one door to `search.list`. It is **shared across every add screen of the
 *   session**, because its cache is what makes a repeated query cost nothing. A model of its
 *   own per screen would throw that cache away every time the household pressed `BACK`.
 * @param log where a real build passes `Log.i`.
 */
class AddSourceModel(
    private val store: SourceStore,
    private val watches: WatchLog,
    private val resolver: SourceResolver,
    private val search: SourceSearch,
    private val log: (String) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {

    private val mutableState = MutableStateFlow<AddSourceState>(AddSourceState.Typing())
    val state: StateFlow<AddSourceState> = mutableState.asStateFlow()

    /**
     * Where `BACK` goes, one step at a time.
     *
     * A stack and not a rule worked out from the current state, because the same
     * [AddSourceState.Confirming] is reached from the field directly, from the first step and
     * from a channel's second step, and only the stack knows which.
     */
    private val history = ArrayDeque<AddSourceState>()

    /**
     * Held for the whole of a lookup and of an add.
     *
     * Each of them reads the state, works something out and writes it back, and each can be
     * started twice by a household pressing the centre button twice on a slow call. Two adds
     * interleaving would write two `sourceAdded` entries for one Source.
     */
    private val working = Mutex()

    /** The household typed. Nothing is asked of YouTube per keystroke. Spec section 6.1. */
    fun typed(text: String) {
        val current = mutableState.value
        if (current !is AddSourceState.Typing) return
        // The message is dropped as soon as the text changes. Leaving a refusal under a field
        // the household has since corrected reads as though the new text was refused too.
        mutableState.value = AddSourceState.Typing(typed = text, message = null)
    }

    /**
     * The household pressed `Find it`, so this is the one moment YouTube is asked.
     *
     * Submit-driven and never per keystroke. Spec section 6.1 says so about search, and it is
     * even more true here: every one of these paths costs a unit.
     *
     * **The duplicate check of spec section 6.4 happens twice, and the first is free.** When
     * what was typed is already a playlist id, storage is asked before YouTube is, so pasting
     * a Source the household already has costs nothing at all. The second check is after the
     * lookup, on what the id resolved to, and it is what catches a channel that was already
     * added under its uploads playlist.
     */
    suspend fun find() = working.withLock {
        val current = mutableState.value as? AddSourceState.Typing ?: return@withLock
        val typed = current.typed.trim()
        val input = parseSourceInput(typed)
        log("Add a Source read \"$typed\" as $input.")

        // Pushed before anything can go wrong, so every answer below, refusal included, has
        // the field to go back to.
        push(current)

        // Free, and it is the criterion about pasting a channel's `UU...` playlist after
        // adding the channel: the id is already the key, so no call is needed to refuse it.
        alreadyAdded(input)?.let { return@withLock show(it) }

        mutableState.value = AddSourceState.Looking
        val resolved = try {
            resolver.resolve(input)
        } catch (failure: YouTubeApiException) {
            log("Add a Source failed: ${failure.reason} ${failure.message.orEmpty().take(300)}")
            return@withLock refuse(typed, addFailureLine(failure.reason))
        }

        when (resolved) {
            is Resolved.Found -> when (val next = fromFound(resolved)) {
                // Nothing to offer after all, which is a refusal and not a step forward: the
                // typed text is kept and the history entry pushed above is popped, so one
                // `BACK` leaves the screen rather than two. Found by `/code-review`.
                null -> refuse(typed, ADD_NOT_FOUND)
                else -> show(next)
            }
            Resolved.NotFound -> refuse(typed, ADD_NOT_FOUND)
            is Resolved.NeedsSearch -> searchFor(resolved.text)
            Resolved.Unreadable -> refuse(typed, ADD_UNREADABLE)
        }
    }

    /**
     * The one path that spends a `search.list` call. Spec sections 6.1 and 6.2.
     *
     * It is reached only from [find], which runs on a submit, so nothing a household does
     * while typing can spend a call. Everything else that protects the day's hundred is
     * [SourceSearch]'s: the per-session cache, the one-at-a-time lock and the latch that stops
     * asking once YouTube says they are gone.
     *
     * A result of **one** row still shows the list rather than jumping to the add screen. A
     * pasted link resolves to one certain thing and skipping a press there is a kindness; a
     * search result is YouTube's guess at what was meant, and adding a Source the household
     * never looked at is the wrong way to be wrong.
     */
    private suspend fun searchFor(query: String) {
        mutableState.value = AddSourceState.Looking
        when (val outcome = search.find(query)) {
            is SearchOutcome.Results -> when {
                outcome.rows.isEmpty() -> refuse(query, searchFoundNothing(query))
                else -> show(AddSourceState.Choosing(searchResultsTitle(query), outcome.rows))
            }
            SearchOutcome.QuotaExhausted -> refuse(query, SEARCH_QUOTA_EXHAUSTED)
            is SearchOutcome.Failed -> refuse(query, addFailureLine(outcome.reason))
        }
    }

    /**
     * The household picked a row.
     *
     * A channel row of the first step opens that channel's own playlists, which is spec
     * section 6.2's second step and costs one more unit. Every other row goes straight to the
     * add screen, unless the household already has that playlist id.
     */
    suspend fun choose(candidate: SourceCandidate) = working.withLock {
        val current = mutableState.value
        if (current !is AddSourceState.Choosing && current !is AddSourceState.Typing) {
            return@withLock
        }

        // Pushed first, so every answer below has the list of rows to go back to.
        push(current)

        if (candidate.secondStep) {
            val channelId = candidate.channelId
            if (channelId == null) {
                // A channel row with no channel behind it cannot happen, because the row is
                // built from a channel. Refusing beats asking YouTube for nothing.
                return@withLock refuse(candidate.title, ADD_NOT_FOUND)
            }
            mutableState.value = AddSourceState.Looking
            val rows = try {
                resolver.channelStep(channelDetailsOf(candidate, channelId), ALL_UPLOADS)
            } catch (failure: YouTubeApiException) {
                log("The channel's playlists could not be read: ${failure.reason}")
                return@withLock refuse(candidate.title, addFailureLine(failure.reason))
            }
            return@withLock show(AddSourceState.Choosing(channelStepTitle(candidate.title), rows))
        }

        existing(candidate.playlistId)?.let { return@withLock show(it) }
        mutableState.value = AddSourceState.Confirming(candidate, ADD_DIRECTIONS.first())
    }

    /** The household moved the Direction choice on the add screen. Nothing is stored yet. */
    fun setDirection(direction: Direction) {
        val current = mutableState.value as? AddSourceState.Confirming ?: return
        mutableState.value = current.copy(direction = direction)
    }

    /**
     * The household pressed `Add`.
     *
     * Two writes, in this order and both local: the Source's own row, which is what puts the
     * card on the rail and what the Source screen reads its title and Direction from, and then
     * one `sourceAdded` entry in the Watch log.
     *
     * **Nothing is walked here.** Opening the Source is what walks it, which is
     * [SourceScreenModel]'s job, and the Activity opens the new Source the moment this
     * returns. Walking from here would spend quota on a screen the household might leave.
     *
     * @return what was stored, or null when there was nothing to store.
     */
    suspend fun add(): AddedSource? = working.withLock {
        val current = mutableState.value as? AddSourceState.Confirming ?: return@withLock null
        val candidate = current.candidate
        if (candidate.playlistId.isBlank()) return@withLock null

        // Checked once more, immediately before the write. The household could have added the
        // same Source from another route while this screen was open, and two rows over one
        // playlist id would split its history in two.
        existing(candidate.playlistId)?.let {
            show(it)
            return@withLock null
        }

        val title = candidate.title.ifBlank { candidate.playlistId }
        store.saveSource(
            StoredSource(
                sourceId = candidate.playlistId,
                title = title,
                // Null on purpose: the Source is added, and it is walked when it is opened.
                lastWalkedAt = null,
                direction = current.direction,
                addedAt = now(),
            ),
        )
        watches.sourceAdded(candidate.playlistId, title, current.direction)
        log(sourceAddedLine(candidate.playlistId, title, current.direction))
        AddedSource(candidate.playlistId, title)
    }

    /**
     * `BACK` inside the add screen.
     *
     * @return true when this screen took the press, false when there is nothing left to go
     *   back to and the Activity should close the screen. The Activity is what owns `BACK`,
     *   for the reason `MainActivity` gives: a Compose `BackHandler` added during composition
     *   is asked before the Activity's own callback and silently steals the key.
     */
    fun back(): Boolean {
        // **Refused while a lookup is in flight.** `back()` runs on the main thread and
        // `find` and `choose` run on `Dispatchers.IO`, so without this the household could
        // step back to the field, start retyping, and then be thrown forward into a result
        // for the text it had already abandoned. It would also race two writers over
        // [history]. A lookup is one call and well under a second. Found by `/code-review`.
        if (working.isLocked) return true
        val previous = history.removeLastOrNull() ?: return false
        mutableState.value = previous
        return true
    }

    // ------------------------------------------------------------------ private

    /** Remembers where `BACK` goes, but never remembers [AddSourceState.Looking]. */
    private fun push(state: AddSourceState) {
        if (state is AddSourceState.Looking) return
        history.addLast(state)
    }

    /**
     * Puts a state on screen after a lookup, and drops the [AddSourceState.Looking] that was
     * pushed in front of it, so one `BACK` goes back to what the household last chose.
     */
    private fun show(state: AddSourceState) {
        mutableState.value = state
    }

    /**
     * Back to the field, with the refusal under it and what was typed still in it.
     *
     * The text is kept on purpose. A household that mistyped one character of a link should
     * fix that character, not type the whole link again on a remote control.
     */
    private fun refuse(typed: String, message: String) {
        history.removeLastOrNull()
        mutableState.value = AddSourceState.Typing(typed = typed, message = message)
    }

    /** The rows of the first step, or the add screen directly when there is only one row. */
    private fun fromFound(found: Resolved.Found): AddSourceState? {
        val rows = firstStepRows(found)
        return when {
            rows.isEmpty() -> null
            // One answer and nothing to choose between. A list of one row would be a press
            // the household makes for nothing.
            rows.size == 1 && !rows.first().secondStep ->
                existing(rows.first().playlistId)
                    ?: AddSourceState.Confirming(rows.first(), ADD_DIRECTIONS.first())
            else -> AddSourceState.Choosing(ADD_PICK_ONE, rows)
        }
    }

    /**
     * The duplicate check, made before YouTube is asked at all.
     *
     * Only a playlist id can be answered for free, because a playlist id is the key every
     * Source is stored under. A handle or a channel id has to be resolved to its uploads
     * playlist first, and that is the check [choose] and [add] make.
     */
    private fun alreadyAdded(input: SourceInput): AddSourceState.AlreadyAdded? {
        val playlistId = (input as? SourceInput.PlaylistId)?.playlistId ?: return null
        return existing(playlistId)
    }

    /** The Source the household already has under this playlist id, or null. */
    private fun existing(playlistId: String): AddSourceState.AlreadyAdded? {
        val stored = store.source(playlistId) ?: return null
        log("Add a Source refused $playlistId: it is already on the home screen.")
        return AddSourceState.AlreadyAdded(
            sourceId = stored.sourceId,
            // The rail's own rule, written once in `ui/HomeScreenText.kt`. Ticket 29.
            name = sourceNameOrId(stored.typedName, stored.title, stored.sourceId),
        )
    }

    /** The channel a first-step row was built from, so its second step can be asked for. */
    private fun channelDetailsOf(
        candidate: SourceCandidate,
        channelId: String,
    ) = ChannelDetails(
        channelId = channelId,
        title = candidate.title,
        handle = candidate.handle,
        thumbnailUrl = candidate.thumbnailUrl,
        videoCount = candidate.videoCount,
        // Blank means the channel has never posted, and `channelStepRows` leaves the
        // `All uploads` row out rather than offering a playlist id that is not one.
        uploadsPlaylistId = candidate.playlistId.takeIf { it.isNotBlank() },
    )
}
