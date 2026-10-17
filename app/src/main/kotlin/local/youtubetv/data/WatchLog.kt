package local.youtubetv.data

import java.time.Instant

/**
 * The household's Watch log, and everything the app is allowed to do to it.
 *
 * It sits on top of the [WatchStore] seam and holds the rules, so every one of them is a JVM
 * unit test with no device and no quota. Nothing else in the app writes an entry.
 *
 * Two rules run through all of it:
 *
 * - **The log is the truth.** Entries are appended and never edited or deleted. Ticket 07 adds
 *   the one narrow exception, which is a play's own position while that play is on screen.
 * - **The derived table is disposable.** It is only ever what [deriveWatchStates] returns,
 *   written down, so throwing it away costs nothing but the time to build it again.
 *
 * @param sources the video cache, which a `played` entry also writes one column of: it ends
 *   the **Newly listed** state of the video played and of everything at or past it. See
 *   [SourceStore.clearNewlyListedUpTo] and build ticket 40. It is the same
 *   [YouTubeTvDatabase] file, so the write lands inside the transaction the log already
 *   opened; `MainActivity` hands in the one [SqliteSourceStore] the whole app shares.
 *
 *   **Null means there is no video cache to update, and the log is still written.** Every
 *   test that only exercises the log passes nothing, and the flag it would have cleared is
 *   read as not newly listed wherever it is missing, which is the safe direction: the app
 *   offers less, never more.
 */
