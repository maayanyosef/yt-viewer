package local.youtubetv.data

/**
 * Where the Source screen puts the highlight: Next unwatched, and Last played.
 *
 * Both are indexes into the videos the grid is showing, **in the Source's Direction**, because
 * a landing point has to be a card the grid actually draws. The list handed in is already in
 * that Direction, so nothing here has to sort.
 *
 * **[nextUnwatchedLanding] takes a Direction all the same, and that changed on 2026-09-21 with
 * build ticket 40.** Until then nothing in this file did, because the order was all a rule
 * needed. Ticket 40 added one exception that is switched on in a **newest-first** Source and
 * off in an oldest-first one, so that function has to know which of the two it was handed.
 * Knowing the order is not the same as knowing the Direction: both lists are in Direction, and
 * the exception is right for only one of them. See [nextUnwatchedLanding], which says why the
 * exception is not written as a test on position instead.
 *
 * These are plain functions over a list and a map, with no storage and no Compose in them, so
 * every rule is a JVM unit test. See spec section 7.2 and ADR 0001.
 */

/**
 * Whether the household can be sent to this video at all.
 *
 * **It is exactly [StoredVideo.isListed], and ticket 23 is why.** The same three facts make a
 * video a poor landing point and keep it off the grid: a Short, an unavailable video and an
 * upcoming premiere. A landing point must be a card the grid actually draws, so one rule now
 * answers both questions.
 *
 * It was not always one rule. Until 2026-09-20 an unavailable or upcoming video was drawn with
 * a badge and counted, and only a landing skipped it. That let the home rail card say
 * `2 UNWATCHED` beside `Nothing left in this Source`. Spec section 4.4 was amended to hide both
 * instead, and the two rules fell together. See `data/StoredVideo.kt`.
 *
 * **This name is kept even though it is now an alias**, because it says at the call site why
 * the test is being made: [nextPlayableIndex] is asking what it can play, not what it can draw.
 * If the two ever come apart again, this is where that is written down.
 */
val StoredVideo.canBeLandedOn: Boolean
    get() = isListed

/**
 * Where Next unwatched lands, and how many videos it can still take the household to.
 *
 * The two travel together because they must never disagree. Ticket 23 made the home rail's
 * badge and its Next unwatched slot agree by putting both over listed videos; build ticket 30
 * had to do it again, because the landing stopped being "the first gap" and a count of every
 * gap would have gone on describing the old rule. See [nextUnwatchedLanding].
 *
 * @property index where to land, in the list that was handed in, or null when the Source has
 *   no gap left at all.
 * @property count how many videos Next unwatched can still take the household to, under
 *   whichever of the two rules below is in force. Zero exactly when [index] is null, which is
 *   what the badge draws as `ALL WATCHED`.
 * @property highWaterMark the furthest the household has worked along this Source: the
 *   **highest** index in Direction carrying a Watch. Null when the Source has no Watch at all.
 *   It is here for the logcat line and the tests; nothing on screen draws it.
 */
data class NextUnwatchedLanding(val index: Int?, val count: Int, val highWaterMark: Int?)

