package local.youtubetv.youtube

import java.time.Instant

/**
 * The slice of YouTube Data API v3 that walking a Source needs.
 *
 * This is the seam between the walk and the network. The walk is a plain algorithm and is
 * unit-tested against a fake implementation of this interface, so the rules that matter
 * (ordering, the Shorts rule, the call count) are proven without a device and without quota.
 *
 * Every endpoint here costs 1 unit per page from the 10,000-unit daily pool, **except
 * [search]**, which costs 1 call from its own bucket of 100 per day. That bucket is the
 * tightest limit in the whole project. Only [SourceSearch] calls [search], only the add
 * screen reaches [SourceSearch], and it is cached per session so one query costs one call
 * however many times it is asked for. See spec section 4.1.
 */
interface YouTubeApi {

    /**
     * One page of `playlistItems.list`, at most 50 items.
     *
     * Asks for `part=contentDetails` only. That part carries the video id and
     * `videoPublishedAt`, which is the date this app orders by. `snippet` is not requested,
     * because `snippet.publishedAt` is the date the video was added to the playlist and must
     * never be used for ordering, and every other `snippet` field arrives again from
     * [videos] anyway. See spec section 4.2.
     */
    suspend fun playlistItems(playlistId: String, pageToken: String? = null): PlaylistItemPage

    /**
     * `videos.list` for up to 50 ids, in one page and one unit.
     *
     * A video that is private or deleted is simply missing from the reply. The caller detects
     * that by comparing what came back with what was asked for. See spec section 4.4.
     */
    suspend fun videos(videoIds: List<String>): List<VideoDetails>

    /**
     * One channel by its `UC...` id, or null when YouTube knows of no such channel.
     *
     * `channels.list?id=`, 1 unit. Adding a Source is the only caller. See spec section 6.1.
     */
    suspend fun channelById(channelId: String): ChannelDetails?

    /**
     * One channel by its `@handle`, without the `@`, or null when there is no such handle.
     *
     * `channels.list?forHandle=`, 1 unit.
     */
    suspend fun channelByHandle(handle: String): ChannelDetails?

    /**
     * One channel by the legacy `/user/<name>` name, or null when there is no such name.
     *
     * `channels.list?forUsername=`, 1 unit. YouTube stopped handing these out years ago, but
     * the endpoint still answers for the channels that never took a handle.
     */
    suspend fun channelByUsername(username: String): ChannelDetails?

    /**
     * One playlist by its id, or null when YouTube knows of no such playlist.
     *
     * `playlists.list?id=`, 1 unit.
     *
     * **A channel's auto-generated uploads playlist can answer null here**, because YouTube
     * does not always list `UU...` ids through this endpoint. That is not an error and the
     * caller falls back on the channel. See [SourceResolver].
     */
    suspend fun playlistById(playlistId: String): PlaylistDetails?

    /**
     * A channel's public playlists, one page of at most 50.
     *
     * `playlists.list?channelId=`, 1 unit. Spec section 6.2 asks for exactly one call here:
     * picking a channel offers `All uploads` and then this list, and a household with more
     * than fifty public playlists is not a case worth a second unit.
     */
    suspend fun playlistsOfChannel(channelId: String): List<PlaylistDetails>

    /**
     * Channels and playlists YouTube thinks the household meant. Spec sections 6.1 and 6.2.
     *
     * **This is the one expensive call in the app.** `search.list` costs 1 call from a bucket
     * of 100 per day, which does not refill until midnight Pacific Time, and nothing else in
     * the app touches it. It is therefore driven by a submit and never by a keystroke, and
     * [SourceSearch] caches its answer for the whole session.
     *
     * What comes back is thin: `search.list` reports no video count, no `@handle` and no
     * uploads playlist id. [channelsByIds] and [playlistsByIds] fill those in for 1 unit each,
     * which is the "2 extra units for up to 50 results" of spec section 6.2.
     *
     * @param maxResults 25, from spec section 6.1. One page and one call.
     */
    suspend fun search(query: String, maxResults: Int = SEARCH_MAX_RESULTS): List<SearchHit>

    /**
     * Up to 50 channels in one `channels.list?id=` call and one unit.
     *
     * The single-channel lookups above are the add paths of ticket 11. This is the batch a
     * page of search results needs, and asking one at a time would cost up to 25 units for
     * one screen.
     */
    suspend fun channelsByIds(channelIds: List<String>): List<ChannelDetails>

    /** Up to 50 playlists in one `playlists.list?id=` call and one unit. */
    suspend fun playlistsByIds(playlistIds: List<String>): List<PlaylistDetails>
}

/** How many results one `search.list` call asks for. Spec section 6.1 fixes it at 25. */
const val SEARCH_MAX_RESULTS = 25

