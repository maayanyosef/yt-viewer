package local.youtubetv.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.youtubetv.data.Direction
import local.youtubetv.data.SourceCard
import local.youtubetv.data.SourceStore
import local.youtubetv.data.WatchLog
import local.youtubetv.data.everythingUnwatched
import local.youtubetv.data.railCard
import local.youtubetv.data.railCardCounts
import local.youtubetv.data.railOrder

/** What the home rail shows right now. */
sealed interface HomeScreenState {

    /** Before anything has been read, which lasts as long as one pass over storage. */
    data object Starting : HomeScreenState

    /**
     * The rail, already in its order: most recently played first. Spec section 7.1.
     *
     * An empty list is a household with no Source at all, which the first-run page of spec
     * section 6.6 fills. Once there is one Source, the rail replaces that page and the
     * `+ Add a Source` card is pinned at its end.
     */
    data class Ready(val cards: List<SourceCard>) : HomeScreenState
}

/**
 * The one thing the home screen draws under its header. Exactly one of the three, always.
 *
 * It is an enum and a pure function rather than a condition written inside the Composable,
 * because the condition written inside the Composable is what build ticket 36 was raised for.
 * It asked for [HomeScreenState.Ready] before it would draw the first-run page, so a rail that
 * was still reading storage fell through to the rail branch with no cards at all, and the
 * `+ Add a Source` card pinned at the rail's end became the whole screen. The owner saw that on the
 * household television on 2026-09-21 and read it as a household with no Source.
 */
enum class HomeBody {

    /** Storage is still being read. Card-sized placeholders and a line saying so. */
    LOADING,

    /** A household with no Source at all. Spec section 6.6's full-screen page. */
    FIRST_RUN,

    /** One or more Sources, with `+ Add a Source` pinned at the end. Spec section 7.1. */
    RAIL,
}

/**
 * Which of the three the home screen draws, for a given state.
 *
 * **[HomeBody.FIRST_RUN] is only ever reached from [HomeScreenState.Ready].** An empty card
 * list while the read is still running is not a household with no Source, it is a household
 * whose Sources have not been counted yet, and the two must never draw the same thing.
 */
fun homeBody(state: HomeScreenState): HomeBody = when (state) {
    HomeScreenState.Starting -> HomeBody.LOADING
    is HomeScreenState.Ready -> if (state.cards.isEmpty()) HomeBody.FIRST_RUN else HomeBody.RAIL
}

/**
 * Whether one of the home screen's overlays is drawn over the rail right now.
 *
 * **One fact, read in one place, because two things depend on it and they must never
 * disagree.** While the rail is covered:
 *
 * - every rail card swallows every key but `BACK`, so a centre press cannot open a Source from
 *   behind an open panel. That is ticket 09's rule, applied to this screen;
 * - the rail never scrolls itself to a card that takes the highlight. That is ticket 37's rule,
 *   and the reason is below.
 *
 * The cards stay `focusable()` while a panel is up, on purpose: `RailCard`'s `enabled` records
 * that taking them out of the focus order was tried and left the highlight nowhere. So a card
 * behind the scrim can still be handed the highlight by Compose, and one is. Choosing
 * `Remove this Source` swaps the menu for the confirmation from a different `when` branch, which
 * destroys the node holding the highlight before the confirmation's own `FocusRequester` ladder
 * has taken it. In that gap Compose hands focus to the first focusable it can find, which is the
 * rail's first card, and a `LazyRow` scrolls to show a card that takes focus. Measured on the
 * `googletv1080p` emulator on 2026-09-21: the first card reported focus with the rail covered,
 * gave it back 53 ms later, and the rail's first visible item was 0 at offset 0 a frame after
 * that. The household was left looking at a confirmation over a rail that had jumped home.
 *
 * The focus move itself is harmless, because nothing the household is standing on is changed by
 * it: a covered card's focus gain does not write `focusedSourceId`. Only the scroll is visible,
 * so only the scroll is stopped.
 *
 * **It is recorded all the same, and build ticket 41 is why.** `SourceRail`'s `highlightOn`
 * writes down what every card reports, covered or not, because it is the retry ladder's exit
 * test and the ladder's one honest question is where the highlight really is. The two answers
 * are different facts: what the household is standing on, and what holds the highlight right
 * now. An earlier version of this paragraph said the rail records nothing behind a scrim, which
 * stopped being true when the two were pulled apart.
 *
 * @param menuFor the Source id whose card menu is open, or null when none is.
 * @param confirmFor the Source id whose confirmation is open, or null when none is.
 */