/**
 * The first gap **after the furthest video the household has already watched**.
 *
 * "Gap" is a video with no Watch. **Any** Watch closes it, complete or not: a video the
 * household started and abandoned is not Next unwatched, and is reached through Last played
 * instead. See `CONTEXT.md` and ADR 0001.
 *
 * A Watch is household-level and is answered by video id alone, so a video watched in another
 * Source closes its gap here too. That is ADR 0001's rule and [watches] is keyed that way.
 *
 * **The high-water mark, and why it replaced "the first gap in the whole Source". Amended
 * 2026-09-21 by build ticket 30.** Until then this was the first gap anywhere in the Source, so
 * a gap the household had already worked past sent them backwards. On their own data that was
 * 135 videos back in one Source and 5,286 in another. The owner's words, 2026-09-20:
 *
 * > If I skip some videos, I do it on purpose - it means I am not interested in them, not that
 * > I overlooked them. So the next unwatched should be the video after the last watched video.
 *
 * So the rule is positional and it never goes backwards:
 *
 * 1. Find the **highest** index in Direction that carries a Watch. That is the high-water mark.
 * 2. Next unwatched is the first gap after it.
 * 3. **The fallback.** When nothing is left after the mark, and gaps remain above it, it is the
 *    first gap in the whole Source, which is exactly the old rule. The owner chose this over a dim
 *    chip: he does not want to be told there is nothing left while there is.
 *
 * A Source with **no Watch at all** keeps the old behaviour exactly, and needs no special case
 * to do it: there is no mark, so nothing is ever "before" it, and the first gap after nothing is
 * the first gap. `Lofi Girl` is that Source on the household set.
 *
 * **The count follows the landing, never the Source.** Under the mark it is the gaps after the
 * mark; under the fallback it is every gap. So the badge jumps once, upwards, at the moment the
 * household finishes the tail and the backlog they skipped becomes the only thing left. That is
 * honest and it was chosen: see the ticket.
 *
 * **Two sharp edges, both found by `/code-review` on build ticket 30 and both left in, because
 * the rule is the owner's.** They are in spec sections 3.2 and 7.1.
 *
 * - **The mark moves to wherever the household last watched, however far down Direction.** In a
 *   newest-first Source an older video is a **higher** index, so watching one old video moves the
 *   mark **forward** and puts every gap between the old mark and the new one behind the
 *   household. Ten seconds of one old video in a long Source can drop that card's count by several
 *   hundred. A `played` entry is what does it, so a real play and a manual `Mark as watched` on one
 *   card are the same thing here. Only `Mark as unwatched` on that video undoes it.
 * - **A newly published video gave a newest-first Source's card no signal.** It arrived above
 *   the mark, so neither the badge nor the card's picture and title moved. **This one is
 *   closed, by build ticket 40 on 2026-09-21**, and what closes it is the Newly listed
 *   exception below. It is written down here because the sharp edge above it is still real,
 *   and because the pair of them is what the spec sections name.
 *
 * **It is not `lastPlayedVideoId` and must never share code with it.** That one follows time and
 * moves backwards whenever the household jumps through the Period list and plays one old video.
 * This one follows position and cannot. The owner was shown "the video watched most recently in
 * time" as the alternative reading and rejected it.
 *
 * **The Newly listed exception. Added 2026-09-21 by build ticket 40.** In a **newest-first**
 * Source a video the app has only just listed is treated as if it sat immediately after the
 * high-water mark, **as the mark stood when the app listed it**. The household never had the
 * chance to skip such a video, so the rule above, which is about videos they did skip, must
 * not silence it. The owner's words, 2026-09-21:
 *
 * > In case the source is ordered newest first, the new video becomes the Next Unwatched.
 * > However, if AFTER it is listed I watch another video then the high-water mark moves to my
 * > last watched video and the new video is treated is intentionally skipped.
 *
 * Three steps, and only the second is new:
 *
 * 1. Find the mark over the list as it stands. A Newly listed video above the mark cannot
 *    affect this, because the mark is the highest index carrying a Watch.
 * 2. In a newest-first Source, move every Newly listed **gap** that sits above the mark so
 *    that it sits immediately after it, keeping their Direction order among themselves.
 * 3. Run the rule above over that order, unchanged, and answer with indexes into the list that
 *    was handed in, so no caller has to know about the reordering.
 *
 * Four things follow from that shape, and no extra code should try to produce any of them:
 *
 * - **The badge follows the landing**, because both still come out of this one call.
 * - **The fallback does not fire while a Newly listed video is waiting**, because that video
 *   is after the mark in the effective order, so something is ahead.
 * - **The mark never moves backwards.** Playing a video above the mark still does not move it.
 * - **A Newly listed video that a Bulk mark covers is never offered.** It is not a gap, so
 *   step 2 leaves it where it is. ADR 0001 says a range covers a video listed later with no
 *   second action, and this is that rule being obeyed rather than a case anybody wrote.
 *
 * **The exception is switched off by Direction and never by position**, which is the owner's own
 * decision of 2026-09-21. A test on position would offer a video published months ago and
 * listed today, which sits above the mark in an **oldest-first** Source as well, and his rule
 * for that Direction is that nothing changes. An oldest-first Source needs no exception at
 * all: `orderByPublishDate` puts the newest video **last** there, so a new video already
 * arrives after the mark and is an ordinary gap. Step 2 does nothing in that Direction.
 *
 * **One pass, because the rail asks this of every video of every Source at launch.** Walking
 * forwards, every gap seen so far is discarded the moment a Watch is met, because it is now
 * below the mark. What survives to the end is the first gap after the last Watch. The
 * reordering costs one more walk of the same list, and it moves nothing at all unless the
 * Source really holds a Newly listed gap above its mark.
 *
 * @param videos the videos the grid shows, already in the Source's Direction.
 * @param watches what the log says, by video id. A video that is missing from it has no Watch.
 * @param direction which way round the Source is. It decides nothing but whether the Newly
 *   listed exception runs. **It is required, and that changed on 2026-09-21 in the review of
 *   the review.** It used to default to newest first, on the argument that the default
 *   [newlyListed] says no and the pair is therefore harmless. The two defaults are
 *   independent, so nothing makes a caller take both. This is the **only** function in the
 *   app that lets a caller hand in the flag at all, so it is the only place where the
 *   exception can be switched on with the Direction left behind. That is exactly the trap
 *   that was taken out of [railCardCounts], [nextUnwatchedIndex] and [openingIndex] earlier
 *   the same day, and those three do not even take a [newlyListed] argument.
 * @param newlyListed whether a walk has only just listed this video. See
 *   [StoredVideo.newlyListed]. **It defaults to saying no**, which is the old rule exactly and
 *   is the safe direction: a caller that does not know about the flag offers less and never
 *   more, which is the same reading a rebuilt `videos` table gets.
 * @param canBeLandedOn whether this video counts at all, as a gap and as a mark alike. The rail
 *   hands in rows a narrow read has already reduced to what the Source lists, so it says `true`;
 *   a caller holding whole videos asks [StoredVideo.canBeLandedOn].
 */
