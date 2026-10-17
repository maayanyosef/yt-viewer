package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOON: Instant = Instant.parse("2026-09-20T12:00:00Z")

/**
 * One `played` entry.
 *
 * @param positionSeconds null is a manual `Mark as watched`, which is a declaration and never a
 *   play. Only an entry carrying a position moves a Source on the rail.
 */
private fun played(
    id: String,
    videoId: String,
    sourceId: String,
    seq: Long,
    positionSeconds: Int? = 30,
) = WatchEntry(
    id = id,
    deviceId = "device-a",
    seq = seq,
    at = NOON.plusSeconds(seq),
    kind = WatchKind.PLAYED,
    payload = playedPayload(videoId, sourceId, positionSeconds, completed = false),
)

/** One `range` entry. */
private fun range(
    id: String,
    sourceId: String,
    seq: Long,
    pivotDay: Int,
    side: RangeSide,
    watched: Boolean,
    clearsLastPlayed: Boolean = false,
) = WatchEntry(
    id = id,
    deviceId = "device-a",
    seq = seq,
    at = NOON.plusSeconds(seq),
    kind = WatchKind.RANGE,
    payload = rangePayload(sourceId, testDay(pivotDay), side, watched, clearsLastPlayed),
)

private fun watchedState(videoId: String) = WatchState(
    videoId = videoId,
    watched = true,
    positionSeconds = null,
    completed = true,
    lastPlayedAt = NOON,
)

private fun watchMap(vararg states: WatchState): Map<String, WatchState> =
    states.associateBy { it.videoId }

private fun card(sourceId: String) = SourceCard(
    sourceId = sourceId,
    title = null,
    direction = Direction.NEWEST_FIRST,
    listedCount = 0,
    unwatchedCount = 0,
    nextUnwatched = null,
    lastPlayedVideoId = null,
    everythingUnwatchedCount = null,
    walked = true,
)

/**
 * What one card of the home rail says about its Source. Spec section 7.1.
 */
class SourceCardTest {

    private val stored = StoredSource(
        sourceId = "UUx",
        title = "TED",
        lastWalkedAt = NOON,
        direction = Direction.NEWEST_FIRST,
    )

    private val videos = listOf(
        testVideo("v3", day = 3),
        testVideo("v2", day = 2),
        testVideo("v1", day = 1),
    )

    @Test
    fun `an untouched Source has every video unwatched and opens on its first card`() {
        val card = sourceCard("UUx", stored, videos, emptyMap(), lastPlayedVideoId = null)

        assertEquals(3, card.listedCount)
        assertEquals(3, card.unwatchedCount)
        assertEquals("v3", card.nextUnwatched?.videoId)
        assertNull(card.lastPlayedVideoId)
        assertTrue(card.walked)
    }

    @Test
    fun `the unwatched count drops as videos are watched`() {
        val card = sourceCard(
            "UUx",
            stored,
            videos,
            watchMap(watchedState("v3"), watchedState("v2")),
            lastPlayedVideoId = null,
        )

        assertEquals(3, card.listedCount)
        assertEquals(1, card.unwatchedCount)
        assertEquals("v1", card.nextUnwatched?.videoId)
    }

    @Test
    fun `a Source with everything watched has no Next unwatched and a zero count`() {
        val watches = watchMap(watchedState("v3"), watchedState("v2"), watchedState("v1"))

        val card = sourceCard("UUx", stored, videos, watches, lastPlayedVideoId = null)

        assertEquals(0, card.unwatchedCount)
        assertNull(card.nextUnwatched)
        // The card keeps its real video count. Nothing about it is dimmed or moved.
        assertEquals(3, card.listedCount)
    }

    /**
     * A Source with no row at all. Its counts are zero because nothing is stored, not because
     * the household has watched it, so [SourceCard.walked] is what keeps the badge honest.
     */
    @Test
    fun `a Source that has never been walked is marked as such`() {
        val card = sourceCard("UUnew", stored = null, videos = emptyList(), emptyMap(), null)

        assertFalse(card.walked)
        assertEquals(0, card.listedCount)
        assertEquals(0, card.unwatchedCount)
        assertNull(card.nextUnwatched)
        assertEquals(Direction.NEWEST_FIRST, card.direction)
    }

