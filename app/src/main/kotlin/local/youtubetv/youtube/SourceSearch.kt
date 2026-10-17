package local.youtubetv.youtube

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Finding a channel or a playlist by name, which is the only thing in this app that spends a
 * `search.list` call. Spec sections 4.1, 6.1 and 6.2.
 *
 * **Why this is a class of its own and not three lines in [SourceResolver].** `search.list`
 * costs 1 call from a bucket of **100 per day** that does not refill until midnight Pacific
 * Time. Every other endpoint in the app draws on a separate pool of 10,000 units at 1 unit a
 * page. Spending that bucket carelessly takes the "find it by name" path away from the
 * household for the rest of the day, and there is no way to buy it back. So the rules that
 * protect it live in one place, where they can be read and unit-tested:
 *
 * - **One call per query per session.** The answer is cached under a normalised query, so
 *   asking again, going back and forward, or a re-render costs nothing.
 * - **An empty answer is cached too.** A query that found nothing is the query a household is
 *   most likely to submit twice.
 * - **Submit only.** Nothing here is reachable from a keystroke; [AddSourceModel.find] is the
 *   one caller and it runs on the `Find it` button and the keyboard's own action key.
 * - **One at a time.** The whole of [find] is under a mutex, so two presses of the centre
 *   button on a slow call cannot spend two calls on one query.
 * - **Exhaustion is latched.** Once YouTube has answered `quotaExceeded` for this endpoint,
 *   every later search in the session is refused without a request. Spec section 5.4 takes
 *   the same line for refresh: no retry loop.
 *
 * **The session is this object's lifetime**, which is the Activity's, but it never outlives
 * the day. The bucket refills at midnight Pacific Time, so the first search after that
 * boundary starts again: the latch is let go, the cache is dropped because its rows would be
 * a day old, and the count of calls spent goes back to zero because the line it writes reads
 * "of 100 today". Without that, an app left running overnight would keep telling the
 * household the day's hundred were gone hours after they came back. Found by `/code-review`.
 *
 * @param onCall told the name of each endpoint as it is called, the same way [SourceResolver]
 *   reports its own, so what a session spent can be read out of logcat rather than guessed.
 * @param log one line per search, saying whether a call was spent and how many the session has
 *   spent in total. Criterion 9 of ticket 12 asks for exactly this.
 * @param maxResults 25, from spec section 6.1.
 * @param now the clock, so the midnight-Pacific reset is a unit test rather than a wait.
 */