fun <T : VideoKey> nextUnwatchedLanding(
    videos: List<T>,
    watches: Map<String, WatchState>,
    direction: Direction,
    newlyListed: (T) -> Boolean = { false },
    canBeLandedOn: (T) -> Boolean,
): NextUnwatchedLanding {
    var highWaterMark = -1
    var firstGapAfterTheMark: Int? = null
    var gapsAfterTheMark = 0
    var firstGapAnywhere: Int? = null
    var gapsAnywhere = 0
    for (index in effectiveOrder(videos, watches, direction, newlyListed, canBeLandedOn)) {
        val video = videos[index]
        if (watches[video.videoId]?.watched == true) {
            // Everything counted above is behind the household now, so it is thrown away
            // rather than remembered. This is the whole of "never backwards".
            highWaterMark = index
            firstGapAfterTheMark = null
            gapsAfterTheMark = 0
            continue
        }
        if (firstGapAfterTheMark == null) firstGapAfterTheMark = index
        gapsAfterTheMark++
        if (firstGapAnywhere == null) firstGapAnywhere = index
        gapsAnywhere++
    }
    val mark = highWaterMark.takeIf { it >= 0 }
    return when {
        firstGapAfterTheMark != null ->
            NextUnwatchedLanding(firstGapAfterTheMark, gapsAfterTheMark, mark)
        // The fallback. Nothing is ahead, so the skipped backlog is all that is left.
        firstGapAnywhere != null ->
            NextUnwatchedLanding(firstGapAnywhere, gapsAnywhere, mark)
        else -> NextUnwatchedLanding(index = null, count = 0, highWaterMark = mark)
    }
}

/**
 * The order [nextUnwatchedLanding] really reads, as indexes into the list it was handed.
 *
 * It is the list's own order, with the unplayable videos left out and with step 2 of the Newly
 * listed exception applied. Build ticket 40.
 *
 * **Only a Newly listed video that is a gap is moved.** A Newly listed video that already
 * carries a Watch, which is what an existing Bulk mark covering it looks like, stays exactly
 * where it is. Two reasons, and both matter:
 *
 * - It is not a gap, so no landing could offer it, and moving it would change no answer.
 * - Moving it **would** change one answer, and wrongly: the walk sets the high-water mark to
 *   the last Watch it meets, so a watched video moved below the mark would report a mark that
 *   had gone backwards.
 *
 * It returns the plain list of landable indexes whenever nothing has to move, which is every
 * Source the household holds today.
 */
private fun <T : VideoKey> effectiveOrder(
    videos: List<T>,
    watches: Map<String, WatchState>,
    direction: Direction,
    newlyListed: (T) -> Boolean,
    canBeLandedOn: (T) -> Boolean,
): List<Int> {
    val landable = videos.indices.filter { canBeLandedOn(videos[it]) }
    // The exception is switched off entirely in an oldest-first Source. See
    // [nextUnwatchedLanding] for why that is a Direction test and not a position test.
    if (direction != Direction.NEWEST_FIRST) return landable
    // No video carries the flag, so nothing can move and the two passes below are wasted.
    // This is the case for **every Source the household holds today**, and it is on the
    // launch frame: the rail asks this of every video of every Source, and `TED` alone is
    // 5,286 rows. One pass that stops at the first flag is cheaper than a `lastOrNull` over
    // the whole list plus a `filter` over it again. Raised by `/code-review high` on
    // 2026-09-21.
    if (videos.none(newlyListed)) return landable
    // Step 1, read off the list as it stands. Without "as the mark stood when the app listed
    // it" the rule would be circular: the mark would decide where the video sits, and the
    // video's position would then decide the mark.
    //
    // A Source with no Watch at all has no mark, so there is nothing to sit after and nothing
    // to move. Its first gap is already its first video. `Lofi Girl` is that Source on the
    // household set.
    val mark = landable.lastOrNull { watches[videos[it].videoId]?.watched == true }
        ?: return landable
    // A Newly listed video already **after** the mark is an ordinary gap and is left alone,
    // which is the `index < mark` test.
    val moved = landable.filter { index ->
        index < mark &&
            newlyListed(videos[index]) &&
            watches[videos[index].videoId]?.watched != true
    }
    // When nothing has to move, the walk reads exactly the list it read before this ticket.
    if (moved.isEmpty()) return landable
    val movedIndexes = moved.toHashSet()
    return buildList(landable.size) {
        for (index in landable) {
            if (index in movedIndexes) continue
            add(index)
            // Immediately after the mark, in the order they already had among themselves,
            // which is the Source's Direction.
            if (index == mark) addAll(moved)
        }
    }
}

