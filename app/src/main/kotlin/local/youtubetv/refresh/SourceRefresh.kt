package local.youtubetv.refresh

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.youtubetv.data.SourceStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.WatchLog
import local.youtubetv.data.inRailOrder
import local.youtubetv.walk.SourceWalk
import local.youtubetv.walk.WalkResult
import local.youtubetv.walk.channelIdForUploads
import local.youtubetv.youtube.YouTubeApi
import local.youtubetv.youtube.YouTubeApiException

/**
 * One Source's re-list is stored, and the screens may show it now. Build ticket 55.
 *
 * @property sourceId the Source that was re-listed.
 * @property changed true when its stored videos changed, which is when a screen has to read
 *   them again. False when the re-list found nothing new, and only its `last_walked_at` moved.
 * @property viewCountsWritten true when [changed] is false and some view counts were written all
 *   the same. Build ticket 59. It is not a change: nothing is logged as reaching the screens and
 *   the home rail is not built again, because the rail shows no count. A kept or open screen of
 *   the Source reads storage again, so its cards show the new counts. Also true for a Source the
 *   one-time first read of `ViewCountFirstRead` wrote counts for.
 */
data class SourceStored(
    val sourceId: String,
    val changed: Boolean,
    val viewCountsWritten: Boolean = false,
)

/** What one refresh run did, so the caller can put the screens it changed back in step. */
data class RefreshRun(
    /** Every Source whose stored videos changed. Nothing else needs redrawing. */
    val changedSourceIds: List<String> = emptyList(),
    /** Every Source that was checked, changed or not. */
    val checkedSourceIds: List<String> = emptyList(),
    /**
     * Every Source that had no stored name and was given the one YouTube has. Ticket 20.
     *
     * It is kept apart from [changedSourceIds], which is about a Source's **videos**. A Source
     * can be named without one video moving, and that is the usual case: all four of the
     * household's Sources are up to date and all four are headed by a raw playlist id. The
     * rail still has to be drawn again, so the caller needs to be told.
     */
    val namedSourceIds: List<String> = emptyList(),
    /** What the whole run spent from the 10,000-unit daily pool. */
    val unitsSpent: Int = 0,
    /** True when a `403 quotaExceeded` stopped it. Spec section 5.4. */
    val stoppedByQuota: Boolean = false,
    /** True when YouTube could not be reached at all. Spec section 5.4. */
    val offline: Boolean = false,
    /**
     * True when the two-unit change check itself failed, so nothing was checked at all.
     * Build ticket 55: [SourceRefresh.refreshOnLaunch] then skips the full walk, which would
     * start with the same check and fail the same way.
     */
    val changeCheckFailed: Boolean = false,
) {
    /** True when YouTube answered the change check and no quota refusal stopped the pass. */
    val succeeded: Boolean get() = !stoppedByQuota && !offline && !changeCheckFailed
}

