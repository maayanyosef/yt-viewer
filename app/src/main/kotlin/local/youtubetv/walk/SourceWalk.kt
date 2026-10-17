package local.youtubetv.walk

import java.time.Instant
import local.youtubetv.data.Availability
import local.youtubetv.data.LiveBroadcastContent
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.orderByPublishDate
import local.youtubetv.youtube.PlaylistItemRef
import local.youtubetv.youtube.VideoDetails
import local.youtubetv.youtube.YouTubeApi
import local.youtubetv.youtube.YouTubeApiException

/** `videos.list` takes at most 50 ids per call, and each call costs 1 unit. */
private const val ENRICHMENT_BATCH = 50

/**
 * How many playlist pages a re-list reads before it gives up on meeting a known video id.
 *
 * Spec section 5.2 asks for page 1. This allows four, because a household that was offline for
 * a month meets a channel with more than fifty new videos, and stopping at one page would leave
 * the rest of them invisible until the next weekly full walk. Four pages is 200 videos and costs
 * four units, against `2 * ceil(N / 50)` for the whole Source.
 */
private const val RELIST_MAX_PAGES = 4

/**
 * Walks one Source end to end and returns every video in it.
 *
 * The walk is three steps:
 *
 * 1. Page `playlistItems.list` to get every video id and its `videoPublishedAt`.
 * 2. Page `videos.list` in batches of 50 to get title, length, live state and embed size.
 * 3. For a channel Source only, page the auto-generated `UUSH` Shorts playlist.
 *
 * Nothing here touches the network directly. [api] is the seam, so every rule below is
 * unit-tested with a fake and costs no quota.
 */
class SourceWalk(private val api: YouTubeApi) {

    /**
     * @param onProgress called as pages arrive, so the screen can show the quiet
     *   `loading 400 of 3,000` line while the walk is still running. `total` is null until
     *   YouTube reports one.
     * @param onVideos called after each batch of 50 is enriched, with **every video finished
     *   so far, already in publish-date order**. Spec section 6.5 wants the videos themselves
     *   to appear as pages arrive, and this is what makes that possible.
     *
     *   It hands back the whole list and not the batch, on purpose. The grid orders by publish
     *   date and the batches arrive in the playlist's own order, so a caller given batches
     *   would have to merge and re-sort them itself, in a screen, on every batch. Sorting a
     *   few thousand rows is cheap and doing it here means the rule lives in one place.
     */
    suspend fun walk(
        playlistId: String,
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
        onVideos: (List<StoredVideo>) -> Unit = {},
    ): WalkResult {
        val refs = mutableListOf<PlaylistItemRef>()
        var playlistPages = 0
        var pageToken: String? = null
        var total: Int? = null

        do {
            val page = api.playlistItems(playlistId, pageToken)
            playlistPages++
            refs += page.items
            total = page.totalResults ?: total
            onProgress(refs.size, total)
            pageToken = page.nextPageToken
        } while (pageToken != null)

        // A curated playlist may hold the same video more than once, and the owner decides
        // that. The first entry wins, because it is the one the playlist shows first. Without
        // this, one video id would become two rows with the same primary key.
        val uniqueRefs = refs.distinctBy { it.videoId }

        val shorts = walkShortsPlaylist(playlistId)

        val details = mutableMapOf<String, VideoDetails>()
        var videoPages = 0
        // Every ref enriched so far, in the playlist's own order. A ref is turned into a
        // StoredVideo once, here, rather than once per batch plus once at the end.
        val finished = mutableListOf<StoredVideo>()
        for (batch in uniqueRefs.chunked(ENRICHMENT_BATCH)) {
            for (video in api.videos(batch.map { it.videoId })) {
                details[video.videoId] = video
            }
            videoPages++
            batch.mapTo(finished) { ref ->
                toStoredVideo(playlistId, ref, details[ref.videoId], shorts)
            }
            // Ordered before it is handed over, because that is the order the grid draws in.
            // A caller handed the playlist's own order would show the household a grid that
            // reshuffles itself every second.
            onVideos(orderByPublishDate(finished))
        }

        return WalkResult(
            videos = orderByPublishDate(finished),
            playlistEntries = refs.size,
            apiCalls = ApiCallCount(
                playlistPages = playlistPages,
                videoPages = videoPages,
                shortsPages = shorts.pagesRead,
            ),
        )
    }

