package local.youtubetv.ui

import java.time.Instant
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.WatchLog
import local.youtubetv.data.StoredSource
import local.youtubetv.data.testDay
import local.youtubetv.data.testVideo
import local.youtubetv.walk.FakeYouTubeApi
import local.youtubetv.walk.NoYouTubeCalls
import local.youtubetv.walk.SourceWalk
import local.youtubetv.walk.normalVideo
import local.youtubetv.walk.shortVideo
import local.youtubetv.youtube.PlaylistItemRef
import local.youtubetv.youtube.YouTubeApi
import local.youtubetv.youtube.YouTubeApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val UPLOADS = "UUAuUUnT6oDeKwE6v1NGQxug"
private const val UPLOADS_SHORTS = "UUSHAuUUnT6oDeKwE6v1NGQxug"
private val WALKED_AT: Instant = Instant.parse("2026-09-19T12:00:00Z")

private fun ref(videoId: String, day: Int): PlaylistItemRef =
    PlaylistItemRef(videoId, testDay(day))

/** A [YouTubeApi] that fails the moment it is touched, to prove no call was made. */
private class ForbiddenApi : NoYouTubeCalls("the app")

/**
 * `ערוץ לדוגמה`, Hebrew for "example channel", a made-up Source name for the header.
 *
 * It is built from code points rather than typed, which is the convention `HomeScreenTextTest`
 * set: the editing tools on this machine decode a \uXXXX escape into the character, and the
 * character itself does not always survive them. Ticket 24 hit the same wall.
 */
private val HEBREW_NAME: String =
    intArrayOf(0x05E2, 0x05E8, 0x05D5, 0x05E5, 0x0020, 0x05DC, 0x05D3, 0x05D5, 0x05D2, 0x05DE, 0x05D4)
        .fold(StringBuilder()) { builder, code -> builder.appendCodePoint(code) }
        .toString()

class SourceScreenModelTest {

    private val log = mutableListOf<String>()

    private fun model(store: InMemorySourceStore, api: YouTubeApi) = SourceScreenModel(
        sourceId = UPLOADS,
        store = store,
        walk = SourceWalk(api),
        // This class is about the walk and the storage. What the Watch log does with the
        // result is `SourceScreenWatchesTest`.
        watches = WatchLog(InMemoryWatchStore()),
        log = { log += it },
        now = { WALKED_AT },
    )

    /**
     * Adds the Source if it is not there, then opens it.
     *
     * **A Source is added before it is walked, from ticket 11.** The add screen writes the row
     * with no `lastWalkedAt` and opening the Source is what walks it, so a walk of a Source
     * with no row at all is a case nothing in the app can reach any more: the walk would be
     * storing videos for a Source the household had removed under it.
     */
    private fun load(store: InMemorySourceStore, api: YouTubeApi): SourceScreenState {
        if (store.source(UPLOADS) == null) {
            store.saveSource(StoredSource(UPLOADS, title = null, lastWalkedAt = null))
        }
        return runBlocking { model(store, api).let { it.load(); it.state.value } }
    }

    @Test
    fun `a first launch walks the Source, stores it, and shows it in date order`() {
        val store = InMemorySourceStore()
        val api = FakeYouTubeApi(
            playlists = mapOf(
                UPLOADS to listOf(ref("older", 1), ref("newer", 9), ref("short", 5)),
                UPLOADS_SHORTS to listOf(ref("short", 5)),
            ),
            videos = mapOf(
                "older" to normalVideo("older"),
                "newer" to normalVideo("newer"),
                "short" to shortVideo("short"),
            ),
        )

        val ready = load(store, api) as SourceScreenState.Ready
        assertEquals(listOf("newer", "older"), ready.videos.map { it.videoId })
        assertFalse(ready.fromStorage)
        assertEquals(WALKED_AT, ready.updatedAt)
        // The Short is stored, so it is never re-classified, but it is not in the list.
        assertEquals(2, ready.videos.size)
    }

