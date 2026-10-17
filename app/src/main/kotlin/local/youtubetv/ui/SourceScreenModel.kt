package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.youtubetv.data.DERIVATION_VERSION
import local.youtubetv.data.Derivation
import local.youtubetv.data.Direction
import local.youtubetv.data.KeptVideo
import local.youtubetv.data.RangeSide
import local.youtubetv.data.SourceStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.data.WatchState
import local.youtubetv.data.canBeLandedOn
import local.youtubetv.data.clearedNewlyListed
import local.youtubetv.data.isListed
import local.youtubetv.data.nextUnwatchedLanding
import local.youtubetv.data.orderByPublishDate
import local.youtubetv.walk.SourceWalk
import local.youtubetv.walk.walkCostLine
import local.youtubetv.youtube.YouTubeApiException

/**
 * What the Source screen shows right now.
 *
 * The plain screen of ticket 02 renders this. Ticket 03 replaces the screen, not this state.
 */
sealed interface SourceScreenState {

    /** Before anything has been read, which lasts only as long as one database read. */
    data object Starting : SourceScreenState

    /**
     * The first walk is running.
     *
     * `loaded` counts the playlist entries seen so far, and `total` is what YouTube says the
     * playlist holds. `total` is null until the first page arrives.
     *
     * The plain screen of ticket 02 shows only a counter here. Spec section 6.5 wants the
     * videos themselves to appear as pages arrive. That belongs to the real grid in
     * ticket 03.
     */
    data class Walking(val loaded: Int, val total: Int?) : SourceScreenState

    data class Ready(
        val videos: List<StoredVideo>,
        val updatedAt: Instant?,
        /** True when this list came from storage and no network call was made at all. */
        val fromStorage: Boolean,
        /**
         * What the Watch log says about each video, by video id.
         *
         * The card's green tick and dark wash are drawn from it, and so is the watch date on
         * the header line. It holds ids from **every** Source, not only this one: a Watch is
         * household-level and is answered by video id alone, so the same video carries its
         * tick in a channel's uploads and in a curated playlist alike. See ADR 0001.
         */
        val watches: Map<String, WatchState> = emptyMap(),
        /**
         * Which way round this Source is browsed, and so the order [videos] is already in.
         *
         * It is read from the Source's own row and written back by [SourceScreenModel.flip],
         * so it survives leaving the Source and coming back. See spec section 7.2.
         */
        val direction: Direction = Direction.NEWEST_FIRST,
        /**
         * The video `Jump to last played` lands on, or null when the chip has nowhere to go.
         *
         * It is the video the household most recently **actually played** in this Source, so
         * a manual `Mark as watched` never sets it and a Bulk mark never moves it. See
         * [lastPlayedVideoId], which is where both rules are written.
         */
        val lastPlayedVideoId: String? = null,
        /**
         * Whether a finished video rolls into the next one by itself. Spec section 8.6.
         *
         * It is read from the Source's own row and written back by
         * [SourceScreenModel.toggleAutoplayNext], exactly as [direction] is, so the choice
         * survives leaving the Source and coming back and survives a relaunch.
         *
         * The default here is the Source's own default, so a screen built before a row has
         * been read shows what a new Source is: off. See [StoredSource.AUTOPLAY_NEXT_DEFAULT].
         */
        val autoplayNext: Boolean = StoredSource.AUTOPLAY_NEXT_DEFAULT,
        /**
         * The first walk that is still running, or null once there is nothing left to load.
         *
         * **A walk in progress is a `Ready` state, not a `Walking` one.** Spec section 6.5
         * asks for the Source screen to open at once and fill page by page, so the grid has to
         * be drawn over a list that is still growing. [SourceScreenState.Walking] is now only
         * the moment before the very first batch arrives, when there is nothing to draw at
         * all.
         *
         * Two things are unavailable while it is not null, because both need the whole list:
         * the `Next unwatched` chip, and the two Bulk mark lines of a card's submenu. See
         * `cardMenuSubLines`.
         */
        val walking: WalkProgress? = null,
    ) : SourceScreenState

