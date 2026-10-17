package local.youtubetv.youtube

import java.net.URLEncoder

private const val API_BASE = "https://youtube.googleapis.com/youtube/v3"

/** Where YouTube serves video thumbnails. This host is not the Data API and costs no quota. */
private const val THUMBNAIL_BASE = "https://i.ytimg.com/vi"

/** YouTube's page size, and the most ids `videos.list` accepts in one call. */
const val YOUTUBE_PAGE_SIZE = 50

/**
 * Builds the two request URLs this app makes.
 *
 * These are plain string functions so the exact query can be asserted in a unit test. Getting
 * `maxHeight` or the `part` list wrong is silent: the call succeeds, a field is missing, and
 * the Shorts rule quietly stops working.
 */
object YouTubeUrls {

    /**
     * One page of a playlist.
     *
     * `part=contentDetails` alone. That carries the video id and `videoPublishedAt`, which is
     * everything the walk needs from this endpoint. Asking for `snippet` here would add a
     * long description per item for nothing, and `snippet.publishedAt` is the wrong date.
     */
    fun playlistItems(playlistId: String, apiKey: String, pageToken: String? = null): String =
        buildString {
            append("$API_BASE/playlistItems")
            append("?part=contentDetails")
            append("&maxResults=$YOUTUBE_PAGE_SIZE")
            append("&playlistId=${encode(playlistId)}")
            if (pageToken != null) append("&pageToken=${encode(pageToken)}")
            append("&key=${encode(apiKey)}")
        }

    /**
     * Up to 50 videos in one call.
     *
     * `statistics` carries `viewCount`, the number build ticket 59 shows on each card. A `list`
     * call costs one unit per page whatever parts it asks for, so it costs no quota.
     *
     * `maxHeight=720` is mandatory. Without it YouTube returns one fixed embed size for every
     * video, and `embedWidth` against `embedHeight` no longer tells a Short from a normal
     * video. See spec section 4.3.
     */
    fun videos(videoIds: List<String>, apiKey: String): String {
        require(videoIds.isNotEmpty()) { "videos.list needs at least one id" }
        require(videoIds.size <= YOUTUBE_PAGE_SIZE) {
            "videos.list takes at most $YOUTUBE_PAGE_SIZE ids, was ${videoIds.size}"
        }
        return buildString {
            append("$API_BASE/videos")
            append("?part=snippet,contentDetails,liveStreamingDetails,player,statistics")
            append("&maxHeight=720")
            append("&id=${encode(videoIds.joinToString(","))}")
            append("&key=${encode(apiKey)}")
        }
    }

    /**
     * One channel, by whichever of its three names the household typed.
     *
     * `part=snippet,contentDetails,statistics` in one call and one unit. All three parts are
     * needed before a Source can be added: the title, the `@handle` and the picture from
     * `snippet`, the uploads playlist from `contentDetails`, and the video count from
     * `statistics`. Asking for them one at a time would cost three units for one answer.
     *
     * **`maxResults` is not optional here, although it looks it.** `channels.list` defaults to
     * **5**, not to everything asked for, and unlike `videos.list` it applies that default to
     * an `id` filter too. Without this line a page of search results with more than five
     * channels on it came back with five priced and the rest silently unpriced: no `@handle`,
     * no count, and no uploads playlist id, which is the one field a channel cannot be added
     * without. Found by `/code-review` on ticket 12.
     *
     * @param by which query parameter names the channel: `id`, `forHandle` or `forUsername`.
     */
    fun channels(by: String, value: String, apiKey: String): String = buildString {
        append("$API_BASE/channels")
        append("?part=snippet,contentDetails,statistics")
        append("&maxResults=$YOUTUBE_PAGE_SIZE")
        append("&$by=${encode(value)}")
        append("&key=${encode(apiKey)}")
    }

    /**
     * One playlist by its id.
     *
     * `part=snippet,contentDetails` carries the title, the picture, whose channel it is, and
     * `contentDetails.itemCount`, which is the count the add screen shows.
     */
    fun playlistById(playlistId: String, apiKey: String): String = buildString {
        append("$API_BASE/playlists")
        append("?part=snippet,contentDetails")
        append("&id=${encode(playlistId)}")
        append("&key=${encode(apiKey)}")
    }

