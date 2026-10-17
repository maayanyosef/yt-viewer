package local.youtubetv.ui

import local.youtubetv.data.Direction
import local.youtubetv.youtube.CandidateKind
import local.youtubetv.youtube.SEARCH_CALLS_PER_DAY
import local.youtubetv.youtube.SourceCandidate
import local.youtubetv.youtube.YouTubeApiException

/**
 * Every string the add screen and the first-run page put on the television.
 *
 * It is kept apart from the Composables for the same reason `ui/SourceScreenText.kt` and
 * `ui/HomeScreenText.kt` are: every wording rule is then a JVM unit test with no device and no
 * quota. See spec sections 6.1 to 6.6.
 *
 * **Shorts are named nowhere in this file.** Spec section 4.3 hides them always, counts them
 * nowhere and offers no toggle, so there is nothing about them to put on the add screen.
 *
 * **Two things in this file are not put on the television at all**, and they are here because
 * they are the add screen's other pure rules and belong with them: [ADD_FIELD], which is what
 * the text field is called to the screen's own highlight record, and [candidateKey], which is
 * what one row of the results is called both to the `LazyColumn` and to that same record. See
 * `ui/HighlightRecord.kt` for what a record is and why only a node may write one.
 */

// ------------------------------------------------------------------ the first-run page

/** The heading of the page a household with no Source at all sees. */
const val FIRST_RUN_TITLE = "Add your first Source"

/**
 * What a Source is, in the household's own words, above the three paths.
 *
 * It says "playlist or channel" because that is what the household will go and find on its
 * phone. `CONTEXT.md` keeps the word Source for the thing once it is added.
 */
const val FIRST_RUN_LEAD =
    "A Source is one YouTube channel or one playlist. This app browses it in order and " +
        "remembers what you watched."

/**
 * The three ways in, each named in full.
 *
 * Spec section 6.1 asks for all three by name on this page, because **none of them is
 * discoverable on its own**. A household that has only ever been shown a text field does not
 * learn that pasting a link works, and one that has only pasted a link does not learn that it
 * can say a name into the microphone.
 *
 * The search path is named here although ticket 12 is what builds it. The page is about what
 * the household can do, and leaving a path off it until the code lands would teach the wrong
 * thing and have to be untaught.
 */
val FIRST_RUN_PATHS: List<String> = listOf(
    "Paste a YouTube link. A channel link, a playlist link or a video link all work.",
    "Type a channel's @handle, such as @TED.",
    "Type or say the name of a channel, and pick it from the results.",
)

/** The button on the first-run page, and the card pinned at the end of the rail. */
const val ADD_A_SOURCE = "+ Add a Source"

/** What the `+ Add a Source` card says under its label, so it reads as a card and not a gap. */
const val ADD_A_SOURCE_META = "A channel, a playlist or a link"

// ------------------------------------------------------------------ typing

/** The heading of the add screen while the household is still typing. */
const val ADD_TITLE = "Add a Source"

/**
 * What sits in the empty field, so the household can see what belongs there.
 *
 * The name comes first because it is the path a household reaches for without being taught,
 * and it is the one the microphone button on the television keyboard is for.
 */
const val ADD_FIELD_HINT = "Type a name, paste a link, or type an @handle"

/**
 * What the add screen's text field is called to that screen's own highlight record.
 *
 * **It is never drawn.** A `HighlightRecord` names each of its nodes by something stable, the
 * way `PERIOD_CHIP` names the Source screen's `Period` chip and a video id names a card in the
 * grid, and the text field has no words of its own to be named by: [ADD_FIELD_HINT] is gone
 * the moment the household types a character. So the field is given a name here instead.
 *
 * Build ticket 48. The ladder that asks the field for the highlight exits on this name being
 * the one the record holds, and never on a `requestFocus` that did not throw.
 */
const val ADD_FIELD = "the add screen's text field"

/** The button under the field. It says what it does, which is ask YouTube. */
const val ADD_FIND = "Find it"

/** What the screen says while it is waiting for YouTube. */
const val ADD_LOOKING = "Asking YouTube…"

// ------------------------------------------------------------------ results

/** The badge on a row, so a channel and a playlist of the same name are told apart. */
fun candidateBadge(candidate: SourceCandidate): String = when (candidate.kind) {
    CandidateKind.CHANNEL -> "CHANNEL"
    CandidateKind.PLAYLIST -> "PLAYLIST"
}

/** The heading over the rows of the first step, when there is more than one thing to pick. */
const val ADD_PICK_ONE = "Which one did you mean?"

/** The heading over the second step, which is one channel's playlists. */
fun channelStepTitle(channelName: String): String = "What of $channelName?"

