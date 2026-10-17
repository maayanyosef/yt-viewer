package local.youtubetv.refresh

import local.youtubetv.data.Availability
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.orderByPublishDate

/**
 * What a refresh did to one Source's cached videos.
 *
 * @property videos the whole list to store, already in publish-date order.
 * @property added the videos this refresh saw for the first time.
 * @property dropped the videos that vanished from YouTube and carried no Watch, so nothing was
 *   lost by forgetting them.
 * @property keptUnavailable the videos that vanished but carry a Watch, so their rows stay in
 *   storage. **Kept means kept in storage, not kept on the grid**, since ticket 23 made an
 *   unavailable video unlisted: the card goes and there is no marker. The row is what matters,
 *   because it is what lets a later full walk put the card back if YouTube serves the video
 *   again. Spec section 5.3 and ADR 0001.
 */
data class RefreshOutcome(
    val videos: List<StoredVideo>,
    val added: List<StoredVideo>,
    val dropped: List<StoredVideo>,
    val keptUnavailable: List<StoredVideo>,
) {
    /** True when this refresh changed nothing the household could see. */
    val isNoChange: Boolean
        get() = added.isEmpty() && dropped.isEmpty() && keptUnavailable.isEmpty()
}

/**
 * Folds a fresh listing into what is already stored for one Source.
 *
 * This is the whole of what a refresh decides, and it is a pure function so that every rule in
 * spec section 5.3 is a JVM unit test. Nothing here reads storage, the network or a clock.
 *
 * Five rules. The last four are each a criterion of ticket 13, and the first is build ticket
 * 40's:
 *
 * - **A video a walk has only just listed is flagged newly listed, and only when the Source
 *   already held videos.** Build ticket 40. See [StoredVideo.newlyListed] for what the flag
 *   means and [newlyListedOf] for the one test that decides it.
 * - **A video already known keeps its `isShort`.** Spec sections 4.3 and 5.2 both say it:
 *   `isShort` is classified once and never changes, and only newly seen video ids are
 *   classified at all. A refresh that re-classified would let a video the household has been
 *   watching disappear off the grid because one `videos.list` reply arrived without its embed
 *   size, which is exactly what a live stream's reply looks like.
 * - **Everything else about a known video is taken from the fresh listing.** The title, the
 *   thumbnail, the duration, the live state and the view count all change on their own, and a
 *   stream that was live an hour ago is a finished VOD now.
 * - **A vanished video with a Watch is kept and marked unavailable.** ADR 0001 makes the log
 *   the household's own record, and a grid that silently lost the video a Watch names would
 *   make that record unreadable.
 * - **A vanished video with no Watch is dropped.** Videos are cache. Nothing is lost.
 *
 * @param fullWalk true when [fresh] is the whole Source. **A partial re-list can never make a
 *   video vanish**, because it deliberately stopped at the first known id and has no opinion
 *   about anything older. Reading a partial listing as the whole truth would throw away every
 *   video but the newest page.
 * @param hasWatch whether the household's log says anything about this video id that is worth
 *   keeping the video for. See [videoHasWatch].
 */