    /**
     * Up to 50 playlists by id, in one call and one unit.
     *
     * The same `part` as [playlistById], because the rows a search built want the same four
     * things: the title, the picture, whose channel it is and `contentDetails.itemCount`.
     */
    fun playlistsByIds(playlistIds: List<String>, apiKey: String): String {
        require(playlistIds.isNotEmpty()) { "playlists.list needs at least one id" }
        require(playlistIds.size <= YOUTUBE_PAGE_SIZE) {
            "playlists.list takes at most $YOUTUBE_PAGE_SIZE ids, was ${playlistIds.size}"
        }
        return buildString {
            append("$API_BASE/playlists")
            append("?part=snippet,contentDetails")
            append("&maxResults=$YOUTUBE_PAGE_SIZE")
            append("&id=${encode(playlistIds.joinToString(","))}")
            append("&key=${encode(apiKey)}")
        }
    }

    /**
     * A channel's public playlists, one page of at most 50 and so one unit.
     *
     * There is no `pageToken` here on purpose. Spec section 6.2 asks for one call, and a
     * household with more than fifty public playlists on one channel is not worth a second.
     */
    fun playlistsOfChannel(channelId: String, apiKey: String): String = buildString {
        append("$API_BASE/playlists")
        append("?part=snippet,contentDetails")
        append("&maxResults=$YOUTUBE_PAGE_SIZE")
        append("&channelId=${encode(channelId)}")
        append("&key=${encode(apiKey)}")
    }

    /**
     * The one request in this app that spends a `search.list` call.
     *
     * `part=snippet` alone. There is no other part worth asking for: `search.list` reports no
     * count and no handle whatever is requested, and those arrive from [channels] and
     * [playlistsByIds] afterwards for 1 unit each.
     *
     * - **`type=channel,playlist`** keeps videos out of the reply. Spec section 6.2 makes this
     *   one mixed list of the two things that can become a Source, and `CONTEXT.md` is
     *   explicit that Source search is not searching for videos.
     * - **`maxResults=25`** is spec section 6.1's number, and it is one page and one call.
     * - **`safeSearch=none`** because YouTube's default is `moderate`, which silently drops
     *   results. A household that cannot find a channel it watches would spend a second call
     *   of its hundred rephrasing the query. This is one household's own television.
     * - `order` is left at YouTube's default, `relevance`, which is what the household means
     *   by "the one I typed the name of".
     */
    fun search(query: String, apiKey: String, maxResults: Int = SEARCH_MAX_RESULTS): String =
        buildString {
            append("$API_BASE/search")
            append("?part=snippet")
            append("&type=channel,playlist")
            append("&safeSearch=none")
            append("&maxResults=$maxResults")
            append("&q=${encode(query)}")
            append("&key=${encode(apiKey)}")
        }

    /**
     * Every picture to try for one video, best first.
     *
     * The stored URL is what `videos.list` reported, and YouTube does not always serve it.
     * A live stream and a live VOD are offered as `fhddefault.jpg` at 1920 wide, which is the
     * widest entry and so the one chosen, and `i.ytimg.com` answers 404 for it. Seen on the
     * television on 2026-09-19: three cards of the TED Source showed an empty tile for that
     * reason alone.
     *
     * The two below are built from the video id and are not reported by any API call.
     * `maxresdefault` is 1280x720 and exists for almost every modern video. `hqdefault` is
     * 480x360 and exists for every video YouTube has ever served, which is why it is last.
     *
     * None of this costs quota. `i.ytimg.com` is a picture host, not the Data API.
     */
    fun thumbnails(videoId: String, storedUrl: String?): List<String> =
        listOfNotNull(
            storedUrl,
            "$THUMBNAIL_BASE/$videoId/maxresdefault.jpg",
            "$THUMBNAIL_BASE/$videoId/hqdefault.jpg",
        ).distinct()

    /**
     * The same URL with the key taken out.
     *
     * [HttpYouTubeApi] puts this, never the raw URL, into every failure message it builds. A
     * URL carrying the key must never reach logcat, which anything on the network can read
     * over adb.
     */
    fun withoutKey(url: String): String = url.replace(Regex("""([?&]key=)[^&]*"""), "$1REDACTED")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