/**
 * Where the `Next unwatched` chip lands, or null when the Source has no gap left.
 *
 * The whole rule is [nextUnwatchedLanding]. This is the half of its answer that the Source
 * screen needs, and it exists so that a caller wanting only the index does not have to know
 * about the count.
 *
 * @param videos the videos the grid shows, already in the Source's Direction.
 * @param watches what the log says, by video id. A video that is missing from it has no Watch.
 */
fun nextUnwatchedIndex(
    videos: List<StoredVideo>,
    watches: Map<String, WatchState>,
    direction: Direction,
): Int? =
    nextUnwatchedLanding(
        videos = videos,
        watches = watches,
        direction = direction,
        newlyListed = { it.newlyListed },
    ) { it.canBeLandedOn }.index

/**
 * Where the highlight starts when the household opens a Source.
 *
 * Its Next unwatched video, which follows the Source's Direction. A Source with no gap left
 * opens on its first card, because the household still has to arrive somewhere and the top of
 * the list is where the chips are.
 */
fun openingIndex(
    videos: List<StoredVideo>,
    watches: Map<String, WatchState>,
    direction: Direction,
): Int = nextUnwatchedIndex(videos, watches, direction) ?: 0

/**
 * Where a Source screen lands when it opens with the landing request [openAt].
 *
 * Which of the three landings this is was chosen on the home rail. Each of the other two falls
 * back to Next unwatched when it has nowhere to go, because the household still has to arrive
 * somewhere. The rail draws `Open at Last played` dim in exactly that case, so the fallback is
 * a belt to that brace.
 *
 * **It is asked twice, and both answers must be the same rule.** The screen asks it when its
 * grid first appears. Since build ticket 55 it asks again when a refresh lands before the
 * household has pressed a key, and moves the highlight to the new answer. See
 * `highlightAfterRefresh` in `refresh/RefreshMerge.kt`.
 *
 * @param videos the videos the grid shows, already in the Source's Direction.
 * @param lastPlayedVideoId what [lastPlayedVideoId] returned for this Source.
 */
fun openingLanding(
    openAt: Landing,
    videos: List<StoredVideo>,
    watches: Map<String, WatchState>,
    direction: Direction,
    lastPlayedVideoId: String?,
): Int = when (openAt) {
    Landing.NEXT_UNWATCHED -> openingIndex(videos, watches, direction)
    Landing.LAST_PLAYED ->
        lastPlayedIndex(videos, lastPlayedVideoId) ?: openingIndex(videos, watches, direction)
    Landing.NEWEST -> newestIndex(videos) ?: openingIndex(videos, watches, direction)
}

/**
 * Where `Jump to last played` lands, or null when it has nowhere to go.
 *
 * Null is what greys the chip out, and it covers more than spec section 7.2's "the Source has
 * no `played` entry":
 *
 * - the Source has no `played` entry that carries a position, which is what
 *   [lastPlayedVideoId] already answers with null;
 * - that video is not on the grid, because it is unlisted, or because a later walk dropped it
 *   from the Source altogether.
 *
 * The second case is the ticket's own rule: a landing point must be a card the grid actually
 * shows. A chip that took focus and moved nothing would be worse than a dim one.
 *
 * **A video the household played and YouTube has since made private now dims this chip**, and
 * that changed on 2026-09-20 with ticket 23. It used to land on it, on the argument that the
 * household really did play it and it was still where they left off. There is no card to land
 * on any more, so the argument no longer applies. Nothing about the record is lost: the Watch
 * log keeps every entry and the `kept_video` copy keeps the title and the publish date.
 *
 * @param videos the videos the grid shows, already in the Source's Direction.
 * @param lastPlayedVideoId what [lastPlayedVideoId] returned for this Source.
 */
