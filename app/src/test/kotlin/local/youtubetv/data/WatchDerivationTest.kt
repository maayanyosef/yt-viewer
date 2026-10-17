package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val NOON: Instant = Instant.parse("2026-09-20T12:00:00Z")

private fun at(secondsAfterNoon: Long): Instant = NOON.plusSeconds(secondsAfterNoon)

private fun played(
    id: String,
    videoId: String,
    at: Instant,
    seq: Long,
    deviceId: String = "device-a",
    positionSeconds: Int? = null,
    completed: Boolean = true,
    sourceId: String = "UUx",
) = WatchEntry(
    id = id,
    deviceId = deviceId,
    seq = seq,
    at = at,
    kind = WatchKind.PLAYED,
    payload = playedPayload(videoId, sourceId, positionSeconds, completed),
)

private fun unwatched(
    id: String,
    videoId: String,
    at: Instant,
    seq: Long,
    deviceId: String = "device-a",
    sourceId: String = "UUx",
) = WatchEntry(
    id = id,
    deviceId = deviceId,
    seq = seq,
    at = at,
    kind = WatchKind.UNWATCHED,
    payload = unwatchedPayload(videoId, sourceId),
)

/**
 * The order the log is read in.
 *
 * ADR 0001: within one device order by `seq`, across devices order by `at`. The reason is that
 * an Android TV boots with a wrong clock and corrects it over the network seconds later, so
 * one device's own wall-clock times can go backwards.
 */
class WatchOrderTest {

    @Test
    fun `one device is ordered by seq, even when its clock jumped backwards`() {
        // What a television that booted at the wrong time and then corrected itself writes.
        val first = played("a", "v1", at = at(10_000), seq = 1)
        val second = played("b", "v2", at = at(0), seq = 2)
        val third = played("c", "v3", at = at(20), seq = 3)

        val order = inLogOrder(listOf(third, first, second)).map { it.id }

        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun `two devices are ordered by wall-clock time`() {
        val phone = played("phone", "v1", at = at(10), seq = 1, deviceId = "device-b")
        val television = played("tv", "v2", at = at(20), seq = 1, deviceId = "device-a")

        assertEquals(listOf("phone", "tv"), inLogOrder(listOf(television, phone)).map { it.id })
    }

    @Test
    fun `an empty log has an empty order`() {
        assertTrue(inLogOrder(emptyList()).isEmpty())
    }

    @Test
    fun `the order is total, so the same log always reads the same way`() {
        val one = played("one", "v1", at = at(5), seq = 7, deviceId = "device-a")
        val two = played("two", "v2", at = at(5), seq = 7, deviceId = "device-b")

        assertEquals(
            inLogOrder(listOf(one, two)).map { it.id },
            inLogOrder(listOf(two, one)).map { it.id },
        )
    }
}

/**
 * Turning the log into one row per video.
 *
 * This is the whole of ADR 0001's "Deriving watched state" that ticket 04 needs: the newest
 * entry naming a video wins. `range` entries, which cover many videos by date, arrive in
 * ticket 05 and are ignored here exactly as any other unrecognised kind is.
 */
class WatchDerivationTest {

    @Test
    fun `a video with no entry has no derived row`() {
        assertTrue(deriveWatchStates(emptyList()).isEmpty())
    }

    @Test
    fun `a played entry makes a video watched`() {
        val states = deriveWatchStates(listOf(played("a", "v1", at = at(0), seq = 1)))

        val state = states.getValue("v1")
        assertTrue(state.watched)
        assertEquals(at(0), state.lastPlayedAt)
    }

    @Test
    fun `an unwatched entry after a played one leaves the video not watched`() {
        val states = deriveWatchStates(
            listOf(
                played("a", "v1", at = at(0), seq = 1),
                unwatched("b", "v1", at = at(10), seq = 2),
            ),
        )

        assertFalse(states.getValue("v1").watched)
    }

    @Test
    fun `watched then unwatched then watched again leaves the video watched`() {
        val states = deriveWatchStates(
            listOf(
                played("a", "v1", at = at(0), seq = 1),
                unwatched("b", "v1", at = at(10), seq = 2),
                played("c", "v1", at = at(20), seq = 3),
            ),
        )

        assertTrue(states.getValue("v1").watched)
    }

