package local.youtubetv.data

/**
 * Everything one card on the home rail says, as pure functions over stored data.
 *
 * The home screen is one rail of Source cards, ordered most recently played first. A card
 * answers the one question the household asks from the sofa: how much of this Source is left,
 * and what would it play next. See spec section 7.1.
 *
 * Nothing here knows about Compose, storage or Android, so every rule below is a JVM unit
 * test. The wording is `ui/HomeScreenText.kt`, in the same way the Source screen keeps its
 * wording in `ui/SourceScreenText.kt`.
 *
 * **Nothing here says anything about a single video's progress.** Spec section 7.1 took the
 * `In progress` line and the bar off the rail card on 2026-09-19: inside a Source the household
 * looks at one video, and on the rail it is choosing a Source. That is why this file carries no
 * position and no fraction, and the type below has no field one could be drawn from.
 */

/**
 * One card of the home rail.
 *
 * @property sourceId the Source this card is about.
 * @property title what **YouTube** calls the Source, fetched once when it was added. It is null
 *   only for a Source stored by a build that had no add screen.
 * @property typedName what the **household** called it, or null when it has typed no name. It
 *   wins over [title] and it never replaces it, which is what lets clearing it bring YouTube's
 *   name back with no API call. Build ticket 27. `railCardName` is the one place the two are
 *   put in order.
 * @property direction the Source's own browse order, which the card states in words.
 * @property listedCount how many videos the Source lists. A hidden video is in no count on this
 *   card: a Short, an unavailable video and an upcoming premiere are all left out, first by
 *   [SourceStore.listedVideos] and again by [sourceCard] itself.
 * @property unwatchedCount how many videos [nextUnwatched] can still take the household to.
 *   This is the badge. It is **not** how many listed videos have no Watch, and it stopped being
 *   that on 2026-09-21 with build ticket 30: a gap the household skipped on purpose is behind
 *   the high-water mark, no landing offers it, so counting it would promise a video the card
 *   cannot reach. See [nextUnwatchedLanding].
 * @property nextUnwatched the video the Source would play next, or null when it has none left.
 * @property lastPlayedVideoId what `Jump to last played` would land on, or null when the menu
 *   line has nowhere to go and is drawn dim.
 * @property everythingUnwatchedCount how many listed videos `Mark everything unwatched` would
 *   reach, or null when it would reach none and the line is dim. It is on the card so that the
 *   menu can state its count without the screen ever holding a Source's videos. See
 *   [everythingUnwatched] for why the count is not simply [listedCount].
 * @property walked false for a Source that has never been walked, whose counts are all zero
 *   because nothing has been stored for it yet. A card must not read `ALL WATCHED` merely
 *   because it knows of no videos. **It is not the same as being on the rail**: a Source is on
 *   the rail from the moment it is added, and walked a few seconds later.
 */
data class SourceCard(
    val sourceId: String,
    val title: String?,
    val typedName: String? = null,
    val direction: Direction,
    val listedCount: Int,
    val unwatchedCount: Int,
    val nextUnwatched: StoredVideo?,
    val lastPlayedVideoId: String?,
    val everythingUnwatchedCount: Int?,
    val walked: Boolean,
)

