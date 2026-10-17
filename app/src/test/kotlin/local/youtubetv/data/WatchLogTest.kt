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
 * The Watch log's own rules, checked against [InMemoryWatchStore].
 *
 * `SqliteWatchStore` puts the same rows in SQLite and is checked on the television, because a
 * unit test cannot open an Android database. Everything that decides an answer lives here, in
 * [WatchLog] and in `WatchDerivation.kt`, so the SQL holds no rule of its own.
 */
class WatchLogTest {

    private val store = InMemoryWatchStore()
    private var clock = NOON
    private val log = WatchLog(store, now = { clock })

    private val video = testVideo("v1", day = 5, title = "A video with a name")

    private fun tick(seconds: Long) {
        clock = clock.plusSeconds(seconds)
    }

    /** Every video the log says the household has watched, across every Source. */
    private fun watched(): Set<String> =
        log.watchStates(listOf(video)).values.filter { it.watched }
            .mapTo(mutableSetOf()) { it.videoId }

    @Test
    fun `marking a video watched writes one entry and makes it watched`() {
        log.markJustThis(video, watched = true)

        assertEquals(1, store.entries().size)
        assertEquals(setOf("v1"), watched())
        assertTrue(log.state("v1")!!.watched)
    }

    @Test
    fun `a manual mark stamps the current time`() {
        tick(600)

        log.markJustThis(video, watched = true)

        assertEquals(NOON.plusSeconds(600), store.entries().single().at)
    }

    @Test
    fun `marking a video unwatched removes it from the watched set`() {
        log.markJustThis(video, watched = true)
        tick(1)

        log.markJustThis(video, watched = false)

        assertTrue(watched().isEmpty())
        assertFalse(log.state("v1")!!.watched)
    }

    @Test
    fun `watched then unwatched then watched again leaves it watched, and three entries`() {
        log.markJustThis(video, watched = true)
        tick(1)
        log.markJustThis(video, watched = false)
        tick(1)
        log.markJustThis(video, watched = true)

        assertEquals(3, store.entries().size)
        assertEquals(setOf("v1"), watched())
    }

    @Test
    fun `every entry carries an id, a device, a rising seq, a time and a kind`() {
        log.markJustThis(video, watched = true)
        tick(1)
        log.markJustThis(video, watched = false)

        val entries = inLogOrder(store.entries())
        assertEquals(listOf(WatchKind.PLAYED, WatchKind.UNWATCHED), entries.map { it.kind })
        assertEquals(listOf(1L, 2L), entries.map { it.seq })
        assertEquals(2, entries.map { it.id }.toSet().size)
        assertEquals(1, entries.map { it.deviceId }.toSet().size)
        assertTrue(entries.first().deviceId.isNotEmpty())
    }

    @Test
    fun `a manual mark as watched writes a played entry with no position`() {
        log.markJustThis(video, watched = true)

        val played = store.entries().single().played()
        assertEquals(Played("v1", "UUx", positionSeconds = null, completed = true), played)
    }

    @Test
    fun `a mark copies the video's title and publish date somewhere durable`() {
        log.markJustThis(video, watched = true)

        assertEquals(KeptVideo("v1", "A video with a name", testDay(5)), log.keptVideo("v1"))
    }

    @Test
    fun `the copy is made once, on the first Watch, and later marks leave it alone`() {
        log.markJustThis(video, watched = true)
        tick(1)

        log.markJustThis(video.copy(title = "A title the walk fetched later"), watched = false)

        assertEquals("A video with a name", log.keptVideo("v1")!!.title)
    }

    @Test
    fun `a video nobody marked was never copied`() {
        assertNull(log.keptVideo("v1"))
    }

    @Test
    fun `a video with no id is not marked, and writes no entry`() {
        // It cannot come from a walk, and the log is never cleaned up, so an entry naming
        // nothing would sit in the household's history forever.
        assertNull(log.markJustThis(testVideo("", day = 1), watched = true))
        assertTrue(store.entries().isEmpty())
    }

    // --- The derived table -------------------------------------------------------------

    @Test
    fun `the first launch derives, because nothing has been derived before`() {
        val outcome = log.openAndDerive()

        assertTrue(outcome is Derivation.Rebuilt)
        assertEquals(DERIVATION_VERSION, store.storedDerivationVersion())
    }

    @Test
    fun `a second launch derives nothing, because the version already matches`() {
        log.openAndDerive()

        assertEquals(Derivation.UpToDate, log.openAndDerive())
    }

    @Test
    fun `a derivationVersion that does not match the code triggers a rebuild`() {
        log.openAndDerive()
        store.setDerivationVersion(DERIVATION_VERSION - 1)

        val outcome = log.openAndDerive()

        assertTrue(outcome is Derivation.Rebuilt)
        assertEquals(DERIVATION_VERSION, store.storedDerivationVersion())
    }

    @Test
    fun `a derived table that is gone triggers a rebuild`() {
        log.openAndDerive()
        log.markJustThis(video, watched = true)

        store.dropDerivedTable()

        val outcome = log.openAndDerive()
        assertTrue(outcome is Derivation.Rebuilt)
        assertEquals(setOf("v1"), watched())
    }

    @Test
    fun `a rebuild from the log gives the same answer the marks gave`() {
        log.openAndDerive()
        log.markJustThis(video, watched = true)
        tick(1)
        log.markJustThis(testVideo("v2", day = 6), watched = true)
        tick(1)
        log.markJustThis(video, watched = false)
        val before = watched()

        store.dropDerivedTable()
        log.rebuild("asked for by a test")

        assertEquals(before, watched())
        assertEquals(setOf("v2"), watched())
    }

    @Test
    fun `an entry of an unknown kind is still in the log after a rebuild`() {
        log.openAndDerive()
        log.markJustThis(video, watched = true)
        val payload = """{"videoId":"v9","somethingNewer":42}"""
        store.append(kind = "inventedByANewerBuild", payload = payload, at = NOON.plusSeconds(5))

        log.rebuild("asked for by a test")

        val kept = store.entries().single { it.kind == "inventedByANewerBuild" }
        assertEquals(payload, kept.payload)
        assertNotNull(kept.id)
        // And it changed nothing, because derivation has no rule for it.
        assertEquals(setOf("v1"), watched())
    }

    @Test
    fun `a rebuild says how much it read and how much it wrote`() {
        log.markJustThis(video, watched = true)
        tick(1)
        log.markJustThis(testVideo("v2", day = 6), watched = true)

        val outcome = log.rebuild("asked for by a test") as Derivation.Rebuilt

        assertEquals(2, outcome.entries)
        assertEquals(2, outcome.rows)
        assertEquals("asked for by a test", outcome.reason)
    }
}