    @Test
    fun `the order of the entries handed in does not change the answer`() {
        val entries = listOf(
            played("c", "v1", at = at(20), seq = 3),
            unwatched("b", "v1", at = at(10), seq = 2),
            played("a", "v1", at = at(0), seq = 1),
        )

        assertTrue(deriveWatchStates(entries).getValue("v1").watched)
    }

    @Test
    fun `a play keeps its position and whether it completed`() {
        val states = deriveWatchStates(
            listOf(played("a", "v1", at = at(0), seq = 1, positionSeconds = 742, completed = false)),
        )

        val state = states.getValue("v1")
        assertEquals(742, state.positionSeconds)
        assertFalse(state.completed)
        assertTrue(state.watched)
    }

    @Test
    fun `unwatching clears the position, because there is no progress to resume`() {
        val states = deriveWatchStates(
            listOf(
                played("a", "v1", at = at(0), seq = 1, positionSeconds = 742, completed = false),
                unwatched("b", "v1", at = at(10), seq = 2),
            ),
        )

        assertNull(states.getValue("v1").positionSeconds)
        assertFalse(states.getValue("v1").completed)
    }

    @Test
    fun `unwatching keeps the newest play's time, which is what Last played reads`() {
        val states = deriveWatchStates(
            listOf(
                played("a", "v1", at = at(0), seq = 1),
                unwatched("b", "v1", at = at(10), seq = 2),
            ),
        )

        assertEquals(at(0), states.getValue("v1").lastPlayedAt)
    }

    @Test
    fun `an entry of an unknown kind changes nothing`() {
        val invented = WatchEntry(
            id = "x",
            deviceId = "device-a",
            seq = 2,
            at = at(10),
            kind = "inventedByANewerBuild",
            payload = """{"videoId":"v1","watched":false}""",
        )

        val states = deriveWatchStates(listOf(played("a", "v1", at = at(0), seq = 1), invented))

        assertTrue(states.getValue("v1").watched)
    }

    @Test
    fun `each video gets its own row`() {
        val states = deriveWatchStates(
            listOf(
                played("a", "v1", at = at(0), seq = 1),
                unwatched("b", "v2", at = at(10), seq = 2),
            ),
        )

        assertEquals(setOf("v1", "v2"), states.keys)
        assertTrue(states.getValue("v1").watched)
        assertFalse(states.getValue("v2").watched)
    }
}

private fun rangeEntry(
    id: String,
    at: Instant,
    seq: Long,
    deviceId: String = "device-a",
    sourceId: String = "UUx",
    pivotDay: Int = 3,
    side: RangeSide = RangeSide.OLDER,
    watched: Boolean = true,
    clearsLastPlayed: Boolean = false,
) = WatchEntry(
    id = id,
    deviceId = deviceId,
    seq = seq,
    at = at,
    kind = WatchKind.RANGE,
    payload = rangePayload(sourceId, testDay(pivotDay), side, watched, clearsLastPlayed),
)

/**
 * Last played: the video the household most recently actually played in one Source.
 *
 * Ticket 06 settled two questions the earlier tickets left open, and both are here. A `played`
 * entry with no position is a manual `Mark as watched` and never moves the chip. A `range`
 * with `clearsLastPlayed = true` takes Last played away for the whole Source.
 */
class LastPlayedTest {

    @Test
    fun `last played is the newest played entry that carries a position`() {
        val first = played("a", "v1", at = at(10), seq = 1, positionSeconds = 30)
        val second = played("b", "v2", at = at(20), seq = 2, positionSeconds = 90)

        assertEquals("v2", lastPlayedVideoId(listOf(first, second), "UUx"))
    }

    @Test
    fun `last played ignores a played entry with no position`() {
        // A real play, then a manual `Mark as watched` on another video. `markJustThis`
        // writes no position, and CONTEXT.md says Last played is what was actually played.
        val play = played("a", "v1", at = at(10), seq = 1, positionSeconds = 30)
        val markedByHand = played("b", "v2", at = at(20), seq = 2, positionSeconds = null)

        assertEquals("v1", lastPlayedVideoId(listOf(play, markedByHand), "UUx"))
    }

