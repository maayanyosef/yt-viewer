package local.youtubetv.refresh

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import local.youtubetv.data.Availability
import local.youtubetv.data.Direction
import local.youtubetv.data.InMemorySourceStore
import local.youtubetv.data.InMemoryWatchStore
import local.youtubetv.data.RangeSide
import local.youtubetv.data.SourceStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.WatchLog
import local.youtubetv.data.nextUnwatchedIndex
import local.youtubetv.data.testDay
import local.youtubetv.walk.FakeYouTubeApi
import local.youtubetv.walk.SourceWalk
import local.youtubetv.walk.normalVideo
import local.youtubetv.youtube.PlaylistDetails
import local.youtubetv.youtube.PlaylistItemRef
import local.youtubetv.youtube.VideoDetails
import local.youtubetv.youtube.YouTubeApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A channel's uploads playlist, and the Shorts companion that belongs to it. */
private const val UPLOADS = "UUAuUUnT6oDeKwE6v1NGQxug"
private const val UPLOADS_SHORTS = "UUSHAuUUnT6oDeKwE6v1NGQxug"
private const val CHANNEL = "UCAuUUnT6oDeKwE6v1NGQxug"

/** A curated playlist. The owner chooses its order, so a new item can sit on any page. */
private const val CURATED = "PLmadeUpPlaylistForTestsA000000002"

private val WALKED_AT: Instant = Instant.parse("2026-09-19T12:00:00Z")
private val NOW: Instant = Instant.parse("2026-09-20T09:00:00Z")

private fun ref(videoId: String, day: Int): PlaylistItemRef = PlaylistItemRef(videoId, testDay(day))

/**
 * What a refresh actually does, end to end, against the network seam and the storage seam.
 *
 * Spec section 5. Every acceptance criterion of ticket 13 that is a rule is settled here, on
 * the JVM, with no device and no unit of quota.
 *
 * **No test in this file can reach `search.list`.** [local.youtubetv.youtube.YouTubeApi] does
 * not declare it, so a refresh that tried to call it would not compile.
 */
class SourceRefreshTest {

    private val log = mutableListOf<String>()

    private fun refresher(
        store: SourceStore,
        api: FakeYouTubeApi,
        watches: WatchLog,
        now: Instant = NOW,
        onSourceStored: suspend (SourceStored) -> Unit = {},
    ) = SourceRefresh(
        api = api,
        store = store,
        watches = watches,
        walk = SourceWalk(api),
        log = { log += it },
        now = { now },
        zone = { ZoneId.of("Asia/Jerusalem") },
        onSourceStored = onSourceStored,
    )

    /** One channel Source and one curated playlist, both already walked and both up to date. */
    private fun storeWith(
        vararg videos: Pair<String, List<StoredVideo>>,
        remoteCounts: Map<String, Int?> = emptyMap(),
        /**
         * The stored title per Source, for the tests of ticket 20. A Source not named here
         * gets `Source <id>`, which is what every other test in this file expects.
         */
        titles: Map<String, String?> = emptyMap(),
    ): InMemorySourceStore {
        val store = InMemorySourceStore()
        for ((sourceId, list) in videos) {
            store.saveSource(
                StoredSource(
                    sourceId = sourceId,
                    title = if (sourceId in titles) titles[sourceId] else "Source $sourceId",
                    lastWalkedAt = WALKED_AT,
                    remoteCount = if (sourceId in remoteCounts) remoteCounts[sourceId] else list.size,
                ),
            )
            store.replaceVideos(sourceId, list)
        }
        return store
    }

    private fun stored(sourceId: String, vararg days: Pair<String, Int>): Pair<String, List<StoredVideo>> =
        sourceId to days.map { (id, day) ->
            StoredVideo(
                videoId = id,
                sourceId = sourceId,
                videoPublishedAt = testDay(day),
                title = "Video $id",
                thumbnailUrl = null,
                durationSeconds = 600,
                liveBroadcastContent = "none",
                liveEndedAt = null,
                availability = Availability.AVAILABLE,
                isShort = false,
            )
        }

    private fun videoDetails(vararg ids: String): Map<String, VideoDetails> =
        ids.associateWith { normalVideo(it) }

    // ------------------------------------------------- criterion: all four triggers fire