/**
 * One row of a `search.list` reply, before it has been priced.
 *
 * It is deliberately not a [SourceCandidate]. A `search.list` item carries no video count, no
 * `@handle` and, for a channel, no uploads playlist id, and a Source is stored under its
 * playlist id. So a channel hit cannot become a row the household can pick until
 * [YouTubeApi.channelsByIds] has answered. [SourceSearch] is what joins the two.
 *
 * @property id the `UC...` channel id or the playlist id, from `id.channelId` or
 *   `id.playlistId`. Which of the two it is, is [kind].
 * @property title `snippet.title`, with YouTube's HTML escaping already undone. `search.list`
 *   escapes `&`, `'` and quotes in this field and the other endpoints do not.
 * @property channelTitle `snippet.channelTitle`: who owns a playlist hit. It is the channel's
 *   own name on a channel hit, which the row does not use.
 * @property channelId `snippet.channelId`, which on a playlist hit says whose playlist it is.
 */
data class SearchHit(
    val kind: CandidateKind,
    val id: String,
    val title: String,
    val channelTitle: String?,
    val channelId: String?,
    val thumbnailUrl: String?,
)

/**
 * What `channels.list` says about one channel.
 *
 * @property handle the channel's `@handle`, from `snippet.customUrl`, including its `@`. It
 *   is null for a channel that never took one.
 * @property videoCount `statistics.videoCount`, which is the channel's own number and can
 *   differ from how many videos its uploads playlist lists. It is shown before the Source is
 *   added and never used as a count afterwards: every count the household sees after that is
 *   over what this app walked.
 * @property uploadsPlaylistId `contentDetails.relatedPlaylists.uploads`, which is what
 *   `All uploads` becomes. Spec section 6.2 takes it from here and never by substituting `UU`
 *   for the `UC` of the channel id.
 */
data class ChannelDetails(
    val channelId: String,
    val title: String,
    val handle: String?,
    val thumbnailUrl: String?,
    val videoCount: Int?,
    val uploadsPlaylistId: String?,
)

/**
 * What `playlists.list` says about one playlist.
 *
 * @property itemCount `contentDetails.itemCount`. It counts private and deleted entries too,
 *   so it is what the add screen promises and never what a walked Source reports.
 */
data class PlaylistDetails(
    val playlistId: String,
    val title: String,
    val channelId: String?,
    val channelTitle: String?,
    val thumbnailUrl: String?,
    val itemCount: Int?,
)

/** One page of a playlist listing. */
data class PlaylistItemPage(
    val items: List<PlaylistItemRef>,
    val nextPageToken: String?,
    /** `pageInfo.totalResults`, used only for the quiet `loading 400 of 3,000` line. */
    val totalResults: Int?,
)

/**
 * One entry of a playlist.
 *
 * [videoPublishedAt] is null for a private or deleted video. That absence is one of the two
 * signals for unavailable.
 */
data class PlaylistItemRef(
    val videoId: String,
    val videoPublishedAt: Instant?,
)

/**
 * What `videos.list` says about one video.
 *
 * Every field here is raw. Nothing is interpreted at this layer, because spec section 3.4
 * says to store raw fields only and decide everything else at query time.
 */
data class VideoDetails(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    /** `snippet.liveBroadcastContent`: `none`, `live` or `upcoming`. */
    val liveBroadcastContent: String,
    /** `liveStreamingDetails.actualEndTime`. Present only on a stream that has ended. */
    val liveEndedAt: Instant?,
    /**
     * `player.embedWidth` and `player.embedHeight`, from `part=...,player&maxHeight=720`.
     *
     * Both are null for a stream that is live right now and for a premiere that has not
     * started. Those do get a `player` object, but it carries `embedHtml` alone, with no
     * `embedWidth` and no `embedHeight`. So a Short can never be classified from either of
     * them. Confirmed against real API replies on 2026-09-19.
     */
    val embedWidth: Int?,
    val embedHeight: Int?,
    /**
     * `snippet.channelId`: which channel posted the video.
     *
     * The walk has no use for it. It is read so that a pasted `watch?v=` URL can offer the
     * video's channel as a Source, which spec section 6.2 asks for, without a second lookup
     * to find out whose video it is.
     */
    val channelId: String? = null,
    /**
     * `statistics.viewCount`: how many times anyone has viewed this video on YouTube. Build
     * ticket 59. YouTube sends it as a decimal string.
     *
     * **Null means YouTube gave no count, and it is never read as zero.** An upcoming premiere
     * has none, and a video whose owner hides it may have none.
     */
    val viewCount: Long? = null,
)

/** Every failure this app maps by hand, so the screen can say something true. */
class YouTubeApiException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /** The playlist id does not exist. On a `UUSH` id this means the channel has no Shorts. */
        PLAYLIST_NOT_FOUND,

        /** `403 quotaExceeded`. Refresh stops until midnight Pacific Time. No retry loop. */
        QUOTA_EXCEEDED,

        /** Any other `403`, most likely the API key being wrong or restricted. */
        FORBIDDEN,

        /** The request never reached YouTube. */
        NETWORK,

        /** A reply arrived but did not parse. */
        BAD_RESPONSE,

        /** Any other HTTP status. */
        HTTP,
    }
}
