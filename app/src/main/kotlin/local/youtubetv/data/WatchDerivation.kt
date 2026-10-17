package local.youtubetv.data

import java.time.Instant

/**
 * Turning the append-only Watch log into one row per video.
 *
 * These are plain functions over a list of [WatchEntry], with no storage and no Android in
 * them, so every rule is a JVM unit test. The derived table in SQLite is nothing more than
 * what [deriveWatchStates] returns, written down. It is disposable: throw it away and run this
 * again and the answer is the same, which is what makes a bug in derivation a rebuild rather
 * than lost history. See ADR 0001.
 */

/**
 * Bumped whenever a rule in this file changes.
 *
 * The version last used is stored beside the derived table. A mismatch on launch rebuilds the
 * table from the log. Bump it in the same commit as the rule change, never separately, or a
 * television will keep serving answers from the old rule until somebody notices.
 *
 * Version 3 does not change a single stored row: Last played is worked out from the log at
 * read time and is not in the derived table at all. It is bumped anyway, so that the number
 * keeps meaning "the rules in this file are these ones". The cost is one rebuild on one
 * television, over a log of a few hundred entries.
 *
 * | Version | What changed |
 * | --- | --- |
 * | 1 | Ticket 04: `played` and `unwatched`. Every other kind was skipped. |
 * | 2 | Ticket 05: a `range` entry now decides, for every video it covers. |
 * | 3 | Ticket 06: Last played is derived here, by [lastPlayedVideoId]. |
 * | 4 | Ticket 42: a `newer` range stops at the later of the moment it was made and its own pivot. |
 * | 5 | Build ticket 69: a Shuffle play is a Watch but never a High-water mark. See [WatchState.countsForHighWaterMark]. |
 *
 * Version 4 changes no stored row either, and it writes nothing to the log. A `newer` range
 * is simply read with a second end on it. See [Range.coversDate].
 *
 * **Version 4 was corrected on 2026-09-21 and was not bumped to 5 for it.** As ticket 42 shipped
 * it, the far end was the entry's own `at` alone, and a television whose clock was behind the
 * pivot video's publish date wrote a range covering nothing at all. Commit `14ce6e9` made the
 * far end `maxOf(madeAt, pivotPublishedAt)`. The row above describes the corrected rule, because
 * that is the only rule any build in the household's hands will ever have.
 *
 * The reason no bump was needed is **not** that the household television never got version 4,
 * though it had not: its `app_meta` read `derivationVersion = 3` and its database read
 * `user_version = 9` on 2026-09-21. Version 3 was bumped although it changed no stored row
 * either, so "no row changed" has never been the test here.
 *
 * The reason is that [Range.coversDate] can never have written a stored row on any device at
 * all. A range names no video, so it is applied only to videos handed in, and the write path
 * hands none in: [WatchLog] stores what `deriveWatchStates(entries)` returns with [videos]
 * left empty, and `LogReading.watchStates` gives back the direct states untouched when that
 * list is empty. Range coverage is worked out at read time, every time. So a bump would force
 * a rebuild that produced a byte-identical table, and no stale answer is possible whatever a
 * device holds.
 */
const val DERIVATION_VERSION = 5

/**
 * What the app knows about one video, derived from the log.
 *
 * @property watched what the card's green tick and dark wash are drawn from.
 * @property positionSeconds how far the newest play got, or null when there is no progress to
 *   resume. Ticket 07 draws the red In progress bar from this.
 * @property lastPlayedAt when the newest `played` entry for this video was written, or null
 *   when there has never been one. It survives a later unwatch on purpose: the play still
 *   happened, and ADR 0001 draws the card's watch date from it.
 *
 *   It is **not** what Last played is derived from. That is [lastPlayedVideoId], which reads
 *   the log itself, because this field holds a raw wall-clock time and a television can boot
 *   with a wrong clock.
 */
data class WatchState(
    val videoId: String,
    val watched: Boolean,
    val positionSeconds: Int?,
    val completed: Boolean,
    val lastPlayedAt: Instant?,
    /**
     * Whether this video can be the **High-water mark**. Build ticket 69, ADR 0002.
     *
     * It is the watched state the log would give **if every Shuffle play were left out of it**,
     * by the same newest-wins rule, and it is never true for a video that is not [watched]. So:
     *
     * - A video watched only by Shuffle plays is a Watch, which closes its Next unwatched gap,
     *   but it is not a mark. Next unwatched keeps the household's place.
     * - A video the household also played by hand, or that a Bulk mark covers, is a mark,
     *   whatever Shuffle did to it later.
     * - An ordinary play undone by a later `unwatched` stays undone, even when Shuffle plays
     *   the video again afterwards.
     *
     * It defaults to [watched], which is the whole rule for a log with no Shuffle play in it:
     * every Watch was a mark before build ticket 69.
     *
     * **It is not a column of the derived table.** That table is read for one thing only, the
     * position a play resumes from, and nothing that decides a mark reads it. A row read back
     * from it takes the default. Next unwatched is always worked out from the log itself, by
     * [LogReading.watchStates].
     */
    val countsForHighWaterMark: Boolean = watched,
)

