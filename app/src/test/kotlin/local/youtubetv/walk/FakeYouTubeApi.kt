package local.youtubetv.walk

import java.time.Instant
import local.youtubetv.youtube.CandidateKind
import local.youtubetv.youtube.ChannelDetails
import local.youtubetv.youtube.PlaylistDetails
import local.youtubetv.youtube.SearchHit
import local.youtubetv.youtube.PlaylistItemPage
import local.youtubetv.youtube.PlaylistItemRef
import local.youtubetv.youtube.VideoDetails
import local.youtubetv.youtube.YouTubeApi
import local.youtubetv.youtube.YouTubeApiException

/** The page size YouTube uses, and the one the walk is built around. */
const val PAGE_SIZE = 50

/**
 * A YouTube Data API that answers from memory.
 *
 * It pages exactly the way the real API does, 50 items at a time with a `nextPageToken`, so
 * the walk's paging and its call count can be checked without spending quota.
 */
open class FakeYouTubeApi(
    private val playlists: Map<String, List<PlaylistItemRef>>,
    private val videos: Map<String, VideoDetails> = emptyMap(),
    /** Playlist ids that answer `playlistNotFound`, the way an absent `UUSH` does. */
    private val missingPlaylists: Set<String> = emptySet(),
    /** Playlist ids that fail some other way, to prove the walk does not guess after an error. */
    private val failingPlaylists: Map<String, YouTubeApiException.Reason> = emptyMap(),
    /** Channels this fake knows, by `UC...` id. Used by the add flow of ticket 11. */
    private val channels: Map<String, ChannelDetails> = emptyMap(),
    /** Channels this fake knows by `@handle`, without the `@`. */
    private val channelsByHandle: Map<String, ChannelDetails> = emptyMap(),
    /** Channels this fake knows by the legacy `/user/` name. */
    private val channelsByUsername: Map<String, ChannelDetails> = emptyMap(),
    /** Playlists `playlists.list?id=` answers for. An uploads playlist is often absent here. */
    private val playlistDetails: Map<String, PlaylistDetails> = emptyMap(),
    /** A channel's public playlists, by channel id. */
    private val channelPlaylists: Map<String, List<PlaylistDetails>> = emptyMap(),
    /**
     * What `search.list` answers, by the **exact** query. Used by the Source search of ticket
     * 12, and empty everywhere else, so any test that searches by accident gets nothing back.
     */
    private val searchResults: Map<String, List<SearchHit>> = emptyMap(),
    /** Queries that fail, so the exhausted bucket and a plain failure are both testable. */
    private val failingSearches: Map<String, YouTubeApiException.Reason> = emptyMap(),
    /** True when the two pricing calls should fail, which must never lose a spent search. */
    private val pricingFails: Boolean = false,
    /**
     * What `channels.list?part=statistics` says each channel's video count is, by `UC...` id.
     *
     * A channel not named here answers from its uploads playlist instead, which is what a real
     * channel that shows its counts roughly does. A channel named with a null hides its counts
     * and is left out of the reply altogether.
     */
    private val channelCounts: Map<String, Int?> = emptyMap(),
    /** What `playlists.list?part=contentDetails` says each playlist's item count is. */
    private val playlistCounts: Map<String, Int?> = emptyMap(),
) : YouTubeApi {

    var playlistItemCalls: Int = 0
        private set

    /**
     * How many `playlistItems.list` pages were read of each playlist.
     *
     * Kept apart from [playlistItemCalls] because a channel Source reads two playlists, its
     * uploads and its `UUSH` companion, and spec section 5.2's "one page, not the whole
     * playlist" is a rule about the uploads one alone.
     */
    val playlistItemCallsBy: MutableMap<String, Int> = mutableMapOf()
    var videoCalls: Int = 0
        private set
    /** Every `videos.list` batch the walk asked for, so a test can assert it never over-asks. */
    val videoBatchSizes: MutableList<Int> = mutableListOf()

    override suspend fun playlistItems(playlistId: String, pageToken: String?): PlaylistItemPage {
        playlistItemCalls++
        playlistItemCallsBy[playlistId] = (playlistItemCallsBy[playlistId] ?: 0) + 1
        failingPlaylists[playlistId]?.let {
            throw YouTubeApiException(it, "fake failure for $playlistId")
        }
        if (playlistId in missingPlaylists) {
            throw YouTubeApiException(
                YouTubeApiException.Reason.PLAYLIST_NOT_FOUND,
                "fake playlistNotFound for $playlistId",
            )
        }
        val all = playlists[playlistId]
            ?: throw YouTubeApiException(
                YouTubeApiException.Reason.PLAYLIST_NOT_FOUND,
                "fake playlistNotFound for $playlistId",
            )
        val from = pageToken?.toInt() ?: 0
        val to = minOf(from + PAGE_SIZE, all.size)
        val next = if (to < all.size) to.toString() else null
        return PlaylistItemPage(items = all.subList(from, to), nextPageToken = next, totalResults = all.size)
    }

    override suspend fun videos(videoIds: List<String>): List<VideoDetails> {
        videoCalls++
        videoBatchSizes += videoIds.size
        require(videoIds.size <= PAGE_SIZE) { "videos.list takes at most $PAGE_SIZE ids" }
        return videoIds.mapNotNull { videos[it] }
    }

    /** Every `channels.list` and `playlists.list` call, so a test can count the units spent. */
    var channelCalls: Int = 0
        private set
    var playlistCalls: Int = 0
        private set

    override suspend fun channelById(channelId: String): ChannelDetails? {
        channelCalls++
        return channels[channelId]
    }

    override suspend fun channelByHandle(handle: String): ChannelDetails? {
        channelCalls++
        return channelsByHandle[handle]
    }

    override suspend fun channelByUsername(username: String): ChannelDetails? {
        channelCalls++
        return channelsByUsername[username]
    }

    override suspend fun playlistById(playlistId: String): PlaylistDetails? {
        playlistCalls++
        return playlistDetails[playlistId]
    }

    override suspend fun playlistsOfChannel(channelId: String): List<PlaylistDetails> {
        playlistCalls++
        return channelPlaylists[channelId].orEmpty()
    }

    /**
     * Every `search.list` call, counted apart from every other endpoint.
     *
     * It has its own counter because it has its own bucket: 100 calls a day against 10,000
     * units for everything else. A test that asserts this number is asserting the one limit
     * the project cannot recover from spending.
     */
    var searchCalls: Int = 0
        private set

    /** Every query this fake was asked, in order, so a cached repeat is visible as an absence. */
    val searchQueries: MutableList<String> = mutableListOf()

    override suspend fun search(query: String, maxResults: Int): List<SearchHit> {
        searchCalls++
        searchQueries += query
        failingSearches[query]?.let { throw YouTubeApiException(it, "fake search failure") }
        return searchResults[query].orEmpty().take(maxResults)
    }

    /**
     * One `channels.list?id=a,b,c` call, whatever the list of ids.
     *
     * The counter is the point: spec section 5.2 makes the change check cost two units over
     * every Source, so a test asserts this is called once and not once per Source.
     *
     * A channel this fake was not told about answers from its uploads playlist, which is
     * roughly what a real channel that shows its counts does. One named in [channelCounts]
     * with a null hides its counts, and a real reply then carries no `statistics` at all.
     */
    override suspend fun channelsByIds(channelIds: List<String>): List<ChannelDetails> {
        channelCalls++
        require(channelIds.size <= PAGE_SIZE) { "channels.list takes at most $PAGE_SIZE ids" }
        if (pricingFails) {
            throw YouTubeApiException(YouTubeApiException.Reason.HTTP, "fake pricing failure")
        }
        return channelIds.map { channelId ->
            channels[channelId] ?: fakeChannel(
                channelId,
                videoCount = if (channelId in channelCounts) {
                    channelCounts[channelId]
                } else {
                    playlists["UU" + channelId.removePrefix("UC")]?.size
                },
            )
        }
    }

    /** One `playlists.list?id=x,y,z` call, whatever the list of ids. */
    override suspend fun playlistsByIds(playlistIds: List<String>): List<PlaylistDetails> {
        playlistCalls++
        require(playlistIds.size <= PAGE_SIZE) { "playlists.list takes at most $PAGE_SIZE ids" }
        if (pricingFails) {
            throw YouTubeApiException(YouTubeApiException.Reason.HTTP, "fake pricing failure")
        }
        return playlistIds.map { playlistId ->
            playlistDetails[playlistId] ?: fakePlaylist(
                playlistId,
                itemCount = if (playlistId in playlistCounts) {
                    playlistCounts[playlistId]
                } else {
                    playlists[playlistId]?.size
                },
            )
        }
    }
}

