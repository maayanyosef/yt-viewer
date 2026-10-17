package local.youtubetv.data

import java.time.Instant
import java.util.UUID

/**
 * Where the household's Watches live between launches.
 *
 * This is the storage seam for ticket 04, in the same shape as [SourceStore]:
 * `SqliteWatchStore` runs on the television and [InMemoryWatchStore] backs the tests, so every
 * rule can be proven on the JVM with no device.
 *
 * It holds four things, and only one of them can be thrown away:
 *
 * - **The log.** Append-only, never deleted, and the source of truth. See [WatchEntry].
 * - **The derived table.** One [WatchState] per video, so reads stay cheap on a long Source.
 *   Disposable: delete it and [WatchLog.rebuild] puts it back from the log.
 * - **The kept videos.** A copy of a video's title and publish date, taken the first time the
 *   household marks it, so history stays readable after YouTube stops serving the video.
 *   Durable, because a cache cannot keep what the API no longer returns.
 * - **Two settings**: this device's id, and the [DERIVATION_VERSION] last used.
 *
 * The seam carries no rules. Everything that decides an answer is in [WatchLog] and in
 * `WatchDerivation.kt`, which is why the SQL needs no test of its own.
 */
interface WatchStore {

    /**
     * Appends one entry, stamping the id, this device's id and the next `seq`.
     *
     * The store owns those three because they are properties of the storage: an id must be
     * unique, and `seq` must only ever go up for this device.
     */
    fun append(kind: String, payload: String, at: Instant): WatchEntry

    /**
     * Rewrites one entry's payload, leaving its id, device, `seq`, `at` and kind alone.
     *
     * **This is the only edit the log allows**, and it exists for one case: the `played`
     * entry of a play that is still on screen, whose `position` and `completed` change while
     * the household watches and then freeze forever. Spec section 3.3 and ADR 0001. Nothing
     * else may call it, which is why [WatchLog] exposes it only through [WatchLog.updatePlay].
     *
     * Writing one entry per position tick instead was rejected: a two-hour video would leave
     * seven hundred entries for one play, and the log is never compacted in v1.
     *
     * An entry id that is not in the log changes nothing. That is not an error: the play may
     * have been on a television whose log was replaced underneath it.
     */
    fun replacePayload(entryId: String, payload: String)

    /** The whole log. In no particular order; [inLogOrder] puts it in one. */
    fun entries(): List<WatchEntry>

    /**
     * Every entry that names [videoId] directly.
     *
     * This is what lets a single mark update one derived row without reading the whole log,
     * and it is what the derived table is built from. The derived table holds the direct
     * entries only.
     *
     * Ticket 05 did **not** widen this. A `range` names no video, so "every entry that bears
     * on this video" would have to take the video's Source and publish date as well, and its
     * answer would go stale the next time a walk changed which videos exist. Ranges are
     * applied where that question is actually asked instead, in [WatchLog.watchStates], over
     * the videos on screen.
     */
    fun entriesNaming(videoId: String): List<WatchEntry>

    /**
     * True when the derived table is not there at all.
     *
     * Only meaningful before anything has read or written the derived table in this process,
     * which is how [WatchLog.openAndDerive] uses it. Every other method on this seam creates
     * the table if it has to, because a missing derived table is a rebuild and never an error.
     */
    fun derivedTableAbsent(): Boolean

    fun watchStates(): Map<String, WatchState>

    fun watchState(videoId: String): WatchState?

    fun putWatchState(state: WatchState)

    /** Replaces the whole derived table, creating it first when it is not there. */
    fun replaceWatchStates(states: Collection<WatchState>)

    /** The [DERIVATION_VERSION] the derived table was last built with, or null. */
    fun storedDerivationVersion(): Int?

    fun setDerivationVersion(version: Int)

    /** Writes the copy, unless this video already has one. The first copy is the one kept. */
    fun keepVideo(video: KeptVideo)

    fun keptVideo(videoId: String): KeptVideo?

    fun keptVideos(): List<KeptVideo>

    /**
     * Runs [body] so that everything it writes lands, or none of it does.
     *
     * Marking one video writes three rows: the log entry, the copy of the video's title, and
     * the derived row. The log entry is the only one that cannot be worked out again, so a
     * failure between them must not leave an entry the other two never heard of.
     *
     * Nesting is allowed, because [append] takes one out of habit and a caller may already
     * hold one.
     */
    fun <T> inTransaction(body: () -> T): T
}

/**
 * A video's title and publish date, copied when the household first marked it.
 *
 * Videos are cache and are rebuilt by re-walking, so a video YouTube stops serving is gone
 * from the cache on the next walk. This copy is the one exception ADR 0001 makes, so the
 * household's history does not become a list of bare video ids.
 */
data class KeptVideo(
    val videoId: String,
    val title: String,
    val videoPublishedAt: Instant?,
)

/**
 * A [WatchStore] that keeps everything in memory.
 *
 * Used by unit tests. It answers exactly as the SQLite store does, including refusing to
 * overwrite a kept video, so a test that passes here is testing the rule and not the SQL.
 */
class InMemoryWatchStore(
    private val deviceId: String = UUID.randomUUID().toString(),
) : WatchStore {

    private val log = mutableListOf<WatchEntry>()
    private var derived: MutableMap<String, WatchState>? = mutableMapOf()
    private val kept = mutableMapOf<String, KeptVideo>()
    private var derivationVersion: Int? = null
    private var nextSeq = 1L

    override fun append(kind: String, payload: String, at: Instant): WatchEntry {
        val entry = WatchEntry(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId,
            seq = nextSeq++,
            at = at,
            kind = kind,
            payload = payload,
        )
        log += entry
        return entry
    }

    override fun replacePayload(entryId: String, payload: String) {
        val at = log.indexOfFirst { it.id == entryId }
        if (at < 0) return
        log[at] = log[at].copy(payload = payload)
    }

    override fun entries(): List<WatchEntry> = log.toList()

    override fun entriesNaming(videoId: String): List<WatchEntry> =
        log.filter { it.namedVideoId() == videoId }

    override fun derivedTableAbsent(): Boolean = derived == null

    private fun table(): MutableMap<String, WatchState> =
        derived ?: mutableMapOf<String, WatchState>().also { derived = it }

    override fun watchStates(): Map<String, WatchState> = table().toMap()

    override fun watchState(videoId: String): WatchState? = table()[videoId]

    override fun putWatchState(state: WatchState) {
        table()[state.videoId] = state
    }

    override fun replaceWatchStates(states: Collection<WatchState>) {
        val table = table()
        table.clear()
        states.forEach { table[it.videoId] = it }
    }

    override fun storedDerivationVersion(): Int? = derivationVersion

    override fun setDerivationVersion(version: Int) {
        derivationVersion = version
    }

    override fun keepVideo(video: KeptVideo) {
        kept.putIfAbsent(video.videoId, video)
    }

    override fun keptVideo(videoId: String): KeptVideo? = kept[videoId]

    override fun keptVideos(): List<KeptVideo> = kept.values.toList()

    /** Nothing here can fail halfway, so there is nothing to roll back. */
    override fun <T> inTransaction(body: () -> T): T = body()

    /** Throws the derived table away, which is what deleting it on the television does. */
    fun dropDerivedTable() {
        derived = null
    }
}