/**
 * The log in order, oldest first.
 *
 * ADR 0001's rule is "within one device order by `seq`, across devices order by `at`", because
 * an Android TV commonly boots with a wrong clock and corrects it over the network seconds
 * later. Taken literally that rule is not a total order, and two entries could each claim to
 * be newer than the other.
 *
 * So each entry is given an **effective time**: the highest `at` that device had written up to
 * and including that entry, walking its own entries in `seq` order. Sorting by effective time
 * then does exactly what the rule asks:
 *
 * - Within one device the effective time never falls as `seq` rises, so the order is `seq`
 *   order, and a clock that jumped backwards changes nothing.
 * - Across devices the effective time is the wall-clock time, unless a device's clock went
 *   backwards, in which case its later entries are held at the last sane time it wrote.
 *
 * The device id and `seq` break any remaining tie, so the same log always reads the same way.
 */
fun inLogOrder(entries: List<WatchEntry>): List<WatchEntry> {
    if (entries.size <= 1) return entries.toList()
    val effective = effectiveTimes(entries)
    return entries.sortedWith(
        compareBy<WatchEntry> { effective[it.id] ?: it.at }
            .thenBy { it.deviceId }
            .thenBy { it.seq },
    )
}

private fun effectiveTimes(entries: List<WatchEntry>): Map<String, Instant> {
    val times = HashMap<String, Instant>(entries.size)
    for ((_, ofDevice) in entries.groupBy { it.deviceId }) {
        var highest: Instant? = null
        for (entry in ofDevice.sortedBy { it.seq }) {
            val here = if (highest == null || entry.at.isAfter(highest)) entry.at else highest
            highest = here
            times[entry.id] = here
        }
    }
    return times
}

/**
 * One [WatchState] per video the log says anything about.
 *
 * The rule is ADR 0001's, and it is one rule stated once:
 *
 * 1. Take the newest entry naming the video directly: a `played` or an `unwatched`.
 * 2. Take the newest `range` in that video's Source whose side and pivot date cover it.
 * 3. Whichever of the two is newer wins.
 * 4. A `played`, or a `range` with `watched = true`, means watched.
 *
 * "Newer" is position in [inLogOrder] and nothing else, so two entries written in the same
 * millisecond still have an order, and the same log always reads the same way.
 *
 * A kind this build does not recognise is still skipped. Skipping is not discarding: the entry
 * stays in the log untouched, and derivation simply has no rule for it.
 *
 * @param entries any part of the log. For step 1 alone, the entries naming one video give that
 *   video the same answer as the whole log does, which is what lets a single mark update one
 *   row without reading everything.
 * @param videos the videos step 2 is asked about. A `range` names no video, so it can only be
 *   applied to videos handed in here. Leave it empty and only the direct entries decide, which
 *   is what the stored derived table holds. See [WatchLog.watchStates].
 *
 * **One known gap, for ticket 11.** A range is matched against a video's own `sourceId`, so a
 * range in one Source does not reach the same video sitting in another Source. ADR 0001 says
 * a Watch is answered by video id alone across every Source, which would make it reach. The
 * two cannot both be satisfied from the log and one Source's video list alone: it would take
 * asking every other Source whether it holds this video too. It cannot arise while the build
 * opens one hardcoded Source, and ticket 11 is where Sources start being added and removed.
 */
fun deriveWatchStates(
    entries: List<WatchEntry>,
    videos: List<VideoKey> = emptyList(),
): Map<String, WatchState> = LogReading.of(entries).watchStates(videos)

