package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.Direction
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.WatchKind
import local.youtubetv.data.WatchLog
import local.youtubetv.walk.FakeYouTubeApi
import local.youtubetv.walk.channelHit
import local.youtubetv.walk.fakeChannel
import local.youtubetv.walk.fakePlaylist
import local.youtubetv.walk.normalVideo
import local.youtubetv.walk.playlistHit
import local.youtubetv.youtube.SourceResolver
import local.youtubetv.youtube.SourceSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ADDED_AT: Instant = Instant.parse("2026-09-20T11:00:00Z")

/**
 * The whole add flow, from what was typed to what was stored.
 *
 * Every test here runs against a fake YouTube and costs no quota. The one thing they all
 * check, whether they say so or not, is that nothing on any path reaches `search.list`: the
 * seam simply has no such endpoint, so a typed name is refused rather than searched for.
 */
class AddSourceModelTest {

    private val channelId = "UCAuUUnT6oDeKwE6v1NGQxug"
    private val uploads = "UUAuUUnT6oDeKwE6v1NGQxug"
    private val curated = "PLmadeUpPlaylistForTestsZ000000001"

    private val ted = fakeChannel(channelId, title = "TED", handle = "@TED", videoCount = 5813)

    private val store = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val watches = WatchLog(watchStore)
    private val log = mutableListOf<String>()

    private fun model(api: FakeYouTubeApi) = AddSourceModel(
        store = store,
        watches = watches,
        resolver = SourceResolver(api),
        search = SourceSearch(api),
        log = { log += it },
        now = { ADDED_AT },
    )

    private fun tedApi() = FakeYouTubeApi(
        playlists = emptyMap(),
        channels = mapOf(channelId to ted),
        channelsByHandle = mapOf("TED" to ted),
        playlistDetails = mapOf(curated to fakePlaylist(curated, title = "Best talks", itemCount = 40)),
        channelPlaylists = mapOf(channelId to listOf(fakePlaylist(curated, title = "Best talks"))),
    )

    // ---------------------------------------------------------------- the four paths

    @Test
    fun `an at-handle opens that channel's second step`() = runBlocking {
        val model = model(tedApi())
        model.typed("@TED")
        model.find()

        // One channel and nothing else, so the first step is skipped and the channel's own
        // playlists are asked for straight away.
        val choosing = model.state.value as AddSourceState.Choosing
        assertEquals(ADD_PICK_ONE, choosing.title)
        assertEquals(listOf(uploads), choosing.rows.map { it.playlistId })
        assertTrue(choosing.rows.single().secondStep)
    }

    @Test
    fun `picking the channel offers All uploads and its playlists`() = runBlocking {
        val model = model(tedApi())
        model.typed("@TED")
        model.find()
        model.choose((model.state.value as AddSourceState.Choosing).rows.single())

        val step = model.state.value as AddSourceState.Choosing
        assertEquals("What of TED?", step.title)
        assertEquals(listOf(uploads, curated), step.rows.map { it.playlistId })
    }

    @Test
    fun `a pasted playlist URL goes straight to the add screen`() = runBlocking {
        val model = model(tedApi())
        model.typed("https://www.youtube.com/playlist?list=$curated")
        model.find()

        val confirming = model.state.value as AddSourceState.Confirming
        assertEquals(curated, confirming.candidate.playlistId)
        assertEquals("Best talks", confirming.candidate.title)
        assertEquals(40, confirming.candidate.videoCount)
    }

    @Test
    fun `a bare channel id resolves the same way a channel URL does`() = runBlocking {
        val model = model(tedApi())
        model.typed(channelId)
        model.find()

        assertTrue(model.state.value is AddSourceState.Choosing)
    }

    @Test
    fun `a pasted channel URL resolves`() = runBlocking {
        val model = model(tedApi())
        model.typed("https://www.youtube.com/channel/$channelId")
        model.find()

        val rows = (model.state.value as AddSourceState.Choosing).rows
        assertEquals("TED", rows.single().title)
    }

    // ---------------------------------------------------------------- the add screen

    @Test
    fun `the add screen starts on newest first`() = runBlocking {
        val model = model(tedApi())
        model.typed(curated)
        model.find()

        assertEquals(
            Direction.NEWEST_FIRST,
            (model.state.value as AddSourceState.Confirming).direction,
        )
    }

