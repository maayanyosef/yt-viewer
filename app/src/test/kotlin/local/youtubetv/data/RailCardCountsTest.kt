package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val RAIL_NOON: Instant = Instant.parse("2026-09-20T12:00:00Z")

private fun watched(videoId: String) = WatchState(
    videoId = videoId,
    watched = true,
    positionSeconds = null,
    completed = true,
    lastPlayedAt = RAIL_NOON,
)

private fun states(vararg watches: WatchState): Map<String, WatchState> =
    watches.associateBy { it.videoId }

/**
 * The narrow read the home rail uses, and the promise that it says the same as the wide one.
 *
 * Build ticket 22 split one function in two. [railCardCounts] works the numbers out over
 * [VideoKey], which is two columns a video, and [railCard] puts them together with the one
 * video the card names. [sourceCard] is the same thing over whole videos and is what every
 * other caller and every older test uses.
 *
 * **The tests that matter most here are the agreement ones.** Two ways of working out the same
 * card is exactly the shape that drifts apart, and a card that counts differently from its own
 * grid is a fault the household would see as a wrong badge.
 */
class RailCardCountsTest {

    private val stored = StoredSource(
        sourceId = "UUx",
        title = "TED",
        lastWalkedAt = RAIL_NOON,
        direction = Direction.NEWEST_FIRST,
    )

    /** Videos with every reason to be dropped: a Short, an unavailable one and a premiere. */
    private val mixed = listOf(
        testVideo("v5", day = 5),
        testVideo("short", day = 4, isShort = true),
        testVideo("gone", day = 3, availability = Availability.UNAVAILABLE),
        testVideo("soon", day = 2, liveBroadcastContent = LiveBroadcastContent.UPCOMING),
        testVideo("v1", day = 1),
        testVideo("undated", day = null),
    )

    /** What a narrow read of [mixed] would hand back: the listed rows, in the same order. */
    private val listed: List<VideoKey> = mixed.filter { it.isListed }

    @Test
    fun `the narrow counts and the wide card agree on an untouched Source`() {
        val wide = sourceCard("UUx", stored, mixed, emptyMap(), lastPlayedVideoId = null)

        val counts = railCardCounts(
            listed,
            emptyMap(),
            lastPlayedVideoId = null,
            Direction.NEWEST_FIRST,
        )
        val narrow = railCard(
            sourceId = "UUx",
            stored = stored,
            counts = counts,
            nextUnwatched = mixed.first { it.videoId == counts.nextUnwatchedVideoId },
        )

        assertEquals(wide, narrow)
    }

    @Test
    fun `the narrow counts and the wide card agree once some videos are watched`() {
        val watches = states(watched("v5"), watched("undated"))
        val wide = sourceCard("UUx", stored, mixed, watches, lastPlayedVideoId = "v5")

        val counts = railCardCounts(
            listed,
            watches,
            lastPlayedVideoId = "v5",
            Direction.NEWEST_FIRST,
        )
        val narrow = railCard(
            sourceId = "UUx",
            stored = stored,
            counts = counts,
            nextUnwatched = mixed.first { it.videoId == counts.nextUnwatchedVideoId },
        )

        assertEquals(wide, narrow)
    }

    @Test
    fun `the narrow counts and the wide card agree on a finished Source`() {
        val watches = states(watched("v5"), watched("v1"), watched("undated"))
        val wide = sourceCard("UUx", stored, mixed, watches, lastPlayedVideoId = null)

        val counts = railCardCounts(
            listed,
            watches,
            lastPlayedVideoId = null,
            Direction.NEWEST_FIRST,
        )
        assertNull(counts.nextUnwatchedVideoId)
        val narrow = railCard("UUx", stored, counts, nextUnwatched = null)

        assertEquals(wide, narrow)
    }

    @Test
    fun `a Short, an unavailable video and a premiere are in none of the counts`() {
        val counts = railCardCounts(
            listed,
            emptyMap(),
            lastPlayedVideoId = null,
            Direction.NEWEST_FIRST,
        )

        assertEquals(3, counts.listedCount)
        assertEquals(3, counts.unwatchedCount)
        assertEquals("v5", counts.nextUnwatchedVideoId)
    }

    @Test
    fun `the undated video is counted on the card but not by Mark everything unwatched`() {
        val counts = railCardCounts(
            listed,
            emptyMap(),
            lastPlayedVideoId = null,
            Direction.NEWEST_FIRST,
        )

        assertEquals(3, counts.listedCount)
        // `v5` and `v1` carry a date; `undated` does not, so no range can reach it.
        assertEquals(2, counts.everythingUnwatchedCount)
    }

    @Test
    fun `a Source of undated videos alone can mark nothing`() {
        val counts = railCardCounts(
            listed = listOf(testVideo("undated", day = null)),
            watches = emptyMap(),
            lastPlayedVideoId = null,
        direction = Direction.NEWEST_FIRST,
        )

        assertNull(counts.everythingUnwatchedCount)
    }

    @Test
    fun `Last played on a video the Source does not list has nowhere to go`() {
        val counts = railCardCounts(
            listed,
            emptyMap(),
            lastPlayedVideoId = "gone",
            Direction.NEWEST_FIRST,
        )

        assertNull(counts.lastPlayedVideoId)
    }

    @Test
    fun `Last played on a listed video is kept`() {
        val counts = railCardCounts(
            listed,
            emptyMap(),
            lastPlayedVideoId = "v1",
            Direction.NEWEST_FIRST,
        )

        assertEquals("v1", counts.lastPlayedVideoId)
    }

