package local.youtubetv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `SqliteSourceStore.replaceVideos` never writes a Newly listed flag back on for a video whose
 * stored flag is already cleared.
 *
 * **This is the test commit `2838f1c` said it owed and did not write.** That commit's own
 * closing words were that `SqliteSourceStore` is Android-only, so no JVM test reaches the
 * change, and that it wanted an `androidTest`. This is it.
 *
 * ## Why the rule exists
 *
 * `SourceRefresh.refreshOne` reads the stored videos, then makes the network calls, then
 * writes the merged list back. A full walk of a long Source takes minutes.
 * `mergeRefresh` carries `newlyListed` forward from the snapshot taken before those calls, and
 * `replaceVideos` deletes every row of the Source and inserts them again. A play during the
 * walk clears the flag in storage through `SourceStore.clearNewlyListedUpTo`, and without the
 * rule the stale `true` from the snapshot is written straight back. The household would then
 * be offered, as Next unwatched, a video they had just finished watching.
 *
 * ## Why this belongs in `androidTest` and not beside the other Newly listed tests
 *
 * `docs/agents/testing.md` reserves this layer for focus, keys, measurement and composition
 * identity, and this is none of the four. It is here for the one other reason: the code under
 * test is SQL running inside a real `SQLiteDatabase` transaction, and there is no SQLite on
 * the JVM in this project. The rule itself is covered on the JVM, against
 * `InMemorySourceStore`, by `ReplaceVideosKeepsClearedFlagsClearedTest` in
 * `app/src/test/kotlin/local/youtubetv/data/NewlyListedTest.kt`. **These two test the same six
 * things on purpose**: the fake's whole promise is that a test passing against it is testing
 * the rule and not the SQL, and that promise was false for this very rule until 2026-09-21.
 * This file is what keeps it honest.
 *
 * The one behaviour the two cannot share is the NULL row, which only SQLite has. It has a
 * test of its own at the end.
 *
 * ## The database
 *
 * A real [YouTubeTvDatabase], with the real schema and the real migrations, opened on a file
 * of this test's own. It is never the app's `youtube-tv.db`: the one table this app may never
 * drop is the Watch log, and a test that writes into the app's own file is one mistake away
 * from that.
 *
 * **This test was first run on 2026-09-21 and all seven of its cases passed.** It was written
 * earlier the same day by the review of the review, in a session that was forbidden to touch
 * the household television and told not to run `./gradlew emulatorTest`, so it went in having
 * never been run at all. Step 11 of ticket 40 existed only to
 * give it that run. It ran twice on the `googletv1080p` emulator, clean both times, with
 * `./gradlew emulatorTest -PemulatorTests=local.youtubetv.data.ReplaceVideosNewlyListedTest`.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceVideosNewlyListedTest {

    private companion object {
        const val DATABASE = "replace-videos-newly-listed-test.db"
        const val SOURCE = "UUtestSource"
        const val OTHER_SOURCE = "UUotherSource"
        val EPOCH: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private lateinit var helper: YouTubeTvDatabase
    private lateinit var store: SqliteSourceStore

    /** Day `day` of this test's calendar, which is what every video's publish date is. */
    private fun day(day: Int): Instant = EPOCH.plusSeconds(day * 86_400L)

    private fun video(
        videoId: String,
        day: Int?,
        newlyListed: Boolean,
        sourceId: String = SOURCE,
    ) = StoredVideo(
        videoId = videoId,
        sourceId = sourceId,
        videoPublishedAt = day?.let(::day),
        title = "Video $videoId",
        thumbnailUrl = null,
        durationSeconds = 600,
        liveBroadcastContent = LiveBroadcastContent.NONE,
        liveEndedAt = null,
        availability = Availability.AVAILABLE,
        isShort = false,
        newlyListed = newlyListed,
    )

    /** What a walk hands `replaceVideos`: three videos, all of them flagged. */
    private val fresh = listOf(
        video("v3", day = 3, newlyListed = true),
        video("v2", day = 2, newlyListed = true),
        video("v1", day = 1, newlyListed = true),
    )

    /** The ids the stored rows still say are Newly listed. */
    private fun flagged(sourceId: String = SOURCE) =
        store.allVideos(sourceId).filter { it.newlyListed }.map { it.videoId }.toSet()

    /**
     * The raw `newly_listed` column of every row of this Source, by video id.
     *
     * A null in the answer is a SQL NULL and a 0 is a real zero. [SqliteSourceStore] reads both
     * back as false, so nothing reachable through the store can tell them apart, and telling
     * them apart is the whole point of [theClearingStatementLeavesAnUnflaggedRowAlone].
     */
    private fun rawFlags(sourceId: String = SOURCE): Map<String, Int?> {
        val out = mutableMapOf<String, Int?>()
        helper.readableDatabase.rawQuery(
            "SELECT video_id, newly_listed FROM videos WHERE source_id = ?",
            arrayOf(sourceId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out[cursor.getString(0)] = if (cursor.isNull(1)) null else cursor.getInt(1)
            }
        }
        return out
    }

    @Before
    fun openAFreshDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Start from nothing, so a previous run cannot decide this one's answer.
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
    fun aFirstListingKeepsTheFlagTheWalkGaveIt() {
        // Nothing is stored, so there is no cleared flag to respect.
        store.replaceVideos(SOURCE, fresh)

        assertEquals(setOf("v3", "v2", "v1"), flagged())
    }

    @Test
    fun aWalkCannotSetTheFlagAgainOnAVideoWhoseFlagIsAlreadyCleared() {
        store.replaceVideos(SOURCE, fresh)
        // The play during the walk. Newest-first, it clears day 2 and everything newer.
        store.clearNewlyListedUpTo(SOURCE, day(2))
        assertEquals(setOf("v1"), flagged())

        // The walk finishes and writes back the snapshot it took before the play.
        store.replaceVideos(SOURCE, fresh)

        assertEquals(setOf("v1"), flagged())
    }

    @Test
    fun theFlagTravelsOneWayOnlyHoweverManyWalksRun() {
        store.replaceVideos(SOURCE, fresh)
        store.clearNewlyListedUpTo(SOURCE, day(1))
        assertEquals(emptySet<String>(), flagged())

        repeat(3) { store.replaceVideos(SOURCE, fresh) }

        assertEquals(emptySet<String>(), flagged())
    }

    @Test
    fun aVideoTheWalkListsForTheFirstTimeIsUnaffectedByTheRule() {
        store.replaceVideos(SOURCE, fresh)
        store.clearNewlyListedUpTo(SOURCE, day(1))

        // `v4` has no stored row at all, so it has no cleared flag to respect.
        val withNewOne = listOf(video("v4", day = 4, newlyListed = true)) + fresh
        store.replaceVideos(SOURCE, withNewOne)

        assertEquals(setOf("v4"), flagged())
    }

    @Test
    fun theRuleDoesNotReachAnotherSource() {
        store.saveSource(StoredSource(OTHER_SOURCE, title = "Another", lastWalkedAt = null))
        store.replaceVideos(SOURCE, fresh)
        store.clearNewlyListedUpTo(SOURCE, day(1))

        store.replaceVideos(
            OTHER_SOURCE,
            listOf(video("v1", day = 1, newlyListed = true, sourceId = OTHER_SOURCE)),
        )

        // The same video id, cleared in one Source, keeps its flag in the other. The read is
        // keyed by `source_id`, and this is what proves it.
        assertTrue(store.allVideos(OTHER_SOURCE).single().newlyListed)
        assertEquals(emptySet<String>(), flagged())
    }

    @Test
    fun nothingButTheFlagIsRewritten() {
        store.replaceVideos(SOURCE, fresh)
        store.clearNewlyListedUpTo(SOURCE, day(1))

        store.replaceVideos(SOURCE, fresh)

        assertEquals(
            fresh.map { it.copy(newlyListed = false) }.sortedBy { it.videoId },
            store.allVideos(SOURCE).sortedBy { it.videoId },
        )
    }

    /**
     * A row whose `newly_listed` is NULL is **not** read as already cleared by the SQL.
     *
     * `clearedNewlyListedIds` asks for `newly_listed = 0`, and SQL's `=` never matches NULL,
     * while `Cursor.booleanOrFalse` reads a NULL back as false. The two disagree, and the KDoc
     * on `clearedNewlyListedIds` argues the disagreement cannot reach the household: a video
     * with a stored row always takes `mergeRefresh`'s "already known" branch and carries
     * `before.newlyListed`, which is false for a NULL row, so `stillNew` is already false.
     *
     * This pins the disagreement itself, so that the argument above is checked against the
     * database rather than believed. Every row the household's television holds is NULL today:
     * database version 10's migration adds the column with no backfill.
     *
     * The JVM twin cannot have this test. [StoredVideo.newlyListed] is a non-null `Boolean`,
     * so [InMemorySourceStore] has no NULL to write.
     */
    @Test
    fun aNullFlagIsNotTreatedAsAlreadyCleared() {
        store.replaceVideos(SOURCE, fresh)
        // What the version 10 migration leaves behind: the column present and every row NULL.
        helper.writableDatabase.execSQL(
            "UPDATE videos SET newly_listed = NULL WHERE source_id = ?",
            arrayOf<Any>(SOURCE),
        )
        // Kotlin reads all three back as not newly listed.
        assertEquals(emptySet<String>(), flagged())

        store.replaceVideos(SOURCE, fresh)

        // The SQL did not see them as cleared, so the walk's own flags won. This is the
        // documented divergence, written down so a later change to either side is a decision.
        assertEquals(setOf("v3", "v2", "v1"), flagged())
    }

    /**
     * `clearNewlyListedUpTo` rewrites only the rows that really carry the flag.
     *
     * Its `UPDATE` used to have no `newly_listed` predicate, so it wrote 0 over every row of the
     * Source at or past the date whether or not that row was flagged. That runs **on the main
     * thread**, inside the transaction `WatchLog.startPlay` opens, on the frame a play passes ten
     * seconds: on a Source the size of `TED` it was over 5,000 rows rewritten to change one.
     * Raised by `/code-review high` on 2026-09-21.
     *
     * The check has to read the raw column, because a row left NULL and a row written to 0 are
     * the same thing to every reader above the SQL. That is also why this test cannot have a JVM
     * twin: [StoredVideo.newlyListed] is a non-null `Boolean`, so [InMemorySourceStore] has no
     * NULL to leave alone.
     */
    @Test
    fun theClearingStatementLeavesAnUnflaggedRowAlone() {
        store.replaceVideos(SOURCE, fresh)
        // `v3` and `v1` become what the version 10 migration leaves behind, and `v2` keeps the
        // flag the walk gave it. All three are at or past day 1.
        helper.writableDatabase.execSQL(
            "UPDATE videos SET newly_listed = NULL WHERE source_id = ? AND video_id IN ('v3', 'v1')",
            arrayOf<Any>(SOURCE),
        )
        assertEquals(mapOf("v3" to null, "v2" to 1, "v1" to null), rawFlags())

        store.clearNewlyListedUpTo(SOURCE, day(1))

        // Only the flagged row was written. The two NULL rows are still NULL, which they could
        // not be if the statement had touched every row in the date range.
        assertEquals(mapOf("v3" to null, "v2" to 0, "v1" to null), rawFlags())
        assertEquals(emptySet<String>(), flagged())
    }
}