fun lastPlayedIndex(videos: List<StoredVideo>, lastPlayedVideoId: String?): Int? {
    if (lastPlayedVideoId.isNullOrBlank()) return null
    val index = videos.indexOfFirst { it.videoId == lastPlayedVideoId && it.isListed }
    return if (index < 0) null else index
}

/**
 * The newest video the grid draws, or null when the Source lists no dated video.
 *
 * It is the third landing of the home rail's long-press menu, and it is the one that does not
 * follow Direction: the newest video is the first card in a newest-first Source and one of the
 * last in an oldest-first one. Finding it by date rather than by position is what makes it mean
 * the same thing in both.
 *
 * An unlisted video is skipped, because there is no card to move the highlight to. **That
 * changed on 2026-09-20 with ticket 23**, which stopped the grid drawing an unavailable or an
 * upcoming video: this used to land on an upcoming premiere, on the argument that the premiere
 * really is the newest thing in the Source.
 *
 * A video with no publish date is skipped, because a private or deleted video is not the newest
 * thing in a Source merely because YouTube stopped saying when it was published. Those sort
 * last in both Directions for the same reason. See `data/VideoOrder.kt`.
 */
fun newestIndex(videos: List<StoredVideo>): Int? {
    var newest = -1
    for ((index, video) in videos.withIndex()) {
        val publishedAt = video.videoPublishedAt ?: continue
        if (!video.isListed) continue
        if (newest < 0 || publishedAt.isAfter(videos[newest].videoPublishedAt)) newest = index
    }
    return if (newest < 0) null else newest
}

/**
 * Where a Source opens when the household chose it from the home rail's long-press menu.
 *
 * Spec section 7.1 puts all three on that menu. [NEXT_UNWATCHED] is also what a plain press on
 * a rail card means, and what spec section 7.2 makes the Source screen's own default.
 */
enum class Landing {
    /** The first gap after the high-water mark, in Direction. See [nextUnwatchedLanding]. */
    NEXT_UNWATCHED,

    /** The video the household most recently really played here. See [lastPlayedIndex]. */
    LAST_PLAYED,

    /** The newest video by publish date, whichever way round the Source is. See [newestIndex]. */
    NEWEST,
}

/**
 * Which way the player moves through the Source: to the next video, or back to the previous.
 *
 * Both are read in the Source's **Direction**, never in date order. In a newest-first Source
 * the next video is the older one, and in an oldest-first Source it is the newer one. The
 * list handed to [nextPlayableIndex] is already in that Direction, so the step is one index.
 *
 * @property offset how far along the list one press moves, before unplayable videos are
 *   skipped over.
 */
enum class PlayStep(val offset: Int) {
    NEXT(1),
    PREVIOUS(-1),
}

/**
 * The next video the player can actually play, in this step's direction, or null at the end.
 *
 * **There is no wrap-around.** Spec section 8.6 says so in as many words: the last video in
 * the Source ends with a message rather than starting the first one again. Null is what says
 * there is nowhere left to go, in both directions.
 *
 * Unplayable videos are stepped over rather than stopped on, and it is the same three facts
 * [canBeLandedOn] already names: a Short, an unavailable video and an upcoming premiere.
 * Spec section 8.6 asks for that of autoplay, and next and previous use the same rule on
 * purpose, because a next that stopped on a private video would answer the household's press
 * with player error 100.
 *
 * @param videos the videos the grid shows, already in the Source's Direction.
 * @param fromIndex where the player is now. Out of range answers null rather than guessing.
 */
fun nextPlayableIndex(videos: List<StoredVideo>, fromIndex: Int, step: PlayStep): Int? {
    if (fromIndex < 0 || fromIndex >= videos.size) return null
    var at = fromIndex + step.offset
    while (at >= 0 && at < videos.size) {
        if (videos[at].canBeLandedOn) return at
        at += step.offset
    }
    return null
}

/**
 * The same step, named by the video the player is on rather than by its index.
 *
 * The player knows which video it is showing; it does not hold an index into a list that a
 * mark or a walk could have changed underneath it. A video that is no longer in the list
 * answers null, which reads as the end of the Source and stops playback, rather than moving
 * the household somewhere they did not ask to go.
 */
fun nextPlayableVideo(
    videos: List<StoredVideo>,
    fromVideoId: String?,
    step: PlayStep,
): StoredVideo? {
    if (fromVideoId.isNullOrBlank()) return null
    val from = videos.indexOfFirst { it.videoId == fromVideoId }
    val to = nextPlayableIndex(videos, from, step) ?: return null
    return videos[to]
}
