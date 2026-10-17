package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOON: Instant = Instant.parse("2026-09-20T12:00:00Z")

/**
 * A Bulk mark range: one log entry that decides for every video it covers.
 *
 * Every rule here is ADR 0001's "Bulk mark ranges" and spec section 3.2. They are checked
 * against [InMemoryWatchStore], because everything that decides an answer is in [WatchLog] and
 * in `WatchDerivation.kt` and none of it is in the SQL.
 */
class WatchRangeTest {

    private val store = InMemoryWatchStore()
    private var clock = NOON
    private val log = WatchLog(store, now = { clock })

    /** Day 1 to day 5 of one Source, oldest first. */
    private val videos = (1..5).map { day -> testVideo("v$day", day = day) }

    private fun tick(seconds: Long) {
        clock = clock.plusSeconds(seconds)
    }

    private fun watched(): Set<String> =
        log.watchStates(videos).values.filter { it.watched }.mapTo(mutableSetOf()) { it.videoId }

    @Test
    fun `a payload survives being written and read back`() {
        val range = Range(
            sourceId = "UUx",
            pivotPublishedAt = testDay(3),
            // The bound comes from the entry's own `at`, not from the payload, so the entry
            // below carries `at = NOON` and the range read back carries `madeAt = NOON`.
            madeAt = NOON,
            side = RangeSide.NEWER,
            watched = false,
            clearsLastPlayed = true,
        )
        val entry = WatchEntry(
            id = "a",
            deviceId = "device-a",
            seq = 1,
            at = NOON,
            kind = WatchKind.RANGE,
            payload = rangePayload(
                sourceId = range.sourceId,
                pivotPublishedAt = range.pivotPublishedAt,
                side = range.side,
                watched = range.watched,
                clearsLastPlayed = range.clearsLastPlayed,
            ),
        )

        assertEquals(range, entry.range())
    }

    @Test
    fun `a range names no video, so it fills no video_id column`() {
        log.markRange(videos[2], side = RangeSide.OLDER, watched = true)

        assertNull(store.entries().single().namedVideoId())
    }

    @Test
    fun `a side this build does not recognise reads as nothing rather than as a guess`() {
        val entry = WatchEntry(
            id = "a",
            deviceId = "device-a",
            seq = 1,
            at = NOON,
            kind = WatchKind.RANGE,
            payload = """{"sourceId":"UUx","pivotPublishedAt":"2026-01-04T00:00:00Z",""" +
                """"side":"sideways","watched":true,"clearsLastPlayed":false}""",
        )

        assertNull(entry.range())
    }

    @Test
    fun `marking this and all older covers the pivot and everything older, and nothing newer`() {
        log.markRange(videos[2], side = RangeSide.OLDER, watched = true)

        assertEquals(setOf("v1", "v2", "v3"), watched())
    }

    @Test
    fun `marking this and all newer covers the pivot and everything newer, and nothing older`() {
        log.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        assertEquals(setOf("v3", "v4", "v5"), watched())
    }

    @Test
    fun `hundreds of videos are covered by exactly one log entry`() {
        val many = (1..2_000).map { day -> testVideo("v$day", day = day) }

        log.markRange(many.last(), side = RangeSide.OLDER, watched = true)

        assertEquals(1, store.entries().size)
        assertEquals(2_000, log.watchStates(many).values.count { it.watched })
    }

    @Test
    fun `a range never reaches another Source`() {
        val elsewhere = testVideo("other", day = 1, sourceId = "UUy")

        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)