fun railIsCovered(menuFor: String?, confirmFor: String?): Boolean =
    menuFor != null || confirmFor != null

/**
 * Which card the highlight goes to, given the cards the rail is holding right now.
 *
 * **One rule, read in two places, because they must never disagree.** The home screen asks it
 * twice for the same gesture:
 *
 * - **before** a panel over the rail leaves composition, to hand the highlight to a card while
 *   there is still something to hand it to. See build ticket 38;
 * - **after**, in `SourceRail`'s own retry ladder, which is what covers the cases the first
 *   ask cannot: a card the lazy row has not placed yet, and a card that has gone.
 *
 * **A named card that is no longer on the rail is not an error, it is the next preference's
 * turn.** That is what makes `Remove this Source` safe: the Source the highlight was on is
 * exactly the one that went, and a focus request naming a card the lazy row no longer holds
 * lands nowhere at all, so the rail would be drawn with no highlight and no key would move
 * one.
 *
 * @param preferred the cards to try, best first. Nulls are skipped, so a caller can pass a
 *   field that is not set yet without checking it.
 * @param cards every Source id the rail is holding, in the rail's own order.
 * @return the Source id to give the highlight to, or null when the rail holds no card at all.
 */
fun railHighlightTarget(preferred: List<String?>, cards: List<String>): String? =
    preferred.asSequence().filterNotNull().firstOrNull { it in cards } ?: cards.firstOrNull()

/**
 * Whether the rail has to scroll before a card can be given the highlight.
 *
 * **It has to scroll for one reason only: a card the lazy row has not placed does not exist
 * yet, and a thing that does not exist cannot take focus.** That is what `SourceRail`'s
 * `scrollToItem` was written for, and it was doing much more than that, because
 * `scrollToItem` **aligns**: it puts the card it names at the start of the viewport whether or
 * not the card was already on the screen. Closing a panel asks for a card that is on the
 * screen, so the rail was dragged to the left edge and then walked back by Compose's own
 * bring-into-view. Measured on the `googletv1080p` emulator on 2026-09-21: 480 px out and
 * about 290 ms back. The household set never showed it, because its rail was at its end, where
 * `scrollToItem` clamps to where it already was. Build ticket 38.
 *
 * A card that **is** placed needs no scroll at all. If it is partly off the screen, taking the
 * highlight brings it into view by itself, which is the rail's own walk and is the smallest
 * movement that works. See [NoScrollOnFocus] for who is allowed to do that and when.
 *
 * @param target the Source id the highlight is going to.
 * @param placed every Source id the lazy row is holding a composed card for, which is exactly
 *   the rail's focus requester map: a card registers when it is composed and takes itself out
 *   when it is dropped.
 */
fun railMustScrollTo(target: String, placed: Set<String>): Boolean = target !in placed

