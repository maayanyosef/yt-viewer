package local.youtubetv.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone remote's routing, commands and JSON. The server itself needs a socket. */
class RemoteModelTest {

    private val token = "abcdefghjk"

    @Test
    fun `the token is the first path segment and nothing is served without it`() {
        assertEquals(RemoteRequest.ShowPage, routeRemoteRequest("GET", "/$token", "", token))
        assertEquals(RemoteRequest.ShowPage, routeRemoteRequest("GET", "/$token/", "", token))
        assertEquals(RemoteRequest.State, routeRemoteRequest("GET", "/$token/state", "", token))
        assertEquals(RemoteRequest.NotFound, routeRemoteRequest("GET", "/wrongtoken/state", "", token))
        assertEquals(RemoteRequest.NotFound, routeRemoteRequest("GET", "/", "", token))
        assertEquals(RemoteRequest.NotFound, routeRemoteRequest("GET", "/$token/nothing", "", token))
    }

    @Test
    fun `the videos route reads its query and bounds the page`() {
        val request = routeRemoteRequest("GET", "/$token/videos?source=UU1&offset=120&limit=500&q=hello%20world", "", token)
        assertEquals(RemoteRequest.Videos("UU1", 120, REMOTE_VIDEOS_PAGE, "hello world"), request)
        assertEquals(RemoteRequest.NotFound, routeRemoteRequest("GET", "/$token/videos", "", token))
    }

    @Test
    fun `a command is a POST and a wrong method is refused`() {
        assertEquals(
            RemoteRequest.Command(RemoteCommand.SeekBy(-10)),
            routeRemoteRequest("POST", "/$token/cmd", "action=seek&by=-10", token),
        )
        assertEquals(RemoteRequest.MethodNotAllowed, routeRemoteRequest("GET", "/$token/cmd", "", token))
        assertEquals(RemoteRequest.BadCommand, routeRemoteRequest("POST", "/$token/cmd", "action=dance", token))
    }

    @Test
    fun `every command the page sends is read back`() {
        assertEquals(RemoteCommand.TogglePlay, parseRemoteCommand("action=toggle"))
        assertEquals(RemoteCommand.NextVideo, parseRemoteCommand("action=next"))
        assertEquals(RemoteCommand.PreviousVideo, parseRemoteCommand("action=prev"))
        assertEquals(RemoteCommand.Back, parseRemoteCommand("action=back"))
        assertEquals(RemoteCommand.OpenSource("UU1"), parseRemoteCommand("action=open_source&source=UU1"))
        assertEquals(RemoteCommand.PlayVideo("UU1", "v1"), parseRemoteCommand("action=play_video&source=UU1&video=v1"))
        assertEquals(RemoteCommand.Sleep(afterThisVideo = true, minutes = null), parseRemoteCommand("action=sleep&after_video=1"))
        assertEquals(RemoteCommand.Sleep(afterThisVideo = false, minutes = 30), parseRemoteCommand("action=sleep&minutes=30"))
        assertEquals(RemoteCommand.Sleep(afterThisVideo = false, minutes = null), parseRemoteCommand("action=sleep"))
        assertEquals(RemoteCommand.ChildMode(true), parseRemoteCommand("action=child_mode&on=1"))
        assertEquals(RemoteCommand.ChildMode(false), parseRemoteCommand("action=child_mode"))
    }

    @Test
    fun `a seek of a year and a sleep of a week are refused`() {
        assertNull(parseRemoteCommand("action=seek&by=99999999"))
        assertEquals(RemoteCommand.Sleep(false, null), parseRemoteCommand("action=sleep&minutes=10080"))
        assertNull(parseRemoteCommand("action=play_video&source=UU1"))
    }

    private fun video(id: String, title: String, watched: Boolean = false) =
        RemoteVideo(id, title, 1_000L, 600, watched, null)

    @Test
    fun `the search matches every word of the query in any order and ignores case`() {
        val all = listOf(video("a", "Masha and the Bear: Picnic"), video("b", "The Bear sleeps"), video("c", "Masha cooks"))
        val (page, total) = pageOfVideos(all, 0, 10, "bear masha")
        assertEquals(listOf("a"), page.map { it.videoId })
        assertEquals(1, total)
        assertEquals(3, pageOfVideos(all, 0, 10, "").second)
    }

    @Test
    fun `a page is cut at the offset and the limit, and the total is of every match`() {
        val all = (1..130).map { video("v$it", "Episode $it") }
        val (page, total) = pageOfVideos(all, 60, 60, "")
        assertEquals(60, page.size)
        assertEquals("v61", page.first().videoId)
        assertEquals(130, total)
    }

    @Test
    fun `the snapshot says what is playing and how long the timer has left`() {
        val snapshot = RemoteSnapshot(
            screen = RemoteScreen.PLAYER,
            openSourceId = "UU1",
            nowPlaying = RemoteNowPlaying("v1", "Picnic", 125, 600, playing = true),
            sources = listOf(RemoteSource("UU1", "Masha", 1546)),
            sleep = SleepTimer(stopAfterThisVideo = false, stopAtMillis = 100_000L),
            nowMillis = 40_000L,
        )
        val json = JSONObject(snapshot.toJson())
        assertEquals("player", json.getString("screen"))
        assertEquals("Picnic", json.getJSONObject("nowPlaying").getString("title"))
        assertEquals(125, json.getJSONObject("nowPlaying").getInt("position"))
        assertEquals(1546, json.getJSONArray("sources").getJSONObject(0).getInt("count"))
        assertEquals(60, json.getJSONObject("sleep").getInt("secondsLeft"))
    }

    @Test
    fun `a snapshot with nothing playing and no timer says so`() {
        val json = JSONObject(
            RemoteSnapshot(RemoteScreen.HOME, null, null, emptyList(), SleepTimer.OFF, 0L).toJson(),
        )
        assertTrue(json.isNull("nowPlaying"))
        assertTrue(json.getJSONObject("sleep").isNull("secondsLeft"))
    }

    @Test
    fun `the page allows its own script by hash and pictures from YouTube only`() {
        assertTrue(REMOTE_CONTENT_SECURITY_POLICY.contains(REMOTE_PAGE_SCRIPT_HASH))
        assertTrue(REMOTE_CONTENT_SECURITY_POLICY.contains("img-src https://i.ytimg.com"))
        assertTrue(remotePageHtml("/$token").contains("data-base=\"/$token\""))
        assertTrue(remotePageHtml("/$token").contains(REMOTE_PAGE_SCRIPT))
    }
}