    @Test
    fun `each of the four triggers runs a refresh and names itself in the log`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
        )
        val watches = WatchLog(InMemoryWatchStore())

        for (trigger in RefreshTrigger.entries) {
            log.clear()
            runBlocking { refresher(store, api, watches).refresh(trigger) }
            assertTrue(
                "no line named ${trigger.stored}: $log",
                log.any { it.startsWith("Refresh started by ${trigger.stored}") },
            )
        }
    }

    // ------------------------ criterion: a check that finds no count change lists nothing

    @Test
    fun `a change check that finds no count change makes no listing call`() {
        val store = storeWith(
            stored(UPLOADS, "u1" to 5, "u2" to 4),
            stored(CURATED, "c1" to 3),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(
                UPLOADS to listOf(ref("u1", 5), ref("u2", 4)),
                CURATED to listOf(ref("c1", 3)),
            ),
            videos = videoDetails("u1", "u2", "c1"),
        )

        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        // One `channels.list` and one `playlists.list`, covering both Sources. Spec 5.2.
        assertEquals(1, api.channelCalls)
        assertEquals(1, api.playlistCalls)
        assertEquals(2, run.unitsSpent)
        // Nothing was listed and nothing was enriched.
        assertEquals(0, api.playlistItemCalls)
        assertEquals(0, api.videoCalls)
        assertTrue(run.changedSourceIds.isEmpty())
        assertTrue(log.any { it.contains("2 made no listing call") })
    }

    // -------------------- criterion: a channel Source with one new video re-lists one page

    @Test
    fun `a channel Source with one new video re-lists one page, not the whole playlist`() {
        // Sixty stored videos, so the uploads playlist is two pages. The newest is new.
        val known = (1..60).map { "u$it" to it }
        val store = storeWith(stored(UPLOADS, *known.toTypedArray()))
        val listing = listOf(ref("u61", 61)) + (60 downTo 1).map { ref("u$it", it) }
        val api = FakeYouTubeApi(
            playlists = mapOf(UPLOADS to listing, UPLOADS_SHORTS to emptyList()),
            videos = videoDetails(*(1..61).map { "u$it" }.toTypedArray()),
        )

        runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        assertEquals(1, api.playlistItemCallsBy[UPLOADS])
        assertEquals(61, store.listedVideos(UPLOADS).size)
        assertEquals("u61", store.listedVideos(UPLOADS).first().videoId)
        assertTrue(log.any { it.contains("one re-list of its newest page") })
    }

    // ---------------------- criterion: a curated playlist with one new item is fully walked

    @Test
    fun `a curated playlist with one new item is fully walked, because the owner chose the order`() {
        val known = (1..60).map { "c$it" to it }
        val store = storeWith(stored(CURATED, *known.toTypedArray()))
        // The new item sits in the middle of page two, which is exactly why a curated playlist
        // cannot be re-listed from its first page.
        val listing = (1..40).map { ref("c$it", it) } +
            listOf(ref("c61", 61)) +
            (41..60).map { ref("c$it", it) }
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listing),
            videos = videoDetails(*(1..61).map { "c$it" }.toTypedArray()),
        )

        runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        assertEquals(2, api.playlistItemCallsBy[CURATED])
        assertEquals(61, store.listedVideos(CURATED).size)
        assertTrue(log.any { it.contains("a full walk") })
    }

    // -------------------------- criterion: a new video in a newest-first Source is next up

    @Test
    fun `a new video in a newest-first Source becomes Next unwatched`() {
        val store = storeWith(stored(CURATED, "old" to 1))
        val watches = WatchLog(InMemoryWatchStore())
        // Everything already watched, so the Source has no gap before the refresh.
        watches.markJustThis(store.listedVideos(CURATED).single(), watched = true)

        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("old", 1), ref("new", 9))),
            videos = videoDetails("old", "new"),
        )
        runBlocking { refresher(store, api, watches).refresh(RefreshTrigger.LAUNCH) }

        val videos = store.listedVideos(CURATED)
        assertEquals(listOf("new", "old"), videos.map { it.videoId })
        val states = watches.watchStates(videos)
        assertEquals(0, nextUnwatchedIndex(videos, states, Direction.NEWEST_FIRST))
    }

    // ------------------ criterion: a new video inside a Bulk mark range arrives watched

    @Test
    fun `a new video inside an existing Bulk mark range arrives already watched`() {
        val store = storeWith(stored(CURATED, "pivot" to 1))
        val watchStore = InMemoryWatchStore()
        val watches = WatchLog(watchStore)
        // "Everything newer than this one is watched", which is the shape the add-a-Source
        // offer and `Mark everything unwatched` both use. One entry, naming no video.
        watches.markRange(
            pivot = store.listedVideos(CURATED).single(),
            side = RangeSide.NEWER,
            watched = true,
        )

        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("pivot", 1), ref("arrived", 9))),
            videos = videoDetails("pivot", "arrived"),
        )
        runBlocking { refresher(store, api, watches).refresh(RefreshTrigger.LAUNCH) }

        val videos = store.listedVideos(CURATED)
        val states = watches.watchStates(videos)
        assertEquals(true, states["arrived"]?.watched)
        // And no second Bulk mark was written to say so.
        assertEquals(1, watchStore.entries().count { it.kind == "range" })
    }

    // ------------------------------- criterion: a video removed with no Watch disappears

    @Test
    fun `a video removed from the playlist with no Watch disappears`() {
        val store = storeWith(stored(CURATED, "gone" to 2, "stays" to 5))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("stays", 5))),
            videos = videoDetails("stays"),
        )

        runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        assertEquals(listOf("stays"), store.listedVideos(CURATED).map { it.videoId })
    }

    // ------------------ criterion: a video removed with a Watch stays, marked unavailable

    @Test
    fun `a video removed from the playlist with a Watch stays, is unavailable, and keeps its Watch`() {
        val store = storeWith(stored(CURATED, "watched" to 2, "stays" to 5))
        val watches = WatchLog(InMemoryWatchStore())
        val gone = store.listedVideos(CURATED).single { it.videoId == "watched" }
        watches.markJustThis(gone, watched = true)

        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("stays", 5))),
            videos = videoDetails("stays"),
        )
        runBlocking { refresher(store, api, watches).refresh(RefreshTrigger.LAUNCH) }

        val kept = store.allVideos(CURATED).single { it.videoId == "watched" }
        assertEquals(Availability.UNAVAILABLE, kept.availability)
        assertEquals(true, watches.state("watched")?.watched)
        // **The row is kept and the card is not drawn. Amended 2026-09-20 by ticket 23.** This
        // read `store.listedVideos` until then, because an unavailable video was still on the
        // grid with a badge. Keeping the row is what still matters here: it is what lets a
        // later walk put the card back if YouTube serves the video again, with no household
        // action. See `refresh.ComingBackTest`.
        assertTrue(store.listedVideos(CURATED).none { it.videoId == "watched" })
        // ADR 0001's durable copy is still readable, which is what makes the history readable
        // after YouTube stops serving the video. **This is ticket 04's own criterion, and
        // ticket 23 does not touch it**: the copy lives in the Watch log, not in the video
        // cache, and no grid rule can reach it.
        val copy = watches.keptVideo("watched")
        assertNotNull(copy)
        assertEquals("Video watched", copy?.title)
        assertEquals(testDay(2), copy?.videoPublishedAt)
    }

    /**
     * A premiere that has started is not a new video, a dropped one or a kept one.
     *
     * Nothing about it is added, dropped or kept unavailable, so a refresh that decided whether
     * to write by those three counts alone threw the fresh reply away. The video then read
     * `upcoming` for ever, `isUpcoming` stayed true, and Next unwatched skipped it with nothing
     * in the app able to undo it. Found by `/code-review`.
     *
     * **Ticket 23 raised what this test is worth.** An upcoming premiere is now unlisted, so
     * the same bug would keep the card off the grid for ever rather than only skipping it.
     * This is the same rule as `refresh.ComingBackTest`, driven through the real refresher
     * instead of `mergeRefresh` alone, so the assertion below is on `listedVideos`.
     */
    @Test
    fun `a premiere that has finished stops being upcoming, and is listed again`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(CURATED, "Source", WALKED_AT, remoteCount = 1))
        store.replaceVideos(
            CURATED,
            listOf(stored(CURATED, "p" to 3).second.single().copy(liveBroadcastContent = "upcoming")),
        )
        // YouTube now reports it as an ordinary video with a real length.
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("p", 3))),
            videos = mapOf("p" to normalVideo("p", title = "The premiere, as broadcast")),
            // The count did not move, so the full walk trigger is what reaches it.
            playlistCounts = mapOf(CURATED to 1),
        )

        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }

        val after = store.listedVideos(CURATED).single()
        assertEquals("none", after.liveBroadcastContent)
        assertEquals("The premiere, as broadcast", after.title)
        assertEquals(listOf(CURATED), run.changedSourceIds)
    }

    /**
     * A Source's recorded count moves only once that Source has caught up to it.
     *
     * Writing every count before the re-list loop let a run that failed part way through leave
     * the later Sources holding a count they had never caught up to. The next check then read
     * them as unchanged and skipped them. Found by `/code-review`.
     */
    @Test
    fun `a Source whose re-list failed does not keep the count it never caught up to`() {
        val store = storeWith(
            stored(CURATED, "c" to 1),
            remoteCounts = mapOf(CURATED to 1),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("c", 1), ref("c2", 2))),
            videos = videoDetails("c", "c2"),
            failingPlaylists = mapOf(CURATED to YouTubeApiException.Reason.HTTP),
        )

        runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH) }

        // The check saw two, the re-list failed, so the recorded count is still one and the
        // next check will try again.
        assertEquals(1, store.source(CURATED)?.remoteCount)
        assertTrue(log.any { it.contains("failed") })
    }

    @Test
    fun `a full walk that gave up on a Source does not mark the day done`() {
        val store = storeWith(
            stored(CURATED, "c" to 1),
            remoteCounts = mapOf(CURATED to 1),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("c", 1))),
            videos = videoDetails("c"),
            failingPlaylists = mapOf(CURATED to YouTubeApiException.Reason.HTTP),
        )
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))

        runBlocking { refresher.refresh(RefreshTrigger.WEEKLY_FULL_WALK) }

        // The repair this Source is owed has not happened, so the walk is still due.
        assertTrue(refresher.isFullWalkDue())
    }

    @Test
    fun `a manual refresh of one Source does not restart the whole household's timer`() {
        val store = storeWith(stored(CURATED, "c" to 1))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("c", 1))),
            videos = videoDetails("c"),
        )
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))
        val before = refresher.lastCheckAt

        runBlocking { refresher.refresh(RefreshTrigger.MANUAL, onlySourceId = CURATED) }
        assertEquals(before, refresher.lastCheckAt)

        // A check over every Source does move it.
        runBlocking { refresher.refresh(RefreshTrigger.TIMER) }
        assertEquals(NOW, refresher.lastCheckAt)
    }

    /**
     * A cold start must not run two refreshes.
     *
     * The Activity starts a launch refresh and its timer within milliseconds of each other, and
     * a null `lastCheckAt` made the timer's first tick find a check due at once. Found by
     * `/code-review`.
     */
    @Test
    fun `the timer does not find a check due the moment the app starts`() {
        val store = storeWith(stored(CURATED, "c" to 1))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("c", 1))),
            videos = videoDetails("c"),
        )
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))

        assertEquals(NOW, refresher.lastCheckAt)
        assertEquals(30 * 60 * 1000L, millisUntilNextCheck(NOW, refresher.lastCheckAt))
    }

    // ------------------------------------------- criterion: 403 quotaExceeded, no retry loop

    @Test
    fun `a 403 quotaExceeded stops refresh with no retry loop, and the cached list is untouched`() {
        val store = storeWith(stored(CURATED, "a" to 1, "b" to 2))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
            failingPlaylists = mapOf(CURATED to YouTubeApiException.Reason.QUOTA_EXCEEDED),
            // A count that moved, so the Source really is re-listed and really does meet the
            // refusal.
            playlistCounts = mapOf(CURATED to 99),
        )
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))

        val first = runBlocking { refresher.refresh(RefreshTrigger.LAUNCH) }
        assertTrue(first.stoppedByQuota)
        assertTrue(log.any { it.contains("No retry loop") })

        // The cached list is exactly what it was. Everything still browses and plays.
        assertEquals(listOf("b", "a"), store.listedVideos(CURATED).map { it.videoId })

        // Every later trigger is refused without touching the network at all, which is what
        // "no retry loop" means in practice.
        val callsAfterFirst = api.playlistItemCalls + api.playlistCalls + api.channelCalls
        val second = runBlocking { refresher.refresh(RefreshTrigger.TIMER) }
        assertTrue(second.stoppedByQuota)
        assertEquals(callsAfterFirst, api.playlistItemCalls + api.playlistCalls + api.channelCalls)
        assertTrue(refresher.isStoppedByQuota())
    }

    @Test
    fun `the quota refusal lifts by itself at midnight Pacific Time`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
            failingPlaylists = mapOf(CURATED to YouTubeApiException.Reason.QUOTA_EXCEEDED),
            playlistCounts = mapOf(CURATED to 99),
        )
        val refusedAt = Instant.parse("2026-09-20T23:30:00Z") // 16:30 Pacific
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()), now = refusedAt)
        runBlocking { refresher.refresh(RefreshTrigger.LAUNCH) }

        assertTrue(refresher.isStoppedByQuota(refusedAt))
        assertFalse(refresher.isStoppedByQuota(Instant.parse("2026-09-21T07:00:00Z")))
    }

    /** Build ticket 64: the quota belongs to each key, so a new key lifts the refusal at once. */
    @Test
    fun `a new API key lifts the quota refusal at once`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
            failingPlaylists = mapOf(CURATED to YouTubeApiException.Reason.QUOTA_EXCEEDED),
            playlistCounts = mapOf(CURATED to 99),
        )
        val refusedAt = Instant.parse("2026-09-20T23:30:00Z")
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()), now = refusedAt)
        runBlocking { refresher.refresh(RefreshTrigger.LAUNCH) }
        assertTrue(refresher.isStoppedByQuota(refusedAt))

        refresher.forgetQuotaRefusal()

        assertFalse(refresher.isStoppedByQuota(refusedAt))
    }

    // ------------------------------------- criterion: offline leaves everything browsable

    @Test
    fun `with no network the stored list is left exactly as it was`() {
        val store = storeWith(stored(CURATED, "a" to 1, "b" to 2))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
            failingPlaylists = mapOf(CURATED to YouTubeApiException.Reason.NETWORK),
            playlistCounts = mapOf(CURATED to 99),
        )

        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        assertTrue(run.offline)
        assertEquals(listOf("b", "a"), store.listedVideos(CURATED).map { it.videoId })
        // The `(updated ...)` line still has its time, because the walk that set it still
        // happened. Spec section 5.4 asks for a quiet line, not for it to disappear.
        assertEquals(WALKED_AT, store.source(CURATED)?.lastWalkedAt)
        assertTrue(log.any { it.contains("could not reach YouTube") })
    }

    @Test
    fun `a change check that cannot reach YouTube lists nothing and changes nothing`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = object : FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
        ) {
            override suspend fun playlistsByIds(playlistIds: List<String>): List<PlaylistDetails> =
                throw YouTubeApiException(YouTubeApiException.Reason.NETWORK, "no network")
        }

        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        assertTrue(run.offline)
        assertEquals(0, api.playlistItemCalls)
        assertEquals(listOf("a"), store.listedVideos(CURATED).map { it.videoId })
    }

    // ---------------------------------------- the row every ticket before this one bruised

    @Test
    fun `a refresh does not reset a single per-Source preference`() {
        val store = InMemorySourceStore()
        store.saveSource(
            StoredSource(
                sourceId = CURATED,
                title = "The household's own name for it",
                lastWalkedAt = WALKED_AT,
                // Every one of these is a non-default, and every one of them sits on the row
                // `saveSource` writes whole.
                direction = Direction.OLDEST_FIRST,
                autoplayNext = true,
                addedAt = Instant.parse("2026-01-05T08:00:00Z"),
                remoteCount = 1,
            ),
        )
        store.replaceVideos(CURATED, stored(CURATED, "a" to 1).second)

        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1), ref("b", 9))),
            videos = videoDetails("a", "b"),
        )
        runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH) }

        val after = store.source(CURATED)!!
        assertEquals("The household's own name for it", after.title)
        assertEquals(Direction.OLDEST_FIRST, after.direction)
        assertEquals(true, after.autoplayNext)
        assertEquals(Instant.parse("2026-01-05T08:00:00Z"), after.addedAt)
        // `last_walked_at` is the one thing a refresh is meant to move, because it is what the
        // `(updated ...)` line of spec section 5.4 draws.
        assertEquals(NOW, after.lastWalkedAt)
        // `remote_count` is the refresh's own column and moves to what YouTube just said.
        assertEquals(2, after.remoteCount)
        // And the Source really was refreshed, so this is not passing by doing nothing.
        assertEquals(listOf("a", "b"), store.listedVideos(CURATED).map { it.videoId })
    }

    // --------------------------------------------- criterion: the weekly full walk, once

    @Test
    fun `the full walk runs once and is not due again that day`() {
        val store = storeWith(stored(UPLOADS, "u1" to 1))
        val api = FakeYouTubeApi(
            playlists = mapOf(UPLOADS to listOf(ref("u1", 1)), UPLOADS_SHORTS to emptyList()),
            videos = videoDetails("u1"),
        )
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))

        assertTrue(refresher.isFullWalkDue())
        runBlocking { refresher.refresh(RefreshTrigger.WEEKLY_FULL_WALK) }
        assertFalse(refresher.isFullWalkDue())

        // It walked in full, whatever the count said.
        assertEquals(1, api.playlistItemCallsBy[UPLOADS])
    }

    /**
     * The full walk makes the change check too, purely to write the counts down.
     *
     * Found on the emulator on 2026-09-20. Without it, every Source came out of the full walk
     * with no recorded count, the next launch read that silence as "changed", and a curated
     * playlist of 588 videos was re-listed for 24 units for nothing.
     */
    @Test
    fun `the full walk records the counts, so the next launch re-lists nothing`() {
        val store = storeWith(
            stored(CURATED, "a" to 1),
            remoteCounts = mapOf(CURATED to null),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
        )
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))

        runBlocking { refresher.refresh(RefreshTrigger.WEEKLY_FULL_WALK) }
        assertEquals(1, api.playlistItemCallsBy[CURATED])
        assertEquals(1, store.source(CURATED)?.remoteCount)

        // The launch that follows finds the count where the full walk left it.
        val second = runBlocking { refresher.refresh(RefreshTrigger.LAUNCH) }
        assertEquals(1, api.playlistItemCallsBy[CURATED])
        assertTrue(second.changedSourceIds.isEmpty())
    }

    @Test
    fun `the full walk is not due six days later, and is due again seven days later`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
        )
        runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore()), now = NOW)
                .refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }
        // The value it reads and writes is still the stored day, under the same key.
        assertEquals("2026-09-20", store.meta(META_LAST_FULL_WALK_DAY))

        val day = 86_400L
        for (later in listOf(NOW, NOW.plusSeconds(day), NOW.plusSeconds(6 * day))) {
            assertFalse(refresher(store, api, WatchLog(InMemoryWatchStore()), now = later).isFullWalkDue())
        }
        assertTrue(
            refresher(store, api, WatchLog(InMemoryWatchStore()), now = NOW.plusSeconds(7 * day)).isFullWalkDue(),
        )
    }

    // --------------------------------------------------- criterion: the unit cost

    @Test
    fun `ten Sources cost two units to check, and only the one that changed is re-listed`() {
        val store = InMemorySourceStore()
        val playlists = mutableMapOf<String, List<PlaylistItemRef>>()
        val videos = mutableMapOf<String, VideoDetails>()
        for (n in 1..10) {
            val sourceId = "PL${"%032d".format(n)}"
            val listing = (1..30).map { ref("v$n-$it", it) }
            playlists[sourceId] = listing
            listing.forEach { videos[it.videoId] = normalVideo(it.videoId) }
            store.saveSource(
                StoredSource(sourceId, "Source $n", WALKED_AT, remoteCount = 30),
            )
            store.replaceVideos(
                sourceId,
                stored(sourceId, *(1..30).map { "v$n-$it" to it }.toTypedArray()).second,
            )
        }
        // One Source grew by one video. Every other one is untouched.
        val changedId = "PL${"%032d".format(3)}"
        playlists[changedId] = listOf(ref("v3-31", 31)) + playlists.getValue(changedId)
        videos["v3-31"] = normalVideo("v3-31")

        val api = FakeYouTubeApi(playlists = playlists, videos = videos)
        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        // One `playlists.list` for all ten, and then one full walk of the one that changed:
        // one `playlistItems.list` page and one `videos.list` page over 31 videos.
        assertEquals(1, api.playlistCalls)
        assertEquals(0, api.channelCalls)
        assertEquals(3, run.unitsSpent)
        assertEquals(listOf(changedId), run.changedSourceIds)
        assertEquals(31, store.listedVideos(changedId).size)
    }

    // -------------------------------------------------------------------- narrower rules

    @Test
    fun `a Source that has never been walked is never refreshed`() {
        val store = InMemorySourceStore()
        // Added a moment ago on the add screen, and not yet opened. Opening it is what walks it.
        store.saveSource(StoredSource(CURATED, "Just added", lastWalkedAt = null))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
        )

        runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        assertEquals(0, api.playlistCalls)
        assertEquals(0, api.channelCalls)
        assertEquals(0, api.playlistItemCalls)
    }

    @Test
    fun `a manual refresh touches only the Source it was asked about`() {
        val store = storeWith(
            stored(CURATED, "c" to 1),
            stored(UPLOADS, "u" to 1),
            remoteCounts = mapOf(CURATED to null, UPLOADS to null),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(
                CURATED to listOf(ref("c", 1)),
                UPLOADS to listOf(ref("u", 1)),
                UPLOADS_SHORTS to emptyList(),
            ),
            videos = videoDetails("c", "u"),
        )

        runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore()))
                .refresh(RefreshTrigger.MANUAL, onlySourceId = CURATED)
        }

        assertEquals(1, api.playlistItemCallsBy[CURATED])
        assertEquals(null, api.playlistItemCallsBy[UPLOADS])
        // No channel Source was in this run at all, so no `channels.list` was made.
        assertEquals(0, api.channelCalls)
    }

    @Test
    fun `a Source no count has ever been recorded for is re-listed once and then left alone`() {
        // Every Source the television already holds is in this state, because `remote_count`
        // arrives with database version 8 and reads as null on a row written before it.
        val store = storeWith(
            stored(CURATED, "a" to 1),
            remoteCounts = mapOf(CURATED to null),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
        )
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))

        runBlocking { refresher.refresh(RefreshTrigger.LAUNCH) }
        assertEquals(1, api.playlistItemCallsBy[CURATED])

        runBlocking { refresher.refresh(RefreshTrigger.TIMER) }
        assertEquals(1, api.playlistItemCallsBy[CURATED])
    }

    @Test
    fun `a Short already classified is not re-classified, and is not counted as new`() {
        val store = InMemorySourceStore()
        store.saveSource(StoredSource(UPLOADS, "Channel", WALKED_AT, remoteCount = 2))
        store.replaceVideos(
            UPLOADS,
            listOf(
                stored(UPLOADS, "normal" to 5).second.single(),
                stored(UPLOADS, "tiny" to 4).second.single().copy(isShort = true),
            ),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(
                UPLOADS to listOf(ref("fresh", 9), ref("normal", 5), ref("tiny", 4)),
                // The Shorts companion no longer lists it, which would re-classify it as a
                // normal video if a refresh ever asked again.
                UPLOADS_SHORTS to emptyList(),
            ),
            videos = videoDetails("fresh", "normal", "tiny"),
        )

        runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        val all = store.allVideos(UPLOADS)
        assertEquals(3, all.size)
        assertEquals(true, all.single { it.videoId == "tiny" }.isShort)
        // The grid still shows two videos and never the Short.
        assertEquals(listOf("fresh", "normal"), store.listedVideos(UPLOADS).map { it.videoId })
    }

    // ------------------- criterion: a change check gives a Source with no name a real name

    /**
     * Ticket 20. The title is already arriving in the change check reply and being dropped.
     *
     * `ChannelDetails.title` and `PlaylistDetails.title` both come back from the two calls
     * spec section 5.2 already makes, so filling a missing title costs no unit at all.
     */
    @Test
    fun `a change check gives a Source with no stored name the name YouTube has`() {
        val store = storeWith(
            stored(UPLOADS, "u1" to 5),
            stored(CURATED, "c1" to 3),
            titles = mapOf(UPLOADS to null, CURATED to "   "),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(UPLOADS to listOf(ref("u1", 5)), CURATED to listOf(ref("c1", 3))),
            videos = videoDetails("u1", "c1"),
        )

        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        // A channel Source is named by its channel, not by its uploads playlist. That is the
        // name the household knows it by, and `playlists.list` is never asked about a `UU` id.
        assertEquals("Channel $CHANNEL", store.source(UPLOADS)!!.title)
        assertEquals("Playlist $CURATED", store.source(CURATED)!!.title)
        // Both, and the order is the store's own, which the rail never reads.
        assertEquals(setOf(UPLOADS, CURATED), run.namedSourceIds.toSet())
    }

    @Test
    fun `naming a Source costs no extra call and no extra unit`() {
        val store = storeWith(
            stored(UPLOADS, "u1" to 5),
            stored(CURATED, "c1" to 3),
            titles = mapOf(UPLOADS to null, CURATED to null),
        )
        val api = FakeYouTubeApi(
            playlists = mapOf(UPLOADS to listOf(ref("u1", 5)), CURATED to listOf(ref("c1", 3))),
            videos = videoDetails("u1", "c1"),
        )

        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        // The same two units spec section 5.2 already spends, and the same log line saying so.
        assertEquals(1, api.channelCalls)
        assertEquals(1, api.playlistCalls)
        assertEquals(2, run.unitsSpent)
        assertEquals(0, api.playlistItemCalls)
        assertEquals(0, api.videoCalls)
        assertTrue(log.any { it.contains("2 units over 2 Sources") })
    }

    @Test
    fun `a Source that already has a name is left alone`() {
        val store =
            storeWith(stored(CURATED, "c1" to 3), titles = mapOf(CURATED to "Example Channel"))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("c1", 3))),
            videos = videoDetails("c1"),
        )

        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        assertEquals("Example Channel", store.source(CURATED)!!.title)
        assertTrue(run.namedSourceIds.isEmpty())
    }

    @Test
    fun `a name written once is not written again on the next check`() {
        val store = storeWith(stored(CURATED, "c1" to 3), titles = mapOf(CURATED to null))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("c1", 3))),
            videos = videoDetails("c1"),
        )
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))

        runBlocking { refresher.refresh(RefreshTrigger.LAUNCH) }
        val second = runBlocking { refresher.refresh(RefreshTrigger.TIMER) }

        // Nothing to put back in step the second time, so the rail is not redrawn for nothing.
        assertTrue(second.namedSourceIds.isEmpty())
    }

    @Test
    fun `a check that failed leaves the Source without a name`() {
        // The card then falls back to the raw playlist id, which is what `railCardName` does.
        val store = storeWith(stored(CURATED, "c1" to 3), titles = mapOf(CURATED to null))
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("c1", 3))),
            videos = videoDetails("c1"),
            pricingFails = true,
        )

        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.LAUNCH)
        }

        assertNull(store.source(CURATED)!!.title)
        assertTrue(run.namedSourceIds.isEmpty())
    }

    // ------------------ build ticket 55: each Source reaches the screens when it is stored

    /** Two curated playlists, both with one new item, so both are re-listed. */
    private fun twoChangedSources(): Pair<InMemorySourceStore, FakeYouTubeApi> {
        val store = storeWith(stored(CURATED, "a" to 1), stored(SECOND, "x" to 1))
        val api = FakeYouTubeApi(
            playlists = mapOf(
                CURATED to listOf(ref("a", 1), ref("b", 2)),
                SECOND to listOf(ref("x", 1), ref("y", 2)),
            ),
            videos = videoDetails("a", "b", "x", "y"),
        )
        return store to api
    }

    @Test
    fun `each Source reaches onSourceStored once it is stored, before the next one is walked`() {
        val (store, api) = twoChangedSources()
        val heard = mutableListOf<String>()
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()), onSourceStored = { stored ->
            // At this moment the Source is already stored with its new item and its new count,
            // and the Source after it has not been asked for a single page yet.
            val other = if (stored.sourceId == CURATED) SECOND else CURATED
            heard += "${stored.sourceId} changed=${stored.changed} " +
                "stored=${store.listedVideos(stored.sourceId).size} " +
                "count=${store.source(stored.sourceId)?.remoteCount} " +
                "otherPages=${api.playlistItemCallsBy[other] ?: 0}"
        })

        val run = runBlocking { refresher.refresh(RefreshTrigger.LAUNCH) }

        assertEquals(
            listOf(
                "$CURATED changed=true stored=2 count=2 otherPages=0",
                "$SECOND changed=true stored=2 count=2 otherPages=1",
            ),
            heard,
        )
        // The run's own answer keeps its meaning.
        assertEquals(listOf(CURATED, SECOND), run.changedSourceIds)
    }

    @Test
    fun `a Source re-listed with nothing new is reported as unchanged`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = FakeYouTubeApi(playlists = mapOf(CURATED to listOf(ref("a", 1))), videos = videoDetails("a"))
        val heard = mutableListOf<SourceStored>()
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()), onSourceStored = { heard += it })
        // The first full walk replaces the test's own rows with what YouTube says, so it is a
        // change. The second finds the same list and is not.
        runBlocking { refresher.refresh(RefreshTrigger.WEEKLY_FULL_WALK) }
        runBlocking { refresher.refresh(RefreshTrigger.WEEKLY_FULL_WALK) }
        assertEquals(listOf(SourceStored(CURATED, changed = true), SourceStored(CURATED, changed = false)), heard)
    }

    @Test
    fun `a Source whose count did not move is not reported at all`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = FakeYouTubeApi(playlists = mapOf(CURATED to listOf(ref("a", 1))), videos = videoDetails("a"))
        val heard = mutableListOf<SourceStored>()
        runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore()), onSourceStored = { heard += it })
                .refresh(RefreshTrigger.LAUNCH)
        }
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `the Sources are re-listed in home rail order, most recently played first`() {
        val (store, api) = twoChangedSources()
        val watches = WatchLog(InMemoryWatchStore())
        val heard = mutableListOf<String>()

        // Nothing played: the store's own order, oldest added first, as the rail has it.
        runBlocking {
            refresher(store, api, watches, onSourceStored = { heard += it.sourceId }).refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }
        assertEquals(listOf(CURATED, SECOND), heard)

        // The household plays a video of the second Source, so it is first on the rail.
        watches.startPlay(store.listedVideos(SECOND).first(), positionSeconds = 30, completed = false)
        heard.clear()
        runBlocking {
            refresher(store, api, watches, onSourceStored = { heard += it.sourceId }).refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }
        assertEquals(listOf(SECOND, CURATED), heard)
    }

    @Test
    fun `a screen that fails to draw a Source does not stop the run`() {
        val (store, api) = twoChangedSources()
        val run = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore()), onSourceStored = { stored ->
                if (stored.sourceId == CURATED) error("the screen could not be drawn")
            }).refresh(RefreshTrigger.LAUNCH)
        }
        assertEquals(listOf(CURATED, SECOND), run.changedSourceIds)
        assertEquals(2, store.listedVideos(SECOND).size)
        assertTrue(log.any { it.startsWith("Refreshed $CURATED was stored, but drawing it again failed") })
    }

    // ------------------------ build ticket 55: the launch check first, the full walk after

    @Test
    fun `a launch that owes no full walk runs the launch check alone and says when one is owed`() {
        val (store, api) = twoChangedSources()
        // NOW is refresh day 2026-09-20, six days after 2026-09-14.
        store.setMeta(META_LAST_FULL_WALK_DAY, "2026-09-14")

        val runs = runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refreshOnLaunch() }

        assertEquals(1, runs.size)
        assertTrue(log.contains("The weekly full walk last completed for 2026-09-14 and is next owed on 2026-09-21, so this launch does a change check only."))
        assertTrue(log.any { it.startsWith("Refresh started by launch") })
        assertFalse(log.any { it.startsWith("Refresh started by the weekly full walk") })
        assertEquals("2026-09-14", store.meta(META_LAST_FULL_WALK_DAY))
    }

    @Test
    fun `a launch that owes a full walk shows the new videos first, then walks every Source`() {
        val (store, api) = twoChangedSources()
        // Seven refresh days before NOW.
        store.setMeta(META_LAST_FULL_WALK_DAY, "2026-09-13")
        val passes = mutableListOf<RefreshRun>()

        val runs = runBlocking {
            refresher(store, api, WatchLog(InMemoryWatchStore()), onSourceStored = { stored ->
                log += "SHOWN ${stored.sourceId} changed=${stored.changed}"
            }).refreshOnLaunch(afterEachPass = { passes += it })
        }

        // The order the log is read in on the television.
        val order = listOf(
            "The weekly full walk is owed (last completed for 2026-09-13)",
            "Refresh started by launch",
            "SHOWN $CURATED changed=true",
            "SHOWN $SECOND changed=true",
            "Refresh by launch finished",
            "Refresh started by the weekly full walk",
            "Refresh by the weekly full walk finished",
        ).map { prefix -> log.indexOfFirst { it.startsWith(prefix) } }
        assertTrue("missing or out of order: $order in $log", order.none { it < 0 } && order == order.sorted())

        // The launch check found the two new items. The full walk then walked both Sources in
        // full and found nothing more to change.
        assertEquals(2, runs.size)
        assertEquals(runs, passes)
        assertEquals(listOf(CURATED, SECOND), runs[0].changedSourceIds)
        assertTrue(runs[1].changedSourceIds.isEmpty())
        assertTrue(log.contains("SHOWN $CURATED changed=false"))
        // Only the completed full walk writes the day.
        assertEquals("2026-09-20", store.meta(META_LAST_FULL_WALK_DAY))
    }

    @Test
    fun `a full walk that never completed is owed on the first launch`() {
        val (store, api) = twoChangedSources()
        val runs = runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refreshOnLaunch() }
        assertEquals(2, runs.size)
        assertTrue(log.contains("The weekly full walk is owed (none has completed), so it runs after the launch check, as a second pass."))
    }

    @Test
    fun `the 30 minute timer cannot slip in between the launch check and the full walk`() {
        val (store, api) = twoChangedSources()
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))

        runBlocking {
            var timer: kotlinx.coroutines.Job? = null
            refresher.refreshOnLaunch(afterEachPass = { run ->
                // The timer fires the moment the launch check ends.
                if (timer == null) timer = launch { refresher.refresh(RefreshTrigger.TIMER) }
                // Give it every chance to run before the full walk starts.
                kotlinx.coroutines.yield()
            })
            timer!!.join()
        }

        val fullWalkFinished = log.indexOfFirst { it.startsWith("Refresh by the weekly full walk finished") }
        val timerStarted = log.indexOfFirst { it.startsWith("Refresh started by the 30 minute timer") }
        assertTrue("timer at $timerStarted, full walk finished at $fullWalkFinished: $log", timerStarted > fullWalkFinished)
    }

    @Test
    fun `a launch check that cannot reach YouTube does not go on to the full walk`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = object : FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
        ) {
            override suspend fun playlistsByIds(playlistIds: List<String>): List<PlaylistDetails> =
                throw YouTubeApiException(YouTubeApiException.Reason.NETWORK, "no network")
        }
        val runs = runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refreshOnLaunch() }
        assertEquals(1, runs.size)
        assertTrue(runs.single().offline)
        assertNull(store.meta(META_LAST_FULL_WALK_DAY))
    }

    @Test
    fun `a launch check that fails for any other reason does not go on to the full walk`() {
        val store = storeWith(stored(CURATED, "a" to 1))
        val api = object : FakeYouTubeApi(
            playlists = mapOf(CURATED to listOf(ref("a", 1))),
            videos = videoDetails("a"),
        ) {
            override suspend fun playlistsByIds(playlistIds: List<String>): List<PlaylistDetails> =
                throw YouTubeApiException(YouTubeApiException.Reason.FORBIDDEN, "403 the key is restricted")
        }
        val runs = runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refreshOnLaunch() }
        assertEquals(1, runs.size)
        assertTrue(runs.single().changeCheckFailed)
        assertFalse(log.any { it.startsWith("Refresh started by the weekly full walk") })
    }

    // ------------------------------------------- build ticket 56: ties in any order

    /**
     * Two full walks of a Source with a tie, the second of which must find nothing to do.
     *
     * The first walk stores what YouTube says. The second finds the same list, but the store hands
     * the tied rows back in the other order, which is what Android's SQLite did with the household
     * Source on the emulator on 2026-10-03. Returns what each walk reported, and how many times
     * the second walk rewrote the Source.
     */
    private fun twoFullWalksWithTiesReversed(refs: List<PlaylistItemRef>): Pair<List<SourceStored>, Int> {
        val inner = storeWith(stored(CURATED, "seed" to 1))
        val store = TiesReversedStore(inner)
        // An undated ref is given no `videos.list` reply, which is what a private video gets.
        val ids = refs.filter { it.videoPublishedAt != null }.map { it.videoId }
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to refs),
            videos = videoDetails(*ids.toTypedArray()),
            playlistCounts = mapOf(CURATED to refs.size),
        )
        val heard = mutableListOf<SourceStored>()
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()), onSourceStored = { heard += it })
        runBlocking { refresher.refresh(RefreshTrigger.WEEKLY_FULL_WALK) }
        val rewritesBefore = store.replaceCalls
        runBlocking { refresher.refresh(RefreshTrigger.WEEKLY_FULL_WALK) }
        return heard to store.replaceCalls - rewritesBefore
    }

    @Test
    fun `a full walk that finds the same rows as storage, two of them tied, is unchanged`() {
        val (heard, rewrites) = twoFullWalksWithTiesReversed(
            listOf(ref("newest", 5), ref("twinA", 3), ref("twinB", 3), ref("oldest", 1)),
        )
        assertEquals(listOf(SourceStored(CURATED, changed = true), SourceStored(CURATED, changed = false)), heard)
        assertEquals(0, rewrites)
    }

    @Test
    fun `a full walk that finds the same rows as storage, two of them undated, is unchanged`() {
        // A real Source's shape: several hundred dated videos and two private ones with no date,
        // `yUndated-_1` and `3Undated-_2`, which SQLite handed back in the other order.
        val (heard, rewrites) = twoFullWalksWithTiesReversed(
            listOf(ref("newest", 5), PlaylistItemRef("privateA", null), ref("oldest", 1), PlaylistItemRef("privateB", null)),
        )
        assertEquals(listOf(SourceStored(CURATED, changed = true), SourceStored(CURATED, changed = false)), heard)
        assertEquals(0, rewrites)
    }

    @Test
    fun `a tie in the other order does not hide a title that changed`() {
        val inner = storeWith(stored(CURATED, "seed" to 1))
        val store = TiesReversedStore(inner)
        val refs = listOf(ref("twinA", 3), ref("twinB", 3))
        val heard = mutableListOf<SourceStored>()
        runBlocking {
            refresher(
                store,
                FakeYouTubeApi(playlists = mapOf(CURATED to refs), videos = videoDetails("twinA", "twinB")),
                WatchLog(InMemoryWatchStore()),
            ).refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }
        val renamed = FakeYouTubeApi(
            playlists = mapOf(CURATED to refs),
            videos = mapOf("twinA" to normalVideo("twinA"), "twinB" to normalVideo("twinB", title = "A new title")),
        )
        runBlocking {
            refresher(store, renamed, WatchLog(InMemoryWatchStore()), onSourceStored = { heard += it })
                .refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }
        assertEquals(listOf(SourceStored(CURATED, changed = true)), heard)
        assertEquals("A new title", inner.allVideos(CURATED).single { it.videoId == "twinB" }.title)
    }

    // ------------------------------------------- build ticket 59: view counts

    private val countedRefs = listOf(ref("a", 3), ref("b", 2), ref("c", 1))

    /** A curated playlist of [countedRefs], each video carrying the count [counts] gives it. */
    private fun countsApi(counts: Map<String, Long?>) = FakeYouTubeApi(
        playlists = mapOf(CURATED to countedRefs),
        videos = countedRefs.associate { it.videoId to normalVideo(it.videoId, viewCount = counts[it.videoId]) },
        playlistCounts = mapOf(CURATED to countedRefs.size),
    )

    private fun countsOf(store: SourceStore, sourceId: String = CURATED): Map<String, Long?> =
        store.allVideos(sourceId).associate { it.videoId to it.viewCount }

    @Test
    fun `a full walk in which only view counts moved reports the Source unchanged, and still writes the counts`() {
        // Both halves of the criterion: no `replaceVideos`, no change reported to the screens,
        // and the new counts in storage all the same. `MainActivity.showStoredSource` writes
        // the "reaches the screens" line only for a Source reported changed.
        val inner = storeWith(stored(CURATED, "seed" to 1))
        val store = TiesReversedStore(inner)
        val watches = WatchLog(InMemoryWatchStore())
        runBlocking {
            refresher(store, countsApi(mapOf("a" to 100L, "b" to 200L)), watches)
                .refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }
        assertEquals(mapOf("a" to 100L, "b" to 200L, "c" to null), countsOf(inner))
        val rewritesBefore = store.replaceCalls
        log.clear()

        val heard = mutableListOf<SourceStored>()
        runBlocking {
            refresher(store, countsApi(mapOf("a" to 150L, "b" to 200L, "c" to 7L)), watches, onSourceStored = { heard += it })
                .refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }

        assertEquals(listOf(SourceStored(CURATED, changed = false, viewCountsWritten = true)), heard)
        assertEquals("the Source's rows were rewritten", 0, store.replaceCalls - rewritesBefore)
        assertEquals(mapOf("a" to 150L, "b" to 200L, "c" to 7L), countsOf(inner))
        assertTrue(log.toString(), log.contains(viewCountsWrittenLine(CURATED, 2)))
    }

    @Test
    fun `a full walk in which nothing moved, counts included, writes no count`() {
        val inner = storeWith(stored(CURATED, "seed" to 1))
        val store = TiesReversedStore(inner)
        val watches = WatchLog(InMemoryWatchStore())
        val api = countsApi(mapOf("a" to 100L, "b" to 200L, "c" to 300L))
        runBlocking { refresher(store, api, watches).refresh(RefreshTrigger.WEEKLY_FULL_WALK) }
        val rewritesBefore = store.replaceCalls
        log.clear()

        val heard = mutableListOf<SourceStored>()
        runBlocking {
            refresher(store, api, watches, onSourceStored = { heard += it }).refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }

        assertEquals(listOf(SourceStored(CURATED, changed = false, viewCountsWritten = false)), heard)
        assertEquals(0, store.replaceCalls - rewritesBefore)
        assertFalse(log.toString(), log.any { it.contains("view counts moved") })
    }

    @Test
    fun `a walk that finds a real change writes the new counts with every other field`() {
        val inner = storeWith(stored(CURATED, "seed" to 1))
        val watches = WatchLog(InMemoryWatchStore())
        runBlocking { refresher(inner, countsApi(mapOf("a" to 1L)), watches).refresh(RefreshTrigger.WEEKLY_FULL_WALK) }

        val renamed = FakeYouTubeApi(
            playlists = mapOf(CURATED to countedRefs),
            videos = mapOf(
                "a" to normalVideo("a", title = "A new title", viewCount = 2L),
                "b" to normalVideo("b", viewCount = 20L),
                "c" to normalVideo("c"),
            ),
            playlistCounts = mapOf(CURATED to countedRefs.size),
        )
        val heard = mutableListOf<SourceStored>()
        runBlocking {
            refresher(inner, renamed, watches, onSourceStored = { heard += it }).refresh(RefreshTrigger.WEEKLY_FULL_WALK)
        }

        assertEquals(listOf(SourceStored(CURATED, changed = true)), heard)
        assertEquals(mapOf("a" to 2L, "b" to 20L, "c" to null), countsOf(inner))
    }

    /**
     * A store holding [countedRefs] with no counts, as every row stored before database version 11
     * is, and with this week's full walk already done, so a launch runs the launch check alone.
     */
    private fun storeBeforeTheFirstRead(): InMemorySourceStore {
        val store = storeWith(stored(CURATED, "a" to 3, "b" to 2, "c" to 1))
        store.setMeta(META_LAST_FULL_WALK_DAY, refreshDay(NOW, ZoneId.of("Asia/Jerusalem")).toString())
        return store
    }

    @Test
    fun `the first launch reads every stored count once, and the next launch does not`() {
        val store = storeBeforeTheFirstRead()
        val watches = WatchLog(InMemoryWatchStore())

        val first = countsApi(mapOf("a" to 100L, "b" to 200L, "c" to 300L))
        val heard = mutableListOf<SourceStored>()
        runBlocking { refresher(store, first, watches, onSourceStored = { heard += it }).refreshOnLaunch() }

        assertEquals(mapOf("a" to 100L, "b" to 200L, "c" to 300L), countsOf(store))
        assertEquals("one videos.list call for three stored videos", 1, first.videoCalls)
        assertEquals(0, first.playlistItemCalls)
        assertNotNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
        assertEquals(listOf(SourceStored(CURATED, changed = false, viewCountsWritten = true)), heard)
        assertFalse(log.any { it.startsWith("Refresh started by the weekly full walk") })

        // The next launch is a new process, so a new refresher over the same storage.
        log.clear()
        val second = countsApi(mapOf("a" to 999L, "b" to 999L, "c" to 999L))
        runBlocking { refresher(store, second, watches).refreshOnLaunch() }

        assertEquals(0, second.videoCalls)
        assertEquals(mapOf("a" to 100L, "b" to 200L, "c" to 300L), countsOf(store))
        assertFalse(log.toString(), log.any { it.startsWith("View count first read") })
    }

    @Test
    fun `the first read runs after the launch check, so a new video reaches the screens first`() {
        val store = storeBeforeTheFirstRead()
        val refs = listOf(ref("new", 4)) + countedRefs
        val api = FakeYouTubeApi(
            playlists = mapOf(CURATED to refs),
            videos = refs.associate { it.videoId to normalVideo(it.videoId, viewCount = 5L) },
            playlistCounts = mapOf(CURATED to refs.size),
        )
        val heard = mutableListOf<SourceStored>()
        runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore()), onSourceStored = { heard += it }).refreshOnLaunch() }

        assertEquals(
            listOf(
                SourceStored(CURATED, changed = true),
                SourceStored(CURATED, changed = false, viewCountsWritten = true),
            ),
            heard,
        )
        val started = log.indexOfFirst { it.startsWith("View count first read started") }
        val launch = log.indexOfFirst { it.startsWith("Refresh started by") }
        assertTrue(log.toString(), launch in 0 until started)
    }

    @Test
    fun `a launch check that cannot reach YouTube does not start the first read`() {
        val store = storeBeforeTheFirstRead()
        val api = object : FakeYouTubeApi(playlists = mapOf(CURATED to countedRefs)) {
            override suspend fun playlistsByIds(playlistIds: List<String>): List<PlaylistDetails> =
                throw YouTubeApiException(YouTubeApiException.Reason.NETWORK, "no route")
        }
        runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refreshOnLaunch() }
        assertEquals(0, api.videoCalls)
        assertNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
    }

    @Test
    fun `a quota refusal in the first read stops every refresh, as one in a pass does`() {
        val store = storeBeforeTheFirstRead()
        val api = object : FakeYouTubeApi(
            playlists = mapOf(CURATED to countedRefs),
            playlistCounts = mapOf(CURATED to countedRefs.size),
        ) {
            override suspend fun videos(videoIds: List<String>): List<VideoDetails> =
                throw YouTubeApiException(YouTubeApiException.Reason.QUOTA_EXCEEDED, "403 quotaExceeded")
        }
        val refresher = refresher(store, api, WatchLog(InMemoryWatchStore()))
        runBlocking { refresher.refreshOnLaunch() }
        assertNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
        assertTrue(refresher.isStoppedByQuota())
    }

    @Test
    fun `a launch that owes a full walk skips the first read, and the full walk marks it done`() {
        // Found by `/code-review`: the first read would pay about 140 units for counts the full
        // walk writes a minute later.
        val store = storeWith(stored(CURATED, "a" to 3, "b" to 2, "c" to 1))
        val api = countsApi(mapOf("a" to 100L, "b" to 200L, "c" to 300L))

        runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refreshOnLaunch() }

        assertFalse(log.toString(), log.any { it.startsWith("View count first read") })
        assertTrue(log.any { it.startsWith("Refresh by the weekly full walk finished") })
        assertEquals(mapOf("a" to 100L, "b" to 200L, "c" to 300L), countsOf(store))
        assertNotNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
    }

    @Test
    fun `the 30 minute timer runs the first read a launch could not`() {
        // Found by `/code-review`: a launch with no network would otherwise leave every card
        // without a count until the next cold start.
        val store = storeBeforeTheFirstRead()
        val api = countsApi(mapOf("a" to 1L, "b" to 2L, "c" to 3L))

        runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.TIMER) }

        assertEquals(mapOf("a" to 1L, "b" to 2L, "c" to 3L), countsOf(store))
        assertNotNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
    }

    @Test
    fun `a manual refresh of one Source does not run the first read`() {
        val store = storeBeforeTheFirstRead()
        val api = countsApi(mapOf("a" to 1L))
        runBlocking { refresher(store, api, WatchLog(InMemoryWatchStore())).refresh(RefreshTrigger.MANUAL, CURATED) }
        assertNull(store.meta(META_VIEW_COUNTS_FIRST_READ))
    }
}