fun mergeRefresh(
    stored: List<StoredVideo>,
    fresh: List<StoredVideo>,
    fullWalk: Boolean,
    hasWatch: (String) -> Boolean,
): RefreshOutcome {
    val storedById = stored.associateBy { it.videoId }
    val freshIds = fresh.mapTo(mutableSetOf()) { it.videoId }
    val newlyListed = newlyListedOf(stored)

    val added = mutableListOf<StoredVideo>()
    val merged = mutableListOf<StoredVideo>()

    for (video in fresh) {
        val before = storedById[video.videoId]
        if (before == null) {
            // A first listing. It is flagged only when this Source already held videos: see
            // [newlyListedOf].
            val arrived = video.copy(newlyListed = newlyListed)
            added += arrived
            merged += arrived
        } else {
            // Classified once, at the walk that first saw it, and never again. The newly
            // listed flag is carried forward in the same place and for the same reason: the
            // fresh listing comes from YouTube, which knows nothing about either fact, and
            // `SourceStore.replaceVideos` deletes every row of the Source and inserts them
            // again, so a value not carried through here is lost on this very walk.
            merged += video.copy(isShort = before.isShort, newlyListed = before.newlyListed)
        }
    }

    val dropped = mutableListOf<StoredVideo>()
    val keptUnavailable = mutableListOf<StoredVideo>()

    for (video in stored) {
        if (video.videoId in freshIds) continue
        if (!fullWalk) {
            // A partial re-list saw only the newest page. It says nothing about this video,
            // so nothing happens to it.
            merged += video
            continue
        }
        if (hasWatch(video.videoId)) {
            // **Counted only the first time.** A video that vanished a month ago is still
            // absent from every later listing, so counting it again would make every weekly
            // full walk report this Source as changed for ever: rewriting all its rows,
            // dropping its kept `SourceScreenModel` and rebuilding the home rail, with nothing
            // having happened. Found by `/code-review`.
            if (video.availability != Availability.UNAVAILABLE) keptUnavailable += video
            merged += video.copy(availability = Availability.UNAVAILABLE)
        } else {
            dropped += video
        }
    }

    return RefreshOutcome(
        // Ordered here, because this is the list the store is handed and the grid draws in
        // publish-date order. The fresh listing arrives in the playlist's own order, which
        // the owner chooses and which spec section 4.2 never displays.
        videos = orderByPublishDate(merged),
        added = added,
        dropped = dropped,
        keptUnavailable = keptUnavailable,
    )
}

/**
 * Whether a refresh changed what is stored for one Source. Build ticket 56.
 *
 * **The rows are compared and their order is not.** [stored] comes from
 * `SourceStore.allVideos`, which asks SQL for publish-date order. Until build ticket 57 SQL
 * promised no order for rows that tie on the publish time: two videos with exactly the same
 * publish time, or two undated ones. [merged] comes from [mergeRefresh], whose sort kept a tie
 * in the playlist's own order.
 *
 * Since build ticket 57 both sides break a tie by the video id, so the two lists arrive in one
 * order. The comparison still ignores order, so this answer does not lean on that agreement.
 *
 * On the emulator on 2026-10-03, with a copy of the household database, Android's SQLite
 * returned the household Source's two undated videos in the reverse of their rowid order. So
 * every full walk read the Source as changed with every row the same, and did all of this for
 * nothing:
 *
 * - Rewrote all 590 rows of the Source.
 * - Dropped the Source's kept `SourceScreenModel`.
 * - Built the home rail again.
 *
 * **Every field of every row is still compared**, through [StoredVideo]'s own equality. So a
 * known video that changed without being added or dropped is still a change, such as:
 *
 * - A finished premiere that stops being `upcoming`.
 * - An edited title.
 * - A private video that comes back.
 *
 * **Both lists are sorted by video id and compared whole**, so the answer needs no assumption
 * about repeated ids. A Source cannot hold one video id twice anyway: the table's primary key is
 * `(source_id, video_id)`, and `SourceWalk.walk` drops a repeated playlist entry. If it ever
 * did, two equal copies in a different order would read as changed, which only costs a rewrite.
 *
 * **A tie the playlist owner reorders is no longer a change**, and since build ticket 57 it
 * changes nothing on screen either: the order of a tie comes from the video ids, never from the
 * playlist or from the order the rows were written in.
 *
 * **[StoredVideo.viewCount] is the one field left out.** Build ticket 59. View counts move every
 * day, so a comparison that read them would find every Source changed on every full walk, and
 * all of the work above would come back. A count that moved on its own is found by
 * [viewCountChanges] and written with its own store call.
 */
fun storedVideosChanged(stored: List<StoredVideo>, merged: List<StoredVideo>): Boolean =
    stored.withoutViewCounts() != merged.withoutViewCounts()

/** Every video sorted by id, with its view count taken out, which is what [storedVideosChanged] compares. */
private fun List<StoredVideo>.withoutViewCounts(): List<StoredVideo> =
    map { it.copy(viewCount = null) }.sortedBy { it.videoId }

