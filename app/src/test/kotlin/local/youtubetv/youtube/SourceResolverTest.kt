package local.youtubetv.youtube

import kotlinx.coroutines.runBlocking
import local.youtubetv.walk.FakeYouTubeApi
import local.youtubetv.walk.fakeChannel
import local.youtubetv.walk.fakePlaylist
import local.youtubetv.walk.normalVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every path spec section 6.1 names, resolved against a fake YouTube.
 *
 * It also pins the **cost** of each path, by endpoint. That is the number this project cares
 * most about, and `search.list` is asserted absent because it is the one endpoint with a
 * bucket of 100 calls a day.
 */
class SourceResolverTest {

    private val channelId = "UCAuUUnT6oDeKwE6v1NGQxug"
    private val uploads = "UUAuUUnT6oDeKwE6v1NGQxug"
    private val curated = "PLmadeUpPlaylistForTestsZ000000001"

    private val ted = fakeChannel(channelId, title = "TED", handle = "@TED", videoCount = 5813)

    /** Every endpoint the resolver reached for, in order. */
    private val calls = mutableListOf<String>()

    private fun resolver(api: FakeYouTubeApi) = SourceResolver(api) { calls += it }

    // ---------------------------------------------------------------- handles and channels

    @Test
    fun `a handle costs one channels call and finds the channel`() = runBlocking {
        val api = FakeYouTubeApi(playlists = emptyMap(), channelsByHandle = mapOf("TED" to ted))
        val found = resolver(api).resolve(SourceInput.Handle("TED")) as Resolved.Found

        assertEquals(ted, found.channel)
        assertNull(found.playlist)
        assertEquals(listOf(SourceResolver.CHANNELS_LIST), calls)
    }

    @Test
    fun `a channel id costs one channels call`() = runBlocking {
        val api = FakeYouTubeApi(playlists = emptyMap(), channels = mapOf(channelId to ted))
        val found = resolver(api).resolve(SourceInput.ChannelId(channelId)) as Resolved.Found

        assertEquals(ted, found.channel)
        assertEquals(listOf(SourceResolver.CHANNELS_LIST), calls)
    }

