package local.youtubetv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real `SqliteSourceStore` puts videos with exactly the same publish time in the same order
 * as [orderByPublishDate], in both Directions. Build ticket 57.
 *
 * ## Why the rule exists
 *
 * Both SQL reads ordered by the publish date alone, and SQL promises no order for rows that tie:
 * two videos with exactly the same publish time, or two undated ones.
 * The query plan reads the index `videos_by_source_and_date` and then sorts in a temporary
 * B-tree, so the order of a tie came from rowids, which is the order the rows were written in,
 * and from the SQLite version. [orderByPublishDate] was a stable sort, so its order of a tie came
 * from the list it was given. The Source screen holds a list Kotlin sorted after a flip and
 * during a first walk, and reads SQL order otherwise, so a tied pair could swap places on screen.
 *
 * ## Why this belongs in `androidTest`
 *
 * The order is SQLite's own behaviour, and there is no SQLite on the JVM in this project. See
 * "The fifth thing" in `docs/agents/testing.md`. The Kotlin side is covered on the JVM by the
 * file of the same name in `app/src/test/`.
 *
 * ## How it fails without the rule
 *
 * Each Source is written twice, once in each order, into two Source ids. Without a video id key
 * the two reads follow the two write orders, so they differ whatever SQLite chooses to do with
 * rowids.
 *
 * ## The database
 *
 * A real [YouTubeTvDatabase] on a file of this test's own, never the app's `youtube-tv.db`.
 */
@RunWith(AndroidJUnit4::class)
class TieOrderTest {

    private companion object {
        const val DATABASE = "tie-order-test.db"
        const val FORWARDS = "UUtieOrderForwards"
        const val BACKWARDS = "UUtieOrderBackwards"
        val EPOCH: Instant = Instant.parse("2014-01-01T00:00:00Z")
    }

    private lateinit var helper: YouTubeTvDatabase
    private lateinit var store: SqliteSourceStore

    private fun video(videoId: String, sourceId: String, day: Int?) = StoredVideo(
        videoId = videoId,
        sourceId = sourceId,
        videoPublishedAt = day?.let { EPOCH.plusSeconds(it * 86_400L) },
        title = if (day == null) "" else "Video $videoId",
        thumbnailUrl = null,
        durationSeconds = if (day == null) null else 600,
        liveBroadcastContent = LiveBroadcastContent.NONE,
        liveEndedAt = null,
        availability = if (day == null) Availability.UNAVAILABLE else Availability.AVAILABLE,
        isShort = false,
    )

    /**
     * The household TED's three tied pairs, each at a publish time of its own, with a dated
     * neighbour on each side, and the household Source's two undated videos last. The undated
     * pair is not listed, so it reaches [SqliteSourceStore.allVideos] alone.
     */
    private fun videosOf(sourceId: String): List<StoredVideo> = listOf(
        video("newest", sourceId, day = 40),
        video("btQHSDrLlok", sourceId, day = 30),
        video("i0h7N1ukZ4A", sourceId, day = 30),
        video("OtVlE9eJaDI", sourceId, day = 20),
        video("QPmUAfuqM08", sourceId, day = 20),
        video("8lXYZ6s3Dfk", sourceId, day = 10),
        video("EHTmxmuhZ10", sourceId, day = 10),
        video("oldest", sourceId, day = 1),
        video("yUndated-_1", sourceId, day = null),
        video("3Undated-_2", sourceId, day = null),
    )

    private fun ids(list: List<VideoKey>) = list.map { it.videoId }

    /** The order `orderByPublishDate` gives the listed videos, which SQL must match. */
    private fun listedInKotlin(direction: Direction) =
        ids(orderByPublishDate(videosOf(FORWARDS).filter { it.isListed }, direction))

    @Before
    fun openAFreshDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE)
        helper = YouTubeTvDatabase(context, DATABASE)
        store = SqliteSourceStore(helper)
        store.saveSource(StoredSource(FORWARDS, title = "Forwards", lastWalkedAt = null))
        store.saveSource(StoredSource(BACKWARDS, title = "Backwards", lastWalkedAt = null))
        store.replaceVideos(FORWARDS, videosOf(FORWARDS))
        store.replaceVideos(BACKWARDS, videosOf(BACKWARDS).reversed())
    }

    @After
    fun deleteIt() {
        helper.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DATABASE)
    }

    private fun turn(direction: Direction) {
        store.setDirection(FORWARDS, direction)
        store.setDirection(BACKWARDS, direction)
    }

    @Test
    fun listedVideosPutsATieInTheOrderKotlinDoesInBothDirections() {
        for (direction in Direction.entries) {
            turn(direction)
            val kotlin = listedInKotlin(direction)

            assertEquals("forwards, $direction", kotlin, ids(store.listedVideos(FORWARDS)))
            assertEquals("backwards, $direction", kotlin, ids(store.listedVideos(BACKWARDS)))
        }
    }

    @Test
    fun listedVideoKeysPutsATieInTheOrderKotlinDoesInBothDirections() {
        // The home rail reads this one, so it must agree with the Source screen's list.
        for (direction in Direction.entries) {
            turn(direction)
            val kotlin = listedInKotlin(direction)

            assertEquals("forwards, $direction", kotlin, ids(store.listedVideoKeys(FORWARDS)))
            assertEquals("backwards, $direction", kotlin, ids(store.listedVideoKeys(BACKWARDS)))
        }
    }

    @Test
    fun allVideosPutsATieAndTheUndatedVideosInTheOrderKotlinDoes() {
        // Newest first always: nothing draws this list, see `SourceStore.allVideos`.
        val kotlin = ids(orderByPublishDate(videosOf(FORWARDS), Direction.NEWEST_FIRST))

        for (direction in Direction.entries) {
            turn(direction)
            assertEquals("forwards, $direction", kotlin, ids(store.allVideos(FORWARDS)))
            assertEquals("backwards, $direction", kotlin, ids(store.allVideos(BACKWARDS)))
        }
    }

    @Test
    fun aFlipInKotlinGivesTheListAFreshReadGives() {
        // `SourceScreenModel.flip` turns the list it holds with `orderByPublishDate` and does
        // not read storage again. The Source opened fresh in that Direction reads SQL.
        for (sourceId in listOf(FORWARDS, BACKWARDS)) {
            for (direction in Direction.entries) {
                turn(direction.flipped())
                val held = store.listedVideos(sourceId)
                turn(direction)

                assertEquals(
                    "$sourceId, to $direction",
                    ids(store.listedVideos(sourceId)),
                    ids(orderByPublishDate(held, direction)),
                )
            }
        }
    }
}