    /**
     * How far the first walk has got, for the quiet `loading 400 of 3,000` line.
     *
     * @property total what YouTube says the playlist holds, or null until it has said.
     */
    data class WalkProgress(val loaded: Int, val total: Int?)

    /**
     * The walk failed and there is nothing stored to fall back on.
     *
     * A Source that has been walked before never reaches this state: it shows its stored list
     * instead, because everything stays browsable offline. See spec section 5.4.
     */
    data class Failed(val message: String) : SourceScreenState
}

/**
 * Drives the Source screen: read storage first, walk only if there is nothing there.
 *
 * The order matters and is the point of one of this ticket's acceptance criteria. A second
 * launch must render the stored list **with no network call**, so storage is read first and
 * the walk is not started at all when it returns anything.
 *
 * Refresh, which is what makes a stored list catch up later, is ticket 13. Until then a
 * walked Source is never re-walked.
 *
 * This class has no Android import on purpose, so its whole flow is unit-tested on the JVM.
 * [log] is where a real build passes `Log.i`.
 */
class SourceScreenModel(
    private val sourceId: String,
    private val store: SourceStore,
    private val walk: SourceWalk,
    private val watches: WatchLog,
    private val log: (String) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {

    private val mutableState = MutableStateFlow<SourceScreenState>(SourceScreenState.Starting)
    val state: StateFlow<SourceScreenState> = mutableState.asStateFlow()

    private val mutableName = MutableStateFlow(sourceId)

    /**
     * What the Source screen's header calls this Source, **right now**.
     *
     * It is a flow of its own and not a field of [SourceScreenState.Ready], on purpose. The
     * header is drawn in every state, including `Starting`, `Walking` and `Failed`, so a name
     * that lived inside `Ready` would leave the first walk of a new Source headed by a raw
     * playlist id.
     *
     * **It is what closes build ticket 29.** The header used to hold whatever the name was at
     * the moment the household walked in, so a name that changed while they stood inside the
     * Source never reached them. Three things write this flow, and each of them is right at
     * the moment it writes:
     *
     * - [openedWithName], from the name the caller is already showing on the rail card. It is
     *   synchronous, so the header never flashes the raw id on the way to the real name. It is
     *   a guess, and one of the two below always follows it.
     * - [load] and [reloadFromStorage], from the Source's own row.
     * - [refreshName], from the Source's own row, on every reopening and after something
     *   outside this screen has changed the name. Today that is the title backfill of the
     *   daily change check; a rename is build ticket 27.
     *
     * The fallback to the raw id is [sourceNameOrId], the same one `railCardName` uses, so the
     * header and the rail card can never disagree about what an unnamed Source is called.
     */
    val name: StateFlow<String> = mutableName.asStateFlow()

    /**
     * The screen is being opened, and the caller knows what its rail card says it is called.
     *
     * It is called on the main thread, before the first frame, because reading the row here
     * would be SQLite on the launch path and the header would draw the raw id until it came
     * back.
     *
     * **It is a guess, and storage always has the last word.** The rail card it comes from can
     * be older than storage, because the rail is rebuilt in the background when the household
     * leaves a Source. So the caller follows this with [load], on a model it has just built,
     * or with [refreshName], on a model it is reusing. Raised by `/code-review`.
     *
     * **Call it before [start], never after.** [load] runs on `Dispatchers.IO`, so a call that
     * came afterwards would be a second writer to this flow in no fixed order, and the one
     * that lost would be the older name.
     */
    fun openedWithName(name: String) {
        mutableName.value = name
    }

    /**
     * Reads the Source's name from storage again.
     *
     * **Call it off the main thread.** It is one row read, by primary key.
     *
     * It answers in every state, unlike [refreshUpdatedAt] and [reloadFromStorage], which both
     * give up unless the grid is `Ready` and settled. A Source being walked for the first time
     * is exactly the Source most likely to be named while its screen is open, so giving up
     * there would leave out the one case that made this a ticket.
     */
    suspend fun refreshName() {
        val row = store.source(sourceId)
        mutableName.value = sourceNameOrId(row?.typedName, row?.title, sourceId)
    }

    /**
     * Held for the whole of a mark, and of a Direction flip, so that two cannot interleave.
     *
     * Each of them reads the screen state, works something out from it, and writes the state
     * back. Two running at once would let the slower one write a state it built before the
     * faster one had happened, and the faster change would vanish from the grid until the next
     * launch. The log and the Source row would still be right, which is exactly what makes
     * such a bug hard to see.
     *
     * Anything else that reads this screen's state and writes it back belongs inside it too,
     * with **one exception that is deliberate**: [applyWatchState]. It is not a suspending
     * function, because it is called from the player's progress tick on the main thread, and
     * it cannot be made one without putting a lock wait on that thread. What makes it safe is
     * that the player covers the whole screen while it runs, so the chip row and the card
     * menu cannot be reached and neither a flip nor a mark can be in flight. If a later
     * ticket ever lets a mark or a flip happen while a video is playing, that exception stops
     * being true. Raised by `/code-review`.
     */
    private val marking = Mutex()

    /**
     * Reads storage and, if there is nothing stored, walks the Source.
     *
     * **On [Dispatchers.IO], never on the caller's thread.** `lifecycleScope` is
     * `Dispatchers.Main.immediate`, and everything this does is SQLite: reading the Source
     * record, reading its videos, and reading the whole Watch log to derive from it. On a
     * Source of a few thousand videos that is long enough to hold up the first frame.
     */
    fun start(scope: CoroutineScope, rebuildDerived: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            if (rebuildDerived) rebuildDerived()
            load()
        }
    }

    suspend fun load() {
        // Before anything reads a watch state. A derived table that does not match the code
        // would otherwise draw green ticks by a rule this build no longer uses.
        log(derivationLine(watches.openAndDerive()))

        // Whether this Source has been walked is answered by its own row's `lastWalkedAt`,
        // never by counting its videos. A Source can legitimately store zero listed videos:
        // an empty playlist, or a channel that posts nothing but Shorts. Counting would
        // re-walk both of those on every launch, which is a network call the second launch
        // must not make.
        //
        // **The row alone is not the answer**, and it was until ticket 11. A Source now has a
        // row from the moment the household adds it, and this screen is what walks it a moment
        // later. Reading the row's presence as "walked" would open every newly added Source on
        // an empty grid that never filled.
        //
        // **The read from storage holds the same lock [reloadFromStorage] takes.** Build ticket
        // 55. A refresh now reaches the screens the moment one Source is stored, which can be
        // while this read is still running. Without the lock, the reload found this screen not
        // yet `Ready` and gave up, and this read then finished with the list from before the
        // refresh. With it, the reload waits for this read and then reads the new list. The
        // walk below does not hold the lock, because it takes minutes.
        val readFromStorage = marking.withLock {
            val row = store.source(sourceId)
            // The one row read answers both questions, so the header's name costs no second
            // read. It has the last word over [openedWithName]: storage is where the rail reads
            // too.
            mutableName.value = sourceNameOrId(row?.typedName, row?.title, sourceId)
            val walkedBefore = row?.takeIf { it.lastWalkedAt != null } ?: return@withLock false
            openFromStorage(walkedBefore)
            true
        }
        if (readFromStorage) return
        firstWalk()
    }

    /** The Source has been walked before, so it opens from storage with no API call. */
    private fun openFromStorage(walkedBefore: StoredSource) {
        // Already in this Source's Direction: the store orders by the Source's own row, so the
        // chosen Direction survives leaving the Source and coming back.
        val stored = store.listedVideos(sourceId)
        // One read of the log for both answers, and the watch map is then used three times.
        // Each of them is a pass over the whole log and over every video on the screen, which
        // is not work to do twice on the launch path.
        val found = watches.watchesOfSource(sourceId, stored)
        log("Opened $sourceId from storage: ${stored.size} listed videos, no API call made.")
        log(openingLine(walkedBefore.direction, stored, found.states))
        reportKeptVideosMissingFromCache(stored)
        mutableState.value = SourceScreenState.Ready(
            videos = stored,
            updatedAt = walkedBefore.lastWalkedAt,
            fromStorage = true,
            watches = found.states,
            direction = walkedBefore.direction,
            lastPlayedVideoId = found.lastPlayedVideoId,
            autoplayNext = walkedBefore.autoplayNext,
        )
    }

    /** The Source has never been walked, so this walks it. Spec section 6.5. */
    private suspend fun firstWalk() {
        // The Source opens at once, on nothing, and fills as pages arrive. Spec section 6.5.
        mutableState.value = SourceScreenState.Walking(loaded = 0, total = null)
        val added = store.source(sourceId)
        try {
            // How far the playlist paging has got. It is read again by the batch callback
            // below, so the progress line keeps counting while the enrichment runs.
            var loadedSoFar = 0
            var totalSoFar: Int? = null
            val result = walk.walk(
                playlistId = sourceId,
                onProgress = { loaded, total ->
                    loadedSoFar = loaded
                    totalSoFar = total
                    // Only while the grid is still empty. Once a batch has arrived the screen
                    // is `Ready` and going back to `Walking` would blank the grid again.
                    val current = mutableState.value
                    if (current !is SourceScreenState.Ready) {
                        mutableState.value = SourceScreenState.Walking(loaded, total)
                    } else {
                        mutableState.value = current.copy(
                            walking = SourceScreenState.WalkProgress(loaded, total),
                        )
                    }
                },
                onVideos = { finished ->
                    // Shorts are hidden everywhere, and the store is what usually drops them.
                    // Nothing is stored yet at this point, so the same filter is applied here.
                    val listed = finished.filter { it.isListed }
                    val current = mutableState.value as? SourceScreenState.Ready
                    // One reading of the log for both questions this batch asks of it.
                    // `watchStates` and `lastPlayed` are each a whole reading of their own,
                    // and a 5,300-video Source is about 106 batches. Found by `/code-review`
                    // on build ticket 22.
                    val ofSource = watches.watchesOfSource(sourceId, listed)
                    mutableState.value = SourceScreenState.Ready(
                        videos = orderByPublishDate(listed, added?.direction ?: Direction.NEWEST_FIRST),
                        // Null until the walk finishes. A half-walked Source has not been
                        // updated at any time, and saying a time would be saying something
                        // untrue about a list that is still growing.
                        updatedAt = null,
                        fromStorage = false,
                        // Worked out again on **every** batch, over the videos that have
                        // arrived so far. Reading the log once and carrying the answer
                        // forward left every video after the first fifty with no green tick
                        // until the walk finished, which is exactly wrong on the path this
                        // ticket promises: a Source removed and added back should show its
                        // history straight away. Found by `/code-review`.
                        watches = ofSource.states,
                        direction = added?.direction ?: Direction.NEWEST_FIRST,
                        lastPlayedVideoId = current?.lastPlayedVideoId
                            ?: ofSource.lastPlayedVideoId,
                        autoplayNext = added?.autoplayNext
                            ?: StoredSource.AUTOPLAY_NEXT_DEFAULT,
                        walking = SourceScreenState.WalkProgress(loadedSoFar, totalSoFar),
                    )
                },
            )
            log(
                walkCostLine(
                    sourceId = sourceId,
                    playlistEntries = result.playlistEntries,
                    distinctVideos = result.videos.size,
                    calls = result.apiCalls,
                ),
            )

            // **The Source may have been removed while this walk was running.** The walk
            // outlives the Source screen: it was launched on the Activity's scope, so leaving
            // the screen does not stop it, and `Remove` is two presses away on the rail.
            // Writing the videos and the row here would put the removed Source back on the
            // rail, named by its raw playlist id, with a `sourceRemoved` entry in the log
            // saying it had gone. Found by `/code-review`.
            if (store.source(sourceId) == null) {
                log(
                    sourceId + " was removed while its first walk was running. " +
                        "The " + result.videos.size + " videos it fetched are thrown away.",
                )
                mutableState.value = SourceScreenState.Starting
                return
            }

            val walkedAt = now()
            store.replaceVideos(sourceId, result.videos)

            // **One column is written, and it is `last_walked_at`.** The Source's row already
            // exists, because adding the Source is what wrote it, and it carries the title,
            // the Direction the household chose on the add screen, its autoplay-next choice
            // and when it was added. A `saveSource` here would write the whole row from
            // whatever this path happened to be holding and reset every one of them.
            //
            // A Source with no row at all cannot be reached from any screen: the rail draws
            // stored Sources and the add screen writes the row before this model is ever
            // built. The belt below is for a Source opened some other way, such as by hand
            // over adb, so that it is remembered as walked rather than walked again on every
            // launch.
            // Checked a second time, because the removal could have landed between the check
            // above and the write. The videos go with it, so nothing is left behind.
            val existing = store.source(sourceId)
            if (existing == null) {
                log(sourceId + " was removed while its walk was stored; its videos go too.")
                store.removeSource(sourceId)
                mutableState.value = SourceScreenState.Starting
                return
            }
            store.markWalked(sourceId, walkedAt)
            // A first walk of a long Source takes minutes, and a change check can name the
            // Source while it runs. This row is already in hand, so the header catches up for
            // nothing. See [name].
            mutableName.value = sourceNameOrId(existing.typedName, existing.title, sourceId)
            val direction = existing.direction
            val autoplayNext = existing.autoplayNext

            val listed = store.listedVideos(sourceId)
            val found = watches.watchesOfSource(sourceId, listed)
            log("Stored ${result.videos.size} videos for $sourceId, of which ${listed.size} are listed.")
            log(openingLine(direction, listed, found.states))
            reportKeptVideosMissingFromCache(listed)
            mutableState.value = SourceScreenState.Ready(
                videos = listed,
                updatedAt = walkedAt,
                fromStorage = false,
                watches = found.states,
                direction = direction,
                lastPlayedVideoId = found.lastPlayedVideoId,
                autoplayNext = autoplayNext,
            )
        } catch (failure: YouTubeApiException) {
            // The message is Google's own text. It is capped because it is the one string
            // on this path that this app did not write.
            log("Walk of $sourceId failed: ${failure.reason} ${failure.message.orEmpty().take(300)}")
            mutableState.value = SourceScreenState.Failed(failureLine(failure))
        }
    }

    /**
     * A refresh changed this Source's stored videos, so the screen catches up with them.
     *
     * Spec section 5.3: a change that arrives while a Source screen is open is applied at once.
     * The screen keeps the highlight on the video the household is standing on; that rule is
     * `refocusIndexAfterRefresh` and lives in `ui/SourceScreen.kt`, because only the screen
     * knows which card has the highlight.
     *
     * **It reads storage again rather than being handed a list.** A refresh can add a video,
     * drop one and mark one unavailable in one pass, and a Bulk mark range can make an arriving
     * video watched with nothing naming it, so the watch map has to be worked out again over
     * the new list. There is no cheaper honest answer.
     *
     * Two things are carried over from the Source's own row and never guessed at: the
     * Direction and the autoplay-next choice. It takes the same lock a mark and a flip take,
     * because it reads the screen's state and writes it back.
     *
     * A Source that is still on its first walk is left alone. The walk is about to write the
     * whole list itself, and a read in the middle of it would put a half-walked list on screen.
     */
    suspend fun reloadFromStorage() = marking.withLock {
        val current = mutableState.value as? SourceScreenState.Ready ?: return@withLock
        if (current.walking != null) return@withLock
        val stored = store.source(sourceId)?.takeIf { it.lastWalkedAt != null } ?: return@withLock
        val videos = store.listedVideos(sourceId)
        val found = watches.watchesOfSource(sourceId, videos)
        // The row is already read, so the header follows a refresh that both moved the videos
        // and filled the name. See [name].
        mutableName.value = sourceNameOrId(stored.typedName, stored.title, sourceId)
        log("Refreshed $sourceId is on screen: ${videos.size} listed videos now.")
        log(openingLine(stored.direction, videos, found.states))
        mutableState.value = current.copy(
            videos = videos,
            updatedAt = stored.lastWalkedAt,
            fromStorage = true,
            watches = found.states,
            direction = stored.direction,
            lastPlayedVideoId = found.lastPlayedVideoId,
            autoplayNext = stored.autoplayNext,
        )
    }

    /**
     * A refresh found nothing to change, so only the `(updated ...)` time moves.
     *
     * One row read, and no list and no log. Spec section 5.4 draws that time from the Source's
     * own `last_walked_at`, and a refresh moves it whether or not anything in the Source
     * changed: the Source really did catch up. Going through [reloadFromStorage] for it would
     * read every video of the Source and derive the whole Watch log again, every thirty
     * minutes, to change one string.
     */
    suspend fun refreshUpdatedAt() = marking.withLock {
        val current = mutableState.value as? SourceScreenState.Ready ?: return@withLock
        if (current.walking != null) return@withLock
        val stored = store.source(sourceId)?.takeIf { it.lastWalkedAt != null } ?: return@withLock
        if (stored.lastWalkedAt == current.updatedAt) return@withLock
        mutableState.value = current.copy(updatedAt = stored.lastWalkedAt)
    }

    /**
     * The household pressed `Order`, so this Source turns around.
     *
     * Two writes, in this order: the Source's own row, so the choice survives leaving the
     * Source and coming back, and then the list in memory. The videos are **not** read from
     * storage again. Reading them again would be a second SQLite pass over a few thousand
     * rows to reach a list this already holds, and it would be a different list if a walk had
     * landed in between.
     *
     * The list is turned by [orderByPublishDate] and never by `reversed()`. An undated video
     * goes last in both Directions, so reversing would carry the private and deleted videos
     * to the top.
     *
     * Where the highlight goes is the screen's, not this class's: the screen knows which card
     * the household is standing on and moves it to that same video's new index.
     *
     * @return the Direction the Source is now in, or null when there was nothing to turn.
     */
    suspend fun flip(): Direction? = marking.withLock {
        val current = mutableState.value as? SourceScreenState.Ready ?: return@withLock null
        val next = current.direction.flipped()
        store.setDirection(sourceId, next)
        val turned = orderByPublishDate(current.videos, next)
        mutableState.value = current.copy(videos = turned, direction = next)
        log(openingLine(next, turned, current.watches))
        next
    }

    /**
     * The household pressed the `Autoplay` chip, so this Source turns autoplay-next around.
     *
     * Two writes, in the same order and for the same reasons [flip] uses: the Source's own
     * row first, so the choice survives leaving the Source and a relaunch, and then the state
     * on screen. It takes the same lock as a mark and a flip, because it reads the screen
     * state and writes it back.
     *
     * @return what autoplay-next is now, or null when there was nothing on screen to change.
     */
    suspend fun toggleAutoplayNext(): Boolean? = marking.withLock {
        val current = mutableState.value as? SourceScreenState.Ready ?: return@withLock null
        val next = !current.autoplayNext
        store.setAutoplayNext(sourceId, next)
        mutableState.value = current.copy(autoplayNext = next)
        log("Autoplay-next on $sourceId is now ${if (next) "on" else "off"}.")
        next
    }

    /**
     * What the Source screen holds right now, for a caller that is not a composition.
     *
     * The Activity needs the list and the Source's own autoplay-next choice at the moment a
     * video ends, which is a callback from the player and nowhere near a composition. It is a
     * read of what is already in memory, so it costs no storage read and cannot block the
     * main thread.
     */
    fun ready(): SourceScreenState.Ready? = mutableState.value as? SourceScreenState.Ready

    /**
     * The household marked one video from its card menu.
     *
     * This is the `Just this` line of either submenu. It writes to storage and never touches
     * the network, so it works exactly the same with the television offline.
     */
    suspend fun markJustThis(video: StoredVideo, watched: Boolean) = marking.withLock {
        val state = watches.markJustThis(video, watched)
        if (state == null) {
            log("Nothing was marked: ${video.title} has no video id.")
            return@withLock
        }
        log("Marked ${video.videoId} as ${if (state.watched) "watched" else "not watched"}.")
        val current = mutableState.value as? SourceScreenState.Ready ?: return@withLock
        // Only this video's row changes, so the rest of the map is carried over rather than
        // read from storage again. The Newly listed flags are the one thing that is not one
        // row: `WatchLog.markJustThis` clears them in storage for this video and everything at
        // or past it, so this screen has to do the same in memory. See [clearedNewlyListed].
        mutableState.value = current.copy(
            watches = current.watches + (video.videoId to state),
            videos = if (state.watched) {
                clearedNewlyListed(current.videos, current.direction, video.videoPublishedAt)
            } else {
                current.videos
            },
        )
    }

    /**
     * A Bulk mark from the card menu: `This and all older`, or `This and all newer`.
     *
     * One log entry is written, however many videos it covers, so the cost of this call does
     * not grow with the size of the Source. The whole watch map is then worked out again,
     * because a range can change the state of any video in the Source and there is no cheaper
     * honest answer than looking at all of them. That pass is over the videos already in
     * memory, so it costs no storage read beyond the log itself.
     */
    suspend fun markRange(pivot: StoredVideo, side: RangeSide, watched: Boolean) =
        marking.withLock {
            val entry = watches.markRange(pivot, side, watched)
            if (entry == null) {
                log("Nothing was marked: ${pivot.title} cannot be the pivot of a range.")
                return@withLock
            }
            val current = mutableState.value as? SourceScreenState.Ready ?: return@withLock
            val states = watches.watchStates(current.videos)
            // Counted over this Source's own videos. The map is keyed by video id and holds
            // every video the log says anything about, in every Source, so counting the map
            // would print a number larger than the total beside it.
            val watchedHere = current.videos.count { states[it.videoId]?.watched == true }
            log(
                "Bulk marked ${side.stored} than ${pivot.videoId} as " +
                    "${if (watched) "watched" else "not watched"}: one log entry, " +
                    "$watchedHere of ${current.videos.size} listed videos now watched.",
            )
            mutableState.value = current.copy(watches = states)
        }

    /**
     * What the log says about one video, read from what the screen already holds.
     *
     * It is what the resume rule of spec section 8.6 is applied to, and it comes from memory
     * rather than from storage because a SQLite read on the main thread is a dropped frame
     * and the household presses the centre button on the main thread.
     */
    fun watchState(videoId: String): WatchState? =
        (mutableState.value as? SourceScreenState.Ready)?.watches?.get(videoId)

    /**
     * One video's row changed, so the card carrying it changes with it.
     *
     * A play calls this every time it writes its position down, so the green tick and the red
     * In progress bar are already right when the household presses `BACK`. Only this video's
     * row is replaced; the rest of the map is carried over rather than read again.
     */
    fun applyWatchState(state: WatchState) {
        val current = mutableState.value as? SourceScreenState.Ready ?: return
        // A play moves Last played, and it has to move now rather than at the next launch, or
        // `Jump to last played` would still point at the video before this one. The rule is
        // `lastPlayedVideoId`'s and is applied here rather than read from storage again: a
        // position means a real play, and a play of a video on this screen is this Source's.
        //
        // It is worked out in memory on purpose. This is called from the player's progress
        // ticks, on the main thread, and a SQLite read there is a dropped frame.
        val played = state.positionSeconds != null &&
            current.videos.any { it.videoId == state.videoId }
        // A play ends the Newly listed state of the video played and of everything at or past
        // it, and `WatchLog.startPlay` has already done that in storage. Doing it here too is
        // what keeps this screen and the home rail saying the same thing: the rail rebuilds
        // from storage, so without this the screen would go on offering a video the rail has
        // already dropped. Build ticket 40, and the `/code-review high` of 2026-09-21.
        val stillNewlyListed = if (played) {
            val publishedAt = current.videos.firstOrNull { it.videoId == state.videoId }
                ?.videoPublishedAt
            clearedNewlyListed(current.videos, current.direction, publishedAt)
        } else {
            current.videos
        }
        mutableState.value = current.copy(
            videos = stillNewlyListed,
            watches = current.watches + (state.videoId to state),
            lastPlayedVideoId = if (played) state.videoId else current.lastPlayedVideoId,
        )
    }

    /**
     * Throws the derived table away and builds it again from the log.
     *
     * ADR 0001 asks for a manual rebuild. There is no button for it, because spec section 7.2
     * fixes what the chip row holds and a rebuild is not household business. It is reached by
     * launching the app with the extra `rebuild_derived`:
     *
     * `adb shell am start -n local.youtubetv/.MainActivity --ez rebuild_derived true`
     */
    suspend fun rebuildDerived() {
        log(derivationLine(watches.rebuild("asked for by hand")))
        val current = mutableState.value as? SourceScreenState.Ready ?: return
        mutableState.value = current.copy(watches = watches.watchStates(current.videos))
    }

    /**
     * Says, in logcat, what is known about a marked video the grid does not draw.
     *
     * ADR 0001 copies a video's title and publish date the first time the household marks it,
     * so the history does not become a list of bare video ids once YouTube stops serving the
     * video. This is where that copy is read back. There is no screen for it: a history screen
     * is not in v1.
     *
     * **Ticket 23 made this line matter more.** It used to name only a video the cache had
     * dropped. A watched video YouTube has since made private is now unlisted as well, so it
     * leaves the grid while its row stays in the cache, and this is the only place its title
     * and date are still shown on the television. Whether that history should become visible
     * on a screen rather than in logcat is not settled; ticket 23's closing notes say so.
     */
    private fun reportKeptVideosMissingFromCache(cached: List<StoredVideo>) {
        val cachedIds = cached.mapTo(mutableSetOf()) { it.videoId }
        for (kept in watches.keptVideos()) {
            if (kept.videoId in cachedIds) continue
            log(keptVideoLine(kept))
        }
    }
}