    @Test
    fun `a legacy user name costs one channels call`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            channelsByUsername = mapOf("TEDtalksDirector" to ted),
        )
        val found = resolver(api).resolve(SourceInput.Username("TEDtalksDirector")) as Resolved.Found

        assertEquals(ted, found.channel)
    }

    @Test
    fun `a handle YouTube does not know is not found`() = runBlocking {
        val api = FakeYouTubeApi(playlists = emptyMap())
        assertEquals(Resolved.NotFound, resolver(api).resolve(SourceInput.Handle("nobody")))
    }

    // ---------------------------------------------------------------- playlists

    @Test
    fun `a playlist id costs one playlists call`() = runBlocking {
        val playlist = fakePlaylist(curated, title = "The curated one", itemCount = 588)
        val api = FakeYouTubeApi(playlists = emptyMap(), playlistDetails = mapOf(curated to playlist))
        val found = resolver(api).resolve(SourceInput.PlaylistId(curated)) as Resolved.Found

        assertEquals(playlist, found.playlist)
        assertNull(found.channel)
        assertEquals(listOf(SourceResolver.PLAYLISTS_LIST), calls)
    }

    @Test
    fun `an uploads playlist YouTube will not list falls back on its own channel`() = runBlocking {
        // `playlists.list?id=UU...` answers with nothing, which is what YouTube really does
        // for an auto-generated uploads playlist.
        val api = FakeYouTubeApi(playlists = emptyMap(), channels = mapOf(channelId to ted))
        val found = resolver(api).resolve(SourceInput.PlaylistId(uploads)) as Resolved.Found

        assertEquals(ted, found.channel)
        assertEquals(
            listOf(SourceResolver.PLAYLISTS_LIST, SourceResolver.CHANNELS_LIST),
            calls,
        )
    }

    @Test
    fun `the fallback trusts the channel's own answer and not the id's shape`() = runBlocking {
        // A channel whose uploads playlist is not the `UU` of its own id. Nothing is
        // substituted, so the pasted id is simply not found.
        val odd = fakeChannel(channelId, uploadsPlaylistId = "UUsomethingelse")
        val api = FakeYouTubeApi(playlists = emptyMap(), channels = mapOf(channelId to odd))

        assertEquals(Resolved.NotFound, resolver(api).resolve(SourceInput.PlaylistId(uploads)))
    }

    @Test
    fun `a Shorts companion playlist is never turned into a channel lookup`() = runBlocking {
        val api = FakeYouTubeApi(playlists = emptyMap(), channels = mapOf(channelId to ted))
        val shorts = "UUSHAuUUnT6oDeKwE6v1NGQxug"

        assertEquals(Resolved.NotFound, resolver(api).resolve(SourceInput.PlaylistId(shorts)))
        assertEquals(listOf(SourceResolver.PLAYLISTS_LIST), calls)
    }

    // ---------------------------------------------------------------- video links

    @Test
    fun `a video link offers the video's channel`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            videos = mapOf("TMade-Up_02" to normalVideo("TMade-Up_02").copy(channelId = channelId)),
            channels = mapOf(channelId to ted),
        )
        val found = resolver(api).resolve(SourceInput.Video("TMade-Up_02")) as Resolved.Found

        assertEquals(ted, found.channel)
        assertNull(found.playlist)
        assertEquals(listOf(SourceResolver.VIDEOS_LIST, SourceResolver.CHANNELS_LIST), calls)
    }

    @Test
    fun `a video link carrying a list offers both, at three units`() = runBlocking {
        val playlist = fakePlaylist(curated)
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            videos = mapOf("TMade-Up_02" to normalVideo("TMade-Up_02").copy(channelId = channelId)),
            channels = mapOf(channelId to ted),
            playlistDetails = mapOf(curated to playlist),
        )
        val found = resolver(api)
            .resolve(SourceInput.Video("TMade-Up_02", playlistId = curated)) as Resolved.Found

        assertEquals(ted, found.channel)
        assertEquals(playlist, found.playlist)
        assertEquals(3, calls.size)
    }

    @Test
    fun `a video YouTube no longer serves is not found`() = runBlocking {
        val api = FakeYouTubeApi(playlists = emptyMap())
        assertEquals(Resolved.NotFound, resolver(api).resolve(SourceInput.Video("TMade-Up_02")))
    }

    // ---------------------------------------------------------------- the paths that cost nothing

    @Test
    fun `a typed name is handed to the search path and costs no call at all`() = runBlocking {
        val api = FakeYouTubeApi(playlists = emptyMap())
        assertEquals(
            Resolved.NeedsSearch("example channel"),
            resolver(api).resolve(SourceInput.Name("example channel")),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `text this app cannot read costs no call at all`() = runBlocking {
        val api = FakeYouTubeApi(playlists = emptyMap())
        assertEquals(Resolved.Unreadable, resolver(api).resolve(SourceInput.Unrecognised))
        assertTrue(calls.isEmpty())
    }

    // ---------------------------------------------------------------- the two steps

    @Test
    fun `the first step puts the channel before the playlist`() {
        val rows = firstStepRows(Resolved.Found(channel = ted, playlist = fakePlaylist(curated)))

        assertEquals(listOf(CandidateKind.CHANNEL, CandidateKind.PLAYLIST), rows.map { it.kind })
        assertEquals(uploads, rows[0].playlistId)
        assertTrue(rows[0].secondStep)
        assertEquals(curated, rows[1].playlistId)
    }

    @Test
    fun `the second step is All uploads and then the channel's own playlists`() = runBlocking {
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            channelPlaylists = mapOf(
                channelId to listOf(fakePlaylist(curated, title = "Best of"), fakePlaylist("PL2")),
            ),
        )
        val rows = resolver(api).channelStep(ted, "All uploads")

        assertEquals(listOf(uploads, curated, "PL2"), rows.map { it.playlistId })
        // The uploads row is named `All uploads`, not `TED`. A channel that also owns a
        // playlist called `TED` would otherwise show two rows of the same name, which is the
        // case this step exists to avoid. Found by `/code-review`.
        assertEquals("All uploads", rows[0].title)
        assertEquals("@TED", rows[0].handle)
        assertEquals(5813, rows[0].videoCount)
        assertEquals(listOf(SourceResolver.PLAYLISTS_LIST), calls)
        // The second step never opens a third one.
        assertTrue(rows.none { it.secondStep })
    }

    @Test
    fun `the uploads playlist is never offered twice on the second step`() {
        val rows = channelStepRows(ted, listOf(fakePlaylist(uploads), fakePlaylist(curated)))
        assertEquals(listOf(uploads, curated), rows.map { it.playlistId })
    }

    @Test
    fun `a channel that has never posted has no All uploads row`() {
        val empty = fakeChannel(channelId, uploadsPlaylistId = null)
        assertEquals(listOf(curated), channelStepRows(empty, listOf(fakePlaylist(curated))).map { it.playlistId })
    }

    @Test
    fun `a playlist row names the channel that owns it`() {
        val rows = channelStepRows(
            fakeChannel(channelId, uploadsPlaylistId = null),
            listOf(fakePlaylist(curated, channelTitle = "TED")),
        )
        assertEquals("TED", rows.single().handle)
    }
}