/**
 * A [YouTubeApi] that fails the moment anything touches it.
 *
 * It is how a test proves a path costs **no quota at all**, which several of this project's
 * rules are: a second launch reads storage and never walks, marking a video never leaves the
 * device, and flipping Direction never leaves it either. The message names the path, so a
 * failure says which rule broke rather than only which endpoint was called.
 *
 * It is one class rather than one per test file so that adding an endpoint to [YouTubeApi] is
 * one change here, and not one in every test that only wanted to forbid all of them.
 */
open class NoYouTubeCalls(private val why: String) : YouTubeApi {

    private fun refuse(endpoint: String): Nothing = error("$why must not call $endpoint")

    override suspend fun playlistItems(playlistId: String, pageToken: String?): Nothing =
        refuse("playlistItems")

    override suspend fun videos(videoIds: List<String>): Nothing = refuse("videos")

    override suspend fun channelById(channelId: String): Nothing = refuse("channels.list")

    override suspend fun channelByHandle(handle: String): Nothing = refuse("channels.list")

    override suspend fun channelByUsername(username: String): Nothing = refuse("channels.list")

    override suspend fun playlistById(playlistId: String): Nothing = refuse("playlists.list")

    override suspend fun playlistsOfChannel(channelId: String): Nothing = refuse("playlists.list")