/**
 * Catches every Source up with YouTube. Spec section 5.
 *
 * The four triggers of spec section 5.1 all arrive here, and what each one costs is decided in
 * one place. There is no Android import, so the whole of it is a JVM test against
 * [local.youtubetv.walk.FakeYouTubeApi] and costs no quota.
 *
 * **The shape of a run, and what it costs:**
 *
 * 1. One change check over **every** Source, for two units however many there are: one
 *    `channels.list?id=a,b,c` for the channel Sources and one `playlists.list?id=x,y,z` for the
 *    playlist ones. Both endpoints take up to fifty ids in one call.
 *
 *    They are `YouTubeApi.channelsByIds` and `YouTubeApi.playlistsByIds`, which ticket 12 added
 *    for pricing a page of search results. **A `list` call costs one unit per page whatever
 *    parts it asks for**, so sharing them with a caller that wants the title and the picture
 *    too costs the change check nothing, and it leaves one way to ask YouTube one question.
 * 2. Only a Source whose count moved is re-listed. One that did not costs nothing more.
 * 3. A channel Source re-lists the newest page of its uploads playlist and stops at the first
 *    id it already knows. A curated playlist is walked in full, because the owner chooses that
 *    order and a new item can sit on any page.
 * 4. The fresh listing is folded into the cache by [mergeRefresh], which is where every rule
 *    about a new or a vanished video lives.
 * 5. **Each Source is handed to [onSourceStored] the moment it is stored**, and not when the
 *    run ends. Build ticket 55. The Sources are re-listed in home rail order, most recently
 *    played first, so the Source the household is most likely to open is the first one the
 *    screens hear about.
 *
 *    Before that ticket the screens heard nothing until the last Source was done. On
 *    2026-10-03 the household Source was stored 13 seconds after launch and reached the screens
 *    105 seconds after launch, because a 5,841-video Source was walked in between.
 *
 * **What it never does:**
 *
 * - It never calls `search.list`. That endpoint has its own bucket of 100 calls a day and is
 *   the tightest limit in the project. Adding a Source by name is the only thing that may use
 *   it, and that is ticket 12's.
 * - It never writes a whole `sources` row. Every write here is [SourceStore.markWalked] or
 *   [SourceStore.setRemoteCount], one column each, so a refresh cannot reset the Direction,
 *   the autoplay-next choice, the initial-mark flag or the added-at date that the household
 *   set before it. `SourceStore.saveSource` is the door for adding a Source and nothing else.
 * - It never retries. A `403 quotaExceeded` writes down one instant and stops until midnight
 *   Pacific Time; anything else is written to logcat and the cached list is left alone.
 * - It never touches a Source that has never been walked. Such a Source has no cached list to
 *   catch up, and opening it is what walks it. See `SourceScreenModel.load`.
 */