/**
 * The whole log, ordered and read once, with every rule in this file already applied to it.
 *
 * **It exists because the home rail was reading the log nine times.** It called
 * [deriveWatchStates] once per Source, [lastPlayedVideoId] once per Source and
 * [sourcesByLastPlay] once, and each of those sorted the whole log with [inLogOrder] and
 * parsed every entry's JSON payload again.
 *
 * **That was never where the time went, and build ticket 22 measured it on the household
 * television rather than believing it.** `WatchLog.railWatches` cost about 1,800 ms on a cold
 * start before this class and about 1,500 ms after it. A log of 67 entries takes
 * 9 ms to read once and 81 ms to read nine times. What cost 1,425 ms of the 1,500 was
 * [watchStates] applying the ranges to about 6,000 videos, and the fix for that is
 * [rangesBySource].
 *
 * Nothing about the rules changed. Each of the three functions above is now one line over one
 * of these, so a caller with one question still asks it the same way and pays for one reading.
 * A caller with several questions, which is the rail, builds one reading and asks it all of
 * them.
 *
 * **Every field is finished when the reading is built.** Nothing here is lazy, so two Sources
 * asking at once cannot see each other's half-built answer, and [watchStates] never writes into
 * what it was built from.
 */
class LogReading private constructor(
    /** What the entries naming a video directly say, before any `range` is applied. */
    private val directStates: Map<String, WatchState>,
    /**
     * Where in the log the entry that decided each video sits.
     *
     * Positions rather than times, because [inLogOrder] already broke every tie and a time
     * would reopen them.
     */
    private val decidedAt: Map<String, Int>,
    /**
     * Where in the log the entry sits that decided each video's watched state **with every
     * Shuffle play left out**. See [WatchState.countsForHighWaterMark] and build ticket 69.
     *
     * Kept apart from [decidedAt] because the two can be decided by different entries: an
     * ordinary play, then a Bulk mark that unwatches it, then a Shuffle play. The Shuffle play
     * decides whether the video is watched, and the Bulk mark decides whether it is a mark.
     */
    private val steadyDecidedAt: Map<String, Int>,
    /** What that entry decided, by video id. A video missing from it has no steady Watch. */
    private val steadyWatched: Map<String, Boolean>,
    /**
     * Every readable `range`, with where in the log it sits, **grouped by the Source it
     * belongs to** and in log order within each group.
     *
     * Grouped rather than one flat list because a range only ever reaches videos of its own
     * Source, so the Source is a map lookup and never a comparison per range per video. Build
     * ticket 22 measured the flat list on the household television: for 6,051 videos and 14
     * ranges it ran 40,617 `Range.covers` calls, almost all of them to decide that the range
     * belonged to another Source, and that was most of the 1,425 ms the rail spent here. A
     * Source with no range of its own is now not in this map at all and costs nothing.
     */
    private val rangesBySource: Map<String, List<IndexedValue<Range>>>,
    /** Where `Jump to last played` lands in each Source. See [lastPlayedVideoId]. */
    private val lastPlayedBySource: Map<String, String?>,
    /** The Sources the household has played, most recently played first. */
    val sourcesByLastPlay: List<String>,
) {

    /** Where `Jump to last played` lands in one Source, or null when it has nowhere to go. */
    fun lastPlayedIn(sourceId: String): String? =
        if (sourceId.isBlank()) null else lastPlayedBySource[sourceId]

    /**
     * One [WatchState] per video the log says anything about, with the ranges applied to
     * [videos].
     *
     * See [deriveWatchStates] for the rule. The maps it works on are copies, so the same
     * reading answers for every Source without one Source's ranges reaching another's videos.
     */
    fun watchStates(videos: List<VideoKey> = emptyList()): Map<String, WatchState> {
        if (rangesBySource.isEmpty() || videos.isEmpty()) return directStates
        val states = LinkedHashMap(directStates)
        val decided = HashMap(decidedAt)
        val steadyDecided = HashMap(steadyDecidedAt)
        // Copied for the same reason as the two maps above. A range that decides the steady
        // answer writes it here, so a later pass over the same video reads the range's answer
        // and not the direct entries' answer.
        val steady = HashMap(steadyWatched)
        applyRanges(states, decided, steadyDecided, steady, videos)
        return states
    }

    private fun applyRanges(
        states: LinkedHashMap<String, WatchState>,
        decidedAt: HashMap<String, Int>,
        steadyDecidedAt: HashMap<String, Int>,
        steadyWatched: HashMap<String, Boolean>,
        videos: List<VideoKey>,
    ) {
        // The Source of the video before this one, and its ranges. Every caller on the launch
        // path hands in one Source's videos, so this is one map lookup for the whole list
        // rather than one per video. A list spanning several Sources still gets the right
        // answer: the Source is re-looked-up the moment it changes.
        var lastSourceId: String? = null
        var ofSource: List<IndexedValue<Range>> = emptyList()
        for (video in videos) {
            if (video.sourceId != lastSourceId) {
                lastSourceId = video.sourceId
                ofSource = rangesBySource[video.sourceId].orEmpty()
            }
            if (ofSource.isEmpty()) continue
            applyRange(states, decidedAt, steadyDecidedAt, steadyWatched, video, ofSource)
        }
    }

    private fun applyRange(
        states: LinkedHashMap<String, WatchState>,
        decidedAt: HashMap<String, Int>,
        steadyDecidedAt: HashMap<String, Int>,
        steadyWatched: HashMap<String, Boolean>,
        video: VideoKey,
        ofSource: List<IndexedValue<Range>>,
    ) {
        // The newest covering range, which is the last one in log order. A Source may carry
        // several, and they may overlap. Only this Source's own ranges are asked, so the date
        // is all that is left to decide: see [Range.coversDate].
        val covering = ofSource.lastOrNull { it.value.coversDate(video.videoPublishedAt) }
            ?: return

        // The same newest-wins question, asked a second time with every Shuffle play left out.
        // It is asked first, because the early return below answers the first question only.
        // See [WatchState.countsForHighWaterMark] and build ticket 69.
        val steadyDecided = steadyDecidedAt[video.videoId]
        val steadyByRange = steadyDecided == null || steadyDecided < covering.index
        if (steadyByRange) {
            steadyDecidedAt[video.videoId] = covering.index
            steadyWatched[video.videoId] = covering.value.watched
        }
        val steady = steadyWatched[video.videoId] == true

        val decided = decidedAt[video.videoId]
        if (decided != null && decided > covering.index) {
            // A direct entry newer than the range decides whether the video is watched. When
            // that entry is a Shuffle play, the range can still decide whether the video is a
            // mark, so that one field is written.
            val direct = states[video.videoId] ?: return
            states[video.videoId] = direct.copy(countsForHighWaterMark = direct.watched && steady)
            return
        }

        val direct = states[video.videoId]
        // Where the winner sits, so that a second copy of the same video, reached later in
        // this loop because it also sits in another Source, cannot overwrite a newer answer
        // with an older one.
        decidedAt[video.videoId] = covering.index
        states[video.videoId] = WatchState(
            videoId = video.videoId,
            watched = covering.value.watched,
            // A Bulk mark says what the household has seen, never how far into one video it
            // got. There is no position to resume and none to invent.
            positionSeconds = null,
            completed = covering.value.watched,
            // The display exception, and the only part of the older state a range keeps. A
            // Bulk mark written today can cover a video the household really played in June,
            // and the card must still show June. See spec section 3.2.
            lastPlayedAt = direct?.lastPlayedAt,
            countsForHighWaterMark = covering.value.watched && steady,
        )
    }

    companion object {

        /**
         * Orders the log once, walks it once, and parses each entry's payload once.
         *
         * The three kinds are tested in the order a `played` is most common, and each of
         * [WatchEntry.played], [WatchEntry.unwatched] and [WatchEntry.range] looks at `kind`
         * before it parses anything, so an entry is parsed once and not three times. A kind
         * this build does not recognise is skipped, which is not the same as discarded: the
         * entry stays in the log untouched and derivation simply has no rule for it.
         */
        fun of(entries: List<WatchEntry>): LogReading {
            val states = LinkedHashMap<String, WatchState>()
            val decidedAt = HashMap<String, Int>()
            // Grouped as the log is walked, so the ranges of one Source are already in log
            // order inside their group and never have to be sorted again.
            val ranges = HashMap<String, ArrayList<IndexedValue<Range>>>()
            // A Source with no play is in neither of these, which is what [railOrder] puts
            // after every Source that has one.
            val lastPlayedBySource = HashMap<String, String?>()
            val playedAt = LinkedHashMap<String, Int>()
            // The watched state with every Shuffle play left out, and where it was decided.
            // See [WatchState.countsForHighWaterMark]. Build ticket 69.
            val steadyWatched = HashMap<String, Boolean>()
            val steadyDecidedAt = HashMap<String, Int>()

            for ((position, entry) in inLogOrder(entries).withIndex()) {
                val play = entry.played()
                if (play != null) {
                    // A Shuffle play leaves the steady state alone: it is a Watch and not a
                    // mark. ADR 0002.
                    if (!play.shuffle) {
                        steadyWatched[play.videoId] = true
                        steadyDecidedAt[play.videoId] = position
                    }
                    states[play.videoId] = WatchState(
                        videoId = play.videoId,
                        watched = true,
                        positionSeconds = play.positionSeconds,
                        completed = play.completed,
                        lastPlayedAt = entry.at,
                        countsForHighWaterMark = steadyWatched[play.videoId] == true,
                    )
                    decidedAt[play.videoId] = position
                    // Last played counts a play and never a declaration, which is what the
                    // position separates. See [lastPlayedVideoId] for the whole rule.
                    if (play.positionSeconds != null && play.sourceId.isNotBlank()) {
                        lastPlayedBySource[play.sourceId] = play.videoId.ifBlank { null }
                        playedAt[play.sourceId] = position
                    }
                    continue
                }
                val unwatch = entry.unwatched()
                if (unwatch != null) {
                    steadyWatched[unwatch.videoId] = false
                    steadyDecidedAt[unwatch.videoId] = position
                    states[unwatch.videoId] = WatchState(
                        videoId = unwatch.videoId,
                        watched = false,
                        // There is no progress to resume in a video the household says it has
                        // not seen.
                        positionSeconds = null,
                        completed = false,
                        // The play still happened, so the time it happened is kept, and the
                        // watch date the card shows is drawn from it. See ADR 0001.
                        lastPlayedAt = states[unwatch.videoId]?.lastPlayedAt,
                    )
                    decidedAt[unwatch.videoId] = position
                    continue
                }
                val range = entry.range() ?: continue
                ranges.getOrPut(range.sourceId) { ArrayList() } += IndexedValue(position, range)
                // A range that clears Last played empties the chip for that Source, and its
                // side and pivot date are not consulted. **It does not move the Source on the
                // rail**, which is why `playedAt` is left alone: spec section 7.1 sorts the
                // rail by the evening the household spent in a Source, and a Bulk mark does
                // not un-happen one.
                if (range.clearsLastPlayed) lastPlayedBySource[range.sourceId] = null
            }

            return LogReading(
                directStates = states,
                decidedAt = decidedAt,
                steadyDecidedAt = steadyDecidedAt,
                steadyWatched = steadyWatched,
                rangesBySource = ranges,
                lastPlayedBySource = lastPlayedBySource,
                sourcesByLastPlay = playedAt.entries
                    .sortedByDescending { it.value }
                    .map { it.key },
            )
        }
    }
}