/**
 * One logcat line saying which way round a Source is, and where its opening lands.
 *
 * Direction and Next unwatched are rules rather than pictures, and this is what proves them on
 * the television. Written on every launch and on every flip, so the two can be compared.
 */
private fun openingLine(
    direction: Direction,
    videos: List<StoredVideo>,
    watches: Map<String, WatchState>,
): String {
    val landing = nextUnwatchedLanding(
        videos = videos,
        watches = watches,
        direction = direction,
        newlyListed = { it.newlyListed },
    ) { it.canBeLandedOn }
    val mark = landing.highWaterMark
    // The mark is what makes the landing checkable from the sofa. Without it a reader cannot
    // tell the high-water rule from the fallback, and those two land in different places.
    val furthest = if (mark == null) {
        "nothing watched here yet"
    } else {
        "furthest watched is ${videos[mark].videoId} at index $mark"
    }
    val where = if (landing.index == null) {
        "no gap left, so it opens on its first card"
    } else {
        "Next unwatched is ${videos[landing.index].videoId} at index ${landing.index}, " +
            "with ${landing.count} still ahead"
    }
    return "Direction is ${direction.stored}: ${videos.size} listed videos, $furthest, $where."
}

/**
 * One logcat line saying whether the launch had to derive, and why.
 *
 * Shared with [HomeModel], because the home rail is now the first screen and whichever of the
 * two reads the log first is the one that pays for a rebuild.
 */
internal fun derivationLine(derivation: Derivation): String = when (derivation) {
    Derivation.UpToDate ->
        "Derived table is up to date at version $DERIVATION_VERSION; nothing was rebuilt."
    is Derivation.Rebuilt ->
        "Derived the Watch log again because ${derivation.reason}: " +
            "${derivation.entries} ${plural(derivation.entries, "entry", "entries")} read, " +
            "${derivation.rows} ${plural(derivation.rows, "video", "videos")} written."
}

private fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many

/** What is still known about a marked video the grid does not draw. */
private fun keptVideoLine(kept: KeptVideo): String =
    "Kept video ${kept.videoId}: \"${kept.title}\", published ${exactDate(kept.videoPublishedAt)}. " +
        "It has a Watch and is not on this Source's grid."


/** One sentence the household can act on. Never the raw exception, and never the key. */
private fun failureLine(failure: YouTubeApiException): String = when (failure.reason) {
    YouTubeApiException.Reason.NETWORK ->
        "No connection, and nothing stored yet for this Source."
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