/**
 * What one Source's card says.
 *
 * **[unwatchedCount] and [nextUnwatched] can no longer disagree.** Both are over listed videos,
 * and [StoredVideo.isListed] is the one rule that says which those are. Since build ticket 30
 * both also come out of one call to [nextUnwatchedLanding], so the badge counts exactly the
 * videos the slot can still reach.
 *
 * It was not always so. Until ticket 23 the count included an unavailable or an upcoming video
 * while [nextUnwatched] skipped both, and this comment argued that the two were "the honest
 * answer to two different questions". On the household television that read as a fault: the
 * 19-video Source said `2 UNWATCHED` beside `Nothing left in this Source`, and it could never
 * reach `ALL WATCHED`, because nothing could ever mark those two videos. Spec sections 4.4 and
 * 7.1 were amended and the two now agree by construction.
 *
 * The [isListed] test is repeated here rather than trusted to the caller, in the same way
 * [canBeLandedOn] repeats it. Every caller does hand in [SourceStore.listedVideos], so it
 * changes nothing today; it is what stops the count drifting back apart if one ever does not.
 *
 * **Nothing in the app calls this any more.** Build ticket 22 split it into [railCardCounts]
 * and [railCard], so that the rail can count a Source from two columns a video instead of ten,
 * and [HomeModel] calls those two. This is kept as the statement of the rule over whole
 * videos, and `RailCardCountsTest` asserts that the narrow pair and this one agree. Delete it
 * and that comparison goes with it.
 *
 * @param stored the Source's own row, or null when it has never been walked.
 * @param videos the Source's listed videos, already in its Direction.
 * @param watches what the log says, by video id.
 * @param lastPlayedVideoId what [lastPlayedVideoId] returned for this Source.
 */
fun sourceCard(
    sourceId: String,
    stored: StoredSource?,
    videos: List<StoredVideo>,
    watches: Map<String, WatchState>,
    lastPlayedVideoId: String?,
): SourceCard {
    val listed = videos.filter { it.isListed }
    val counts = railCardCounts(
        listed = listed,
        watches = watches,
        lastPlayedVideoId = lastPlayedVideoId,
        direction = stored?.direction ?: Direction.NEWEST_FIRST,
    )
    return railCard(
        sourceId = sourceId,
        stored = stored,
        counts = counts,
        nextUnwatched = listed.firstOrNull { it.videoId == counts.nextUnwatchedVideoId },
    )
}

/**
 * Everything a card says that is a number, worked out without reading a video whole.
 *
 * **This is the whole of what the home rail needs from a Source's few thousand videos**, and
 * it is why [VideoKey] exists. Four counts and one video id come out; the one video the card
 * actually names is fetched afterwards, by id, through [SourceStore.listedVideo].
 *
 * @property listedCount how many videos the Source lists. See [SourceCard.listedCount].
 * @property unwatchedCount how many of them [nextUnwatchedVideoId] can still take the household
 *   to. This is the badge. See [SourceCard.unwatchedCount].
 * @property nextUnwatchedVideoId the first gap after the high-water mark, in the Source's
 *   Direction, or null when the Source has none left. See [nextUnwatchedLanding].
 * @property lastPlayedVideoId what `Jump to last played` would land on, already tested against
 *   what the Source lists, or null when the menu line has nowhere to go.
 * @property everythingUnwatchedCount how many listed videos `Mark everything unwatched` would
 *   reach, or null when it would reach none.
 */
data class RailCardCounts(
    val listedCount: Int,
    val unwatchedCount: Int,
    val nextUnwatchedVideoId: String?,
    val lastPlayedVideoId: String?,
    val everythingUnwatchedCount: Int?,
)

/**
 * The numbers on one card, over that Source's listed videos.
 *
 * **Every video handed in is already listed**, which is what a [VideoKey] read promises: the
 * three conditions of [StoredVideo.isListed] are in the query. So there is no `isListed` test
 * here, and there cannot be one, because the fields it is made of are not on a [VideoKey].
 * [sourceCard] applies the filter itself before calling this, which is how a caller holding
 * whole videos keeps the same answer.
 *
 * @param listed the Source's listed videos, already in its Direction.
 * @param watches what the log says, by video id.
 * @param lastPlayedVideoId what [lastPlayedVideoId] returned for this Source, before it is
 *   tested against what the Source lists.
 * @param direction which way round the Source is. **It is here for one reason only**: build
 *   ticket 40's Newly listed exception is switched on in a newest-first Source and off in an
 *   oldest-first one, and [nextUnwatchedLanding] cannot tell the two apart from a list that is
 *   already in Direction. Nothing else on the card reads it, and the card's own
 *   [SourceCard.direction] comes from [railCard]. A Source with no stored row reads as newest
 *   first, which is what every other reader of that column answers.
 */