class SourceSearch(
    private val api: YouTubeApi,
    private val onCall: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val maxResults: Int = SEARCH_MAX_RESULTS,
    private val now: () -> Instant = Instant::now,
) {

    init {
        // The two enrichment calls take at most 50 ids each, so a page bigger than that could
        // not be priced in one unit apiece. Spec section 6.2's "2 extra units for up to 50
        // results" is the same limit read from the other end.
        require(maxResults in 1..YOUTUBE_PAGE_SIZE) {
            "search.list asks for at most $YOUTUBE_PAGE_SIZE results, was $maxResults"
        }
    }

    /**
     * What `search.list` answered for each query this session has asked, under its normalised
     * form.
     *
     * **This is the cache that protects the day's hundred**, and everything that succeeds
     * goes in it, including a query that matched nothing. Once a query is here, it can never
     * cost a second call for the rest of the Pacific day.
     */
    private val hitCache = LinkedHashMap<String, List<SearchHit>>()

    /**
     * The finished rows, under the same key. **Only a fully priced answer goes in here.**
     *
     * The two caches are separate because the two calls behind them are worth wildly
     * different amounts. A search whose `channels.list` or `playlists.list` call failed gives
     * rows with no count and, for a channel, no playlist id to add it under. Remembering
     * those would leave the household no way back but a different query, and a different
     * query costs one of the hundred. Remembering nothing at all would be worse still: the
     * next press would re-run the search itself, spending the expensive call to save a
     * 1-unit one. So the hits are kept and only the pricing is tried again. Found by
     * `/code-review`, and its first fix was the wrong one of the two.
     */
    private val rowCache = LinkedHashMap<String, List<SourceCandidate>>()

    /** The Pacific day both caches and the latch belong to. All are dropped when it changes. */
    private var pacificDay: LocalDate = pacificDayOf(now())

    private val working = Mutex()

    /**
     * How many `search.list` calls this session has spent, of the hundred available.
     *
     * A request that never reached YouTube does not count, because YouTube never saw it. A
     * request YouTube refused does count, because an invalid request costs a call too. See
     * spec section 4.1.
     */
    var callsSpent: Int = 0
        private set

    /** True once YouTube has said the day's hundred are gone. Nothing searches after that. */
    @Volatile
    var quotaExhausted: Boolean = false
        private set

    /**
     * A new API key was saved. Its hundred `search.list` calls are its own, so the latch and
     * the count are let go. The caches stay: a search already answered is still the answer.
     * Build ticket 64.
     */
    fun forgetQuotaRefusal() {
        quotaExhausted = false
        callsSpent = 0
    }

    /**
     * What the household typed, searched for.
     *
     * The rows come back in YouTube's own relevance order, which is what the household means
     * by "the one I typed the name of". Each row is priced by two further calls of 1 unit
     * each, because `search.list` reports no video count, no `@handle` and no uploads playlist
     * id, and a channel row cannot be picked at all without the last of those.
     *
     * **A pricing failure never throws the search away.** The expensive call has already been
     * spent by then, and rows with no count still let the household pick the channel it meant.
     */
    suspend fun find(query: String): SearchOutcome = working.withLock {
        val text = query.trim()
        if (text.isEmpty()) return@withLock SearchOutcome.Results(emptyList(), spentACall = false)

        rollOverAtPacificMidnight()
        val key = normalise(text)
        rowCache[key]?.let {
            log(searchCacheLine(text, callsSpent))
            return@withLock SearchOutcome.Results(it, spentACall = false)
        }
        // Asked before, and answered, but the pricing failed that time. The expensive call is
        // already paid for, so only the two 1-unit calls are tried again.
        hitCache[key]?.let {
            log(searchRepricedLine(text, callsSpent))
            return@withLock SearchOutcome.Results(priceAndRemember(key, it), spentACall = false)
        }

        if (quotaExhausted) {
            log(searchExhaustedLine(text))
            return@withLock SearchOutcome.QuotaExhausted
        }

        val hits = try {
            val found = api.search(text, maxResults)
            callsSpent++
            onCall(SEARCH_LIST)
            log(searchCallLine(text, callsSpent))
            found
        } catch (failure: YouTubeApiException) {
            // A request that never left the television spent nothing. Anything YouTube
            // answered, refusal included, came out of the day's hundred.
            if (failure.reason != YouTubeApiException.Reason.NETWORK) {
                callsSpent++
                onCall(SEARCH_LIST)
            }
            if (failure.reason == YouTubeApiException.Reason.QUOTA_EXCEEDED) {
                quotaExhausted = true
                log(searchExhaustedLine(text))
                return@withLock SearchOutcome.QuotaExhausted
            }
            log("Source search failed: ${failure.reason}. $callsSpent search.list calls this session.")
            return@withLock SearchOutcome.Failed(failure.reason)
        }

        val unique = hits.distinctBy { it.kind to it.id }
        // The expensive call is remembered whatever the pricing then does, so this query can
        // never cost a second one today.
        hitCache[key] = unique
        SearchOutcome.Results(priceAndRemember(key, unique), spentACall = true)
    }

    /** Prices a set of hits, and remembers the rows only when both pricing calls answered. */
    private suspend fun priceAndRemember(key: String, hits: List<SearchHit>): List<SourceCandidate> {
        val priced = price(hits)
        if (priced.complete) rowCache[key] = priced.rows
        return priced.rows
    }

    /**
     * Lets go of everything that belonged to yesterday.
     *
     * The bucket of a hundred refills at midnight Pacific Time and nothing tells the app when.
     * So the day is read off the clock on every search: when it has moved on, the latch goes,
     * the cached rows go because they would be a day old, and the count goes back to zero
     * because the log line it feeds says "of 100 **today**".
     */
    private fun rollOverAtPacificMidnight() {
        val today = pacificDayOf(now())
        if (today == pacificDay) return
        pacificDay = today
        hitCache.clear()
        rowCache.clear()
        quotaExhausted = false
        callsSpent = 0
    }

    /**
     * The two calls of spec section 6.2 that turn hits into rows the household can pick.
     *
     * One `channels.list?id=` over every channel hit and one `playlists.list?id=` over every
     * playlist hit: 1 unit each, from the 10,000-unit pool and not from the hundred. Either
     * can be skipped when the search found nothing of that kind.
     */
    private suspend fun price(hits: List<SearchHit>): Priced {
        val channelIds = hits.filter { it.kind == CandidateKind.CHANNEL }.map { it.id }
        val playlistIds = hits.filter { it.kind == CandidateKind.PLAYLIST }.map { it.id }
        var complete = true

        val channels = if (channelIds.isEmpty()) {
            emptyMap()
        } else {
            onCall(SourceResolver.CHANNELS_LIST)
            priced("channels.list") { api.channelsByIds(channelIds) }
                .also { if (it == null) complete = false }
                .orEmpty()
                .associateBy { it.channelId }
        }
        val playlists = if (playlistIds.isEmpty()) {
            emptyMap()
        } else {
            onCall(SourceResolver.PLAYLISTS_LIST)
            priced("playlists.list") { api.playlistsByIds(playlistIds) }
                .also { if (it == null) complete = false }
                .orEmpty()
                .associateBy { it.playlistId }
        }
        return Priced(searchRows(hits, channels, playlists), complete)
    }

    /** What the two pricing calls came to, and whether both of them actually answered. */
    private class Priced(val rows: List<SourceCandidate>, val complete: Boolean)

    /**
     * One enrichment call, whose failure is reported rather than thrown.
     *
     * The `search.list` call is already spent by the time this runs. Throwing the results away
     * because a 1-unit call failed would cost the household one of its hundred for nothing,
     * and a row with no count is still a row it can pick. So the rows are shown, and null says
     * "do not remember this", which lets the next press re-price them for 1 unit.
     */
    private suspend inline fun <T> priced(endpoint: String, call: () -> List<T>): List<T>? = try {
        call()
    } catch (failure: YouTubeApiException) {
        log(
            "Source search could not price its results with $endpoint: ${failure.reason}. " +
                "The results are shown without counts and are not cached, so trying again " +
                "costs 1 unit and no search call.",
        )
        null
    }

    /**
     * The form of a query two spellings of the same thing share.
     *
     * Case and the spacing between words are dropped, because YouTube's own search ignores
     * both and a household saying a name into the microphone twice will not capitalise it the
     * same way. Anything else is left alone: `ted` and `ted talks` are different questions.
     */
    private fun normalise(text: String): String =
        text.lowercase().replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}

