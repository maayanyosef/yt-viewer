package local.youtubetv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlsTest {

    private val key = "AIzaSyHouseholdKey"

    @Test
    fun `a playlist page asks for contentDetails only, and fifty at a time`() {
        val url = YouTubeUrls.playlistItems("UUAuUUnT6oDeKwE6v1NGQxug", key)
        assertTrue(url.contains("part=contentDetails"))
        assertFalse("snippet.publishedAt is the wrong date and must never be fetched", url.contains("snippet"))
        assertTrue(url.contains("maxResults=50"))
        assertTrue(url.contains("playlistId=UUAuUUnT6oDeKwE6v1NGQxug"))
    }

    @Test
    fun `the first page carries no page token`() {
        assertFalse(YouTubeUrls.playlistItems("UUx", key).contains("pageToken"))
    }

    @Test
    fun `a later page carries its token, encoded`() {
        val url = YouTubeUrls.playlistItems("UUx", key, pageToken = "EAAa+Hl/BU=")
        assertTrue(url.contains("pageToken=EAAa%2BHl%2FBU%3D"))
    }

    @Test
    fun `videos_list always asks for maxHeight, which the Shorts rule depends on`() {
        val url = YouTubeUrls.videos(listOf("a", "b"), key)
        assertTrue(url.contains("maxHeight=720"))
        assertTrue(url.contains("part=snippet,contentDetails,liveStreamingDetails,player,statistics"))
        assertTrue(url.contains("id=a%2Cb"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses more than fifty ids, because YouTube would silently drop them`() {
        YouTubeUrls.videos((1..51).map { "v$it" }, key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses an empty id list, which would spend a unit for nothing`() {
        YouTubeUrls.videos(emptyList(), key)
    }

    @Test
    fun `the key is taken out before anything is logged`() {
        val url = YouTubeUrls.playlistItems("UUx", key, pageToken = "token")
        val safe = YouTubeUrls.withoutKey(url)
        assertFalse(safe.contains(key))
        assertTrue(safe.contains("key=REDACTED"))
        // Redacting must not eat the rest of the query.
        assertTrue(safe.contains("playlistId=UUx"))
    }

    @Test
    fun `redacting a key in the middle of a query keeps the parameters after it`() {
        assertEquals(
            "https://x/y?key=REDACTED&part=snippet",
            YouTubeUrls.withoutKey("https://x/y?key=AIzaSySecret&part=snippet"),
        )
    }

    // --- thumbnails ---------------------------------------------------------------------

    @Test
    fun `the stored picture is tried first, then the two that always exist`() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/abc/fhddefault.jpg",
                "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
                "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            ),
            YouTubeUrls.thumbnails("abc", "https://i.ytimg.com/vi/abc/fhddefault.jpg"),
        )
    }

    @Test
    fun `a video with no stored picture still has two to try`() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
                "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            ),
            YouTubeUrls.thumbnails("abc", null),
        )
    }

    @Test
    fun `the stored picture is not tried twice when it is already one of the fallbacks`() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
                "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            ),
            YouTubeUrls.thumbnails("abc", "https://i.ytimg.com/vi/abc/maxresdefault.jpg"),
        )
    }

    // ------------------------------------------------------------------ the expensive one

    @Test
    fun `a search asks for channels and playlists only, twenty-five of them, and no videos`() {
        val url = YouTubeUrls.search("example channel", key)
        assertTrue(url.startsWith("https://youtube.googleapis.com/youtube/v3/search?"))
        // `CONTEXT.md`: Source search is looking for a channel or a playlist to add, never for
        // videos. Videos in the reply would be results the household cannot act on.
        assertTrue(url.contains("type=channel,playlist"))
        assertTrue("spec section 6.1 fixes it at 25", url.contains("maxResults=25"))
        assertTrue(url.contains("part=snippet"))
        assertTrue("the query is encoded, so a space cannot break the request", url.contains("q=example+channel"))
        // YouTube's default is `moderate`, which drops results without saying so, and a
        // household that cannot find its channel spends a second of its hundred rephrasing.
        assertTrue(url.contains("safeSearch=none"))
    }

    @Test
    fun `pricing a page of results is one call per kind, batched by id`() {
        val playlists = YouTubeUrls.playlistsByIds(listOf("PLone", "PLtwo"), key)
        assertTrue(playlists.contains("part=snippet,contentDetails"))
        assertTrue("both ids in one call and one unit", playlists.contains("id=PLone%2CPLtwo"))

        assertTrue("without this, YouTube sends at most 5", playlists.contains("maxResults=50"))

        val channels = YouTubeUrls.channels("id", listOf("UCone", "UCtwo").joinToString(","), key)
        assertTrue(channels.contains("part=snippet,contentDetails,statistics"))
        assertTrue(channels.contains("id=UCone%2CUCtwo"))
        // **`channels.list` defaults to 5, and applies that default to an `id` filter.** A
        // page of search results with more than five channels on it came back with five
        // priced and the rest with no handle, no count and no uploads playlist id, which is
        // the one field a channel cannot be added without. Found by `/code-review`.
        assertTrue("channels.list defaults to five, not to everything asked for", channels.contains("maxResults=50"))
    }

    @Test
    fun `a key never reaches a log line`() {
        assertFalse(YouTubeUrls.withoutKey(YouTubeUrls.search("ted", key)).contains(key))
    }
}
