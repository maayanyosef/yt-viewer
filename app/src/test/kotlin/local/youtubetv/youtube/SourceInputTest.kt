package local.youtubetv.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every shape spec section 6.1 names, plus the rubbish a household can type.
 *
 * The heart of ticket 11 is a pure function, so this is where the ticket is really proven.
 * None of it touches the network, so none of it costs quota.
 */
class SourceInputTest {

    // The real ids this project already uses, so a test failure can be checked by hand.
    private val curatedPlaylist = "PLmadeUpPlaylistForTestsZ000000001"
    private val tedUploads = "UUAuUUnT6oDeKwE6v1NGQxug"
    private val tedChannel = "UCAuUUnT6oDeKwE6v1NGQxug"

    // ---------------------------------------------------------------- handles

    @Test
    fun `an at-handle is read as a handle without its at`() {
        assertEquals(SourceInput.Handle("TED"), parseSourceInput("@TED"))
    }

    @Test
    fun `a handle URL is read as a handle`() {
        assertEquals(
            SourceInput.Handle("examplechannel"),
            parseSourceInput("https://www.youtube.com/@examplechannel"),
        )
    }

    @Test
    fun `a handle URL with a trailing path is still a handle`() {
        assertEquals(
            SourceInput.Handle("TED"),
            parseSourceInput("https://www.youtube.com/@TED/videos"),
        )
    }

    @Test
    fun `a handle may carry dots dashes and underscores`() {
        assertEquals(SourceInput.Handle("a.b-c_d"), parseSourceInput("@a.b-c_d"))
    }

    @Test
    fun `a bare at with nothing after it is not a handle`() {
        assertEquals(SourceInput.Unrecognised, parseSourceInput("@"))
    }

    @Test
    fun `a handle of two characters is too short to be one`() {
        assertEquals(SourceInput.Unrecognised, parseSourceInput("@ab"))
    }

    // ---------------------------------------------------------------- channels

    @Test
    fun `a bare UC id is a channel`() {
        assertEquals(SourceInput.ChannelId(tedChannel), parseSourceInput(tedChannel))
    }

    @Test
    fun `a channel URL is a channel`() {
        assertEquals(
            SourceInput.ChannelId(tedChannel),
            parseSourceInput("https://www.youtube.com/channel/$tedChannel"),
        )
    }

    @Test
    fun `a channel URL carrying something that is not a UC id is refused`() {
        assertEquals(SourceInput.Unrecognised, parseSourceInput("youtube.com/channel/nonsense"))
    }

    @Test
    fun `a legacy user URL asks the forUsername endpoint`() {
        assertEquals(
            SourceInput.Username("TEDtalksDirector"),
            parseSourceInput("https://www.youtube.com/user/TEDtalksDirector"),
        )
    }

    @Test
    fun `a custom c URL is tried as the handle of the same spelling`() {
        assertEquals(SourceInput.Handle("TED"), parseSourceInput("https://www.youtube.com/c/TED"))
    }

    // ---------------------------------------------------------------- playlists

    @Test
    fun `a bare PL id is a playlist`() {
        assertEquals(SourceInput.PlaylistId(curatedPlaylist), parseSourceInput(curatedPlaylist))
    }

    @Test
    fun `a playlist URL is a playlist`() {
        assertEquals(
            SourceInput.PlaylistId(curatedPlaylist),
            parseSourceInput("https://www.youtube.com/playlist?list=$curatedPlaylist"),
        )
    }

    @Test
    fun `an uploads playlist id is a playlist and not a channel`() {
        assertEquals(SourceInput.PlaylistId(tedUploads), parseSourceInput(tedUploads))
    }

    @Test
    fun `a Shorts companion playlist id is still a playlist`() {
        assertEquals(
            SourceInput.PlaylistId("UUSHAuUUnT6oDeKwE6v1NGQxug"),
            parseSourceInput("UUSHAuUUnT6oDeKwE6v1NGQxug"),
        )
    }

    @Test
    fun `a playlist URL with no list parameter is refused`() {
        assertEquals(SourceInput.Unrecognised, parseSourceInput("youtube.com/playlist"))
    }

    // ---------------------------------------------------------------- videos

    @Test
    fun `a watch URL is a video`() {
        assertEquals(
            SourceInput.Video("TMade-Up_02"),
            parseSourceInput("https://www.youtube.com/watch?v=TMade-Up_02"),
        )
    }