    @Test
    fun `Add stores the row, the title and the chosen Direction, and walks nothing`() = runBlocking {
        val api = tedApi()
        val model = model(api)
        model.typed(curated)
        model.find()
        model.setDirection(Direction.OLDEST_FIRST)
        val added = model.add()

        assertEquals(AddedSource(curated, "Best talks"), added)
        val stored = store.source(curated)!!
        assertEquals("Best talks", stored.title)
        assertEquals(Direction.OLDEST_FIRST, stored.direction)
        assertEquals(ADDED_AT, stored.addedAt)
        assertNull("adding a Source never walks it", stored.lastWalkedAt)
        assertEquals(0, api.playlistItemCalls)
        assertEquals(0, api.videoCalls)
    }

    @Test
    fun `Add writes one sourceAdded entry`() = runBlocking {
        val model = model(tedApi())
        model.typed(curated)
        model.find()
        model.add()

        assertEquals(listOf(WatchKind.SOURCE_ADDED), watchStore.entries().map { it.kind })
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    fun `a playlist the household already has is refused before YouTube is asked`() = runBlocking {
        store.saveSource(StoredSource(curated, "Best talks", null, addedAt = ADDED_AT))
        val api = tedApi()
        val model = model(api)
        model.typed(curated)
        model.find()

        assertEquals(
            AddSourceState.AlreadyAdded(curated, "Best talks"),
            model.state.value,
        )
        // The whole point: refusing a duplicate playlist id costs nothing at all.
        assertEquals(0, api.playlistCalls)
        assertEquals(0, api.channelCalls)
    }

    @Test
    fun `pasting a channel's uploads playlist after adding the channel is refused`() = runBlocking {
        // The household added the channel, so what is stored is its uploads playlist id.
        store.saveSource(StoredSource(uploads, "TED", null, addedAt = ADDED_AT))
        val api = tedApi()
        val model = model(api)
        model.typed("https://www.youtube.com/playlist?list=$uploads")
        model.find()

        assertEquals(AddSourceState.AlreadyAdded(uploads, "TED"), model.state.value)
        assertEquals(0, api.playlistCalls)
    }

    @Test
    fun `adding the same channel twice is refused at the row it resolves to`() = runBlocking {
        store.saveSource(StoredSource(uploads, "TED", null, addedAt = ADDED_AT))
        val model = model(tedApi())
        model.typed("@TED")
        model.find()
        // The handle resolves to a channel, whose second step holds `All uploads`. Picking it
        // is where the collision is found, because that is where the playlist id is known.
        model.choose((model.state.value as AddSourceState.Choosing).rows.single())
        val uploadsRow = (model.state.value as AddSourceState.Choosing).rows.first()
        model.choose(uploadsRow)

        assertEquals(AddSourceState.AlreadyAdded(uploads, "TED"), model.state.value)
    }

    @Test
    fun `the offer to open the existing Source names it`() = runBlocking {
        store.saveSource(StoredSource(curated, "Best talks", null, addedAt = ADDED_AT))
        val model = model(tedApi())
        model.typed(curated)
        model.find()

        val already = model.state.value as AddSourceState.AlreadyAdded
        assertEquals("Open Best talks", openExisting(already.name))
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun `words YouTube knows nothing about are refused, and the query is repeated back`() =
        runBlocking {
            val api = tedApi()
            val model = model(api)
            model.typed("example channel")
            model.find()

            val typing = model.state.value as AddSourceState.Typing
            assertEquals("example channel", typing.typed)
            assertEquals(searchFoundNothing("example channel"), typing.message)
            // One `search.list` call, and the two pricing calls skipped because there was
            // nothing to price. An empty answer must not cost 3.
            assertEquals(1, api.searchCalls)
            assertEquals(0, api.channelCalls)
            assertEquals(0, api.playlistCalls)
        }

    @Test
    fun `a handle YouTube does not know is refused with the text kept`() = runBlocking {
        val model = model(tedApi())
        model.typed("@nobodyatall")
        model.find()

        val typing = model.state.value as AddSourceState.Typing
        assertEquals("the typed text is kept so one character can be fixed", "@nobodyatall", typing.typed)
        assertEquals(ADD_NOT_FOUND, typing.message)
    }

    @Test
    fun `typing again clears the last refusal`() = runBlocking {
        val model = model(tedApi())
        model.typed("@nobodyatall")
        model.find()
        model.typed("@nobodyatal")

        assertNull((model.state.value as AddSourceState.Typing).message)
    }

    @Test
    fun `nothing typed is refused and costs nothing`() = runBlocking {
        val api = tedApi()
        val model = model(api)
        model.find()

        assertEquals(ADD_UNREADABLE, (model.state.value as AddSourceState.Typing).message)
        assertEquals(0, api.channelCalls)
    }

    // ---------------------------------------------------------------- BACK

    @Test
    fun `BACK walks the steps back one at a time and then lets go`() = runBlocking {
        val model = model(tedApi())
        model.typed("@TED")
        model.find()
        model.choose((model.state.value as AddSourceState.Choosing).rows.single())
        model.choose((model.state.value as AddSourceState.Choosing).rows.first())
        assertTrue(model.state.value is AddSourceState.Confirming)

        assertTrue(model.back())
        assertEquals("What of TED?", (model.state.value as AddSourceState.Choosing).title)

        assertTrue(model.back())
        assertEquals(ADD_PICK_ONE, (model.state.value as AddSourceState.Choosing).title)

        assertTrue(model.back())
        assertEquals("@TED", (model.state.value as AddSourceState.Typing).typed)

        // Nothing left, so the Activity closes the screen. A guard with no way off it is the
        // one outcome this project keeps warning about.
        assertFalse(model.back())
    }

    @Test
    fun `BACK from a refusal goes back to the field`() = runBlocking {
        store.saveSource(StoredSource(curated, "Best talks", null, addedAt = ADDED_AT))
        val model = model(tedApi())
        model.typed(curated)
        model.find()

        assertTrue(model.back())
        assertEquals(curated, (model.state.value as AddSourceState.Typing).typed)
    }

    @Test
    fun `BACK on the field alone lets go straight away`() {
        assertFalse(model(tedApi()).back())
    }

    // ------------------------------------------------------- finding a Source by name, ticket 12

    /** A model with a [SourceSearch] the caller owns, so two screens can share one cache. */
    private fun model(api: FakeYouTubeApi, search: SourceSearch) = AddSourceModel(
        store = store,
        watches = watches,
        resolver = SourceResolver(api),
        search = search,
        log = { log += it },
        now = { ADDED_AT },
    )

    private val video = "TMade-Up_02"

    /** A fake that answers the query `ted talks` with the channel and one of its playlists. */
    private fun searchApi() = FakeYouTubeApi(
        playlists = emptyMap(),
        videos = mapOf(video to normalVideo(video).copy(channelId = channelId)),
        channels = mapOf(channelId to ted),
        playlistDetails = mapOf(
            curated to fakePlaylist(
                curated,
                title = "Best talks",
                channelId = channelId,
                channelTitle = "TED",
                itemCount = 40,
            ),
        ),
        channelPlaylists = mapOf(channelId to listOf(fakePlaylist(curated, title = "Best talks"))),
        searchResults = mapOf(
            "ted talks" to listOf(
                channelHit(channelId, title = "TED"),
                playlistHit(curated, title = "Best talks"),
            ),
        ),
    )

    @Test
    fun `typing alone reaches no endpoint at all, least of all search`() = runBlocking {
        val api = searchApi()
        val model = model(api)
        // Every keystroke of a household typing the name of a channel. Spec section 6.1: the
        // search runs on a submit and never on a keystroke, because the call it would spend
        // comes from a bucket of a hundred a day.
        for (length in 1.."ted talks".length) model.typed("ted talks".take(length))

        assertEquals(0, api.searchCalls)
        assertEquals(0, api.channelCalls)
        assertEquals(0, api.playlistCalls)
        assertEquals(0, api.videoCalls)
        assertEquals("ted talks", (model.state.value as AddSourceState.Typing).typed)
    }

    @Test
    fun `a typed name is searched for on submit, and the results are shown`() = runBlocking {
        val api = searchApi()
        val model = model(api)
        model.typed("ted talks")
        model.find()

        val choosing = model.state.value as AddSourceState.Choosing
        assertEquals(searchResultsTitle("ted talks"), choosing.title)
        assertEquals(listOf(uploads, curated), choosing.rows.map { it.playlistId })
        assertEquals(1, api.searchCalls)
    }

    @Test
    fun `repeating the same query in one session makes no second search call`() = runBlocking {
        val api = searchApi()
        // One search per session, shared by every add screen, which is how `MainActivity`
        // builds it. A fresh one per screen would pay for the same query twice.
        val search = SourceSearch(api)

        val first = model(api, search)
        first.typed("ted talks")
        first.find()

        // The household left the add screen and came back, which builds a new model.
        val second = model(api, search)
        second.typed("ted talks")
        second.find()

        assertTrue(second.state.value is AddSourceState.Choosing)
        assertEquals(1, api.searchCalls)
    }

    @Test
    fun `picking a channel result opens the second step with All uploads`() = runBlocking {
        val api = searchApi()
        val model = model(api)
        model.typed("ted talks")
        model.find()
        model.choose((model.state.value as AddSourceState.Choosing).rows.first())

        val step = model.state.value as AddSourceState.Choosing
        assertEquals("What of TED?", step.title)
        assertEquals(listOf(uploads, curated), step.rows.map { it.playlistId })
        assertEquals(ALL_UPLOADS, step.rows.first().title)
        // The second step is one `playlists.list?channelId=` call, and no second search.
        assertEquals(1, api.searchCalls)
    }

    @Test
    fun `picking a playlist result goes straight to the add screen`() = runBlocking {
        val api = searchApi()
        val model = model(api)
        model.typed("ted talks")
        model.find()
        model.choose((model.state.value as AddSourceState.Choosing).rows.last())

        val confirming = model.state.value as AddSourceState.Confirming
        assertEquals(curated, confirming.candidate.playlistId)
        assertEquals(1, api.searchCalls)
    }

    @Test
    fun `a pasted watch URL offers that video's channel, and costs no search call`() = runBlocking {
        val api = searchApi()
        val model = model(api)
        model.typed("https://www.youtube.com/watch?v=$video")
        model.find()

        val rows = (model.state.value as AddSourceState.Choosing).rows
        assertEquals("TED", rows.single().title)
        assertEquals(uploads, rows.single().playlistId)
        // Spec section 6.2: one `videos.list` call gets the channel. The expensive bucket is
        // never touched by a pasted link.
        assertEquals(0, api.searchCalls)
    }

    @Test
    fun `the same URL with a list offers both the playlist and the channel`() = runBlocking {
        val api = searchApi()
        val model = model(api)
        model.typed("https://www.youtube.com/watch?v=$video&list=$curated")
        model.find()

        val rows = (model.state.value as AddSourceState.Choosing).rows
        // The channel first: a household that pasted a video link wants the channel far more
        // often than whatever playlist it happened to be playing from.
        assertEquals(listOf(uploads, curated), rows.map { it.playlistId })
        assertEquals("CHANNEL", candidateBadge(rows.first()))
        assertEquals("PLAYLIST", candidateBadge(rows.last()))
        assertEquals(0, api.searchCalls)
    }

    @Test
    fun `an exhausted search bucket is said plainly, and the other two paths keep working`() =
        runBlocking {
            val api = FakeYouTubeApi(
                playlists = emptyMap(),
                channels = mapOf(channelId to ted),
                channelsByHandle = mapOf("TED" to ted),
                playlistDetails = mapOf(curated to fakePlaylist(curated, title = "Best talks")),
                failingSearches = mapOf(
                    "ted talks" to local.youtubetv.youtube.YouTubeApiException.Reason.QUOTA_EXCEEDED,
                ),
            )
            val search = SourceSearch(api)
            val model = model(api, search)
            model.typed("ted talks")
            model.find()

            val typing = model.state.value as AddSourceState.Typing
            assertEquals(SEARCH_QUOTA_EXHAUSTED, typing.message)
            assertEquals("the text is kept so it can be turned into a link", "ted talks", typing.typed)

            // The @handle path draws on the other pool entirely and is untouched.
            val byHandle = model(api, search)
            byHandle.typed("@TED")
            byHandle.find()
            assertTrue(byHandle.state.value is AddSourceState.Choosing)

            // And the pasted-playlist path, which is the other of the two.
            val byPaste = model(api, search)
            byPaste.typed(curated)
            byPaste.find()
            assertTrue(byPaste.state.value is AddSourceState.Confirming)
        }
}