    /** The card states the Source's own Direction, which is stored on its row. */
    @Test
    fun `the card carries the Source's own Direction`() {
        val flipped = stored.copy(direction = Direction.OLDEST_FIRST)

        assertEquals(
            Direction.OLDEST_FIRST,
            sourceCard("UUx", flipped, videos, emptyMap(), null).direction,
        )
    }

    /**
     * **This test said the opposite until ticket 23, and the defect it describes is why.**
     *
     * It read "an unwatched but unavailable video is counted and is not Next unwatched", and it
     * asserted `unwatchedCount == 1` beside `nextUnwatched == null`. The comment above it argued
     * that this was "the honest answer to two different questions".
     *
     * On the household television on 2026-09-20 it read as a fault. The 19-video Source held two
     * deleted videos, so its card said `2 UNWATCHED` while its Next unwatched slot said
     * `Nothing left in this Source`. No landing could reach those two, and no Bulk mark could
     * either, because a deleted video has no publish date for a `range` to cover. The Source
     * could never show `ALL WATCHED`. See spec sections 4.4 and 7.1.
     *
     * The count and Next unwatched now agree, because an unavailable video is not a listed
     * video at all.
     */
    @Test
    fun `an unwatched but unavailable video is in no count and is not Next unwatched`() {
        val withDeleted = listOf(
            testVideo("gone", day = 4, availability = Availability.UNAVAILABLE),
            testVideo("v3", day = 3),
        )
        val watches = watchMap(watchedState("v3"))

        val card = sourceCard("UUx", stored, withDeleted, watches, lastPlayedVideoId = null)

        assertEquals(0, card.unwatchedCount)
        assertEquals(1, card.listedCount)
        assertNull(card.nextUnwatched)
    }

    /**
     * `Open at Last played` is dim when the video it would open is not on the grid.
     *
     * The rail menu's line and the Source screen's own chip must agree, and `lastPlayedIndex`
     * is the chip's rule. Found by `/code-review` on ticket 23: this was the one field of
     * `sourceCard` left without the repeated `isListed` test that the counts were given.
     */
    @Test
    fun `last played is null when that video is no longer listed`() {
        val withGone = listOf(
            testVideo("gone", day = 4, availability = Availability.UNAVAILABLE),
            testVideo("v3", day = 3),
        )

        val card = sourceCard("UUx", stored, withGone, emptyMap(), lastPlayedVideoId = "gone")

        assertNull(card.lastPlayedVideoId)
    }

    /** The same for a premiere that has not started. Spec section 4.4 treats the two alike. */
    @Test
    fun `an upcoming premiere is in no count and is not Next unwatched`() {
        val withPremiere = listOf(
            testVideo("soon", day = 4, liveBroadcastContent = LiveBroadcastContent.UPCOMING),
            testVideo("v3", day = 3),
        )
        val watches = watchMap(watchedState("v3"))

        val card = sourceCard("UUx", stored, withPremiere, watches, lastPlayedVideoId = null)

        assertEquals(0, card.unwatchedCount)
        assertEquals(1, card.listedCount)
        assertNull(card.nextUnwatched)
    }

    /** Hidden Shorts are in no count, because the store leaves them out before this is called. */
    @Test
    fun `the unwatched count is over listed videos only`() {
        val listed = listOf(testVideo("v3", day = 3), testVideo("v2", day = 2))

        assertEquals(2, sourceCard("UUx", stored, listed, emptyMap(), null).unwatchedCount)
    }

    /**
     * The count the long-press menu and the confirmation both state. It is on the card so the
     * screen never has to hold a Source's videos to work it out.
     */
    @Test
    fun `the card carries the count Mark everything unwatched would reach`() {
        val card = sourceCard("UUx", stored, videos, emptyMap(), null)

        assertEquals(3, card.everythingUnwatchedCount)
    }

