package local.youtubetv.youtube

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads a reply captured from the real API and stored under `src/test/resources/youtube/`.
 *
 * These files are what YouTube actually sent on 2026-09-19, not hand-written examples. That
 * is the point: the shapes this parser gets wrong are the ones nobody would invent.
 */
private fun fixture(name: String): String =
    checkNotNull(YouTubeJsonTest::class.java.getResourceAsStream("/youtube/$name")) {
        "missing test fixture /youtube/$name"
    }.bufferedReader().use { it.readText() }

class YouTubeJsonTest {

    // --- playlistItems ------------------------------------------------------------------

    @Test
    fun `reads the video id and videoPublishedAt of every item`() {
        val page = YouTubeJson.parsePlaylistItemPage(fixture("playlistItems-page.json"))
        assertEquals(5, page.items.size)
        assertEquals("eMade-Up_01", page.items.first().videoId)
        assertEquals(
            Instant.parse("2026-09-19T15:00:36Z"),
            page.items.first().videoPublishedAt,
        )
    }

    @Test
    fun `reads the next page token, so the walk knows there is more`() {
        val page = YouTubeJson.parsePlaylistItemPage(fixture("playlistItems-page.json"))
        assertEquals("EAAaHlBUOkNBVWlFRE0yTXprM01FRTBOVEUxTUVJeU1FRQ", page.nextPageToken)
    }

    @Test
    fun `reads totalResults, which backs the loading line`() {
        val page = YouTubeJson.parsePlaylistItemPage(fixture("playlistItems-page.json"))
        assertEquals(5813, page.totalResults)
    }