class WatchLog(
    private val store: WatchStore,
    private val sources: SourceStore? = null,
    private val now: () -> Instant = Instant::now,
) {

    /**
     * Brings the derived table up to date, and says whether it had to.
     *
     * Called once on launch, before anything reads a watch state. Rebuilding on every launch
     * was rejected in ADR 0001, because the launch path is worth keeping clear.
     */
    fun openAndDerive(): Derivation {
        val stored = store.storedDerivationVersion()
        val reason = when {
            store.derivedTableAbsent() -> "the derived table was gone"
            stored == null -> "nothing had been derived yet"
            stored != DERIVATION_VERSION ->
                "derivationVersion moved from $stored to $DERIVATION_VERSION"
            else -> return Derivation.UpToDate
        }
        return rebuild(reason)
    }

    /**
     * Throws the derived table away and builds it again from the whole log.
     *
     * Also reachable by hand, which ADR 0001 asks for: launch the app with
     * `adb shell am start -n local.youtubetv/.MainActivity --ez rebuild_derived true`.
     */
    fun rebuild(reason: String): Derivation {
        val entries = store.entries()
        val states = deriveWatchStates(entries)
        store.replaceWatchStates(states.values)
        store.setDerivationVersion(DERIVATION_VERSION)
        return Derivation.Rebuilt(reason = reason, entries = entries.size, rows = states.size)
    }

    /**
     * The household marked one video as watched, or as not watched, from its card menu.
     *
     * This is the `Just this` line of both submenus. `This and all older` and `This and all
     * newer` write one `range` entry instead; see [markRange].
     *
     * A `Mark as watched` writes a `played` entry, because ADR 0001 lists no other kind that
     * can make a single video watched. It carries **no position**: the household said it had
     * seen the video, not that it had reached a particular second of it.
     *
     * **That missing position is load-bearing.** Ticket 06 decided that Last played counts
     * only a `played` entry carrying a position, so marking a video watched here never moves
     * `Jump to last played`. `CONTEXT.md` defines Last played as the video the household most
     * recently *actually played*, and a mark is a declaration rather than a play. See
     * [lastPlayedVideoId]. Anything that starts writing a position here would move the chip
     * onto a video nobody watched.
     *
     * The three writes are one transaction. The log entry is the only one of them that
     * nothing could work out again, so a failure part way through must not leave an entry
     * that the copy and the derived row never heard of.
     *
     * @return what the video's state now is, or null when there was nothing to mark. Null
     *   means no entry was written either. It is the winning state even when a `range` covers
     *   the video, because the entry just written is the newest in the whole log, and the
     *   newest entry wins.
     */
    fun markJustThis(video: StoredVideo, watched: Boolean): WatchState? {
        // A video with no id is not a video. Nothing is written, rather than an entry that
        // names nothing being appended to a log that is never cleaned up.
        if (video.videoId.isBlank()) return null

        val kind = if (watched) WatchKind.PLAYED else WatchKind.UNWATCHED
        val payload = if (watched) {
            playedPayload(video.videoId, video.sourceId, positionSeconds = null, completed = true)
        } else {
            unwatchedPayload(video.videoId, video.sourceId)
        }

        return store.inTransaction {
            store.append(kind = kind, payload = payload, at = now())

            // A `Mark as watched` writes a `played` entry, so it ends the Newly listed state
            // of this video and of every video at or past it, exactly as a real play does.
            // `Mark as unwatched` writes an `unwatched` entry and reaches nothing, which is
            // the rule stated once in [SourceStore.clearNewlyListedUpTo]: only a `played`
            // entry ends it.
            if (watched) clearNewlyListedFrom(video)

            // Taken on any entry naming the video, not only on a `played` one. The point of
            // the copy is that the log stays readable, and an `unwatched` entry is in the same
            // log. The store keeps the first copy and ignores later ones.
            store.keepVideo(
                KeptVideo(
                    videoId = video.videoId,
                    title = video.title,
                    videoPublishedAt = video.videoPublishedAt,
                ),
            )

            // Derived from the entries naming this video, by the same function a full rebuild
            // uses. One rule, in one place: a mark and a rebuild cannot disagree.
            val state = deriveWatchStates(store.entriesNaming(video.videoId))[video.videoId]
            if (state != null) store.putWatchState(state)
            state
        }
    }

    /**
     * A Bulk mark: the chosen video and every listed video older, or newer, marked in one go.
     *
     * It appends **one** `range` entry and nothing else, however many videos it covers. That
     * one entry is the whole record. Which videos it covers is worked out at read time, in
     * [watchStates], against whatever videos are stored at that moment, so a video a later
     * walk finds inside the range is covered with no second Bulk mark and no fix-up code.
     *
     * No copy of a video's title is kept here, unlike [markJustThis]. A range names no video,
     * so there is no one video whose title has to stay readable, and keeping only the pivot's
     * out of the hundreds it covers would be an arbitrary half-measure.
     *
     * @param pivot the video the household opened the menu on. Its publish date is the range's
     *   pivot, so a video with no publish date, which is what a private or deleted video looks
     *   like, cannot be one.
     * @param clearsLastPlayed only ever true for `Mark everything unwatched`, which is ticket
     *   10's. See ADR 0001. [lastPlayedVideoId] honours it: such a range leaves the whole
     *   Source with no Last played until its next `played` entry, and its side and pivot date
     *   are not consulted. Every other range leaves Last played exactly where it was.
     * @return the entry written, or null when there was nothing to write. Null means the log
     *   was not touched at all.
     */
    fun markRange(
        pivot: StoredVideo,
        side: RangeSide,
        watched: Boolean,
        clearsLastPlayed: Boolean = false,
    ): WatchEntry? {
        if (pivot.videoId.isBlank() || pivot.sourceId.isBlank()) return null
        // Nothing to pivot on. The household is offered `Just this` and no more on such a
        // card, so this is the belt to that brace rather than a case it can reach.
        val pivotPublishedAt = pivot.videoPublishedAt ?: return null

        return store.append(
            kind = WatchKind.RANGE,
            payload = rangePayload(
                sourceId = pivot.sourceId,
                pivotPublishedAt = pivotPublishedAt,
                side = side,
                watched = watched,
                clearsLastPlayed = clearsLastPlayed,
            ),
            at = now(),
        )
    }

    /**
     * The household added a Source. Spec section 6.6.
     *
     * One `sourceAdded` entry, naming the Source, the title it had at the time and the
     * Direction the household chose. It changes nothing the screen draws: the entry names no
     * video, so [deriveWatchStates] skips it and no card's green tick moves.
     *
     * @return the entry written, or null when there was nothing to write.
     */
    fun sourceAdded(sourceId: String, title: String?, direction: Direction): WatchEntry? {
        if (sourceId.isBlank()) return null
        return store.append(
            kind = WatchKind.SOURCE_ADDED,
            payload = sourceAddedPayload(sourceId, title, direction),
            at = now(),
        )
    }

    /**
     * The household removed a Source. Spec section 6.6.
     *
     * **Nothing is deleted from the log.** A removal is one more entry appended to it, exactly
     * like everything else. Every `played`, every `unwatched` and every `range` that Source
     * ever earned stays where it is, which is what makes adding the same playlist id back
     * restore the household's history whole. The Source's **videos** are deleted, and that is
     * the store's job, not this one's: they are cache and a re-walk fetches them again.
     *
     * @return the entry written, or null when there was nothing to write.
     */
    fun sourceRemoved(sourceId: String): WatchEntry? {
        if (sourceId.isBlank()) return null
        return store.append(
            kind = WatchKind.SOURCE_REMOVED,
            payload = sourceRemovedPayload(sourceId),
            at = now(),
        )
    }

    /**
     * Playback passed ten seconds, so this play gets its one `played` entry.
     *
     * Appended once per play and never on start, so a mis-press on the wrong card does not
     * remove that video from Next unwatched. Spec section 3.3.
     *
     * The same three writes as [markJustThis], in one transaction, and for the same reason:
     * the log entry is the only one of them that nothing could work out again.
     *
     * @return the handle [updatePlay] needs, or null when there was nothing to write.
     */
    fun startPlay(video: StoredVideo, positionSeconds: Int, completed: Boolean): PlayInProgress? {
        if (video.videoId.isBlank()) return null
        return store.inTransaction {
            val entry = store.append(
                kind = WatchKind.PLAYED,
                payload = playedPayload(
                    videoId = video.videoId,
                    sourceId = video.sourceId,
                    positionSeconds = positionSeconds,
                    completed = completed,
                ),
                at = now(),
            )
            // This is a real play, so every video at or past it stops being Newly listed. A
            // Newly listed video the household has now moved past becomes an ordinary skipped
            // video, and is never offered again. Build ticket 40.
            clearNewlyListedFrom(video)
            store.keepVideo(
                KeptVideo(
                    videoId = video.videoId,
                    title = video.title,
                    videoPublishedAt = video.videoPublishedAt,
                ),
            )
            val state = deriveAndStore(video.videoId)
            if (state == null) null else PlayInProgress(entry.id, state)
        }
    }

    /**
     * The play moved on, so its own entry says something different now.
     *
     * **This is the one place in the app that changes an entry that already exists**, and the
     * only entry it may touch is the one [startPlay] just wrote for the play still on screen.
     * See spec section 3.3, ADR 0001 and [WatchStore.replacePayload]. Once the household
     * leaves the video, nothing calls this again and the entry is frozen forever.
     *
     * @return the play with its state brought up to date, or null when the entry has gone.
     */
    fun updatePlay(
        play: PlayInProgress,
        video: StoredVideo,
        positionSeconds: Int,
        completed: Boolean,
    ): PlayInProgress? {
        if (video.videoId.isBlank()) return null
        return store.inTransaction {
            store.replacePayload(
                entryId = play.entryId,
                payload = playedPayload(
                    videoId = video.videoId,
                    sourceId = video.sourceId,
                    positionSeconds = positionSeconds,
                    completed = completed,
                ),
            )
            val state = deriveAndStore(video.videoId)
            if (state == null) null else PlayInProgress(play.entryId, state)
        }
    }

    /**
     * This video's row, worked out again from the entries naming it, and written down.
     *
     * The same function a full rebuild uses, so a play and a rebuild cannot disagree.
     */
    /**
     * Ends the **Newly listed** state of this video and of everything at or past it.
     *
     * Called from the two places that append a `played` entry, and from nowhere else. Both
     * hold the whole [StoredVideo], so both have the Source id and the publish date the rule
     * needs. The side is the Source's own Direction, which the store reads for itself.
     *
     * It runs inside the transaction the caller already opened, because the video cache and
     * the Watch log are tables of one [YouTubeTvDatabase] file and `MainActivity` builds one
     * helper for the whole app.
     *
     * A Source with no [SourceStore] behind it does nothing at all. See the class comment.
     */
    private fun clearNewlyListedFrom(video: StoredVideo) {
        if (video.sourceId.isBlank()) return
        sources?.clearNewlyListedUpTo(video.sourceId, video.videoPublishedAt)
    }

    private fun deriveAndStore(videoId: String): WatchState? {
        val state = deriveWatchStates(store.entriesNaming(videoId))[videoId]
        if (state != null) store.putWatchState(state)
        return state
    }

    /**
     * What is known about each of [videos], by video id.
     *
     * By video id and not by Source, on purpose. A video can sit in a channel's uploads and in
     * a curated playlist, and the household watched it once, so its card carries the green
     * tick wherever it appears. See ADR 0001, "What a Watch is".
     *
     * **Why this reads the log rather than the derived table.** A `range` covers videos by
     * date and names none, so which videos it covers depends on which videos are stored right
     * now, and that changes with every walk. Storing the answer would mean rewriting hundreds
     * of rows per Bulk mark, and rewriting them again after every walk, with a stale derived
     * table drawing wrong green ticks in between. Working it out here costs one pass over a
     * log of a few hundred entries and one pass over the videos on screen, and it makes the
     * ticket's hardest rule true by construction.
     *
     * The derived table is still kept up to date and still holds the direct entries, which is
     * what [state] reads and what a rebuild writes.
     */
    fun watchStates(videos: List<StoredVideo>): Map<String, WatchState> =
        deriveWatchStates(store.entries(), videos)

    /**
     * The video `Jump to last played` lands on in this Source, or null when there is none.
     *
     * It reads the whole log, for the same reason [watchStates] does: a `range` that clears
     * Last played names no video, so it cannot be found by asking about one. The rules
     * themselves are [lastPlayedVideoId]'s, and the two that matter are that a `played` entry
     * with no position is a manual mark and never counts, and that a range with
     * `clearsLastPlayed` takes Last played away for the whole Source.
     */
    fun lastPlayed(sourceId: String): String? = lastPlayedVideoId(store.entries(), sourceId)

    /**
     * The Sources the household has played, most recently played first. See
     * [local.youtubetv.data.sourcesByLastPlay], which holds the rule.
     *
     * A refresh run reads it to re-list the Sources in home rail order. Build ticket 55. It is
     * one read of the log, which is a few hundred entries at most.
     */
    fun sourcesByLastPlay(): List<String> = sourcesByLastPlay(store.entries())

    /**
     * Both of the above, over **one** read of the log.
     *
     * The launch path needs the watch map and Last played together, and each of [watchStates]
     * and [lastPlayed] reads the whole log and orders it. Doing that twice on the one path
     * this project keeps clear is work for nothing. Found by `/code-review`.
     */
    fun watchesOfSource(sourceId: String, videos: List<StoredVideo>): SourceWatches {
        // One [LogReading], asked both questions, the same way [railWatches] does. It used to
        // call `deriveWatchStates` and `lastPlayedVideoId`, and both of those are now one line
        // over a reading of their own, so this promised one reading and paid for two. Found by
        // `/code-review` on build ticket 22.
        val reading = LogReading.of(store.entries())
        return SourceWatches(
            states = reading.watchStates(videos),
            lastPlayedVideoId = reading.lastPlayedIn(sourceId),
        )
    }

    /**
     * What the whole home rail needs, over **one** read of the log.
     *
     * The rail asks three things of the log at once, for every Source it draws: which videos
     * carry a Watch, where `Jump to last played` would land in each Source, and which Source
     * the household played most recently. Each of [watchStates], [lastPlayed] and
     * [sourcesByLastPlay] reads the whole log itself, so asking them one at a time would read
     * the log several times per Source on the launch path. This is the same reason
     * [watchesOfSource] exists for one Source.
     *
     * **Each Source is derived over its own videos, and never over one flattened list.**
     * [deriveWatchStates] keys its answer by video id alone, so a `range` written in one Source
     * would decide the state of the same video id sitting in another, and the rail's badge
     * would then disagree with what that Source's own screen says. Derivation still costs one
     * pass over the log per Source, which is small beside a pass over the videos. Found by
     * `/code-review`.
     *
     * @param videosBySource every listed video of every Source on the rail, by Source id.
     */
    fun railWatches(videosBySource: Map<String, List<VideoKey>>): RailWatches {
        // **One reading of the log for the whole rail.** It used to ask the three questions
        // below through their own functions, and each of those ordered the whole log and
        // parsed every entry again: nine readings for four Sources. See [LogReading].
        //
        // **Reading the log once was not where the time was.** Build ticket 22 measured this
        // whole function on the household television at about 1,800 ms on a cold start, and
        // then measured it again with one reading: still about 1,500 ms. A log of 67
        // entries costs 9 ms to read however often it is done. The cost was
        // applying the ranges to about 6,000 videos, which is `LogReading.watchStates`.
        val reading = LogReading.of(store.entries())
        return RailWatches(
            statesBySource = videosBySource.mapValues { (_, videos) ->
                reading.watchStates(videos)
            },
            sourcesByLastPlay = reading.sourcesByLastPlay,
            lastPlayedBySource = videosBySource.keys.associateWith { reading.lastPlayedIn(it) },
        )
    }

    /**
     * The three remaining reads the screen needs.
     *
     * They pass straight through to the store, and they are here rather than left to the
     * screen because this class is the only door to the log. Handing a screen the store as
     * well would give it a way to write an entry that none of the rules above ever saw.
     *
     * [state] answers from the **direct** entries only, because that is what the derived table
     * holds. It is the right answer for a position to resume from, which is the only thing a
     * range never carries. It is **not** the answer a card's green tick is drawn from: that is
     * [watchStates], which needs the video's Source and publish date to apply a range at all.
     */
    fun state(videoId: String): WatchState? = store.watchState(videoId)

    fun keptVideo(videoId: String): KeptVideo? = store.keptVideo(videoId)

    fun keptVideos(): List<KeptVideo> = store.keptVideos()
}