/**
 * The zone every YouTube daily quota resets in. Spec section 4.1.
 *
 * Not the household's own zone, and not UTC. Google resets at midnight Pacific Time wherever
 * the request came from, so the day this app counts against has to be the Pacific one.
 */
private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")

/** Which Pacific day an instant falls in, which is the day a quota belongs to. */
private fun pacificDayOf(at: Instant): LocalDate = at.atZone(PACIFIC).toLocalDate()

/** The name the cost lines use for the one endpoint with its own bucket. */
const val SEARCH_LIST = "search.list"

/** The bucket `search.list` draws on, per day, resetting at midnight Pacific Time. */
const val SEARCH_CALLS_PER_DAY = 100

/** What a search came to. */
sealed interface SearchOutcome {

    /**
     * YouTube answered, or this session's cache did.
     *
     * @property rows what the household picks from, empty when nothing matched.
     * @property spentACall true only when a `search.list` call actually left the television.
     */
    data class Results(val rows: List<SourceCandidate>, val spentACall: Boolean) : SearchOutcome

    /** The day's hundred are gone. They come back at midnight Pacific Time and not before. */
    data object QuotaExhausted : SearchOutcome

    /** Anything else YouTube or the network did. The screen turns it into one sentence. */
    data class Failed(val reason: YouTubeApiException.Reason) : SearchOutcome
}