    /**
     * The refusal that matters most in this project.
     *
     * `search.list` has a bucket of 100 calls a day that nothing can refill before midnight
     * Pacific Time. A test that reaches this line has found a path spending it that should
     * not, which is worth more than any of the others here.
     */
    override suspend fun search(query: String, maxResults: Int): Nothing = refuse("search.list")

    override suspend fun channelsByIds(channelIds: List<String>): Nothing = refuse("channels.list")

    override suspend fun playlistsByIds(playlistIds: List<String>): Nothing = refuse("playlists.list")
}

/** A channel as one row of a `search.list` reply, before anything has priced it. */
fun channelHit(
    channelId: String,
    title: String = "Channel $channelId",
): SearchHit = SearchHit(
    kind = CandidateKind.CHANNEL,
    id = channelId,
    title = title,
    channelTitle = title,
    channelId = channelId,
    thumbnailUrl = "https://yt3.example/search/$channelId.jpg",
)

/** A playlist as one row of a `search.list` reply. */
fun playlistHit(
    playlistId: String,
    title: String = "Playlist $playlistId",
    channelId: String? = null,
    channelTitle: String? = null,
): SearchHit = SearchHit(
    kind = CandidateKind.PLAYLIST,
    id = playlistId,
    title = title,
    channelTitle = channelTitle,
    channelId = channelId,
    thumbnailUrl = "https://i.ytimg.example/search/$playlistId.jpg",
)

/** A channel as `channels.list` reports one. */
fun fakeChannel(
    channelId: String,
    title: String = "Channel $channelId",
    handle: String? = "@handle",
    videoCount: Int? = 120,
    uploadsPlaylistId: String? = "UU" + channelId.removePrefix("UC"),
): ChannelDetails = ChannelDetails(
    channelId = channelId,
    title = title,
    handle = handle,
    thumbnailUrl = "https://yt3.example/$channelId.jpg",
    videoCount = videoCount,
    uploadsPlaylistId = uploadsPlaylistId,
)

/** A playlist as `playlists.list` reports one. */
fun fakePlaylist(
    playlistId: String,
    title: String = "Playlist $playlistId",
    channelId: String? = null,
    channelTitle: String? = null,
    itemCount: Int? = 19,
): PlaylistDetails = PlaylistDetails(
    playlistId = playlistId,
    title = title,
    channelId = channelId,
    channelTitle = channelTitle,
    thumbnailUrl = "https://i.ytimg.example/$playlistId.jpg",
    itemCount = itemCount,
)

/** A plain, wide, ten-minute video that nothing in the rules makes special. */
fun normalVideo(
    videoId: String,
    title: String = "Video $videoId",
    liveBroadcastContent: String = "none",
    liveEndedAt: Instant? = null,
    durationSeconds: Int? = 631,
    embedWidth: Int? = 1280,
    embedHeight: Int? = 720,
    viewCount: Long? = null,
): VideoDetails = VideoDetails(
    videoId = videoId,
    title = title,
    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
    durationSeconds = durationSeconds,
    liveBroadcastContent = liveBroadcastContent,
    liveEndedAt = liveEndedAt,
    embedWidth = embedWidth,
    embedHeight = embedHeight,
    viewCount = viewCount,
)

/** A tall, two-minute video: a Short by the embed-shape rule. */
fun shortVideo(videoId: String): VideoDetails =
    normalVideo(videoId, durationSeconds = 121, embedWidth = 405, embedHeight = 720)

/**
 * A stream that is live right now.
 *
 * YouTube reports no embed size for these, and their duration is `P0D`. Confirmed against
 * real API replies on 2026-09-19.
 */
fun liveVideo(videoId: String): VideoDetails = normalVideo(
    videoId,
    liveBroadcastContent = "live",
    durationSeconds = 0,
    embedWidth = null,
    embedHeight = null,
)

/** A premiere that has not started. Same missing embed size as a live stream. */
fun upcomingVideo(videoId: String): VideoDetails = normalVideo(
    videoId,
    liveBroadcastContent = "upcoming",
    durationSeconds = 0,
    embedWidth = null,
    embedHeight = null,
)

/** A live stream that has ended, which is the case the `WAS LIVE` marker is for. */
fun liveVodVideo(videoId: String, endedAt: Instant): VideoDetails = normalVideo(
    videoId,
    liveBroadcastContent = "none",
    liveEndedAt = endedAt,
    durationSeconds = 3875,
)