/**
 * The view counts a refresh has to write, by video id. Build ticket 59.
 *
 * One entry for each video of [merged] whose count differs from the one [stored] holds for it.
 * A video [stored] has no row for is left out, because a count is never a reason to add a row.
 * A count that went from a number to nothing is in the map as null, because null is what YouTube
 * last said. That is the same rule as every other field a walk reads.
 *
 * **Only a refresh that found the Source unchanged needs this.** A changed Source is rewritten
 * whole by `SourceStore.replaceVideos`, which writes the new counts with every other field.
 */
fun viewCountChanges(stored: List<StoredVideo>, merged: List<StoredVideo>): Map<String, Long?> {
    val storedCounts = stored.associate { it.videoId to it.viewCount }
    return merged
        .filter { it.videoId in storedCounts && storedCounts[it.videoId] != it.viewCount }
        .associate { it.videoId to it.viewCount }
}

/**
 * Whether a video this walk lists for the first time counts as **Newly listed**.
 *
 * One test, and it is whether the Source already held any video at all. Build ticket 40.
 *
 * It says no to three cases at once, and each of them would otherwise flag a whole Source:
 *
 * - The **first walk of a newly added Source**. Every video of it is a first listing, and none
 *   of them is news to a household that has just chosen the Source.
 * - The **first walk after a Source was removed and added again**. Removing a Source deletes
 *   its cached videos, so the walk that follows sees the whole Source as new. The Watch log is
 *   untouched and the household's history is still there, so flagging everything would offer a
 *   video they watched last year.
 * - The **first walk after the video tables are rebuilt**, for whatever reason. The same shape
 *   as the case above.
 *
 * **It is pure and it needs no extra stored state**, which is why this test and not a date or a
 * counter. `stored` is what `SourceStore.allVideos` returned, Shorts and unavailable videos
 * included, so a Source whose every listed video is hidden still reads as non-empty and cannot
 * be flagged whole by accident.
 */
private fun newlyListedOf(stored: List<StoredVideo>): Boolean = stored.isNotEmpty()

/**
 * Where the highlight belongs after a refresh changed the list under it.
 *
 * Spec section 5.3: the focused video keeps focus, so the list moves around it. A refresh that
 * inserts a newer video puts every later video one place along, and an index left alone would
 * quietly name a different video from the one the household is looking at.
 *
 * **The focused video can be the one that vanished**, and then there is no same video to go
 * to. The highlight stays on the index it is on, clamped to the end of the shorter list, which
 * puts it on the card that took the vanished one's place. Leaving the index alone was worse: it
 * would name a video past the end of the list, and both the focused-title line and the play
 * action read the list at that index. Found by `/code-review`.
 *
 * **Nothing selected is one of the null cases**, and that is the right answer rather than an
 * accident. A chip holds the highlight, `focusedIndex` is `NO_SELECTION`, and there is no card
 * to keep. A refresh is not something the household did, so it may not take the highlight off
 * the chip row. See `ui/SourceSelection.kt` and ticket 16.
 *
 * @return where the highlight belongs now, or null when nothing should move: the list did not
 *   move under it, or nothing had focus at all.
 */
fun refocusIndexAfterRefresh(
    before: List<StoredVideo>,
    after: List<StoredVideo>,
    focusedIndex: Int,
): Int? {
    if (after.isEmpty()) return null
    val videoId = before.getOrNull(focusedIndex)?.videoId ?: return null
    val to = after.indexOfFirst { it.videoId == videoId }
        .takeIf { it >= 0 }
        ?: focusedIndex.coerceAtMost(after.lastIndex)
    if (to == focusedIndex) return null
    return to
}

/**
 * What a refresh does to the highlight on an open Source screen. Build ticket 55.
 *
 * Spec section 5.3, amended 2026-10-03: the focused video keeps focus, so the list moves around
 * it, **unless the household has pressed no key since the screen landed**. Then nobody chose the
 * video the highlight is on. The screen chose it, from a list that was older than the one it now
 * has, and the highlight goes to the landing the screen would choose if it opened now.
 *
 * The owner reported it on 2026-10-03: "when I open a source [...] the latest video does not show up
 * the first time." The screen had landed on the old Next unwatched before the new video was
 * stored, and the old rule then kept the highlight there, about 157 cards down the grid.
 */