fun railCardCounts(
    listed: List<VideoKey>,
    watches: Map<String, WatchState>,
    lastPlayedVideoId: String?,
    direction: Direction,
): RailCardCounts {
    // A video with no publish date is private or deleted, so no `range` can cover it and
    // `Mark everything unwatched` cannot reach it. See [everythingUnwatched], which makes the
    // same test over whole videos.
    var dated = 0
    var lastPlayedIsListed = false
    for (video in listed) {
        if (video.videoPublishedAt != null) dated++
        if (video.videoId == lastPlayedVideoId) lastPlayedIsListed = true
    }
    // **The badge and the slot come out of one call, and that is the point.** Build ticket 30
    // made Next unwatched the first gap after the high-water mark, so a count of every gap
    // would still be describing the rule the landing no longer follows. Every row handed in is
    // already listed, which is what a narrow read promises, so every one of them can be landed
    // on and the test is `true`.
    //
    // **Do not write a second copy of the Newly listed rule here.** The badge, the slot and the
    // Source screen's own landing all come out of this one function, which is what keeps them
    // from disagreeing. Build ticket 40 only had to thread the Direction and the flag in.
    val landing = nextUnwatchedLanding(
        videos = listed,
        watches = watches,
        direction = direction,
        newlyListed = { it.newlyListed },
    ) { true }
    return RailCardCounts(
        listedCount = listed.size,
        unwatchedCount = landing.count,
        nextUnwatchedVideoId = landing.index?.let { listed[it].videoId },
        // A landing point has to be a card the Source screen actually draws, which is the same
        // rule the chip follows. See `lastPlayedIndex`, which makes the identical test.
        //
        // **The `isListed` half was missing until `/code-review` on ticket 23.** Without it the
        // rail menu could draw `Open at Last played` live while the Source screen's own chip
        // was dim, on a video the grid no longer shows.
        lastPlayedVideoId = lastPlayedVideoId?.takeIf { lastPlayedIsListed },
        everythingUnwatchedCount = dated.takeIf { it > 0 },
    )
}

/**
 * One card, from its numbers and the one video it names.
 *
 * @param nextUnwatched the video [RailCardCounts.nextUnwatchedVideoId] names, read whole
 *   because the card draws its title, its date and its thumbnail. Null when the Source has no
 *   gap left, and null as well when the id names a video the store no longer lists, which
 *   leaves the card reading `Nothing left in this Source`.
 */
fun railCard(
    sourceId: String,
    stored: StoredSource?,
    counts: RailCardCounts,
    nextUnwatched: StoredVideo?,
): SourceCard = SourceCard(
    sourceId = sourceId,
    title = stored?.title,
    typedName = stored?.typedName,
    direction = stored?.direction ?: Direction.NEWEST_FIRST,
    listedCount = counts.listedCount,
    unwatchedCount = counts.unwatchedCount,
    nextUnwatched = nextUnwatched,
    lastPlayedVideoId = counts.lastPlayedVideoId,
    everythingUnwatchedCount = counts.everythingUnwatchedCount,
    // The **walk**, not the row. A Source has a row from the moment the household adds
    // it, and its first walk finishes some seconds later. A card that read `ALL WATCHED`
    // in that window would be lying about a Source nobody has opened. See
    // [StoredSource.lastWalkedAt].
    walked = stored?.lastWalkedAt != null,
)

/**
 * The Sources the household has played, most recently played first.
 *
 * "Played" is a `played` entry that carries a position, which is the same rule
 * [lastPlayedVideoId] follows: a manual `Mark as watched` writes a `played` entry with no
 * position and is a declaration, not a play. A Bulk mark writes no `played` entry at all.
 *
 * **Newest means last in [inLogOrder] and nothing else.** An Android television can boot with a
 * wrong clock, which is why `seq` exists and why the entry's own `at` is never compared here.
 *
 * **A range that clears Last played does not move a Source on the rail.** `Mark everything
 * unwatched` says "I am starting this Source again"; it does not un-happen the evening the
 * household spent in it. The rail has one sort rule, which is when the household last played
 * something here, and it keeps it.
 *
 * @return the Source ids that have such an entry, most recent first. A Source with none is not
 *   in the list at all, and [railOrder] puts it after every Source that is.
 */