        // No entry says anything about it at all, so the log has no row for it and the card
        // draws no tick.
        assertNull(log.watchStates(videos + elsewhere)["other"])
    }

    @Test
    fun `a video with no publish date is covered by nothing`() {
        val undated = testVideo("gone", day = null)

        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)

        assertNull(log.watchStates(videos + undated)["gone"])
    }

    @Test
    fun `a video with no publish date cannot be a pivot, and nothing is written`() {
        val undated = testVideo("gone", day = null)

        assertNull(log.markRange(undated, side = RangeSide.OLDER, watched = true))
        assertEquals(0, store.entries().size)
    }

    @Test
    fun `a later unwatched on one video beats an earlier range that covered it`() {
        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)
        tick(60)
        log.markJustThis(videos[1], watched = false)

        assertEquals(setOf("v1", "v3", "v4", "v5"), watched())
    }

    @Test
    fun `a later range beats an earlier unwatched on a video it covers`() {
        log.markJustThis(videos[1], watched = false)
        tick(60)
        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)

        assertEquals(setOf("v1", "v2", "v3", "v4", "v5"), watched())
    }

    @Test
    fun `an unwatched range beats an earlier played entry it covers`() {
        log.markJustThis(videos[1], watched = true)
        tick(60)
        log.markRange(videos[4], side = RangeSide.OLDER, watched = false)

        assertEquals(emptySet<String>(), watched())
    }

    @Test
    fun `the newest of two overlapping ranges wins`() {
        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)
        tick(60)
        log.markRange(videos[2], side = RangeSide.NEWER, watched = false)

        assertEquals(setOf("v1", "v2"), watched())
    }

    @Test
    fun `a video found by a later walk falls inside an existing range with no second Bulk mark`() {
        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)
        val entriesAfterTheMark = store.entries().size

        // An upload made public months late: a video nobody had listed before, with a publish
        // date that sits inside a range the household already declared watched.
        val foundLater = testVideo("late", day = 2)

        assertTrue(log.watchStates(videos + foundLater).getValue("late").watched)
        assertEquals(entriesAfterTheMark, store.entries().size)
    }

    @Test
    fun `a video with a played entry keeps that entry's date under a newer watched range`() {
        log.markJustThis(videos[1], watched = true)
        val playedAt = clock
        tick(86_400)
        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)

        val state = log.watchStates(videos).getValue("v2")
        assertTrue(state.watched)
        assertEquals(playedAt, state.lastPlayedAt)
    }

    @Test
    fun `a video a range makes watched but that was never played has no date to show`() {
        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)

        val state = log.watchStates(videos).getValue("v1")
        assertTrue(state.watched)
        assertNull(state.lastPlayedAt)
    }

    @Test
    fun `a watched range drops the position of a play it covers`() {
        store.append(
            kind = WatchKind.PLAYED,
            payload = playedPayload("v2", "UUx", positionSeconds = 90, completed = false),
            at = clock,
        )
        tick(60)
        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)

        val state = log.watchStates(videos).getValue("v2")
        assertTrue(state.watched)
        assertNull(state.positionSeconds)
    }

    @Test
    fun `clearsLastPlayed is false unless the caller asks for it`() {
        val entry = log.markRange(videos[2], side = RangeSide.OLDER, watched = true)

        assertNotNull(entry)
        assertFalse(entry!!.range()!!.clearsLastPlayed)
    }

    @Test
    fun `a range covers a hidden Short by date, exactly as it covers anything else`() {
        val short = testVideo("short", day = 2, isShort = true)

        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)

        assertTrue(log.watchStates(videos + short).getValue("short").watched)
    }

    @Test
    fun `a video published at the very instant of the pivot is covered by both sides`() {
        val twin = testVideo("twin", day = 3)

        log.markRange(videos[2], side = RangeSide.OLDER, watched = true)
        assertTrue(log.watchStates(listOf(twin)).getValue("twin").watched)

        tick(60)
        log.markRange(videos[2], side = RangeSide.NEWER, watched = false)
        assertFalse(log.watchStates(listOf(twin)).getValue("twin").watched)
    }

    @Test
    fun `the derived table still holds only the entries that name a video`() {
        log.markJustThis(videos[0], watched = true)
        log.markRange(videos[4], side = RangeSide.OLDER, watched = true)

        // The range wrote no derived row, which is what keeps a Bulk mark one write however
        // many videos it covers.
        assertEquals(setOf("v1"), store.watchStates().keys)
    }
}