    /**
     * The card names a video the store no longer lists.
     *
     * It cannot happen inside one read, because the same query answers both. It can happen if a
     * refresh drops the video between the two, and the card must then read as a finished Source
     * rather than draw a picture of nothing.
     */
    @Test
    fun `a Next unwatched the store no longer lists leaves the card with no video`() {
        val counts = railCardCounts(
            listed,
            emptyMap(),
            lastPlayedVideoId = null,
            Direction.NEWEST_FIRST,
        )

        val card = railCard("UUx", stored, counts, nextUnwatched = null)

        assertNull(card.nextUnwatched)
        // The counts are untouched: the badge still says what the read found.
        assertEquals(3, card.unwatchedCount)
    }

    /**
     * The badge counts exactly the videos the slot can still reach.
     *
     * Build ticket 30's own table has three rows, and the fault it protects against is the one
     * ticket 23 removed from this card: a count saying one thing while the Next unwatched line
     * says another. [nextUnwatchedLanding] answers both, so they cannot come apart, and this is
     * the test that would fail first if a second copy of the rule ever appeared.
     *
     * The three listed videos are `v5`, `v1` and `undated`, in that order.
     */
    @Test
    fun `the badge and the Next unwatched slot agree in all three rows of the table`() {
        // Row one: a gap after the high-water mark. `v5` is watched, so the mark is index 0
        // and two videos are still ahead.
        val ahead = railCardCounts(
            listed,
            states(watched("v5")),
            lastPlayedVideoId = null,
            Direction.NEWEST_FIRST,
        )
        assertEquals("v1", ahead.nextUnwatchedVideoId)
        assertEquals(2, ahead.unwatchedCount)

        // Row two: nothing after the mark, and a gap above it. `undated` is the last listed
        // video, so the mark is the end of the list and the fallback offers `v5` again. The
        // count jumps with the landing, from one to two, and never disagrees with it.
        val behind = railCardCounts(
            listed,
            states(watched("undated")),
            lastPlayedVideoId = null,
            Direction.NEWEST_FIRST,
        )
        assertEquals("v5", behind.nextUnwatchedVideoId)
        assertEquals(2, behind.unwatchedCount)

        // Row three: no gap anywhere. `ALL WATCHED` beside `Nothing left in this Source`.
        val finished = railCardCounts(
            listed,
            states(watched("v5"), watched("v1"), watched("undated")),
            lastPlayedVideoId = null,
        direction = Direction.NEWEST_FIRST,
        )
        assertNull(finished.nextUnwatchedVideoId)
        assertEquals(0, finished.unwatchedCount)
    }

    /** A Source the household added a moment ago, which no walk has reached yet. */
    @Test
    fun `a Source that has never been walked is not walked on its card`() {
        val unwalked = stored.copy(lastWalkedAt = null)

        val card = railCard("UUx", unwalked, railCardCounts(
            emptyList(),
            emptyMap(),
            null,
            Direction.NEWEST_FIRST), null,
        )

        assertEquals(false, card.walked)
        assertEquals(0, card.listedCount)
    }
}

/**
 * The narrow read of the store, against the wide one it has to agree with.
 *
 * [InMemorySourceStore] answers both from the same list, so this cannot catch a SQL mistake.
 * What it does fix is the shape of the promise: same rows, same order. The SQLite side keeps
 * the two honest by sharing one `WHERE` and one `ORDER BY`; there is no `androidTest` source
 * set to prove that on a device, and `SqliteSourceStore` is named in the ticket's closing notes
 * for it.
 */
class ListedVideoKeysTest {

    private fun storeWith(direction: Direction): SourceStore {
        val store = InMemorySourceStore()
        store.saveSource(
            StoredSource(
                sourceId = "UUx",
                title = "TED",
                lastWalkedAt = RAIL_NOON,
                direction = direction,
            ),
        )
        store.replaceVideos(
            "UUx",
            listOf(
                testVideo("v3", day = 3),
                testVideo("short", day = 4, isShort = true),
                testVideo("v1", day = 1),
                testVideo("v2", day = 2),
            ),
        )
        return store
    }

    @Test
    fun `the keys are the listed videos, in the same order, newest first`() {
        val store = storeWith(Direction.NEWEST_FIRST)

        assertEquals(
            store.listedVideos("UUx").map { it.videoId },
            store.listedVideoKeys("UUx").map { it.videoId },
        )
        assertEquals(listOf("v3", "v2", "v1"), store.listedVideoKeys("UUx").map { it.videoId })
    }

    @Test
    fun `the keys follow the Source's own Direction`() {
        val store = storeWith(Direction.OLDEST_FIRST)

        assertEquals(listOf("v1", "v2", "v3"), store.listedVideoKeys("UUx").map { it.videoId })
    }

    @Test
    fun `each key carries the date a range is compared against`() {
        val store = storeWith(Direction.NEWEST_FIRST)

        assertEquals(
            store.listedVideos("UUx").map { it.videoPublishedAt },
            store.listedVideoKeys("UUx").map { it.videoPublishedAt },
        )
    }

    @Test
    fun `one listed video is read whole by its id`() {
        val store = storeWith(Direction.NEWEST_FIRST)

        assertEquals("Video v2", store.listedVideo("UUx", "v2")?.title)
    }

    @Test
    fun `a video the Source does not list is not read`() {
        val store = storeWith(Direction.NEWEST_FIRST)

        assertNull(store.listedVideo("UUx", "short"))
        assertNull(store.listedVideo("UUx", "never-stored"))
    }
}

/** [availabilityNamed] replaced `Availability.valueOf` because it was measured as slow. */
class AvailabilityNamedTest {

    @Test
    fun `every stored name reads back as the value it was written from`() {
        for (value in Availability.entries) {
            assertEquals(value, availabilityNamed(value.name))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a name no build ever wrote still throws, exactly as valueOf did`() {
        availabilityNamed("SOMETHING_ELSE")
    }
}