    @Test
    fun `a watch URL carrying a list offers the playlist as well`() {
        assertEquals(
            SourceInput.Video("TMade-Up_02", playlistId = curatedPlaylist),
            parseSourceInput("https://www.youtube.com/watch?v=TMade-Up_02&list=$curatedPlaylist"),
        )
    }

    @Test
    fun `a watch URL carrying an index as well still finds both`() {
        assertEquals(
            SourceInput.Video("TMade-Up_02", playlistId = curatedPlaylist),
            parseSourceInput(
                "https://m.youtube.com/watch?v=TMade-Up_02&list=$curatedPlaylist&index=4&t=90s",
            ),
        )
    }

    @Test
    fun `a youtu dot be link is a video`() {
        assertEquals(SourceInput.Video("TMade-Up_02"), parseSourceInput("https://youtu.be/TMade-Up_02"))
    }

    @Test
    fun `a youtu dot be link carrying a list offers the playlist too`() {
        assertEquals(
            SourceInput.Video("TMade-Up_02", playlistId = curatedPlaylist),
            parseSourceInput("https://youtu.be/TMade-Up_02?list=$curatedPlaylist"),
        )
    }

    @Test
    fun `a shorts link is a video`() {
        assertEquals(
            SourceInput.Video("TMade-Up_02"),
            parseSourceInput("https://www.youtube.com/shorts/TMade-Up_02"),
        )
    }

    @Test
    fun `an embed link is a video`() {
        assertEquals(
            SourceInput.Video("TMade-Up_02"),
            parseSourceInput("https://www.youtube-nocookie.com/embed/TMade-Up_02"),
        )
    }

    @Test
    fun `a bare eleven character id is a video`() {
        assertEquals(SourceInput.Video("TMade-Up_02"), parseSourceInput("TMade-Up_02"))
    }

    // ---------------------------------------------------------------- rubbish

    @Test
    fun `nothing typed is unrecognised`() {
        assertEquals(SourceInput.Unrecognised, parseSourceInput(""))
        assertEquals(SourceInput.Unrecognised, parseSourceInput("   "))
    }

    @Test
    fun `words are a name, which ticket 12 searches for`() {
        assertEquals(SourceInput.Name("example channel"), parseSourceInput("example channel"))
    }

    @Test
    fun `one word that is no id at all is a name`() {
        assertEquals(SourceInput.Name("cooking"), parseSourceInput("cooking"))
    }

    @Test
    fun `a link to another site is not a YouTube link and reads as a name`() {
        assertEquals(
            SourceInput.Name("https://vimeo.com/12345"),
            parseSourceInput("https://vimeo.com/12345"),
        )
    }

    @Test
    fun `a YouTube link to a page this app knows nothing about is refused`() {
        assertEquals(SourceInput.Unrecognised, parseSourceInput("https://www.youtube.com/feed/history"))
    }

    @Test
    fun `the bare home page is refused`() {
        assertEquals(SourceInput.Unrecognised, parseSourceInput("https://www.youtube.com/"))
    }

    // ---------------------------------------------------------------- shapes of the text

    @Test
    fun `surrounding spaces are ignored`() {
        assertEquals(
            SourceInput.PlaylistId(curatedPlaylist),
            parseSourceInput("   $curatedPlaylist \n"),
        )
    }

    @Test
    fun `a link with no scheme is still a link`() {
        assertEquals(
            SourceInput.Handle("TED"),
            parseSourceInput("youtube.com/@TED"),
        )
    }

    @Test
    fun `a link with a plain http scheme is still a link`() {
        assertEquals(
            SourceInput.ChannelId(tedChannel),
            parseSourceInput("http://youtube.com/channel/$tedChannel"),
        )
    }

    @Test
    fun `the host is read whatever case it is typed in`() {
        assertEquals(SourceInput.Handle("TED"), parseSourceInput("https://WWW.YouTube.COM/@TED"))
    }

    @Test
    fun `a music dot youtube playlist link is read like any other`() {
        assertEquals(
            SourceInput.PlaylistId(curatedPlaylist),
            parseSourceInput("https://music.youtube.com/playlist?list=$curatedPlaylist"),
        )
    }

    @Test
    fun `a fragment after the query is ignored`() {
        assertEquals(
            SourceInput.Video("TMade-Up_02"),
            parseSourceInput("https://www.youtube.com/watch?v=TMade-Up_02#t=10"),
        )
    }
}