/**
 * The first row of the second step: the channel's whole uploads playlist.
 *
 * It is the row the household wants nearly every time, so it is first and it is named plainly.
 * Spec section 6.2 fixes both.
 */
const val ALL_UPLOADS = "All uploads"

/**
 * The count under a row, or null when YouTube gave no number.
 *
 * No thousands separator, the same as every other count in this app, so a large channel reads
 * `5813 videos`. Settled by the owner on 2026-09-19 for the Source screen header and kept here.
 *
 * **It is YouTube's number and not this app's.** It counts Shorts, and private and deleted
 * entries, none of which the walked Source will list. Saying nothing at all would be worse:
 * the household is choosing between two playlists and the size is how it tells them apart.
 */
fun candidateCount(videoCount: Int?): String? = when {
    videoCount == null -> null
    videoCount == 1 -> "1 video"
    else -> "$videoCount videos"
}

/**
 * The meta line of a row and of the add screen, as parts that are never joined.
 *
 * **Joining them was the defect ticket 24 fixes.** One `Text` holding
 * `PLAYLIST   <Hebrew name>   57 videos` runs one bidirectional layout over the whole string.
 * The Hebrew run then pulls the neutral spaces and the digits around it, and the household
 * reads `PLAYLIST 57 <Hebrew name> videos`: the count torn in half. The owner saw it on the
 * household television on 2026-09-20, in a screenshot of the Galgalatz channel,
 * and chose separate runs over dropping the channel name.
 *
 * A part drawn by its own `Text` gets its own bidirectional run, and one run cannot reorder
 * another. So the rule this type carries is simply that the parts stay apart, all the way to
 * the screen. `AddSourceScreen.kt` draws one `Text` per part and never joins them.
 *
 * A bidi control character around the count would work too. It is not used, because it hides
 * the reason inside an invisible character the next reader cannot see.
 */
data class CandidateMeta(
    val badge: String,
    val handle: String?,
    val count: String?,
) {
    /** The parts in reading order, with whatever YouTube did not give left out. */
    val parts: List<String> get() = listOfNotNull(badge, handle, count)

    /**
     * Which part of [parts] gives way when the line is too wide, or null when none may.
     *
     * **Only the channel name may shrink.** The badge is two words the household reads to tell
     * a channel from a playlist, and the count is the whole point of the line, so a long name
     * must end in an ellipsis rather than push the count off the row. The badge is always
     * first, so the name is always second when there is one.
     */
    val shrinkingPart: Int? get() = if (handle == null) null else 1
}

/**
 * What one row of the results is called, both to the `LazyColumn` and to the highlight record.
 *
 * **It carries the channel id as well as the playlist id**, because a page of search results
 * can hold two channel rows whose uploads playlist YouTube declined to price, and both would
 * then be named by a blank playlist id. A `LazyColumn` throws on a repeated key, so that would
 * be a crash on a screen the household reached by typing a name. The kind is on the end so
 * that a channel row and its own uploads playlist, which share both ids, are still two names.
 *
 * **Both users have to be the same expression, which is why this is a function and not two
 * copies.** The `LazyColumn` uses it to decide which node is which across a recomposition, and
 * the screen's highlight record uses it to say which row reports holding the highlight. A
 * record naming a row's **place** in the list would be the defect `/code-review` found on
 * build ticket 47's grid: a place moves under the record while Compose reports nothing. Build
 * ticket 48.
 *
 * The NUL between the two ids keeps them apart, so no pair of ids can be split two ways and
 * give one name.
 */
fun candidateKey(candidate: SourceCandidate): String =
    candidate.channelId + "\u0000" + candidate.playlistId + candidate.kind

/** The parts of one candidate's meta line. See [CandidateMeta] for why they stay apart. */
fun candidateMeta(candidate: SourceCandidate): CandidateMeta = CandidateMeta(
    badge = candidateBadge(candidate),
    handle = candidate.handle,
    count = candidateCount(candidate.videoCount),
)

// ------------------------------------------------------------------ the add screen

/** The heading of the last step, where the household chooses a Direction and presses `Add`. */
const val ADD_CONFIRM_TITLE = "Add this Source?"

/** The label over the two Direction choices. `CONTEXT.md` fixes the word Direction. */
const val ADD_DIRECTION_LABEL = "Browse it"

/**
 * The two Direction choices, newest-first first.
 *
 * Newest-first is preselected, which spec section 6.3 asks for, and it is first on the row so
 * that the preselected choice is also where the highlight starts.
 */
val ADD_DIRECTIONS: List<Direction> = listOf(Direction.NEWEST_FIRST, Direction.OLDEST_FIRST)

/** The button that adds the Source. */
const val ADD_CONFIRM = "Add"