/**
 * Search hits, priced, as rows the household picks from. Spec section 6.2.
 *
 * A pure function so that the shape of every row is a unit test: the thumbnail, the title, the
 * `CHANNEL` or `PLAYLIST` badge, the `@handle` where it applies, and the video count.
 *
 * - **A channel row carries its uploads playlist id**, because that is what a Source is stored
 *   under, and it opens the second step of spec section 6.2 rather than the add screen.
 * - **The priced title wins over the search title.** They are the same name, but `search.list`
 *   HTML-escapes its copy and the other two endpoints do not.
 * - **A hit YouTube then failed to price keeps its row.** The `search.list` call is already
 *   spent, and a row with no count still names the thing the household was looking for.
 *
 * @param channels what `channels.list` said, by channel id. Empty when that call failed.
 * @param playlists what `playlists.list` said, by playlist id. Empty when that call failed.
 */
fun searchRows(
    hits: List<SearchHit>,
    channels: Map<String, ChannelDetails>,
    playlists: Map<String, PlaylistDetails>,
): List<SourceCandidate> = hits.map { hit ->
    when (hit.kind) {
        CandidateKind.CHANNEL -> {
            val channel = channels[hit.id]
            SourceCandidate(
                kind = CandidateKind.CHANNEL,
                // Blank when the channel could not be priced, or when it has never posted.
                // The second step then offers its playlists without an `All uploads` row,
                // which is the same answer `channelStepRows` gives for a channel with no
                // uploads playlist of its own.
                playlistId = channel?.uploadsPlaylistId.orEmpty(),
                channelId = hit.id,
                title = channel?.title?.takeIf { it.isNotBlank() } ?: hit.title,
                handle = channel?.handle,
                thumbnailUrl = channel?.thumbnailUrl ?: hit.thumbnailUrl,
                videoCount = channel?.videoCount,
                secondStep = true,
            )
        }
        CandidateKind.PLAYLIST -> {
            val playlist = playlists[hit.id]
            SourceCandidate(
                kind = CandidateKind.PLAYLIST,
                playlistId = hit.id,
                channelId = playlist?.channelId ?: hit.channelId,
                title = playlist?.title?.takeIf { it.isNotBlank() } ?: hit.title,
                // A playlist has no handle. The channel that owns it is what the row names
                // underneath, exactly as on the pasted-link path.
                handle = playlist?.channelTitle ?: hit.channelTitle,
                thumbnailUrl = playlist?.thumbnailUrl ?: hit.thumbnailUrl,
                videoCount = playlist?.itemCount,
                secondStep = false,
            )
        }
    }
}

/** One logcat line per `search.list` call spent, counted against the day's hundred. */
fun searchCallLine(query: String, callsSpent: Int): String =
    "Source search called search.list for \"$query\". " +
        "That is $callsSpent of $SEARCH_CALLS_PER_DAY today, from this session alone."

/** One logcat line when this session's cache answered, so a zero-cost search is visible too. */
fun searchCacheLine(query: String, callsSpent: Int): String =
    "Source search answered \"$query\" from this session's cache. No search.list call. " +
        "Still $callsSpent of $SEARCH_CALLS_PER_DAY today."

/**
 * One logcat line when a query is answered from the hits of an earlier call, but priced again.
 *
 * It is the line that says the expensive call was **not** spent a second time on a query
 * whose first pricing failed.
 */
fun searchRepricedLine(query: String, callsSpent: Int): String =
    "Source search reused the results it already had for \"$query\" and priced them again, " +
        "which costs 2 units and no search.list call. Still $callsSpent of " +
        "$SEARCH_CALLS_PER_DAY today."

/** One logcat line when the day's hundred are gone and nothing more will be asked. */
fun searchExhaustedLine(query: String): String =
    "Source search refused \"$query\": YouTube says the $SEARCH_CALLS_PER_DAY search calls " +
        "for today are used up. Nothing more will be asked until midnight Pacific Time. " +
        "Pasting a link and typing an @handle are unaffected."