    @Test
    fun `a Source that was only ever marked by hand has no last played`() {
        val marks = (1..3).map { n ->
            played("m$n", "v$n", at = at(n * 10L), seq = n.toLong(), positionSeconds = null)
        }

        assertNull(lastPlayedVideoId(marks, "UUx"))
    }

    @Test
    fun `last played is per Source`() {
        val here = played("a", "v1", at = at(10), seq = 1, positionSeconds = 30, sourceId = "UUx")
        val elsewhere =
            played("b", "v9", at = at(20), seq = 2, positionSeconds = 30, sourceId = "UUy")

        val entries = listOf(here, elsewhere)
        assertEquals("v1", lastPlayedVideoId(entries, "UUx"))
        assertEquals("v9", lastPlayedVideoId(entries, "UUy"))
    }

    @Test
    fun `an ordinary range never moves last played`() {
        val play = played("a", "v1", at = at(10), seq = 1, positionSeconds = 30)
        // A Bulk mark covering every video in the Source, including the one just played.
        val bulk = rangeEntry("b", at = at(20), seq = 2, pivotDay = 9, side = RangeSide.OLDER)

        assertEquals("v1", lastPlayedVideoId(listOf(play, bulk), "UUx"))
    }

    @Test
    fun `a range that clears last played leaves the Source with none`() {
        val play = played("a", "v1", at = at(10), seq = 1, positionSeconds = 30)
        val clearing = rangeEntry(
            "b",
            at = at(20),
            seq = 2,
            watched = false,
            clearsLastPlayed = true,
        )

        assertNull(lastPlayedVideoId(listOf(play, clearing), "UUx"))
    }

    @Test
    fun `a clearing range in another Source leaves this one alone`() {
        val play = played("a", "v1", at = at(10), seq = 1, positionSeconds = 30, sourceId = "UUx")
        val clearing = rangeEntry(
            "b",
            at = at(20),
            seq = 2,
            sourceId = "UUy",
            watched = false,
            clearsLastPlayed = true,
        )

        assertEquals("v1", lastPlayedVideoId(listOf(play, clearing), "UUx"))
    }

    @Test
    fun `a clearing range does not reach a play written after it`() {
        val play = played("a", "v1", at = at(10), seq = 1, positionSeconds = 30)
        val clearing = rangeEntry("b", at = at(20), seq = 2, clearsLastPlayed = true)
        val later = played("c", "v4", at = at(30), seq = 3, positionSeconds = 12)

        assertEquals("v4", lastPlayedVideoId(listOf(play, clearing, later), "UUx"))
    }

    @Test
    fun `a clearing range clears the whole Source, whatever its pivot covers`() {
        // Day 5 was played. The clearing range pivots on day 3 and covers only what is older,
        // so it covers nothing that was played. ADR 0001 still clears the Source.
        val play = played("a", "v5", at = at(10), seq = 1, positionSeconds = 30)
        val clearing = rangeEntry(
            "b",
            at = at(20),
            seq = 2,
            pivotDay = 3,
            side = RangeSide.OLDER,
            watched = false,
            clearsLastPlayed = true,
        )

        assertNull(lastPlayedVideoId(listOf(play, clearing), "UUx"))
    }

    @Test
    fun `marking the last played video unwatched does not move last played`() {
        // The play still happened. Only `Mark everything unwatched` takes Last played away.
        val play = played("a", "v1", at = at(10), seq = 1, positionSeconds = 30)
        val undone = unwatched("b", "v1", at = at(20), seq = 2)

        assertEquals("v1", lastPlayedVideoId(listOf(play, undone), "UUx"))
    }

    @Test
    fun `last played follows the log order and not the wall clock`() {
        // One television that booted with a wrong clock. `seq` says v2 was played last, even
        // though its `at` is earlier. See `inLogOrder`.
        val first = played("a", "v1", at = at(10_000), seq = 1, positionSeconds = 30)
        val second = played("b", "v2", at = at(10), seq = 2, positionSeconds = 30)

        assertEquals("v2", lastPlayedVideoId(listOf(first, second), "UUx"))
    }

    @Test
    fun `an empty log has no last played`() {
        assertNull(lastPlayedVideoId(emptyList(), "UUx"))
    }
}