/**
 * Which rail card really holds the highlight, after one card reports its own focus changing.
 *
 * **This is the fact, and it is not the same thing as the card the app asked for.** Build
 * ticket 38 gave the home screen one field for both: `handHighlightBack` wrote the card it had
 * chosen into the very field the rail's retry ladder read as its exit test, so the ladder's
 * first rung always saw what it was waiting for. A `requestFocus` that quietly moved nothing
 * then left the rail with no highlight and no retry, which is the failure the ladder exists
 * for. Build ticket 41, found by build ticket 39.
 *
 * So the home screen now keeps two answers apart:
 *
 * - the card the app chose, which `handHighlightBack` and the rail's own asks write;
 * - the card that reports holding the highlight, which is this rule and which only a card's own
 *   focus report can write.
 *
 * **A card reporting that it lost the highlight only clears the answer when the answer is that
 * card.** Compose may report the gain before the loss when the highlight moves from one card to
 * the next, and a loss applied without that check would throw away the gain that had just
 * arrived, leaving the rail reading as if nothing held the highlight at all.
 *
 * **The rule itself is [highlightHolder] in `ui/HighlightRecord.kt`, and this is the home
 * rail's reading of it.** Build ticket 47 needed the same rule for the Source screen's grid
 * cards, its card menu lines, its Period jump list rows and its `Period` chip, so it moved the
 * three lines out rather than writing a fifth copy of a rule whose whole difficulty is the
 * middle branch.
 *
 * @param held the Source id of the card that held the highlight until now, or null when no card
 *   reported holding it.
 * @param reported the Source id of the card whose focus has just changed.
 * @param holdsIt what that card now reports: true when it has the highlight, false when it has
 *   lost it.
 */
fun railHighlightHolder(held: String?, reported: String, holdsIt: Boolean): String? =
    highlightHolder(held, reported, holdsIt)

/**
 * The rungs of a focus ladder: how long to wait before each ask, in milliseconds.
 *
 * The first is zero, so the first ask is immediate and an ask that works costs no delay at all.
 *
 * **240 is the last rung's own wait and is not when the ladder gives up.** A ladder that never
 * succeeds also waits [HIGHLIGHT_LADDER_SETTLE_MILLIS] after every ask, the last one included,
 * so it makes its fourth and last ask at about **468 ms** and returns at about **484 ms**. That
 * total is the one number every key guard in this app is really counting on, because a guard
 * that swallows keys while nothing holds the highlight swallows them for exactly that long.
 * `RailCard` is one of them, when it lets `BACK` through a covered card. Several comments said
 * 240 ms; `/code-review` on build ticket 47 caught it.
 *
 * **It is a `List` and not an `IntArray` because an array's elements can be written.** A shared
 * public `IntArray` is one assignment away from changing every ladder in the app for the rest of
 * the process, and a test reading the same object would not notice. Found by `/code-review` on
 * build ticket 41.
 */
val HIGHLIGHT_LADDER_WAITS: List<Int> = listOf(0, 60, 120, 240)

/**
 * One frame, which is how long a card is given to report back after it has been asked.
 *
 * Nothing about a focus request is answered inside the call that makes it, so the ladder waits
 * a frame and then reads the report.
 */
const val HIGHLIGHT_LADDER_SETTLE_MILLIS: Long = 16L

/**
 * Asks for the highlight until a card reports holding it, or until the rungs run out.
 *
 * **The whole point is that [holds] is a report and never the app's own request.**
 * `FocusRequester.requestFocus` throws when no node is attached to it and says nothing at all
 * about whether the highlight moved, so a card that is attached and quietly does not take the
 * highlight is invisible to the caller. The ladder exists for exactly that card, and it can only
 * see it by asking where the highlight actually is. Build ticket 38's own `/code-review` found
 * the first half of this and build ticket 41 the second.
 *
 * It is a suspend function with its waiting handed in rather than a loop written inside a
 * `LaunchedEffect`, so the JVM tests can run every rung without waiting for any of them.
 *
 * @param wait how the caller waits, in milliseconds. A real caller passes `delay`.
 * @param ask asks the card for the highlight. It may do nothing at all, and usually says nothing
 *   about which of the two it did.
 * @param holds whether a card now reports holding the highlight this ladder is asking for.
 *   [railHighlightHolder] is what keeps that answer true.
 * @param waits the rungs, in milliseconds before each ask.
 * @param settleMillis how long a card is given to report back after each ask.
 */
