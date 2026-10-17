package local.youtubetv.refresh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import local.youtubetv.data.Availability
import local.youtubetv.data.LiveBroadcastContent
import local.youtubetv.data.SqliteSourceStore
import local.youtubetv.data.StoredSource
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.YouTubeTvDatabase
import local.youtubetv.data.orderByPublishDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A list read back from the real `SqliteSourceStore` is the same rows as the list written,
 * whatever order SQLite returns the ties in. Build ticket 56.
 *
 * ## Why the rule exists
 *
 * `SourceRefresh.refreshOne` reads `SourceStore.allVideos`, merges the fresh listing into it, and
 * asks [storedVideosChanged] whether to rewrite the Source. `allVideos` ordered by the publish
 * date alone, and SQL promises no order for tied rows: two videos with exactly the same publish
 * time, or two undated ones. On the emulator on 2026-10-03, with a copy of the household
 * database, Android's SQLite returned the household Source's two undated videos in the reverse
 * of their rowid order. An in-order comparison then read every full walk as a change.
 *
 * Build ticket 57 added the video id as the last key, so a tie now has one order, and
 * `data/TieOrderTest` checks that order.
 *
 * ## Why this belongs in `androidTest`
 *
 * The order is SQLite's own behaviour, and there is no SQLite on the JVM in this project. See
 * "The fifth thing" in `docs/agents/testing.md`. The rule itself is covered on the JVM by
 * `RefreshMergeTest` and, end to end, by `SourceRefreshTest` with a store that reverses ties.
 * This file checks the rule against the order the real store really gives.
 *
 * **It asserts nothing about which order SQLite chooses.** That is not promised and may differ
 * between SQLite versions. It asserts that the rule gives the right answer either way.
 *
 * ## The database
 *
 * A real [YouTubeTvDatabase] on a file of this test's own, never the app's `youtube-tv.db`.
 */
@RunWith(AndroidJUnit4::class)
class StoredVideosTieTest {

    private companion object {
        const val DATABASE = "stored-videos-tie-test.db"
        const val SOURCE = "PLtiesTestSource"
        val EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private lateinit var helper: YouTubeTvDatabase
    private lateinit var store: SqliteSourceStore

    private fun video(videoId: String, day: Int?, title: String = "Video $videoId") = StoredVideo(
        videoId = videoId,
        sourceId = SOURCE,
        videoPublishedAt = day?.let { EPOCH.plusSeconds(it * 86_400L) },
        title = title,
        thumbnailUrl = null,
        durationSeconds = if (day == null) null else 600,
        liveBroadcastContent = LiveBroadcastContent.NONE,
        liveEndedAt = null,
        availability = if (day == null) Availability.UNAVAILABLE else Availability.AVAILABLE,
        isShort = false,
    )

    /**
     * The household Source's shape in small, in the order `mergeRefresh` hands `replaceVideos`:
     * two dated videos with exactly the same publish time, and two undated ones last, each pair
     * in the playlist's order.
     */
    private val written = orderByPublishDate(
        listOf(
            video("newest", day = 9),
            video("twinA", day = 5),
            video("twinB", day = 5),
            video("oldest", day = 1),
            video("yUndated-_1", day = null, title = ""),
            video("3Undated-_2", day = null, title = ""),
        ),
    )

    @Before
    fun openAFreshDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE)
        helper = YouTubeTvDatabase(context, DATABASE)
        store = SqliteSourceStore(helper)
        store.saveSource(StoredSource(SOURCE, title = "A Source", lastWalkedAt = null))
    }

    @After
    fun deleteIt() {
        helper.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DATABASE)
    }

    @Test
    fun theRowsReadBackAreTheRowsWrittenWhateverOrderSqliteChose() {
        store.replaceVideos(SOURCE, written)

        val readBack = store.allVideos(SOURCE)

        assertEquals(written.toSet(), readBack.toSet())
        assertFalse(
            "read back as ${readBack.map { it.videoId }}, written as ${written.map { it.videoId }}",
            storedVideosChanged(stored = readBack, merged = written),
        )
    }

    @Test
    fun aTitleThatChangedIsStillAChangeAgainstTheRealStore() {
        store.replaceVideos(SOURCE, written)

        val merged = written.map { if (it.videoId == "twinB") it.copy(title = "A new title") else it }

        assertTrue(storedVideosChanged(stored = store.allVideos(SOURCE), merged = merged))
    }
}