/**
 * The video the household most recently actually played in one Source, or null when there is
 * none.
 *
 * This is what the `Jump to last played` chip lands on. It is deliberately not the same
 * question as "which video is watched", and it is not read off [WatchState] either.
 *
 * **Two rules decide it, and both are ticket 06's.**
 *
 * 1. **Only a `played` entry that carries a position counts.** `CONTEXT.md` defines Last
 *    played as the video the household most recently *actually played*. A real play always
 *    writes a position, once playback passes ten seconds (spec section 3.3). A manual
 *    `Mark as watched` writes a `played` entry with no position at all
 *    ([WatchLog.markJustThis]), because ADR 0001 lists no other kind that can make one video
 *    watched. So the position is what separates a play from a declaration, and a declaration
 *    never moves the chip. It is the same reason ADR 0001 gives for a bulk-marked Source
 *    having no Last played: nobody ever sat and watched anything there.
 * 2. **A `range` with `clearsLastPlayed = true` takes it away.** Its side and pivot date are
 *    not consulted: ADR 0001 says such a range makes Last played derive as nothing *for that
 *    Source* until the next `played` entry, so it clears the Source and not a date range. It
 *    is written by one action only, ticket 10's `Mark everything unwatched`. Every other
 *    range leaves Last played exactly where it was.
 *
 * An `unwatched` entry does not move it. The play still happened, which is the same reason
 * [WatchState.lastPlayedAt] survives an unwatch.
 *
 * **Never work this out by comparing [WatchState.lastPlayedAt] across videos.** That field
 * holds the entry's raw wall-clock `at`, because it is also the date the card shows and a
 * shown date must be the real one. An Android TV can boot with a wrong clock, which is the
 * whole reason `seq` exists. Newest here means last in [inLogOrder] and nothing else.
 *
 * @param entries any part of the log. It has to be the whole log of that Source, because a
 *   clearing range names no video and so cannot be found by asking about one.
 * @param sourceId the Source the chip belongs to. Last played is per Source: the same video
 *   played in a channel's uploads is not this playlist's Last played.
 */
fun lastPlayedVideoId(entries: List<WatchEntry>, sourceId: String): String? =
    LogReading.of(entries).lastPlayedIn(sourceId)