suspend fun askUntilHighlightHolds(
    wait: suspend (Long) -> Unit,
    ask: () -> Unit,
    holds: () -> Boolean,
    waits: List<Int> = HIGHLIGHT_LADDER_WAITS,
    settleMillis: Long = HIGHLIGHT_LADDER_SETTLE_MILLIS,
) {
    for (rung in waits) {
        if (rung > 0) wait(rung.toLong())
        ask()
        wait(settleMillis)
        if (holds()) return
    }
}

/**
 * Drives the home rail: read every stored Source, and say what each one's card should hold.
 *
 * It is the same shape as [SourceScreenModel], and for the same reason: no Android import, so
 * the whole flow is a JVM test with no device and no quota. [log] is where a real build passes
 * `Log.i`.
 *
 * **Nothing here walks a Source.** A walk costs quota, and the household did not ask for one by
 * arriving at the home screen. A Source is walked when it is opened, which is
 * [SourceScreenModel]'s job, and the rail shows what storage already holds.
 *
 * **Nothing is on the rail that the household did not add.** There was one hardcoded Source id
 * here until ticket 11, so that a fresh install had something to open at all. A fresh install
 * now opens on the first-run page instead, which is what spec section 6.6 asks for.
 */
class HomeModel(
    private val store: SourceStore,
    private val watches: WatchLog,
    private val log: (String) -> Unit = {},
) {

    private val mutableState = MutableStateFlow<HomeScreenState>(HomeScreenState.Starting)
    val state: StateFlow<HomeScreenState> = mutableState.asStateFlow()

    /**
     * Held for the whole of a `Mark everything unwatched`, for the same reason
     * [SourceScreenModel] holds one: the action writes to the log and then rebuilds the rail
     * from it, and two of those interleaving would leave the rail showing a state that never
     * existed.
     */
    private val marking = Mutex()

    /**
     * Builds the rail for the first time, off the main thread.
     *
     * The same shape as [SourceScreenModel.start] and for the same reason: `lifecycleScope` is
     * `Dispatchers.Main.immediate`, and everything [load] does is SQLite.
     *
     * @param rebuildDerived the launch asked for the derived table to be thrown away and built
     *   again from the log. See [EXTRA_REBUILD_DERIVED]. The rail is now the first screen, so
     *   this is where that request is honoured.
     */
    fun start(scope: CoroutineScope, rebuildDerived: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            if (rebuildDerived) log(derivationLine(watches.rebuild("asked for by hand")))
            load()
        }
    }

    /**
     * Builds the rail again, off the main thread.
     *
     * Called when the household comes back from a Source. Playing a video there is what moves
     * that Source to the front of the rail, and marking one is what changes its counts, so the
     * rail is stale the moment it is left.
     */
    fun reload(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { load() }
    }

    /**
     * Reads every stored Source and builds the rail.
     *
     * **Call it on a background thread.** All of it is SQLite: the Source rows, every listed
     * video of every Source, and the whole Watch log to derive from. On the household's two
     * Sources that is a few thousand rows, which is long enough to hold up the first frame.
     *
     * It is also what derives the log, because the rail is now the first screen and a derived
     * table that does not match this build would give every card a wrong count. That check is
     * the same one [SourceScreenModel.load] makes, and whichever runs first pays for it.
     */
    suspend fun load() = loading.withLock { load(readAgainIfACardIsStale = true) }

    /**
     * Held for the whole of one rail build, so two builds never overlap. Build ticket 55.
     *
     * Each build reads storage at its start and writes [state] at its end, so two overlapping
     * builds could finish in the wrong order and leave the older picture on screen. Since build
     * ticket 55 a refresh rebuilds the rail once for every Source it changes, while `BACK` out of
     * a Source rebuilds it too, so the overlap became likely. Found by its `/code-review`. One
     * after the other, the build that starts last also finishes last, and it read the newest
     * storage.
     *
     * It is never taken inside itself. The private `load` that a stale card reads again with is
     * already inside it, and [marking] is always taken outside it, never inside.
     */
    private val loading = Mutex()

    /**
     * @param readAgainIfACardIsStale a card whose Next unwatched video cannot be read back is
     *   built from a video list that storage has already moved on from, so the whole rail is
     *   read again, once. See [railStaleCardLine].
     */
    private suspend fun load(readAgainIfACardIsStale: Boolean) {
        val startedAt = System.nanoTime()
        log(derivationLine(watches.openAndDerive()))
        val derived = System.nanoTime()

        val stored = store.allSources()
        val sourcesRead = System.nanoTime()
        // The store's own order, which is oldest added first. It is only a tie-break: the
        // rail's one sort rule is most recently played first, and `railOrder` applies it
        // below.
        val sourceIds = stored.map { it.sourceId }
        val rows = stored.associateBy { it.sourceId }
        // Already in each Source's own Direction, because the store orders by the Source's row.
        //
        // **This is the launch read, and it is what made the first frame late.** It touches
        // every listed video of every Source to work out each card's counts, so its cost grows
        // with the whole library and not with what is on screen. Ticket 10 asked for it to be
        // measured rather than guessed at, so it measures itself: see [railReadLine].
        //
        // **It reads two columns a video and not ten.** Build ticket 22 measured where the
        // 1,327 ms went on the household television and found almost all of it in `Cursor`
        // accessors for fields no card draws. See [SourceStore.listedVideoKeys]. The one video
        // a card really names is read whole below, by id, and there is one of those per card.
        val videos = sourceIds.associateWith { store.listedVideoKeys(it) }
        val keysRead = System.nanoTime()
        // One read of the whole log for the whole rail, however many Sources it holds. Each
        // Source is still derived over its own videos: see `WatchLog.railWatches`.
        val rail = watches.railWatches(videos)
        val watchesRead = System.nanoTime()

        // The whole rows the cards name, read one at a time, and counted into the video read
        // rather than into the card building. Together with the key lists above they are every
        // video read the rail makes, which is what [railReadLine] promises its number covers.
        var namedVideoNanos = 0L
        var staleCard = false
        val cards = sourceIds.map { sourceId ->
            val counts = railCardCounts(
                listed = videos.getValue(sourceId),
                watches = rail.statesBySource[sourceId].orEmpty(),
                lastPlayedVideoId = rail.lastPlayedBySource[sourceId],
                // Read from the Source's own row, which is already in hand for `railCard`
                // below. It decides whether build ticket 40's Newly listed exception runs, and
                // nothing else. A Source with no row reads as newest first.
                direction = rows[sourceId]?.direction ?: Direction.NEWEST_FIRST,
            )
            // The one video the card names, and the only video of the Source that is read
            // whole. Its title, its date and its thumbnail are all the card draws.
            val namedAt = System.nanoTime()
            val named = counts.nextUnwatchedVideoId?.let { store.listedVideo(sourceId, it) }
            namedVideoNanos += System.nanoTime() - namedAt
            val card = railCard(
                sourceId = sourceId,
                stored = rows[sourceId],
                counts = counts,
                nextUnwatched = named,
            )
            // The counts named a video and the store no longer lists it, so a walk or a
            // refresh dropped it between the two reads. The card would draw a count beside
            // `Nothing left in this Source`, which is the contradiction ticket 23 removed from
            // the Source screen and spec sections 4.4 and 7.1 forbid. Found by `/code-review`
            // on build ticket 22.
            if (counts.nextUnwatchedVideoId != null && named == null) {
                log(railStaleCardLine(sourceId, counts.nextUnwatchedVideoId))
                staleCard = true
            }
            card
        }
        val ordered = railOrder(cards, rail.sourcesByLastPlay)
        val built = System.nanoTime()

        // Read again rather than drawn. One retry only: a second miss means the video really
        // is gone in a way re-reading will not mend, and a card the household can look at is
        // better than a rail that reads for ever.
        if (staleCard && readAgainIfACardIsStale) {
            load(readAgainIfACardIsStale = false)
            return
        }

        val readNanos = (keysRead - sourcesRead) + namedVideoNanos
        log(
            railLoadLine(
                RailLoadParts(
                    derive = (derived - startedAt) / 1_000_000,
                    sources = (sourcesRead - derived) / 1_000_000,
                    videos = readNanos / 1_000_000,
                    watches = (watchesRead - keysRead) / 1_000_000,
                    cards = (built - watchesRead - namedVideoNanos) / 1_000_000,
                ),
            ),
        )
        log(railReadLine(sourceIds.size, videos.values.sumOf { it.size }, readNanos / 1_000_000))
        log(railLogLine(ordered))
        mutableState.value = HomeScreenState.Ready(ordered)
    }

    /**
     * One Source was renamed, so its card says the new name in the next frame.
     *
     * **It patches one field of one card rather than reading the rail again**, and it is the one
     * thing in this class that does. Every other change on the rail can change a count, a Next
     * unwatched video or the rail's own order, and for those there is no honest answer cheaper
     * than reading storage again: [markEverythingUnwatched] says so in as many words. A rename
     * changes a **heading** and nothing else. No video moved, no Watch was written, and the
     * rail's sort rule is when a Source was last played, which a rename does not touch.
     *
     * The caller still reads the rail again afterwards. This exists only to close the window
     * between the write landing and that read finishing, which on the household's 6,904 videos
     * is over a second of the rail sitting there under the old name. Found by `/code-review`.
     *
     * It does nothing before the first read has finished, because there are no cards to patch
     * and the read that is running will bring the new name with it.
     *
     * @param typedName what `sources.typed_name` holds now, straight from the row, or null when
     *   the household cleared it and the card falls back to the name YouTube gave the Source.
     */
    fun renamed(sourceId: String, typedName: String?) {
        val current = mutableState.value as? HomeScreenState.Ready ?: return
        if (current.cards.none { it.sourceId == sourceId }) return
        mutableState.value = HomeScreenState.Ready(
            current.cards.map {
                if (it.sourceId == sourceId) it.copy(typedName = typedName) else it
            },
        )
    }

    /**
     * `Mark everything unwatched` on one Source, after the household confirmed it.
     *
     * One `range` entry is written, however many videos it covers, so the cost of this does not
     * grow with the size of the Source. It carries `clearsLastPlayed`, which is the flag's one
     * and only writer in the whole app: it says "I am starting this Source again", and
     * `lastPlayedVideoId` reads it by leaving the Source with no Last played until its next
     * positioned `played` entry.
     *
     * The pivot and the side come from [everythingUnwatched] together, because a side put the
     * wrong way round here is a button that writes an entry covering one video and looks to the
     * household as though it did nothing.
     *
     * @return how many listed videos the range reached, or null when nothing was written.
     */
    suspend fun markEverythingUnwatched(sourceId: String): Int? = marking.withLock {
        val videos = store.listedVideos(sourceId)
        val chosen = everythingUnwatched(videos)
        if (chosen == null) {
            log("Nothing was marked: $sourceId lists no video with a publish date to pivot on.")
            return@withLock null
        }
        val entry = watches.markRange(
            pivot = chosen.pivot,
            side = chosen.side,
            watched = false,
            clearsLastPlayed = true,
        )
        if (entry == null) {
            log("Nothing was marked: ${chosen.pivot.videoId} cannot be the pivot of a range.")
            return@withLock null
        }
        log(
            "Marked everything unwatched in $sourceId: one range entry, " +
                "pivot ${chosen.pivot.videoId} published ${chosen.pivot.videoPublishedAt}, " +
                "side ${chosen.side.stored}, covering ${chosen.count} listed videos. " +
                "Last played is cleared for this Source.",
        )
        // Read again rather than patched in memory. A range can change the state of any video
        // in the Source, and every card's count is worked out from those states, so there is no
        // cheaper honest answer than building the rail again.
        load()
        chosen.count
    }
}