/**
 * A play that is still on screen, and so the one entry the log lets anything change.
 *
 * It is a handle and not a copy of the entry: [entryId] is what [WatchLog.updatePlay] rewrites,
 * and [state] is what the video's card draws from right now, so the red In progress bar moves
 * with the play without anything reading storage again.
 *
 * It stops being meaningful the moment the household leaves the video. From then on the entry
 * is frozen and this handle is thrown away.
 */
data class PlayInProgress(val entryId: String, val state: WatchState)

/**
 * What one Source's screen needs from the log, read in one pass.
 *
 * @property states what the green tick and the dark wash are drawn from, by video id, for
 *   every video handed in.
 * @property lastPlayedVideoId where `Jump to last played` lands, or null when it has nowhere
 *   to go in this Source.
 */
data class SourceWatches(
    val states: Map<String, WatchState>,
    val lastPlayedVideoId: String?,
)

/**
 * What the home rail needs from the log, read in one pass. See [WatchLog.railWatches].
 *
 * @property statesBySource what each rail card's unwatched count is worked out from: the inner
 *   map is by video id, and the outer one is by Source id. One map per Source and never one
 *   shared map, because a `range` belongs to a Source and a video id does not.
 * @property sourcesByLastPlay the Sources the household has played, most recently played
 *   first. A Source that has never been played is not in it. See [sourcesByLastPlay].
 * @property lastPlayedBySource where `Jump to last played` would land in each Source, by
 *   Source id, with a null value for a Source that has nowhere to land.
 */
data class RailWatches(
    val statesBySource: Map<String, Map<String, WatchState>>,
    val sourcesByLastPlay: List<String>,
    val lastPlayedBySource: Map<String, String?>,
)

/** What [WatchLog.openAndDerive] found, so the launch can say it in one logcat line. */
sealed interface Derivation {

    /** The derived table already matched the code. Nothing was read and nothing was written. */
    data object UpToDate : Derivation

    /**
     * The derived table was built again from the log.
     *
     * @property reason why, in words meant for whoever is reading logcat.
     * @property entries how many log entries were read.
     * @property rows how many videos the log says anything about.
     */
    data class Rebuilt(val reason: String, val entries: Int, val rows: Int) : Derivation
}