    @Test
    fun `a last page has no next page token`() {
        val page = YouTubeJson.parsePlaylistItemPage(
            """{"items":[],"pageInfo":{"totalResults":0,"resultsPerPage":50}}""",
        )
        assertNull(page.nextPageToken)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `a private video has no videoPublishedAt and still parses`() {
        val page = YouTubeJson.parsePlaylistItemPage(
            """{"items":[{"contentDetails":{"videoId":"gone"}}]}""",
        )
        assertEquals("gone", page.items.single().videoId)
        assertNull(page.items.single().videoPublishedAt)
    }

    // --- videos -------------------------------------------------------------------------

    @Test
    fun `reads the embed size of a Short, as strings, not numbers`() {
        val short = videosFixture().single { it.videoId == "DnUAXX41KJ8" }
        assertEquals(405, short.embedWidth)
        assertEquals(720, short.embedHeight)
        assertEquals(121, short.durationSeconds)
    }

    @Test
    fun `reads a normal video`() {
        val normal = videosFixture().single { it.videoId == "eMade-Up_01" }
        assertEquals(1280, normal.embedWidth)
        assertEquals(720, normal.embedHeight)
        assertEquals(631, normal.durationSeconds)
        assertEquals("none", normal.liveBroadcastContent)
        assertNull(normal.liveEndedAt)
        assertEquals("The Wiggly Wonder of Music | Jacob Collier | TED", normal.title)
    }

    @Test
    fun `reads actualEndTime, which is what makes a video WAS LIVE`() {
        val vod = videosFixture().single { it.videoId == "P12XxVMbYXg" }
        assertEquals(Instant.parse("2026-08-31T18:06:40Z"), vod.liveEndedAt)
        assertEquals("none", vod.liveBroadcastContent)
        assertEquals(3875, vod.durationSeconds)
    }

    @Test
    fun `an unstarted premiere reads as upcoming, with no embed size`() {
        val premiere = videosFixture().single { it.videoId == "x_g72BLelDU" }
        assertEquals("upcoming", premiere.liveBroadcastContent)
        // The `player` object is there, but it carries `embedHtml` alone.
        assertNull(premiere.embedWidth)
        assertNull(premiere.embedHeight)
        assertEquals(0, premiere.durationSeconds)
        assertNull(premiere.liveEndedAt)
    }

    @Test
    fun `a stream that is live right now reads as live, with no end time`() {
        val live = videosFixture().single { it.videoId == "3PFJ9SETS4M" }
        assertEquals("live", live.liveBroadcastContent)
        assertNull(live.embedWidth)
        assertNull(live.liveEndedAt)
    }

    @Test
    fun `picks the widest thumbnail the television can actually use`() {
        // This video also offers uhd at 3840 wide, which is wasted on a 1920-wide window.
        val video = videosFixture().single { it.videoId == "eMade-Up_01" }
        assertTrue(video.thumbnailUrl!!.endsWith("maxresdefault.jpg"))
    }

    @Test
    fun `picks fhd when a live stream offers it, although no other video has that key`() {
        val premiere = videosFixture().single { it.videoId == "x_g72BLelDU" }
        assertTrue(premiere.thumbnailUrl!!.endsWith("fhddefault_live.jpg"))
    }

    @Test
    fun `falls back to the narrowest when every thumbnail is too wide`() {
        val body = """{"items":[{"id":"v","snippet":{"title":"t","liveBroadcastContent":"none",
            "thumbnails":{"a":{"url":"wide.jpg","width":4000,"height":2000},
                          "b":{"url":"wider.jpg","width":8000,"height":4000}}}}]}"""
        assertEquals("wide.jpg", YouTubeJson.parseVideos(body).single().thumbnailUrl)
    }

    @Test
    fun `a video with no thumbnails at all parses with a null thumbnail`() {
        val body = """{"items":[{"id":"v","snippet":{"title":"t","liveBroadcastContent":"none"}}]}"""
        assertNull(YouTubeJson.parseVideos(body).single().thumbnailUrl)
    }

    @Test
    fun `reads the view count, which YouTube sends as a string`() {
        // The shape of a real reply the orchestrator read on 2026-10-03. Build ticket 59.
        val body = """{"items":[{"id":"eMade-Up_03","snippet":{"title":"t","liveBroadcastContent":"none"},
            "statistics":{"viewCount":"116945","likeCount":"1333","favoriteCount":"0","commentCount":"92"}}]}"""
        assertEquals(116_945L, YouTubeJson.parseVideos(body).single().viewCount)
    }

    @Test
    fun `a video with no view count has no count, not a count of zero`() {
        // An upcoming premiere: `statistics` is there, `viewCount` is not.
        val noViewCount = """{"items":[{"id":"v","snippet":{"title":"t","liveBroadcastContent":"upcoming"},
            "statistics":{"likeCount":"3"}}]}"""
        assertNull(YouTubeJson.parseVideos(noViewCount).single().viewCount)
        val noStatistics = """{"items":[{"id":"v","snippet":{"title":"t","liveBroadcastContent":"none"}}]}"""
        assertNull(YouTubeJson.parseVideos(noStatistics).single().viewCount)
    }

    @Test
    fun `a real count of zero is kept as zero`() {
        val body = """{"items":[{"id":"v","snippet":{"title":"t","liveBroadcastContent":"none"},
            "statistics":{"viewCount":"0"}}]}"""
        assertEquals(0L, YouTubeJson.parseVideos(body).single().viewCount)
    }

    @Test
    fun `videos_list may answer in its own order, so the caller must key by id`() {
        // Asked for eMade-Up_01 first; YouTube answered with DnUAXX41KJ8 first.
        assertEquals("DnUAXX41KJ8", videosFixture().first().videoId)
    }

    private fun videosFixture(): List<VideoDetails> =
        YouTubeJson.parseVideos(fixture("videos-page.json"))

    // --- errors -------------------------------------------------------------------------

    @Test
    fun `maps a real playlistNotFound reply`() {
        val body = fixture("error-playlistNotFound.json")
        assertEquals(
            YouTubeApiException.Reason.PLAYLIST_NOT_FOUND,
            YouTubeJson.parseErrorReason(404, body),
        )
    }

    @Test
    fun `maps quotaExceeded, which stops refresh until midnight Pacific Time`() {
        val body = """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}"""
        assertEquals(
            YouTubeApiException.Reason.QUOTA_EXCEEDED,
            YouTubeJson.parseErrorReason(403, body),
        )
    }

    @Test
    fun `maps an invalid key from the newer details shape`() {
        val body =
            """{"error":{"code":400,"errors":[{"reason":"badRequest"}],"details":[{"reason":"API_KEY_INVALID"}]}}"""
        assertEquals(YouTubeApiException.Reason.FORBIDDEN, YouTubeJson.parseErrorReason(400, body))
    }

    @Test
    fun `maps the refusal an Android app restriction produces`() {
        val body = """{"error":{"code":403,"details":[{"reason":"API_KEY_ANDROID_APP_BLOCKED"}]}}"""
        assertEquals(YouTubeApiException.Reason.FORBIDDEN, YouTubeJson.parseErrorReason(403, body))
    }

    @Test
    fun `falls back on the status when there is no body at all`() {
        assertEquals(YouTubeApiException.Reason.FORBIDDEN, YouTubeJson.parseErrorReason(403, null))
        assertEquals(YouTubeApiException.Reason.HTTP, YouTubeJson.parseErrorReason(500, null))
        assertEquals(YouTubeApiException.Reason.HTTP, YouTubeJson.parseErrorReason(500, "not json"))
    }

    @Test
    fun `takes the message out of an error body for the screen`() {
        val message = YouTubeJson.parseErrorMessage(404, fixture("error-playlistNotFound.json"))
        assertTrue(message.contains("cannot be found"))
    }

    @Test
    fun `falls back on a plain sentence when there is no message`() {
        assertEquals("YouTube replied with HTTP 500", YouTubeJson.parseErrorMessage(500, null))
    }

    // --- bad input ----------------------------------------------------------------------

    @Test(expected = YouTubeApiException::class)
    fun `a reply that is not JSON is a BAD_RESPONSE, not a crash`() {
        YouTubeJson.parsePlaylistItemPage("<html>gateway timeout</html>")
    }

    // --- search.list ---------------------------------------------------------------------

    /**
     * The reply shape `search.list` uses, which no other endpoint in this app does.
     *
     * Two things are different and both would be silent bugs: the id is an object whose `kind`
     * says which field carries it, and `snippet.title` is HTML-escaped.
     */
    private val searchReply = """
        {
          "items": [
            {
              "id": { "kind": "youtube#channel", "channelId": "UCAuUUnT6oDeKwE6v1NGQxug" },
              "snippet": {
                "title": "Tom &amp; Jerry&#39;s channel",
                "channelId": "UCAuUUnT6oDeKwE6v1NGQxug",
                "channelTitle": "Tom &amp; Jerry",
                "thumbnails": { "high": { "url": "https://yt3.example/a.jpg", "width": 800 } }
              }
            },
            {
              "id": { "kind": "youtube#playlist", "playlistId": "PLone" },
              "snippet": {
                "title": "Best talks",
                "channelId": "UCother",
                "channelTitle": "TED",
                "thumbnails": { "high": { "url": "https://i.ytimg.example/b.jpg", "width": 480 } }
              }
            },
            {
              "id": { "kind": "youtube#video", "videoId": "TMade-Up_02" },
              "snippet": { "title": "a video that should not be here" }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a search reply is read as channels and playlists, in YouTube's own order`() {
        val hits = YouTubeJson.parseSearchResults(searchReply)
        assertEquals(2, hits.size)
        assertEquals(CandidateKind.CHANNEL, hits.first().kind)
        assertEquals("UCAuUUnT6oDeKwE6v1NGQxug", hits.first().id)
        assertEquals(CandidateKind.PLAYLIST, hits.last().kind)
        assertEquals("PLone", hits.last().id)
        assertEquals("TED", hits.last().channelTitle)
        assertEquals("https://i.ytimg.example/b.jpg", hits.last().thumbnailUrl)
    }

    @Test
    fun `a video in a search reply is dropped rather than guessed at`() {
        // `type=channel,playlist` should mean there are none, but a kind this app cannot turn
        // into a Source must never become a row the household can press.
        assertTrue(YouTubeJson.parseSearchResults(searchReply).none { it.id == "TMade-Up_02" })
    }

    @Test
    fun `search titles arrive HTML-escaped and are unescaped before the television sees them`() {
        val hits = YouTubeJson.parseSearchResults(searchReply)
        assertEquals("Tom & Jerry's channel", hits.first().title)
        assertEquals("Tom & Jerry", hits.first().channelTitle)
    }

    @Test
    fun `unescaping is one pass, so an escaped escape stays escaped`() {
        // `&amp;lt;` is the escaping of the literal text `&lt;`. Replacing `&amp;` first and
        // `&lt;` second would turn it into a tag that was never in the title.
        assertEquals("&lt;", unescapeHtml("&amp;lt;"))
        assertEquals("a & b", unescapeHtml("a &amp; b"))
        assertEquals("\"quoted\"", unescapeHtml("&quot;quoted&quot;"))
        assertEquals("ערוץ לדוגמה", unescapeHtml("&#1506;&#1512;&#1493;&#1509; &#1500;&#1491;&#1493;&#1490;&#1502;&#1492;"))
        assertEquals("nothing to do", unescapeHtml("nothing to do"))
        // An escape this app cannot read is left exactly as it arrived.
        assertEquals("&unknownentity;", unescapeHtml("&unknownentity;"))
    }

    @Test
    fun `a search that matched nothing is an empty list and not a failure`() {
        assertTrue(YouTubeJson.parseSearchResults("""{"items":[]}""").isEmpty())
    }

    @Test
    fun `a BAD_RESPONSE carries that reason`() {
        val failure = runCatching { YouTubeJson.parseVideos("nonsense") }.exceptionOrNull()
        assertTrue(failure is YouTubeApiException)
        assertEquals(
            YouTubeApiException.Reason.BAD_RESPONSE,
            (failure as YouTubeApiException).reason,
        )
    }
}
