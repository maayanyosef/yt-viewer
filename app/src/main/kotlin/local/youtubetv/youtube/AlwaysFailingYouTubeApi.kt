package local.youtubetv.youtube

/**
 * A [YouTubeApi] that refuses every call, for driving the two failure paths of spec section 5.4.
 *
 * Both of those paths are rules about what does **not** happen: with no network, and with the
 * quota spent, the cached list still browses and still plays, there is no banner, no dialog and
 * no retry loop. None of that can be photographed, and the one honest way to see it on a
 * television is to make the calls fail and read the log.
 *
 * **It exists so that nothing has to touch the network settings.** On the household's set the
 * `adb` connection runs over the same wifi the app does, so turning wifi off to test an offline
 * path takes away the only way to see the result. This is the seam doing the job the seam is
 * for: `HttpYouTubeApi` is swapped for this one, in one place, for one launch.
 *
 * It is reached only through [local.youtubetv.EXTRA_REFRESH_FAILURE] and is never in the way
 * of a normal launch.
 */
class AlwaysFailingYouTubeApi(
    private val reason: YouTubeApiException.Reason,
) : YouTubeApi {

    private fun refuse(endpoint: String): Nothing = throw YouTubeApiException(
        reason,
        "$endpoint was refused on purpose, by ${local.youtubetv.EXTRA_REFRESH_FAILURE}",
    )

    override suspend fun playlistItems(playlistId: String, pageToken: String?): Nothing =
        refuse("playlistItems.list")

    override suspend fun videos(videoIds: List<String>): Nothing = refuse("videos.list")

    override suspend fun channelById(channelId: String): Nothing = refuse("channels.list")

    override suspend fun channelByHandle(handle: String): Nothing = refuse("channels.list")

    override suspend fun channelByUsername(username: String): Nothing = refuse("channels.list")

    override suspend fun playlistById(playlistId: String): Nothing = refuse("playlists.list")

    override suspend fun playlistsOfChannel(channelId: String): Nothing = refuse("playlists.list")

    /**
     * `search.list` refused like everything else.
     *
     * It matters most here of all: this is the class that proves the quota path, and the
     * bucket `search.list` draws on is 100 a day, not 10,000.
     */
    override suspend fun search(query: String, maxResults: Int): Nothing =
        refuse("search.list")

    override suspend fun channelsByIds(channelIds: List<String>): Nothing = refuse("channels.list")

    override suspend fun playlistsByIds(playlistIds: List<String>): Nothing =
        refuse("playlists.list")
}