    @Test
    fun `a Source no range can reach carries no such count`() {
        val undated = listOf(testVideo("gone", day = null))

        assertNull(sourceCard("UUx", stored, undated, emptyMap(), null).everythingUnwatchedCount)
        assertNull(sourceCard("UUnew", null, emptyList(), emptyMap(), null).everythingUnwatchedCount)
    }

    /** The menu line has to land on a card the Source screen draws. */
    @Test
    fun `Last played is dropped when the Source no longer lists that video`() {
        val card = sourceCard("UUx", stored, videos, emptyMap(), lastPlayedVideoId = "gone")

        assertNull(card.lastPlayedVideoId)
    }

    @Test
    fun `Last played is kept when the Source still lists that video`() {
        val card = sourceCard("UUx", stored, videos, emptyMap(), lastPlayedVideoId = "v2")

        assertEquals("v2", card.lastPlayedVideoId)
    }
}

/**
 * The rail's one sort rule: most recently played first. Spec section 7.1.
 */
class RailOrderTest {

    @Test
    fun `the Source played most recently comes first`() {
        val entries = listOf(
            played("a", "v1", "UUa", seq = 1),
            played("b", "v2", "UUb", seq = 2),
            played("c", "v3", "UUc", seq = 3),
        )

        assertEquals(listOf("UUc", "UUb", "UUa"), sourcesByLastPlay(entries))
    }

    /** Playing in a Source again moves it to the front, and moves nothing else. */
    @Test
    fun `playing again in an older Source brings it back to the front`() {
        val entries = listOf(
            played("a", "v1", "UUa", seq = 1),
            played("b", "v2", "UUb", seq = 2),
            played("c", "v9", "UUa", seq = 3),
        )

        assertEquals(listOf("UUa", "UUb"), sourcesByLastPlay(entries))
    }

    /**
     * A manual `Mark as watched` writes a `played` entry with no position. It is a declaration
     * and not a play, which is the same rule `lastPlayedVideoId` follows.
     */
    @Test
    fun `a played entry with no position does not move a Source`() {
        val entries = listOf(
            played("a", "v1", "UUa", seq = 1),
            played("b", "v2", "UUb", seq = 2, positionSeconds = null),
        )

        assertEquals(listOf("UUa"), sourcesByLastPlay(entries))
    }

    /**
     * `Mark everything unwatched` says "I am starting this Source again". It takes Last played
     * away, and it does not un-happen the evening the household spent in the Source, so the
     * card keeps its place.
     */
    @Test
    fun `a range that clears Last played does not move a Source on the rail`() {
        val entries = listOf(
            played("a", "v1", "UUa", seq = 1),
            played("b", "v2", "UUb", seq = 2),
            range("c", "UUb", seq = 3, pivotDay = 1, side = RangeSide.NEWER, watched = false,
                clearsLastPlayed = true),
        )

        assertEquals(listOf("UUb", "UUa"), sourcesByLastPlay(entries))
    }

    /** Never played means after everything that has been, in the order the cards arrived in. */
    @Test
    fun `a Source that has never been played keeps its place at the end`() {
        val cards = listOf(card("UUa"), card("UUb"), card("UUc"))

        val order = railOrder(cards, playedOrder = listOf("UUc")).map { it.sourceId }

        assertEquals(listOf("UUc", "UUa", "UUb"), order)
    }

    @Test
    fun `a rail nobody has played keeps the order it was handed`() {
        val cards = listOf(card("UUb"), card("UUa"))

        val order = railOrder(cards, playedOrder = emptyList()).map { it.sourceId }

        assertEquals(listOf("UUb", "UUa"), order)
    }

    /** A Source the log knows about but the rail does not draw changes nothing. */
    @Test
    fun `a played Source that is not on the rail is ignored`() {
        val cards = listOf(card("UUa"), card("UUb"))

        val order = railOrder(cards, playedOrder = listOf("UUgone", "UUb")).map { it.sourceId }

        assertEquals(listOf("UUb", "UUa"), order)
    }
}