class SourceRefresh(
    private val api: YouTubeApi,
    private val store: SourceStore,
    private val watches: WatchLog,
    private val walk: SourceWalk,
    private val log: (String) -> Unit = {},
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /**
     * Called once for every Source a run re-lists, as soon as that Source is stored and its
     * count is written down. Build ticket 55.
     *
     * **It runs inside the run, with the `running` lock held**, so the next Source waits for
     * it. That is on purpose: the screens hear about the Sources in the order they were stored,
     * and two rail rebuilds can never land out of order. A failure in it is written to the log
     * and the run goes on, because a screen that could not be drawn again is no reason to
     * leave the other Sources behind YouTube.
     */
    private val onSourceStored: suspend (SourceStored) -> Unit = {},
) {

    /**
     * Held for the whole of a run, so two triggers can never walk one Source at once.
     *
     * A launch and the 30 minute timer can land within milliseconds of each other, and two runs
     * would race two `replaceVideos` calls over the same Source and spend the quota twice. The
     * second waits, finds every count unchanged, and costs two units.
     */
    private val running = Mutex()

    /** The one-time view count first read of build ticket 59. See [refreshOnLaunch]. */
    private val firstRead = ViewCountFirstRead(api = api, store = store, log = log, now = now)

    /**
     * When the quota refusal of spec section 5.4 stops standing, or null when none stands.
     *
     * It is deliberately in memory and not in storage. It lasts until midnight Pacific Time,
     * and a relaunch after a refusal costs two units to find out the refusal is still there,
     * which is the same two units a launch spends anyway.
     */
    @Volatile
    private var quotaStoppedUntil: Instant? = null

    /**
     * When the last check ran, so the Activity's timer knows how long to sleep.
     *
     * **It starts at the moment this is built, and not at null.** The Activity starts a launch
     * refresh and its timer within milliseconds of each other, and a null here made the timer's
     * first tick find a check due immediately and run a second refresh on top of the launch
     * one, for two units of the daily 10,000 on every single cold start. Found by
     * `/code-review`.
     */
    @Volatile
    var lastCheckAt: Instant? = now()
        private set

    /**
     * A new API key was saved, so a refusal YouTube gave the old key no longer stands.
     *
     * The quota belongs to each key. Build ticket 64.
     */
    fun forgetQuotaRefusal() {
        quotaStoppedUntil = null
    }

    /** Whether a `403 quotaExceeded` is still stopping every refresh right now. */
    fun isStoppedByQuota(at: Instant = now()): Boolean = quotaStopped(at, quotaStoppedUntil)

    /**
     * Whether the weekly full walk of spec section 5.1 is owed on this launch.
     *
     * The refresh day of the last completed full walk is remembered in `app_meta`, and
     * [fullWalkDue] holds the rule: seven or more refresh days ago, or never.
     */
    fun isFullWalkDue(at: Instant = now()): Boolean =
        fullWalkDue(at, zone(), lastFullWalkDay())

    /**
     * Runs one refresh.
     *
     * @param trigger which of the four triggers of spec section 5.1 asked for it. A
     *   [RefreshTrigger.WEEKLY_FULL_WALK] walks every Source in full whatever the change check
     *   says: it is not looking for a change, it is the weekly repair of a cache that a run of
     *   partial re-lists can only ever add to. It still **makes** the check, so that every
     *   Source comes out of it with a recorded count and the next launch is cheap. A launch
     *   does not call this with it: see [refreshOnLaunch].
     * @param onlySourceId the one Source to refresh, which is what the rail's `Refresh` line
     *   passes, or null for every Source.
     */
    suspend fun refresh(
        trigger: RefreshTrigger,
        onlySourceId: String? = null,
    ): RefreshRun = running.withLock {
        val run = pass(trigger, onlySourceId)
        // **The 30 minute timer tries the first read too.** Build ticket 59. A launch whose check
        // found no network, which a television that starts the app before its wifi is up does,
        // would otherwise leave every card without a count until the next cold start, and that
        // can be days away. Found by `/code-review`.
        if (trigger == RefreshTrigger.TIMER && run.succeeded) firstReadOfViewCounts()
        run
    }

    /**
     * The launch trigger of spec section 5.1, and the weekly full walk after it when one is
     * owed. Build ticket 55.
     *
     * **The plain launch check always runs first.** It costs two units and re-lists only the
     * Sources whose count moved, so a new video reaches the screens in seconds. Until build
     * ticket 55 a launch that owed the full walk ran the full walk **instead**, and the
     * household Source then waited behind a 5,841-video Source for 82 seconds.
     *
     * **Both passes run under one hold of the `running` lock.** The 30 minute timer or a
     * manual `Refresh` that arrives between them waits until the full walk is done, so nothing
     * can slip in between the two passes.
     *
     * **Between the two passes, the one-time view count first read runs when it is owed**, and
     * only when no full walk is owed. Build ticket 59. It needs the lock for the reason
     * [firstReadOfViewCounts] gives, and it runs after the launch check so it never delays a new
     * video. A full walk reads every count itself, so on a launch that owes one the first read
     * would pay about 140 units for counts the full walk writes a minute later. The full walk
     * marks the first read done instead. Found by `/code-review`.
     *
     * The full walk is decided **before** the first pass, from the stored day alone. It is
     * skipped when the first pass was stopped by the quota or by a lost network, or when its
     * change check failed for any other reason. None of those would be any different a second
     * later, and the full walk starts with the same check.
     *
     * @param afterEachPass the end-of-pass work, called with each pass's [RefreshRun] while the
     *   lock is still held. Each Source has already reached the screens through
     *   [onSourceStored] by then.
     * @return one [RefreshRun] per pass that ran, in the order they ran.
     */
    suspend fun refreshOnLaunch(
        afterEachPass: suspend (RefreshRun) -> Unit = {},
    ): List<RefreshRun> = running.withLock {
        val lastDay = lastFullWalkDay()
        val owed = fullWalkDue(now(), zone(), lastDay)
        if (owed) log(fullWalkOwedLine(lastDay)) else log(fullWalkNotOwedLine(lastDay!!, nextFullWalkDay(lastDay)))
        val launch = pass(RefreshTrigger.LAUNCH, onlySourceId = null)
        afterEachPass(launch)
        if (launch.stoppedByQuota || launch.offline || launch.changeCheckFailed) return@withLock listOf(launch)
        // **After the launch check, so a new video still reaches the screens first, and under
        // the same hold of the lock**, so no walk can read the stored videos before a count is
        // written and then write the old null back over it. Build ticket 59.
        if (!owed) {
            firstReadOfViewCounts()
            return@withLock listOf(launch)
        }
        val full = pass(RefreshTrigger.WEEKLY_FULL_WALK, onlySourceId = null)
        afterEachPass(full)
        listOf(launch, full)
    }

    /**
     * The one-time view count first read of build ticket 59. **The caller holds the `running` lock.**
     *
     * Each Source whose counts it writes is handed to [onSourceStored] as unchanged with counts
     * written, the same as a re-list whose only news was new counts. A quota refusal stops every
     * refresh until midnight Pacific Time, the same as one met by a pass.
     */
    private suspend fun firstReadOfViewCounts(): FirstReadRun {
        if (!firstRead.isOwed()) return FirstReadRun()
        if (quotaStopped(now(), quotaStoppedUntil)) return FirstReadRun(stoppedByQuota = true)
        val run = firstRead.runIfOwed { sourceId ->
            report(SourceStored(sourceId, changed = false, viewCountsWritten = true))
        }
        if (run.stoppedByQuota) quotaStoppedUntil = quotaResetsAt(now())
        return run
    }

    /**
     * One pass over the Sources, for one trigger. **The caller holds the `running` lock.**
     *
     * This is the body [refresh] has always had. [refreshOnLaunch] runs it twice under one hold
     * of the lock, which is why the lock is taken outside it.
     */
    private suspend fun pass(trigger: RefreshTrigger, onlySourceId: String?): RefreshRun {
        val startedAt = now()
        // Written first, and on **every** path out of this function, because it is what the
        // Activity's 30 minute timer sleeps on. A path that left it alone would make the timer
        // find a check due for ever and spin: the quota refusal below is exactly such a path.
        //
        // **A refresh of one named Source does not move it.** That is the rail's `Refresh`
        // line, which is about one card, and it must not make the other Sources wait another
        // thirty minutes for a check they were a minute away from. Found by `/code-review`.
        if (onlySourceId == null) lastCheckAt = startedAt
        if (quotaStopped(startedAt, quotaStoppedUntil)) {
            log(refreshStillStoppedLine(trigger, quotaStoppedUntil!!))
            return RefreshRun(stoppedByQuota = true)
        }

        // A Source with no `lastWalkedAt` has never been walked, so there is no cached list to
        // catch up and opening it is what fills it. Refreshing one here would spend a full
        // walk's quota on a Source the household may never open.
        //
        // **In home rail order, most recently played first.** Build ticket 55. The re-lists
        // below run in this order, so the Source the household is most likely to open is
        // stored first and reaches the screens first.
        val walked = inRailOrder(
            store.allSources()
                .filter { it.lastWalkedAt != null }
                .filter { onlySourceId == null || it.sourceId == onlySourceId },
            watches.sourcesByLastPlay(),
        ) { it.sourceId }
        if (walked.isEmpty()) {
            log(refreshStartLine(trigger, 0))
            return RefreshRun()
        }
        log(refreshStartLine(trigger, walked.size))

        val fullWalk = trigger.isFullWalk
        var units = 0

        // **The change check runs on every trigger, the weekly full walk included.** The full
        // walk does not need it to decide anything: it re-walks every Source whatever the
        // counts say. It runs it to **write the counts down**, for two units.
        //
        // Without that, a Source came out of a full walk with no recorded count, and
        // the very next launch read the silence as "changed" and re-listed the whole Source
        // again. Seen on the emulator on 2026-09-20: the full walk cost 269 units, and the
        // launch two minutes later paid another 24 for a curated playlist that had not moved.
        val check = try {
            changeCheck(walked)
        } catch (failure: YouTubeApiException) {
            return stopOn(trigger, null, failure).copy(changeCheckFailed = true)
        }
        units += check.units
        // **Before anything is re-listed, and for every Source the check covered.** A name is
        // right the moment YouTube says it, whether or not that Source's videos moved, and a
        // run that stops on a later Source has still put the names it read on the rail.
        val named = fillMissingNames(walked, check.titles)
        val toRefresh: List<StoredSource> = if (fullWalk) walked else check.changed
        for (source in walked - toRefresh.toSet()) {
            source.remoteCount?.let { log(refreshUnchangedLine(source.sourceId, it)) }
        }
        log(
            refreshCheckLine(
                channelCalls = check.channelCalls,
                playlistCalls = check.playlistCalls,
                checked = walked.size,
                changed = toRefresh.size,
            ),
        )
        // **A Source's count is written down only once that Source is up to date with it**, so
        // for a Source that is not being re-listed that is now, and for one that is it is after
        // its own re-list below. Writing every count here let a run that failed part way
        // through leave the later Sources holding a count they had never caught up to, and the
        // next check then read them as unchanged and skipped them until the next full walk.
        // Found by `/code-review`.
        for (source in walked - toRefresh.toSet()) {
            check.counts[source.sourceId]?.let { store.setRemoteCount(source.sourceId, it) }
        }

        val changedIds = mutableListOf<String>()
        var anyFailed = false
        for (source in toRefresh) {
            val result = try {
                refreshOne(source, fullWalk)
            } catch (failure: YouTubeApiException) {
                // A quota refusal or a lost network stops the whole run. Anything else is this
                // one Source's problem, so the next Source is still tried.
                if (failure.reason == YouTubeApiException.Reason.QUOTA_EXCEEDED ||
                    failure.reason == YouTubeApiException.Reason.NETWORK
                ) {
                    return stopOn(
                        trigger,
                        source.sourceId,
                        failure,
                        units = units,
                        checked = walked.map { it.sourceId },
                        changed = changedIds,
                        named = named,
                    )
                }
                log(refreshFailedLine(trigger, source.sourceId, failure.message.orEmpty()))
                anyFailed = true
                continue
            }
            units += result.units
            if (result.changed) changedIds += source.sourceId
            check.counts[source.sourceId]?.let { store.setRemoteCount(source.sourceId, it) }
            // **Now, and not when the run ends.** This Source is stored and its count is
            // written, so it is finished, and the screens can show it while the next Source is
            // still being walked. Build ticket 55.
            report(SourceStored(source.sourceId, result.changed, viewCountsWritten = result.viewCountsWritten))
        }

        // **Only a full walk that every Source came through marks the day done.** One that
        // gave up on a Source would otherwise make `fullWalkDue` answer false for another
        // week, and that Source would go that long without the repair the full walk exists to
        // give it. Found by `/code-review`.
        if (fullWalk && !anyFailed) {
            store.setMeta(META_LAST_FULL_WALK_DAY, refreshDay(startedAt, zone()).toString())
            // Every Source was walked in full, so every stored count was just read. Build
            // ticket 59.
            firstRead.markDone()
        }
        log(refreshFinishedLine(trigger, toRefresh.size, units))
        return RefreshRun(
            changedSourceIds = changedIds,
            checkedSourceIds = walked.map { it.sourceId },
            namedSourceIds = named,
            unitsSpent = units,
        )
    }

    /**
     * Hands one stored Source to [onSourceStored], and keeps the run going whatever it does.
     *
     * Only a cancellation goes through, because that is the run itself being stopped.
     */
    private suspend fun report(stored: SourceStored) {
        try {
            onSourceStored(stored)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log(sourceReachScreensFailedLine(stored.sourceId, failure.message ?: failure.javaClass.simpleName))
        }
    }

    /**
     * Gives every Source with no stored name the one the change check just read. Ticket 20.
     *
     * **It spends nothing.** `channels.list` and `playlists.list` are already being called for
     * the counts, a `list` call costs one unit per page whatever parts it asks for, and both
     * replies carry a title the check used to drop on the floor.
     *
     * **The store has the last word, and this reads its answer.** The row here was read before
     * the check ran, so the title it holds can be out of date by the time the write happens:
     * once the `Rename` of ticket 20's part 2 exists, a household can rename a Source in that
     * window. The store's `WHERE` refuses such a write, and taking that refusal seriously here
     * is what keeps the log from announcing a name the Source does not have and keeps the rail
     * from being drawn again for nothing. Found by `/code-review`.
     *
     * @return the Sources that really were named, so the caller can draw the rail again.
     */
    private fun fillMissingNames(
        walked: List<StoredSource>,
        titles: Map<String, String>,
    ): List<String> {
        val named = mutableListOf<String>()
        for (source in walked) {
            // A cheap first test, so a household with named Sources does not ask the database
            // once per Source on every check. It is not the rule: the store's is.
            if (!source.title.isNullOrBlank()) continue
            val title = titles[source.sourceId]?.takeIf { it.isNotBlank() } ?: continue
            if (!store.fillMissingTitle(source.sourceId, title)) continue
            named += source.sourceId
            log(refreshNamedLine(source.sourceId, title))
        }
        return named
    }

    /** What one Source's re-list did. */
    private class OneResult(val changed: Boolean, val units: Int, val viewCountsWritten: Boolean)

    private suspend fun refreshOne(source: StoredSource, forceFullWalk: Boolean): OneResult {
        val sourceId = source.sourceId
        // Every stored video, Shorts included. See [SourceStore.allVideos]: a merge handed only
        // the listed videos would see every Short as new on every refresh.
        val stored = store.allVideos(sourceId)
        val knownIds = stored.mapTo(mutableSetOf()) { it.videoId }

        // A channel's uploads playlist is newest-first and nothing is inserted into its middle,
        // so its newest page is the whole of what can have changed. A curated playlist is in
        // the owner's own order and needs a full walk. Spec section 5.2.
        val isChannelSource = channelIdForUploads(sourceId) != null
        val partial = isChannelSource && !forceFullWalk && knownIds.isNotEmpty()

        val fresh: WalkResult =
            if (partial) walk.relistNewest(sourceId, knownIds) else walk.walk(sourceId)

        // The states are derived over the stored videos, so a video covered by a Bulk mark
        // range counts as having a Watch even though no entry names it. Spec section 5.3 keeps
        // a vanished video that has one.
        val states = watches.watchStates(stored)
        val outcome = mergeRefresh(
            stored = stored,
            fresh = fresh.videos,
            fullWalk = !partial,
            hasWatch = { videoId ->
                states[videoId]?.let { it.watched || it.positionSeconds != null } == true
            },
        )

        log(refreshSourceLine(sourceId, fullWalk = !partial, outcome = outcome, calls = fresh.apiCalls))

        // **The whole merged list is compared, and never only the three counts.** A video the
        // Source already held can change without being added, dropped or kept: a premiere
        // finishes, a live stream becomes a VOD, a title is edited, a private video comes back.
        // Asking `outcome.isNoChange` here left a finished premiere reading `upcoming` for
        // ever, so `Next unwatched` skipped it and nothing could ever fix it. Found by
        // `/code-review`.
        //
        // **The rows are compared, and not the order they came back in.** A plain `!=` on the
        // two lists read the household Source as changed on every full walk. SQL promises no
        // order for rows that tie on the publish time, and Android's SQLite handed back its two
        // undated videos the other way round. Build ticket 56. See
        // [storedVideosChanged].
        //
        // **A view count that moved, and nothing else, is not a change.** Build ticket 59. The
        // counts move every day, so [storedVideosChanged] leaves them out, and they are written
        // here with one column each. No row is deleted and the screens are not told the Source
        // changed. They are told the counts moved, through [SourceStored.viewCountsWritten].
        val changed = storedVideosChanged(stored = stored, merged = outcome.videos)
        var countsWritten = 0
        if (changed) {
            store.replaceVideos(sourceId, outcome.videos)
        } else {
            val counts = viewCountChanges(stored = stored, merged = outcome.videos)
            store.writeViewCounts(sourceId, counts)
            countsWritten = counts.size
            if (countsWritten > 0) log(viewCountsWrittenLine(sourceId, countsWritten))
        }
        // **One column, and it is `last_walked_at`.** A `saveSource` here would write the whole
        // row from whatever this path happens to be holding, and reset the Direction, the
        // autoplay-next choice, the initial-mark flag and the added-at date with it.
        //
        // It moves whether or not anything changed, because the Source genuinely did catch up
        // and the `(updated ...)` line of spec section 5.4 is what says when it last did.
        store.markWalked(sourceId, now())
        return OneResult(changed = changed, units = fresh.apiCalls.total, viewCountsWritten = countsWritten > 0)
    }

    /** What the two-unit change check found. */
    private class Check(
        val changed: List<StoredSource>,
        val counts: Map<String, Int>,
        /**
         * What YouTube calls each Source, by Source id. Ticket 20.
         *
         * A channel Source is keyed by its **uploads playlist id**, the same as its count is,
         * and carries the channel's own title. That is the name the household knows it by, and
         * `playlists.list` is never asked about a `UU` id at all.
         */
        val titles: Map<String, String>,
        val channelCalls: Int,
        val playlistCalls: Int,
    ) {
        val units: Int get() = channelCalls + playlistCalls
    }

    /**
     * One `channels.list` and one `playlists.list`, covering every Source. Spec section 5.2.
     *
     * A Source YouTube gave no count for is treated as changed rather than as unchanged. A
     * channel can hide its counts, and reading silence as "nothing new" would leave such a
     * Source frozen for ever.
     */
    private suspend fun changeCheck(walked: List<StoredSource>): Check {
        val channelIdBySource = walked.mapNotNull { source ->
            channelIdForUploads(source.sourceId)?.let { source.sourceId to it }
        }.toMap()
        val playlistIds = walked.map { it.sourceId }.filter { it !in channelIdBySource }

        var channelCalls = 0
        var playlistCalls = 0
        val counts = mutableMapOf<String, Int>()
        val titles = mutableMapOf<String, String>()

        for (batch in channelIdBySource.values.distinct().chunked(CHECK_BATCH)) {
            // `channels.list?part=snippet,contentDetails,statistics`, one unit whatever parts
            // it asks for. This is the batch call ticket 12 added for its search results, and
            // it is the same request the change check needs: a `list` call is priced per page
            // and not per part, so asking for three parts instead of one costs nothing more
            // and spec section 5.2's two units still hold.
            val channels = api.channelsByIds(batch)
            channelCalls++
            // **A null count is not a zero.** A channel can hide its counts, and reading
            // silence as zero would make the next step think every video had vanished. The
            // Source is simply left without a recorded count, which reads as "changed" and
            // re-lists it. See the filter below.
            val byChannel = channels
                .mapNotNull { channel -> channel.videoCount?.let { channel.channelId to it } }
                .toMap()
            // The title arrives in the same reply and used to be dropped here. Ticket 20.
            val titleByChannel = channels.associate { it.channelId to it.title }
            for ((sourceId, channelId) in channelIdBySource) {
                byChannel[channelId]?.let { counts[sourceId] = it }
                titleByChannel[channelId]?.let { titles[sourceId] = it }
            }
        }
        for (batch in playlistIds.distinct().chunked(CHECK_BATCH)) {
            val playlists = api.playlistsByIds(batch)
            playlistCalls++
            // A null `itemCount` is left out for the same reason a null `videoCount` is.
            for (playlist in playlists) {
                playlist.itemCount?.let { counts[playlist.playlistId] = it }
                titles[playlist.playlistId] = playlist.title
            }
        }

        val changed = walked.filter { source ->
            val seen = counts[source.sourceId]
            seen == null || source.remoteCount == null || seen != source.remoteCount
        }
        return Check(changed, counts, titles, channelCalls, playlistCalls)
    }

    /** A run that stopped where it stood, and the one line that says why. */
    private fun stopOn(
        trigger: RefreshTrigger,
        sourceId: String?,
        failure: YouTubeApiException,
        units: Int = 0,
        checked: List<String> = emptyList(),
        changed: List<String> = emptyList(),
        named: List<String> = emptyList(),
    ): RefreshRun = when (failure.reason) {
        YouTubeApiException.Reason.QUOTA_EXCEEDED -> {
            val resetsAt = quotaResetsAt(now())
            quotaStoppedUntil = resetsAt
            log(refreshQuotaExceededLine(trigger, resetsAt))
            RefreshRun(changed, checked, named, units, stoppedByQuota = true)
        }
        YouTubeApiException.Reason.NETWORK -> {
            log(refreshOfflineLine(trigger))
            RefreshRun(changed, checked, named, units, offline = true)
        }
        else -> {
            log(refreshFailedLine(trigger, sourceId, failure.message.orEmpty()))
            RefreshRun(changed, checked, named, units)
        }
    }

    /** The refresh day of the last completed full walk, or null when none has completed. */
    private fun lastFullWalkDay(): LocalDate? =
        store.meta(META_LAST_FULL_WALK_DAY)?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                // A value this build cannot read is not a reason to refuse to walk. It reads
                // as "never", which costs one full walk and then writes a value it can read.
                null
            }
        }
}

/** `channels.list` and `playlists.list` both take at most fifty ids in one call. */
private const val CHECK_BATCH = 50
