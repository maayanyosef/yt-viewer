package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What one entry of the Watch log carries, and what this build can read back out of it.
 *
 * The kind-specific fields live in a JSON payload rather than in columns of their own. That is
 * what makes ADR 0001's rule cheap to keep: an entry of a kind this build has never heard of
 * keeps every field it was written with, because nothing here has to understand it.
 */
class WatchEntryTest {

    private fun entry(kind: String, payload: String) = WatchEntry(
        id = "e1",
        deviceId = "device-a",
        seq = 1,
        at = Instant.parse("2026-09-20T10:00:00Z"),
        kind = kind,
        payload = payload,
    )

    @Test
    fun `a played entry carries its video, its Source, its position and whether it completed`() {
        val read = entry(WatchKind.PLAYED, playedPayload("vid", "UUx", 125, completed = false)).played()

        assertEquals(Played("vid", "UUx", 125, completed = false), read)
    }

    @Test
    fun `a manual mark writes a played entry with no position`() {
        val read = entry(WatchKind.PLAYED, playedPayload("vid", "UUx", null, completed = true)).played()

        assertEquals(Played("vid", "UUx", null, completed = true), read)
    }

    @Test
    fun `an unwatched entry carries its video and its Source`() {
        val read = entry(WatchKind.UNWATCHED, unwatchedPayload("vid", "UUx")).unwatched()

        assertEquals(Unwatched("vid", "UUx"), read)
    }

    @Test
    fun `a played entry is not read as an unwatched one, or the other way round`() {
        assertNull(entry(WatchKind.PLAYED, playedPayload("vid", "UUx", null, true)).unwatched())
        assertNull(entry(WatchKind.UNWATCHED, unwatchedPayload("vid", "UUx")).played())
    }

    @Test
    fun `an entry of an unknown kind reads as neither, and keeps its payload untouched`() {
        val payload = """{"sourceId":"UUx","somethingNewer":42}"""
        val unknown = entry("inventedByANewerBuild", payload)

        assertNull(unknown.played())
        assertNull(unknown.unwatched())
        assertNull(unknown.namedVideoId())
        assertEquals(payload, unknown.payload)
    }

    @Test
    fun `the video an entry names is the one its payload holds`() {
        assertEquals("vid", entry(WatchKind.PLAYED, playedPayload("vid", "UUx", null, true)).namedVideoId())
        assertEquals("vid", entry(WatchKind.UNWATCHED, unwatchedPayload("vid", "UUx")).namedVideoId())
    }

    @Test
    fun `a payload this build cannot parse is not a crash`() {
        val broken = entry(WatchKind.PLAYED, "not json at all")

        assertNull(broken.played())
        assertNull(broken.namedVideoId())
    }
}