fun sourcesByLastPlay(entries: List<WatchEntry>): List<String> =
    LogReading.of(entries).sourcesByLastPlay

/**
 * The rail's one sort rule: most recently played first.
 *
 * A Source the household has never played keeps the place it arrived in, after every Source
 * that has been played. The sort is stable, so two never-played Sources never swap by
 * themselves between launches.
 *
 * **A finished Source is not moved.** Spec section 7.1 is explicit: the badge reading
 * `ALL WATCHED` is the whole of how a finished Source is shown, and the rail has one sort rule
 * and keeps it.
 *
 * @param playedOrder what [sourcesByLastPlay] returned.
 */
fun railOrder(cards: List<SourceCard>, playedOrder: List<String>): List<SourceCard> =
    inRailOrder(cards, playedOrder) { it.sourceId }

/**
 * The rail's one sort rule, for anything that names a Source and not only a card.
 *
 * [railOrder] is this applied to cards. A refresh run applies it to the stored Sources, so it
 * re-lists them in the order the household sees them, most recently played first. Build ticket
 * 55: the household Source is then the first one stored, and the first one the screens hear
 * about.
 *
 * @param items in the store's own order, which is oldest added first. That order is the
 *   tie-break, because the sort is stable.
 * @param playedOrder what [sourcesByLastPlay] returned.
 */
fun <T> inRailOrder(
    items: List<T>,
    playedOrder: List<String>,
    sourceIdOf: (T) -> String,
): List<T> {
    val rank = playedOrder.withIndex().associate { (index, sourceId) -> sourceId to index }
    return items.sortedBy { rank[sourceIdOf(it)] ?: Int.MAX_VALUE }
}

/**
 * The one `range` entry `Mark everything unwatched` writes, and the count it promises first.
 *
 * Spec section 7.1 calls it "one `range` pivoted at the start of time". In this log that is the
 * Source's **oldest** dated video with [RangeSide.NEWER], because `Range.covers` includes the
 * pivot instant and `NEWER` means "published on or after the pivot". Pivoting on the newest
 * video with `NEWER` would cover that one video and nothing else.
 *
 * The pivot and the side travel together for exactly that reason. Handing a caller a video and
 * letting it choose a side is how the two get put the wrong way round, and a wrong side here is
 * a silent no-op the household would read as a broken button.
 *
 * @property count how many listed videos the range really reaches, which is what the
 *   confirmation promises. Two kinds of video fall out of it, for two different reasons.
 *   - A video with **no publish date** is not counted, because no range can cover it. A private
 *     or deleted video has none. See spec section 4.4.
 *   - An **unlisted** video is not counted, because every count the household sees is over
 *     listed videos. An upcoming premiere is the case where this matters: it does carry a
 *     publish date, so the range really does cover it, and only the count leaves it out. That
 *     is the same way a hidden Short has always been treated. See spec section 4.3.
 */
data class EverythingUnwatched(val pivot: StoredVideo, val side: RangeSide, val count: Int)

/**
 * What `Mark everything unwatched` would write for this Source, or null when it can write
 * nothing.
 *
 * Null means the Source lists no dated video, so there is no pivot to put a range on. The menu
 * line is drawn dim in that case rather than acting and doing nothing.
 *
 * @param videos the Source's listed videos, in any order.
 */
fun everythingUnwatched(videos: List<StoredVideo>): EverythingUnwatched? {
    val dated = videos.filter { it.isListed && it.videoPublishedAt != null }
    val oldest = dated.minByOrNull { it.videoPublishedAt!! } ?: return null
    return EverythingUnwatched(pivot = oldest, side = RangeSide.NEWER, count = dated.size)
}