    /**
     * Re-lists only the newest page or pages of a channel Source, stopping at the first known id.
     *
     * Spec section 5.2: a channel Source re-lists page 1 of its uploads playlist and stops at
     * the first video it already knows. A channel's uploads playlist is newest-first and
     * nothing is ever inserted into the middle of it, so everything new is at the front and
     * the rest of the playlist cannot have changed.
     *
     * **This is never used for a curated playlist.** The owner chooses that order, so a new
     * item can sit on any page and only a full [walk] can find it. Spec section 5.2 says so and
     * `SourceRefresh` is what picks between the two.
     *
     * The cost is one `playlistItems.list` page, one `videos.list` page for the new ids, and
     * the `UUSH` pages the Shorts rule needs, against `2 * ceil(N / 50)` for a full walk.
     *
     * @param knownIds every video id already stored for this Source. An empty set makes this a
     *   full walk of the first page and nothing more, which is the right answer for a Source
     *   with nothing stored.
     * @param maxPages how many playlist pages to read before giving up on meeting a known id.
     *   More than one only matters when a channel posted over fifty videos since the last
     *   check, which is a state this app reaches after a long time offline.
     */
    suspend fun relistNewest(
        playlistId: String,
        knownIds: Set<String>,
        maxPages: Int = RELIST_MAX_PAGES,
    ): WalkResult {
        val refs = mutableListOf<PlaylistItemRef>()
        var playlistPages = 0
        var pageToken: String? = null
        var metKnown = false

        while (playlistPages < maxPages) {
            val page = api.playlistItems(playlistId, pageToken)
            playlistPages++
            for (item in page.items) {
                if (item.videoId in knownIds) {
                    metKnown = true
                    break
                }
                refs += item
            }
            pageToken = page.nextPageToken
            if (metKnown || pageToken == null) break
        }

        val newRefs = refs.distinctBy { it.videoId }.filter { it.videoId !in knownIds }
        if (newRefs.isEmpty()) {
            return WalkResult(
                videos = emptyList(),
                playlistEntries = refs.size,
                apiCalls = ApiCallCount(playlistPages = playlistPages, videoPages = 0, shortsPages = 0),
            )
        }

        // Only the new ids are classified, which spec section 5.2 asks for by name. The
        // `UUSH` playlist is paged only far enough back to cover the oldest of them, because
        // absence from it counts only once it has been paged past the video being classified.
        val shorts = walkShortsPlaylist(playlistId, backTo = newRefs.mapNotNull { it.videoPublishedAt }.minOrNull())

        val details = mutableMapOf<String, VideoDetails>()
        var videoPages = 0
        val finished = mutableListOf<StoredVideo>()
        for (batch in newRefs.chunked(ENRICHMENT_BATCH)) {
            for (video in api.videos(batch.map { it.videoId })) {
                details[video.videoId] = video
            }
            videoPages++
            batch.mapTo(finished) { ref -> toStoredVideo(playlistId, ref, details[ref.videoId], shorts) }
        }

        return WalkResult(
            videos = orderByPublishDate(finished),
            playlistEntries = refs.size,
            apiCalls = ApiCallCount(
                playlistPages = playlistPages,
                videoPages = videoPages,
                shortsPages = shorts.pagesRead,
            ),
        )
    }

    /**
     * Pages the `UUSH` Shorts playlist of a channel Source.
     *
     * A curated playlist has no such companion, so this returns "no opinion" and the walk
     * falls back on the embed-shape rule.
     *
     * @param backTo the oldest publish date being classified, or null to page the whole
     *   playlist. Spec section 4.3 makes absence authoritative only once `UUSH` has been paged
     *   back past the oldest video in question, and a re-list only ever asks about the newest
     *   handful, so one page almost always answers it.
     */
    private suspend fun walkShortsPlaylist(
        playlistId: String,
        backTo: Instant? = null,
    ): ShortsMembership {
        val shortsPlaylistId = shortsPlaylistIdFor(playlistId) ?: return ShortsMembership.noOpinion()

        val ids = mutableSetOf<String>()
        var pages = 0
        var pageToken: String? = null
        try {
            do {
                val page = api.playlistItems(shortsPlaylistId, pageToken)
                pages++
                ids += page.items.map { it.videoId }
                pageToken = page.nextPageToken
                // Paged back past the oldest video being classified, so absence now means
                // something. A full walk passes no date and reads the whole playlist.
                if (backTo != null && page.items.any { ref ->
                        ref.videoPublishedAt?.isBefore(backTo) == true
                    }
                ) {
                    break
                }
            } while (pageToken != null)
        } catch (failure: YouTubeApiException) {
            // `playlistNotFound` on a `UUSH` id means the channel has never posted a Short.
            // That is an answer, and an authoritative one: nothing in this Source is a Short.
            if (failure.reason == YouTubeApiException.Reason.PLAYLIST_NOT_FOUND) {
                return ShortsMembership(videoIds = emptySet(), authoritative = true, pagesRead = pages)
            }
            // Any other failure leaves the question open. Fall back on the embed-shape rule
            // rather than calling every video not-a-Short.
            return ShortsMembership(videoIds = emptySet(), authoritative = false, pagesRead = pages)
        }
        return ShortsMembership(videoIds = ids, authoritative = true, pagesRead = pages)
    }