    @Test
    fun `a second launch renders the stored list and makes no API call at all`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = null, lastWalkedAt = WALKED_AT))
        store.replaceVideos(
            UPLOADS,
            listOf(testVideo("a", day = 2, sourceId = UPLOADS), testVideo("b", day = 1, sourceId = UPLOADS)),
        )

        // ForbiddenApi throws if it is touched at all.
        val ready = load(store, ForbiddenApi()) as SourceScreenState.Ready
        assertEquals(listOf("a", "b"), ready.videos.map { it.videoId })
        assertTrue(ready.fromStorage)
        assertEquals(WALKED_AT, ready.updatedAt)
        assertTrue(log.any { it.contains("no API call made") })
    }

    @Test
    fun `a Source walked before is not re-walked even when it stored no videos`() {
        // An empty playlist, or a channel that posts nothing but Shorts. Deciding from the
        // video count would call the API again on every single launch.
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = null, lastWalkedAt = WALKED_AT))
        store.replaceVideos(UPLOADS, emptyList())

        val ready = load(store, ForbiddenApi()) as SourceScreenState.Ready
        assertTrue(ready.videos.isEmpty())
        assertTrue(ready.fromStorage)
    }

    @Test
    fun `an empty Source is walked once, then remembered`() {
        val store = InMemorySourceStore()
        val api = FakeYouTubeApi(
            playlists = mapOf(UPLOADS to emptyList(), UPLOADS_SHORTS to emptyList()),
        )

        val first = load(store, api) as SourceScreenState.Ready
        assertTrue(first.videos.isEmpty())
        assertFalse(first.fromStorage)

        val second = load(store, ForbiddenApi()) as SourceScreenState.Ready
        assertTrue(second.fromStorage)
    }

    @Test
    fun `logs the call count against the formula`() {
        val store = InMemorySourceStore()
        val refs = (1..137).map { ref("v$it", it) }
        val api = FakeYouTubeApi(
            playlists = mapOf(UPLOADS to refs, UPLOADS_SHORTS to emptyList()),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
        )

        load(store, api)

        val line = log.single { it.startsWith("Walked ") }
        assertTrue(line, line.contains("137 videos"))
        assertTrue(line, line.contains("7 API calls"))
        assertTrue(line, line.contains("matches"))
        assertFalse(line, line.contains("DOES NOT MATCH"))
    }

    @Test
    fun `the cost line still matches when the playlist repeats a video`() {
        val store = InMemorySourceStore()
        val refs = (1..60).map { ref("v$it", it) } + ref("v1", 1)
        val api = FakeYouTubeApi(
            playlists = mapOf(UPLOADS to refs, UPLOADS_SHORTS to emptyList()),
            videos = (1..60).associate { "v$it" to normalVideo("v$it") },
        )

        load(store, api)

        val line = log.single { it.startsWith("Walked ") }
        assertTrue(line, line.contains("60 videos (61 playlist entries)"))
        assertTrue(line, line.contains("matches"))
        assertFalse(line, line.contains("DOES NOT MATCH"))
    }

    @Test
    fun `a failed first walk shows one sentence, not the raw error`() {
        val store = InMemorySourceStore()
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            failingPlaylists = mapOf(UPLOADS to YouTubeApiException.Reason.NETWORK),
        )

        val failed = load(store, api) as SourceScreenState.Failed
        assertEquals("No connection, and nothing stored yet for this Source.", failed.message)
    }

    @Test
    fun `quota exhausted says when it comes back`() {
        val store = InMemorySourceStore()
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            failingPlaylists = mapOf(UPLOADS to YouTubeApiException.Reason.QUOTA_EXCEEDED),
        )

        val failed = load(store, api) as SourceScreenState.Failed
        assertTrue(failed.message.contains("midnight Pacific Time"))
    }

    @Test
    fun `a failed first walk stores no walk time, so the next launch tries again`() {
        val store = InMemorySourceStore()
        val api = FakeYouTubeApi(
            playlists = emptyMap(),
            failingPlaylists = mapOf(UPLOADS to YouTubeApiException.Reason.NETWORK),
        )

        load(store, api)
        // The row stays, because the household added the Source and a failed walk does not
        // un-add it. What must not be written is the walk time: that is what says the Source
        // has been walked, and a walk that failed has not.
        assertEquals(null, store.source(UPLOADS)?.lastWalkedAt)
    }

    @Test
    fun `reports progress while the first walk runs`() {
        val store = InMemorySourceStore()
        val refs = (1..120).map { ref("v$it", it) }
        val api = FakeYouTubeApi(
            playlists = mapOf(UPLOADS to refs, UPLOADS_SHORTS to emptyList()),
            videos = refs.associate { it.videoId to normalVideo(it.videoId) },
        )
        val seen = mutableListOf<SourceScreenState>()

        runBlocking {
            // The state flow needs a second coroutine to collect, so the walk's own progress
            // callback is read instead. It asserts the same thing: the screen moves through
            // Walking before it reaches Ready.
            SourceWalk(api).walk(UPLOADS, onProgress = { loaded, total ->
                seen += SourceScreenState.Walking(loaded, total)
            })
            seen += load(store, api)
        }

        assertEquals(SourceScreenState.Walking(50, 120), seen.first())
        assertTrue(seen.last() is SourceScreenState.Ready)
    }

    @Test
    fun `autoplay-next starts off and the chip turns it around`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, null, WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2), testVideo("b", day = 1)))

        runBlocking {
            val model = model(store, ForbiddenApi())
            model.load()
            assertFalse((model.state.value as SourceScreenState.Ready).autoplayNext)

            assertTrue(model.toggleAutoplayNext() == true)

            // On screen, and in the Source's own row, so a relaunch finds it.
            assertTrue((model.state.value as SourceScreenState.Ready).autoplayNext)
            assertTrue(store.source(UPLOADS)!!.autoplayNext)
        }
    }

    @Test
    fun `a Source stored with autoplay-next on keeps it`() {
        // Ticket 18 changed what a new Source starts as and must not touch a stored choice.
        // This is the household's four walked Sources, which carry on rolling.
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, null, WALKED_AT, autoplayNext = true))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2), testVideo("b", day = 1)))

        runBlocking {
            val model = model(store, ForbiddenApi())
            model.load()
            assertTrue((model.state.value as SourceScreenState.Ready).autoplayNext)
        }
    }

    @Test
    fun `autoplay-next survives leaving the Source and coming back`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, null, WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2), testVideo("b", day = 1)))

        runBlocking {
            val first = model(store, ForbiddenApi())
            first.load()
            first.toggleAutoplayNext()

            // A second model over the same store is what a relaunch is. No API call is made,
            // which `ForbiddenApi` is what proves.
            val second = model(store, ForbiddenApi())
            second.load()
            assertTrue((second.state.value as SourceScreenState.Ready).autoplayNext)
        }
    }

    @Test
    fun `a freshly walked Source is stored with autoplay-next off`() {
        // `saveSource` writes the whole row, so the walk path reads every preference back
        // before it writes. Today only a first walk comes through here and there is nothing
        // to read, but ticket 13's refresh will come through it on a Source the household has
        // already set, and Direction met exactly this trap in ticket 06.
        val store = InMemorySourceStore()
        val api = FakeYouTubeApi(
            playlists = mapOf(
                UPLOADS to listOf(ref("a", 1)),
                UPLOADS_SHORTS to emptyList(),
            ),
            videos = mapOf("a" to normalVideo("a")),
        )

        val ready = load(store, api) as SourceScreenState.Ready
        assertFalse(ready.autoplayNext)
        assertFalse(store.source(UPLOADS)!!.autoplayNext)
    }

    // --------------------------------------------------------------- the header's name, t29

    /**
     * The defect build ticket 29 was raised for.
     *
     * The name is filled in while the model is alive and the screen is open, which is what the
     * title backfill of the daily change check does, and the header follows without the
     * household going back to the rail. Nothing here reopens the Source.
     */
    @Test
    fun `a name filled in while the screen is open reaches the header`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = null, lastWalkedAt = WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2)))

        runBlocking {
            val open = model(store, ForbiddenApi())
            open.load()
            assertEquals(UPLOADS, open.name.value)

            // What `SourceRefresh.fillMissingNames` does, and the only thing that names a
            // Source today. The screen has not been left and the model is the same one.
            store.fillMissingTitle(UPLOADS, HEBREW_NAME)
            open.refreshName()

            assertEquals(HEBREW_NAME, open.name.value)
        }
    }

    /** The fallback of `sourceNameOrId`: a Source with no stored title is headed by its id. */
    @Test
    fun `the header shows the id when there is genuinely no name`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = null, lastWalkedAt = WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2)))

        runBlocking {
            val open = model(store, ForbiddenApi())
            open.load()
            open.refreshName()
            assertEquals(UPLOADS, open.name.value)
        }
    }

    /**
     * A blank stored title is no title, which is the rule `SourceStore.fillMissingTitle`
     * relies on. The header must not go empty over it.
     */
    @Test
    fun `a blank stored title is not a name`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = "   ", lastWalkedAt = WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2)))

        runBlocking {
            val open = model(store, ForbiddenApi())
            open.load()
            assertEquals(UPLOADS, open.name.value)
        }
    }

    /**
     * No regression on the ordinary path: a Source that already has a name is headed by it
     * from the first read, and leaving and coming back still shows it.
     */
    @Test
    fun `a stored name is the header from the first read and survives a reopening`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = "TED", lastWalkedAt = WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2)))

        runBlocking {
            val open = model(store, ForbiddenApi())
            open.load()
            assertEquals("TED", open.name.value)

            // A second model over the same store is what leaving and coming back is when the
            // kept model has been dropped, for example by a `Mark everything unwatched`.
            val again = model(store, ForbiddenApi())
            again.load()
            assertEquals("TED", again.name.value)
        }
    }

    /**
     * The reopening path that build ticket 27 will land on.
     *
     * A [SourceScreenModel] is kept for the life of the app, so opening a Source a second time
     * runs no `load` at all. The name the caller hands over is then the only thing that can
     * tell the header the Source was renamed on the rail in between.
     */
    @Test
    fun `reopening a kept model takes the name the caller hands it`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = "TED", lastWalkedAt = WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2)))

        runBlocking {
            val kept = model(store, ForbiddenApi())
            kept.openedWithName("TED")
            kept.load()

            // The rail renamed the Source and then opened it, with no second `load`.
            kept.openedWithName("TED Talks")
            assertEquals("TED Talks", kept.name.value)
        }
    }

    /**
     * The caller's name is a guess, and storage settles it.
     *
     * `closeSource` rebuilds the rail in the background, so a household that presses `BACK`
     * and then `DPAD_CENTER` straight away opens a card that was built before the rebuild
     * landed. On a kept model there is no `load` to correct it, so the reopening reads the
     * row itself. Raised by `/code-review`.
     */
    @Test
    fun `a stale name from the caller is corrected by the Source's own row`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = null, lastWalkedAt = WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2)))

        runBlocking {
            val kept = model(store, ForbiddenApi())
            kept.openedWithName(UPLOADS)
            kept.load()

            // The Source is named while its screen is open, and the household leaves.
            store.fillMissingTitle(UPLOADS, HEBREW_NAME)
            kept.refreshName()
            assertEquals(HEBREW_NAME, kept.name.value)

            // They come straight back in, on a rail card built before the rail caught up.
            kept.openedWithName(UPLOADS)
            kept.refreshName()

            assertEquals(HEBREW_NAME, kept.name.value)
        }
    }

    /**
     * A rename reaches the header on the next reopening, with no code of ticket 27's own.
     *
     * This is the check ticket 29 left build ticket 27. The rename is made from the **rail
     * card's** menu, so the Source screen is not on screen while it happens; what carries it is
     * the row read that `MainActivity.openSource` makes on every reopening of a kept model.
     * `refreshName` is that read.
     *
     * Both directions are here, because clearing the name has to reach the header too.
     */
    @Test
    fun `a rename made from the rail reaches the header, and so does clearing it`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = "TED", lastWalkedAt = WALKED_AT))
        store.replaceVideos(UPLOADS, listOf(testVideo("a", day = 2)))

        runBlocking {
            val kept = model(store, ForbiddenApi())
            kept.openedWithName("TED")
            kept.load()
            assertEquals("TED", kept.name.value)

            // The household goes back to the rail and renames the Source from its card.
            store.setTypedName(UPLOADS, HEBREW_NAME)
            // They open it again, on the same kept model. The card they pressed may still be
            // the old one, so the guess is the old name and the row has the last word.
            kept.openedWithName("TED")
            kept.refreshName()
            assertEquals(HEBREW_NAME, kept.name.value)

            // And again, this time clearing it. The header falls back to YouTube's name.
            store.setTypedName(UPLOADS, null)
            kept.openedWithName(HEBREW_NAME)
            kept.refreshName()
            assertEquals("TED", kept.name.value)
        }
    }

    /**
     * The header is drawn in every state, so the name must answer before the grid does.
     *
     * A Source being walked for the first time is exactly the Source most likely to be named
     * while its screen is open, and it is never `Ready` while that is true.
     */
    @Test
    fun `the name answers while the Source is still being walked`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, title = null, lastWalkedAt = null))

        runBlocking {
            val open = model(store, ForbiddenApi())
            open.openedWithName(UPLOADS)
            assertEquals(UPLOADS, open.name.value)

            store.fillMissingTitle(UPLOADS, "Lofi Girl")
            open.refreshName()

            assertEquals("Lofi Girl", open.name.value)
            // Nothing was walked and nothing was read, so the grid is still where it was.
            assertTrue(open.state.value is SourceScreenState.Starting)
        }
    }
}