/** A second curated playlist, for the tests that need two Sources re-listed in one run. */
private const val SECOND = "PLsecondPlaylistForBuildTicket55xxxxxx"

/**
 * A store that hands back rows with the same publish time in the other order. Build ticket 56.
 *
 * SQL gives rows that tie on the `ORDER BY` no fixed order, and `SqliteSourceStore.allVideos`
 * ordered by the publish date alone. On the emulator on 2026-10-03, with a copy of the household
 * database, Android's SQLite returned the household Source's two undated videos in the reverse of
 * the order `replaceVideos` had written them in. [InMemorySourceStore] never hands a tie back in
 * another order, so on its own it can never show the defect.
 *
 * Build ticket 57 gave a tie one fixed order, by video id, in the SQL and in Kotlin. This store
 * is kept: it proves `storedVideosChanged` does not depend on that agreement.
 */
private class TiesReversedStore(private val inner: InMemorySourceStore) : SourceStore by inner {
    /** How many times a refresh rewrote a Source's rows. */
    var replaceCalls = 0
        private set

    override fun allVideos(sourceId: String): List<StoredVideo> =
        inner.allVideos(sourceId).groupBy { it.videoPublishedAt }.values.flatMap { it.reversed() }

    override fun replaceVideos(sourceId: String, videos: List<StoredVideo>) {
        replaceCalls++
        inner.replaceVideos(sourceId, videos)
    }
}
