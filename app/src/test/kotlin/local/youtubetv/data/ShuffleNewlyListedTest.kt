package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Shuffle play never ends the Newly listed state. An ordinary play still does.
 *
 * Build ticket 72, "A Shuffle play never ends Newly listed", which reverses the first reading
 * build ticket 69 took. ADR 0002 says why: Shuffle is a detour that never costs the household
 * its place, and a new video it never saw is part of that place.
 *
 * The Source is newest-first, because that is the only Direction in which the Newly listed
 * exception moves anything. `V` is Newly listed at the top, `d20` to `d1` are below it, and
 * the household has worked down from the top as far as `d18`. So the High-water mark is
 * `d18`, and the exception offers `V` as Next unwatched.
 */
class ShuffleNewlyListedTest {

    private var clock: Instant = Instant.parse("2026-10-17T12:00:00Z")
    private val sources = InMemorySourceStore()
    private val store = InMemoryWatchStore()
    private val log = WatchLog(store, sources, now = { clock.also { clock = clock.plusSeconds(1) } })

    private val v = testVideo("V", day = 30, newlyListed = true)
    private val older = (20 downTo 1).map { testVideo("d$it", day = it) }

    private fun open(listed: List<StoredVideo> = listOf(v) + older) {
        sources.saveSource(
            StoredSource(
                sourceId = "UUx",
                title = "A Source",
                lastWalkedAt = Instant.parse("2026-10-17T10:00:00Z"),
                direction = Direction.NEWEST_FIRST,
            ),
        )
        sources.replaceVideos("UUx", listed)
    }

    /** The videos as storage holds them now, in the Source's Direction. */
    private fun videos(): List<StoredVideo> =
        orderByPublishDate(sources.allVideos("UUx"), Direction.NEWEST_FIRST)

    private fun video(id: String): StoredVideo = videos().single { it.videoId == id }

    private fun nextUnwatched(): String? {
        val videos = videos()
        return nextUnwatchedIndex(videos, log.watchStates(videos), Direction.NEWEST_FIRST)
            ?.let { videos[it].videoId }
    }

    private fun play(id: String, shuffle: Boolean) {
        log.startPlay(video(id), positionSeconds = 30, completed = true, shuffle = shuffle)
    }

    /** The household has worked down from the top as far as `d18`, by ordinary plays. */
    private fun workedDownToD18() {
        // These three plays happened before the walk that listed V.
        open(listed = older)
        for (id in listOf("d20", "d19", "d18")) play(id, shuffle = false)
        sources.replaceVideos("UUx", listOf(v) + older)
        assertTrue(video("V").newlyListed)
        assertEquals("V", nextUnwatched())
    }

    @Test
    fun `a Shuffle play below a Newly listed video leaves it offered as Next unwatched`() {
        workedDownToD18()

        play("d5", shuffle = true)

        assertTrue("a Shuffle play ended the flag", video("V").newlyListed)
        assertEquals("V", nextUnwatched())
        // The Shuffle play is a Watch all the same.
        assertTrue(log.watchStates(videos()).getValue("d5").watched)
    }

    @Test
    fun `many Shuffle plays below it leave it offered all the same`() {
        workedDownToD18()

        for (id in listOf("d1", "d9", "d17", "d14")) play(id, shuffle = true)

        assertTrue(video("V").newlyListed)
        assertEquals("V", nextUnwatched())
    }

    @Test
    fun `an ordinary play below a Newly listed video still ends it, as before`() {
        workedDownToD18()

        play("d5", shuffle = false)

        assertFalse("an ordinary play no longer ends the flag", video("V").newlyListed)
        // The ordinary play moved the High-water mark to d5, and V is an ordinary skipped video.
        assertEquals("d4", nextUnwatched())
    }

    @Test
    fun `an ordinary play just past the mark ends it, and Next unwatched moves on`() {
        workedDownToD18()

        play("d17", shuffle = false)

        assertFalse(video("V").newlyListed)
        assertEquals("d16", nextUnwatched())
    }