/**
 * One line saying what will happen next, under the button.
 *
 * It sets the expectation that the Source opens straight away and fills while the household
 * watches, which is what spec section 6.5 builds.
 */
const val ADD_CONFIRM_DETAIL = "It opens at once and fills as YouTube answers."

// ------------------------------------------------------------------ refusals

/** The heading when the household already has this Source. Spec section 6.4. */
const val ALREADY_ADDED_TITLE = "You already have this Source"

/**
 * Why a duplicate is refused, in terms of what the household would lose.
 *
 * It says what the app is protecting rather than what the app refuses to do. Two Sources over
 * one playlist id would split the household's history in two, and the counts on both cards
 * would then be wrong for ever with nothing able to put them back together.
 */
fun alreadyAddedDetail(existingName: String): String =
    "$existingName is on your home screen already. Adding it twice would split what you have " +
        "watched between two cards."

/** The line that takes the household to the Source it already has. */
fun openExisting(existingName: String): String = "Open $existingName"

/** The line that goes back to the field, so the household can try something else. */
const val ADD_TRY_SOMETHING_ELSE = "Type something else"

/** What the screen says when YouTube knows of no such channel or playlist. */
const val ADD_NOT_FOUND =
    "YouTube has no channel or playlist with that name. Check the link or the @handle."

/**
 * What the screen says when a search found nothing at all.
 *
 * **It repeats the query back.** The household may have said the name into the microphone, and
 * seeing what the television heard is the difference between rephrasing and giving up. It then
 * names the path that costs nothing, because another search costs one of the day's hundred.
 */
fun searchFoundNothing(query: String): String =
    "YouTube found no channel or playlist for \"$query\". Try fewer words, or paste the " +
        "channel's link instead."

/**
 * What the screen says when the day's hundred `search.list` calls are gone.
 *
 * Three things, in this order, because that is the order the household needs them:
 *
 * - What ran out, plainly, so it does not read as the app being broken.
 * - When it comes back, which spec section 4.1 fixes at midnight Pacific Time.
 * - That the other two add paths still work, which they do: they draw on a separate pool of
 *   10,000 units a day and a search has never touched it.
 */
const val SEARCH_QUOTA_EXHAUSTED =
    "YouTube allows $SEARCH_CALLS_PER_DAY searches a day and today's are used up. They come " +
        "back at midnight Pacific Time. You can still add a Source by pasting its link or " +
        "typing its @handle."

/** The heading over a page of search results, repeating what was searched for. */
fun searchResultsTitle(query: String): String = "Results for \"$query\""

/** What the screen says when the text is not a shape this app can read at all. */
const val ADD_UNREADABLE = "That is not a YouTube link, an @handle or an id."

/**
 * One sentence the household can act on when YouTube refused the call.
 *
 * The same five answers `SourceScreenModel` gives a failed walk, because they are the same
 * five failures and a household should not meet two different sentences for one thing.
 */
fun addFailureLine(failure: YouTubeApiException.Reason): String = when (failure) {
    YouTubeApiException.Reason.NETWORK ->
        "No connection, so nothing can be added right now."
    YouTubeApiException.Reason.QUOTA_EXCEEDED ->
        "Your API key's 10,000 units for today are used up. They reset at midnight Pacific Time."
    YouTubeApiException.Reason.FORBIDDEN ->
        "YouTube refused your API key. Replace it with API key: replace or remove, on the " +
            "home screen's first page or in a Source's menu."
    YouTubeApiException.Reason.PLAYLIST_NOT_FOUND ->
        "That playlist no longer exists."
    YouTubeApiException.Reason.BAD_RESPONSE,
    YouTubeApiException.Reason.HTTP,
    -> "YouTube replied with something this app could not use."
}

// ------------------------------------------------------------------ the log

/**
 * One logcat line per API call the add flow caused, named by endpoint.
 *
 * Quota is the tightest constraint on this whole project and `search.list` is the tightest
 * part of it, so what a session spent is written down as it is spent rather than counted
 * afterwards from memory. See spec section 4.1.
 */
fun addApiCallLine(endpoint: String, typed: String): String =
    "Add a Source called $endpoint (1 unit) for \"$typed\"."

/** One logcat line saying what was added, which is a rule and not a picture. */
fun sourceAddedLine(sourceId: String, title: String, direction: Direction): String =
    "Added Source $sourceId \"$title\", browsed ${direction.stored}. " +
        "Its Watch log entries, if it has any, were never touched."

/** One logcat line saying what was removed, and what was deliberately kept. */
fun sourceRemovedLine(sourceId: String, title: String): String =
    "Removed Source $sourceId \"$title\": its row and its cached videos are gone. " +
        "Every Watch and every Bulk mark range it earned is kept."