    private fun toStoredVideo(
        playlistId: String,
        ref: PlaylistItemRef,
        details: VideoDetails?,
        shorts: ShortsMembership,
    ): StoredVideo {
        // Missing from `videos.list` means private or deleted. A missing `videoPublishedAt`
        // says the same thing. Either one is enough. See spec section 4.4.
        if (details == null || ref.videoPublishedAt == null) {
            return StoredVideo(
                videoId = ref.videoId,
                sourceId = playlistId,
                videoPublishedAt = ref.videoPublishedAt,
                title = details?.title.orEmpty(),
                thumbnailUrl = details?.thumbnailUrl,
                durationSeconds = details?.durationSeconds,
                liveBroadcastContent = details?.liveBroadcastContent ?: LiveBroadcastContent.NONE,
                liveEndedAt = details?.liveEndedAt,
                availability = Availability.UNAVAILABLE,
                viewCount = details?.viewCount,
                // **An unavailable video is never stored as a Short**, and since ticket 23 the
                // reason is no longer that it is shown with a marker. It is not shown at all.
                //
                // The reason now is that `isShort` is the one conclusion this app stores for
                // ever: spec section 4.3 classifies it once and never again. Writing true here,
                // on a video that merely has no `videos.list` reply, would hide it permanently,
                // and a later walk that found it public again could not undo that. The
                // `availability` field beside it is what YouTube last said, so a later walk
                // simply overwrites it and the card comes back. See `data/StoredVideo.kt`.
                isShort = false,
            )
        }

        return StoredVideo(
            videoId = ref.videoId,
            sourceId = playlistId,
            videoPublishedAt = ref.videoPublishedAt,
            title = details.title,
            thumbnailUrl = details.thumbnailUrl,
            durationSeconds = details.durationSeconds,
            liveBroadcastContent = details.liveBroadcastContent,
            liveEndedAt = details.liveEndedAt,
            availability = Availability.AVAILABLE,
            viewCount = details.viewCount,
            isShort = shorts.classify(ref.videoId) ?: isShort(
                embedWidth = details.embedWidth,
                embedHeight = details.embedHeight,
                durationSeconds = details.durationSeconds,
                publishedAt = ref.videoPublishedAt,
            ),
        )
    }
}

/** What one full walk produced. */
data class WalkResult(
    val videos: List<StoredVideo>,
    /**
     * How many entries the playlist held, before the same video listed twice was folded into
     * one. [videos] is the deduplicated list, so this is the larger of the two whenever a
     * playlist repeats a video. Only the cost line needs the difference.
     */
    val playlistEntries: Int,
    val apiCalls: ApiCallCount,
)

/**
 * What the walk spent, split by endpoint so the log line can be checked against the formula.
 *
 * A full walk of N videos costs `2 * ceil(N / 50)` units, plus one unit per `UUSH` page for a
 * channel Source. See spec section 4.1.
 */
data class ApiCallCount(
    val playlistPages: Int,
    val videoPages: Int,
    val shortsPages: Int,
) {
    val total: Int get() = playlistPages + videoPages + shortsPages
}

/**
 * The `UUSH` playlist's answer about which videos are Shorts.
 *
 * For a channel Source this membership is authoritative and overrides the embed-shape rule.
 * Absence only counts once the whole playlist has been paged, which a full walk always does.
 * See spec section 4.3.
 */
private data class ShortsMembership(
    val videoIds: Set<String>,
    val authoritative: Boolean,
    val pagesRead: Int,
) {
    /** Returns null when this playlist has no opinion, so the caller falls back on the rule. */
    fun classify(videoId: String): Boolean? = if (authoritative) videoId in videoIds else null

    companion object {
        fun noOpinion() = ShortsMembership(videoIds = emptySet(), authoritative = false, pagesRead = 0)
    }
}

/**
 * The auto-generated Shorts playlist that belongs to a channel's uploads playlist.
 *
 * A channel's uploads playlist is `UU<channel id without its UC prefix>`, and its Shorts
 * companion is `UUSH<the same suffix>`. A curated playlist starts with `PL` and has no
 * companion, so this returns null and the walk uses the embed-shape rule instead.
 */
fun shortsPlaylistIdFor(playlistId: String): String? = when {
    playlistId.startsWith("UUSH") -> null
    playlistId.startsWith("UU") -> "UUSH" + playlistId.removePrefix("UU")
    else -> null
}

/**
 * The channel a Source's uploads playlist belongs to, or null when the Source is not one.
 *
 * A channel's uploads playlist id is `UU` followed by the channel id without its `UC`, and both
 * ids are 24 characters. The length is checked rather than assumed, so `UUSH`, `UULF` and the
 * other auto-generated companions cannot be read as an uploads playlist.
 *
 * The change check of spec section 5.2 needs this, because a `UU...` id cannot be asked about
 * through `playlists.list`: YouTube does not always list one there. The channel's own
 * `statistics.videoCount` is the number that answers for it.
 */
fun channelIdForUploads(playlistId: String): String? {
    if (!playlistId.startsWith("UU")) return null
    if (playlistId.startsWith("UUSH")) return null
    val channelId = "UC" + playlistId.removePrefix("UU")
    return channelId.takeIf { it.length == CHANNEL_ID_LENGTH }
}

/** A YouTube channel id is always this long, and so is its uploads playlist id. */
private const val CHANNEL_ID_LENGTH = 24