/**
 * `Mark everything unwatched`: one `range` pivoted at the start of time. Spec section 7.1.
 */
class EverythingUnwatchedTest {

    private val videos = listOf(
        testVideo("v3", day = 3),
        testVideo("v2", day = 2),
        testVideo("v1", day = 1),
    )

    /**
     * The pivot is the **oldest** video and the side is `NEWER`.
     *
     * This is the pair ticket 05's note in this ticket's file had the wrong way round. A range
     * pivoted on the newest video with `NEWER` covers that one video and nothing else, which
     * would be a button that looked as though it had done nothing.
     */
    @Test
    fun `the range is pivoted on the oldest video and runs newer`() {
        val chosen = everythingUnwatched(videos)!!

        assertEquals("v1", chosen.pivot.videoId)
        assertEquals(RangeSide.NEWER, chosen.side)
    }

    @Test
    fun `the range really covers every dated video of the Source`() {
        val chosen = everythingUnwatched(videos)!!
        val range = Range(
            sourceId = "UUx",
            pivotPublishedAt = chosen.pivot.videoPublishedAt!!,
            // `Mark everything unwatched` is made now, and now is after every video the
            // Source lists. Ticket 42 bounds the `newer` side at this moment.
            madeAt = testDay(100),
            side = chosen.side,
            watched = false,
            clearsLastPlayed = true,
        )

        for (video in videos) {
            assertTrue(video.videoId, range.covers("UUx", video.videoPublishedAt))
        }
    }

    /** The Direction the list arrives in must not change which video is the pivot. */
    @Test
    fun `the pivot is the same whichever way round the Source is`() {
        val oldestFirst = orderByPublishDate(videos, Direction.OLDEST_FIRST)

        assertEquals(
            everythingUnwatched(videos)!!.pivot.videoId,
            everythingUnwatched(oldestFirst)!!.pivot.videoId,
        )
    }

    /**
     * The confirmation promises a count, and it has to be a count the range can keep. A
     * private or deleted video has no publish date, so no range covers it.
     */
    @Test
    fun `the count leaves out a video no range can cover`() {
        val withUndated = videos + testVideo("gone", day = null)

        assertEquals(3, everythingUnwatched(withUndated)!!.count)
    }

    /**
     * An upcoming premiere **does** carry a publish date, so a range really would cover it.
     * It is still left out of the count, because ticket 23 made it an unlisted video and every
     * count the household sees is over listed videos. Spec sections 4.3 and 4.4.
     *
     * The range itself is unchanged: it carries a pivot date and a side and nothing else, so it
     * still covers the premiere by date. Only the promised count is smaller.
     */
    @Test
    fun `the count leaves out an upcoming premiere, which a range still covers`() {
        val premiere = testVideo("soon", day = 9, liveBroadcastContent = LiveBroadcastContent.UPCOMING)
        val withPremiere = videos + premiere

        val chosen = everythingUnwatched(withPremiere)!!

        assertEquals(3, chosen.count)
        val range = Range(
            sourceId = "UUx",
            pivotPublishedAt = chosen.pivot.videoPublishedAt!!,
            // `Mark everything unwatched` is made now, and now is after every video the
            // Source lists. Ticket 42 bounds the `newer` side at this moment.
            madeAt = testDay(100),
            side = chosen.side,
            watched = false,
            clearsLastPlayed = true,
        )
        assertTrue(range.covers("UUx", premiere.videoPublishedAt))
    }

    @Test
    fun `a Source with no dated video can write nothing`() {
        assertNull(everythingUnwatched(listOf(testVideo("gone", day = null))))
        assertNull(everythingUnwatched(emptyList()))
    }

    /** A Source of nothing but unlisted videos has no pivot, so the line is dim. */
    @Test
    fun `a Source listing nothing can write nothing`() {
        assertNull(
            everythingUnwatched(
                listOf(testVideo("soon", day = 9, liveBroadcastContent = LiveBroadcastContent.UPCOMING)),
            ),
        )
    }
}