sealed interface HighlightAfterRefresh {

    /**
     * The highlight stays on the video it was on, which is now at [index].
     *
     * Nothing scrolls and nothing asks for focus. The grid is keyed by video id, so the card
     * holding the highlight keeps its node by itself. Only the screen's own index moves.
     */
    data class Follow(val index: Int) : HighlightAfterRefresh

    /**
     * The highlight moves to the screen's new landing, at [index], exactly as an opening does:
     * the grid scrolls there and the card takes focus.
     */
    data class Land(val index: Int) : HighlightAfterRefresh
}

/**
 * Where the highlight goes when a refresh changes the list under an open Source screen.
 *
 * - **A key was pressed since the screen landed:** [refocusIndexAfterRefresh], unchanged. A
 *   highlight the household moved is never taken away from them. That includes a press that
 *   moved nothing, because the household was looking at the screen when they pressed it.
 * - **No key was pressed, and the new landing is a different video:** [HighlightAfterRefresh.Land]
 *   on the new landing.
 * - **No key was pressed, and the new landing is the same video:** it is followed, because
 *   following and landing put the highlight on the same card and following does not scroll.
 *
 * **Nothing happens before the screen has landed.** An empty [before] means the grid was not
 * drawn at all, and the grid lands by itself when it first appears, on the landing worked out
 * from the list it appears with.
 *
 * @param before the list the grid was drawing.
 * @param after the list the refresh left.
 * @param focusedIndex where the screen says the highlight is, or `NO_SELECTION`.
 * @param keyPressed whether the household has pressed any key since the grid made its opening landing.
 * @param landingAfter what `openingLanding` in `data/Landings.kt` answers over [after], with the
 *   landing request the screen was opened with.
 * @return what to do, or null when nothing should move.
 */
fun highlightAfterRefresh(
    before: List<StoredVideo>,
    after: List<StoredVideo>,
    focusedIndex: Int,
    keyPressed: Boolean,
    landingAfter: Int,
): HighlightAfterRefresh? {
    // **An unchanged list never lands, because an unchanged list is not a refresh.** The
    // screen's refresh effect also runs once when the screen is first composed, with the same
    // list on both sides, while the grid is making its own opening landing. A second landing
    // on the same card there started a second retry ladder. `RefreshLandingTest` on
    // `emulator-5554` caught a `Right` pressed while that ladder waited between rungs, and the
    // ladder took the highlight back. The ladder now stops at any key, which is the real fix
    // and lives in `VideoGrid` in `ui/SourceScreen.kt`. This test keeps the screen from
    // landing twice for nothing.
    //
    // **A list whose view counts alone moved is an unchanged list here too.** Build ticket 59.
    // A count-only update reloads an open screen so its cards show the new counts, and that
    // reload must never move the highlight. Found by `/code-review`.
    if (!keyPressed && before.isNotEmpty() && !sameVideosApartFromViewCounts(before, after)) {
        val landingVideo = after.getOrNull(landingAfter)?.videoId
        val highlighted = before.getOrNull(focusedIndex)?.videoId
        if (landingVideo != null && landingVideo != highlighted) {
            return HighlightAfterRefresh.Land(landingAfter)
        }
    }
    return refocusIndexAfterRefresh(before, after, focusedIndex)?.let { HighlightAfterRefresh.Follow(it) }
}

/**
 * Whether two lists hold the same videos in the same order, with every field the same apart from
 * the view count. Build ticket 59. Order matters here, unlike in [storedVideosChanged], because
 * this is about where the cards stand on the screen.
 */
private fun sameVideosApartFromViewCounts(before: List<StoredVideo>, after: List<StoredVideo>): Boolean =
    before.size == after.size &&
        before.indices.all { before[it] == after[it] || before[it].copy(viewCount = null) == after[it].copy(viewCount = null) }
