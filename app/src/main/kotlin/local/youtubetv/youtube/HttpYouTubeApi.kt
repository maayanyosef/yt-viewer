package local.youtubetv.youtube

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 20_000

/**
 * The real [YouTubeApi], over `HttpURLConnection`.
 *
 * No HTTP library is used. The app makes two kinds of GET request, both of them plain, and
 * `HttpURLConnection` ships inside Android. Adding OkHttp or Retrofit would buy nothing here
 * and would add a dependency to keep current.
 *
 * Google enforces an API key's Android application restriction from two request headers,
 * not by inspecting the APK. A plain HTTP client sends neither on its own, so this class
 * sends both, or the key stops working the moment that restriction is applied. See ticket 14.
 *
 * @param apiKey reads the user's key at the moment of each call. It is a function and not a
 *   string because the key can be replaced or removed on the key screen while the app runs,
 *   and every object built on this class would otherwise go on using the old one. Build
 *   ticket 64.
 * @param androidPackage the app's package name, sent as `X-Android-Package`.
 * @param androidCertSha1 the signing certificate SHA-1, sent as `X-Android-Cert`. Null leaves
 *   both headers off, which is the state the app is in while the key carries no application
 *   restriction.
 */
class HttpYouTubeApi(
    private val apiKey: () -> String,
    private val androidPackage: String? = null,
    private val androidCertSha1: String? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openConnection: (String) -> HttpURLConnection = {
        URL(it).openConnection() as HttpURLConnection
    },
) : YouTubeApi {

    override suspend fun playlistItems(playlistId: String, pageToken: String?): PlaylistItemPage =
        YouTubeJson.parsePlaylistItemPage(get(YouTubeUrls.playlistItems(playlistId, apiKey(), pageToken)))

    override suspend fun videos(videoIds: List<String>): List<VideoDetails> {
        if (videoIds.isEmpty()) return emptyList()
        return YouTubeJson.parseVideos(get(YouTubeUrls.videos(videoIds, apiKey())))
    }

    override suspend fun channelById(channelId: String): ChannelDetails? =
        oneChannel("id", channelId)

    override suspend fun channelByHandle(handle: String): ChannelDetails? =
        oneChannel("forHandle", handle)

    override suspend fun channelByUsername(username: String): ChannelDetails? =
        oneChannel("forUsername", username)

    /**
     * One `channels.list` call, and the first channel it named.
     *
     * YouTube answers a handle or a name it does not know with HTTP 200 and an empty `items`
     * array rather than with an error, so "no such channel" is an empty list and not an
     * exception.
     */
    private suspend fun oneChannel(by: String, value: String): ChannelDetails? {
        if (value.isBlank()) return null
        return YouTubeJson.parseChannels(get(YouTubeUrls.channels(by, value, apiKey())))
            .firstOrNull()
    }

    override suspend fun playlistById(playlistId: String): PlaylistDetails? {
        if (playlistId.isBlank()) return null
        return YouTubeJson.parsePlaylists(get(YouTubeUrls.playlistById(playlistId, apiKey())))
            .firstOrNull()
    }

    override suspend fun playlistsOfChannel(channelId: String): List<PlaylistDetails> {
        if (channelId.isBlank()) return emptyList()
        return YouTubeJson.parsePlaylists(get(YouTubeUrls.playlistsOfChannel(channelId, apiKey())))
    }

    /**
     * The one call in this app that spends from the bucket of 100 per day.
     *
     * A blank query is refused here without a request. It cannot arrive from the add screen,
     * which trims what was typed, but a call spent on nothing is a call the household cannot
     * get back until midnight Pacific Time.
     */
    override suspend fun search(query: String, maxResults: Int): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return YouTubeJson.parseSearchResults(
            get(YouTubeUrls.search(query, apiKey(), maxResults)),
        )
    }

    override suspend fun channelsByIds(channelIds: List<String>): List<ChannelDetails> {
        if (channelIds.isEmpty()) return emptyList()
        require(channelIds.size <= YOUTUBE_PAGE_SIZE) {
            "channels.list takes at most $YOUTUBE_PAGE_SIZE ids, was ${channelIds.size}"
        }
        return YouTubeJson.parseChannels(
            get(YouTubeUrls.channels("id", channelIds.joinToString(","), apiKey())),
        )
    }

    override suspend fun playlistsByIds(playlistIds: List<String>): List<PlaylistDetails> {
        if (playlistIds.isEmpty()) return emptyList()
        return YouTubeJson.parsePlaylists(get(YouTubeUrls.playlistsByIds(playlistIds, apiKey())))
    }

    private suspend fun get(url: String): String = withContext(dispatcher) {
        // Every message below names the request, with the key taken out. A URL carrying the
        // key must never reach logcat, which anything on the network can read over adb.
        val safeUrl = YouTubeUrls.withoutKey(url)

        val connection = try {
            openConnection(url)
        } catch (failure: IOException) {
            throw YouTubeApiException(
                YouTubeApiException.Reason.NETWORK,
                "Could not reach YouTube: $safeUrl",
                failure,
            )
        }

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            androidPackage?.let { connection.setRequestProperty("X-Android-Package", it) }
            androidCertSha1?.let { connection.setRequestProperty("X-Android-Cert", it) }

            val status = try {
                connection.responseCode
            } catch (failure: IOException) {
                throw YouTubeApiException(
                    YouTubeApiException.Reason.NETWORK,
                    "Could not reach YouTube: $safeUrl",
                    failure,
                )
            }

            if (status !in 200..299) {
                val body = connection.errorStream?.use { it.bufferedReader().readText() }
                throw YouTubeApiException(
                    YouTubeJson.parseErrorReason(status, body),
                    "${YouTubeJson.parseErrorMessage(status, body)} ($safeUrl)",
                )
            }

            try {
                connection.inputStream.use { it.bufferedReader().readText() }
            } catch (failure: IOException) {
                // **BAD_RESPONSE and not NETWORK**, although it is an `IOException`. YouTube
                // answered 2xx before this line ran, so the request was served and it was
                // charged. Calling it a network failure made `SourceSearch` report one of the
                // day's hundred as unspent when it had gone. Found by `/code-review`.
                throw YouTubeApiException(
                    YouTubeApiException.Reason.BAD_RESPONSE,
                    "The reply from YouTube was cut off: $safeUrl",
                    failure,
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