    /**
     * Build ticket 72, "A Shuffle play never ends Newly listed", asked whether V's Newly listed state ends here or simply stops mattering.
     * It stops mattering. The flag stays set, because a Shuffle play never writes it. V carries
     * a Watch, so it is not a gap and Next unwatched does not offer it.
     */
    @Test
    fun `a Shuffle play of the Newly listed video itself makes it watched and no longer offered`() {
        workedDownToD18()

        play("V", shuffle = true)

        assertTrue(log.watchStates(videos()).getValue("V").watched)
        assertEquals("d17", nextUnwatched())
        assertTrue("the flag stays set and simply stops mattering", video("V").newlyListed)
        // The High-water mark did not move to V.
        assertFalse(log.watchStates(videos()).getValue("V").countsForHighWaterMark)
    }

    /**
     * The consequence of "stops mattering", written down so that nobody meets it by surprise.
     * `Mark as unwatched` takes the Watch away, and V is a Newly listed gap again. That is the
     * same answer as if Shuffle had never played it.
     */
    @Test
    fun `Mark as unwatched after a Shuffle play of it offers it again`() {
        workedDownToD18()
        play("V", shuffle = true)

        log.markJustThis(video("V"), watched = false)

        assertEquals("V", nextUnwatched())
    }

    @Test
    fun `an ordinary play of the Newly listed video itself ends it, as before`() {
        workedDownToD18()

        play("V", shuffle = false)

        assertFalse(video("V").newlyListed)
        // V can be a mark now, but the High-water mark is the highest index that can, and that
        // is still d18. V is watched, so it is not a gap, and the first gap after d18 is d17.
        assertEquals("d17", nextUnwatched())
        assertTrue(log.watchStates(videos()).getValue("V").countsForHighWaterMark)
    }

    /**
     * A Watch log written before build ticket 69 carries no `shuffle` field at all. Every
     * entry reads as an ordinary play, so it gives the same Next unwatched it always gave.
     */
    @Test
    fun `a Watch log from before build ticket 69 replays to the same Next unwatched`() {
        open()
        // Written by hand, exactly as a build before ticket 69 wrote them.
        for ((index, id) in listOf("d20", "d19", "d18").withIndex()) {
            store.append(
                kind = WatchKind.PLAYED,
                payload = """{"videoId":"$id","sourceId":"UUx","position":40,"completed":true}""",
                at = Instant.parse("2026-09-20T12:00:00Z").plusSeconds(index.toLong()),
            )
        }
        assertTrue(store.entries().none { it.played()!!.shuffle })

        assertEquals("V", nextUnwatched())

        log.rebuild("test")
        assertEquals("V", nextUnwatched())
        val states = log.watchStates(videos())
        assertTrue(listOf("d20", "d19", "d18").all { states.getValue(it).countsForHighWaterMark })
    }

    /**
     * The same old log, carried on by this build. The household now mixes Shuffle plays with
     * ordinary ones. The Shuffle plays change nothing, and the first ordinary play past V ends
     * its Newly listed state exactly as it did before build ticket 69. Its entry is written in
     * the old format, with no `shuffle` field.
     */
    @Test
    fun `an old log carried on with mixed plays still ends Newly listed only on an ordinary play`() {
        open()
        for ((index, id) in listOf("d20", "d19", "d18").withIndex()) {
            store.append(
                kind = WatchKind.PLAYED,
                payload = """{"videoId":"$id","sourceId":"UUx","position":40,"completed":true}""",
                at = Instant.parse("2026-09-20T12:00:00Z").plusSeconds(index.toLong()),
            )
        }

        play("d12", shuffle = true)
        play("d3", shuffle = true)
        assertTrue(video("V").newlyListed)
        assertEquals("V", nextUnwatched())

        play("d10", shuffle = false)
        assertFalse(video("V").newlyListed)
        assertEquals("d9", nextUnwatched())
        assertFalse(store.entries().last().payload.contains("shuffle"))

        log.rebuild("test")
        assertEquals("d9", nextUnwatched())
    }
}
