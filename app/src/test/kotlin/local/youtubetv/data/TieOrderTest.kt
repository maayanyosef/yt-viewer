package local.youtubetv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Videos with exactly the same publish time have one fixed order. Build ticket 57. Two videos
 * from the same day at different times are not a tie, and their publish times order them.
 *
 * ## Why the rule exists
 *
 * Videos are ordered in two places. `SqliteSourceStore` orders in SQL, and SQL promises no order
 * for rows that tie on the `ORDER BY`, so the answer depended on rowids and on the SQLite
 * version. [orderByPublishDate] was a stable sort, so a tie kept the order of the list it was
 * given. The two could disagree, and the household's copy of TED has three pairs of listed
 * videos that reach the screen, each pair with exactly the same publish time.
 *
 * ## The rule
 *
 * The video id is the last ordering key, and it keeps one order **in both Directions**, for
 * dated and undated videos alike. Only the publish time key turns around with the Direction.
 * See spec section 4.2.
 *
 * The SQL side is checked against the real SQLite in `app/src/androidTest/`, in the file of the
 * same name, because the order SQLite gives is its own behaviour and there is no SQLite on the
 * JVM in this project.
 */
class TieOrderTest {

    /** The household TED's three tied pairs, each at its own publish time, and two neighbours. */
    private val videos = listOf(
        testVideo("newest", day = 40),
        testVideo("btQHSDrLlok", day = 30),
        testVideo("i0h7N1ukZ4A", day = 30),
        testVideo("OtVlE9eJaDI", day = 20),
        testVideo("QPmUAfuqM08", day = 20),
        testVideo("8lXYZ6s3Dfk", day = 10),
        testVideo("EHTmxmuhZ10", day = 10),
        testVideo("oldest", day = 1),
    )

    private fun ids(list: List<StoredVideo>) = list.map { it.videoId }

    @Test
    fun `a tie comes out in one order whatever order the list arrived in`() {
        for (direction in Direction.entries) {
            assertEquals(
                "in $direction",
                ids(orderByPublishDate(videos, direction)),
                ids(orderByPublishDate(videos.reversed(), direction)),
            )
        }
    }

    @Test
    fun `newest first breaks a tie by video id`() {
        assertEquals(
            listOf(
                "newest",
                "btQHSDrLlok", "i0h7N1ukZ4A",
                "OtVlE9eJaDI", "QPmUAfuqM08",
                "8lXYZ6s3Dfk", "EHTmxmuhZ10",
                "oldest",
            ),
            ids(orderByPublishDate(videos.reversed(), Direction.NEWEST_FIRST)),
        )
    }

    @Test
    fun `oldest first breaks a tie by video id too, so a flip never swaps a tied pair`() {
        // Only the publish time key turns around. The video id key does not, so each pair
        // reads in the same order in both Directions.
        assertEquals(
            listOf(
                "oldest",
                "8lXYZ6s3Dfk", "EHTmxmuhZ10",
                "OtVlE9eJaDI", "QPmUAfuqM08",
                "btQHSDrLlok", "i0h7N1ukZ4A",
                "newest",
            ),
            ids(orderByPublishDate(videos.reversed(), Direction.OLDEST_FIRST)),
        )
    }

    @Test
    fun `the video id order is the one SQLite's BINARY collation gives, not a case-blind one`() {
        // SQLite compares TEXT byte by byte. A YouTube video id is ASCII, so that is the same
        // order as Kotlin's own `String.compareTo`: `-` (0x2D), then digits, then upper case,
        // then `_` (0x5F), then lower case.
        val tied = listOf(
            testVideo("b", day = 5),
            testVideo("_", day = 5),
            testVideo("Z", day = 5),
            testVideo("0", day = 5),
            testVideo("-", day = 5),
        )

        assertEquals(
            listOf("-", "0", "Z", "_", "b"),
            ids(orderByPublishDate(tied, Direction.NEWEST_FIRST)),
        )
    }

    @Test
    fun `undated videos go last in video id order, the same in both Directions`() {
        // Two undated videos with made-up ids. Android's SQLite returned a real pair like
        // this in the reverse of their rowid order on 2026-10-03.
        val withUndated = listOf(
            testVideo("yUndated-_1", day = null),
            testVideo("dated", day = 3),
            testVideo("3Undated-_2", day = null),
        )

        for (direction in Direction.entries) {
            for (input in listOf(withUndated, withUndated.reversed())) {
                assertEquals(
                    "in $direction from ${ids(input)}",
                    listOf("dated", "3Undated-_2", "yUndated-_1"),
                    ids(orderByPublishDate(input, direction)),
                )
            }
        }
    }

    @Test
    fun `a flip gives the list the Source would open on fresh in that Direction`() {
        // The Source screen turns the list it holds, it does not read storage again.
        for (direction in Direction.entries) {
            for (input in listOf(videos, videos.reversed())) {
                val held = orderByPublishDate(input, direction.flipped())
                assertEquals(
                    "to $direction",
                    ids(orderByPublishDate(input, direction)),
                    ids(orderByPublishDate(held, direction)),
                )
            }
        }
    }

    @Test
    fun `two publish times less than a millisecond apart are a tie, as they are in SQL`() {
        // SQLite stores the date as epoch milliseconds, so it sees these two as one publish time
        // and breaks the tie by id. The newer by 0.4 ms has the larger id, so the id decides.
        val earlier = testVideo("aEarlier", day = 5)
        val later = earlier.copy(
            videoId = "bLater",
            videoPublishedAt = earlier.videoPublishedAt!!.plusNanos(400_000),
        )

        for (direction in Direction.entries) {
            assertEquals(
                "in $direction",
                listOf("aEarlier", "bLater"),
                ids(orderByPublishDate(listOf(later, earlier), direction)),
            )
        }
    }

    @Test
    fun `the in-memory store reads ties back in one order whatever order they were written in`() {
        for (direction in Direction.entries) {
            val forwards = InMemorySourceStore()
            val backwards = InMemorySourceStore()
            for (store in listOf(forwards, backwards)) {
                store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = null))
                store.setDirection("UUx", direction)
            }
            forwards.replaceVideos("UUx", videos)
            backwards.replaceVideos("UUx", videos.reversed())

            assertEquals(ids(forwards.listedVideos("UUx")), ids(backwards.listedVideos("UUx")))
            assertEquals(
                forwards.listedVideoKeys("UUx").map { it.videoId },
                backwards.listedVideoKeys("UUx").map { it.videoId },
            )
            assertEquals(ids(forwards.allVideos("UUx")), ids(backwards.allVideos("UUx")))
        }
    }
}
