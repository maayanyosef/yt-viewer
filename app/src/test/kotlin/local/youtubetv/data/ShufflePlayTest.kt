package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Shuffle play leaves a Watch and moves Last played, but never moves the High-water mark.
 * Build ticket 69, rule 5, and ADR 0002.
 */
class ShufflePlayTest {

    private var clock: Instant = Instant.parse("2026-10-17T12:00:00Z")
    private val store = InMemoryWatchStore()
    private val log = WatchLog(store, now = { clock.also { clock = clock.plusSeconds(1) } })

    /** An oldest-first Source of [count] videos, `v1` the oldest and first in Direction. */
    private fun oldestFirst(count: Int): List<StoredVideo> =
        orderByPublishDate((1..count).map { testVideo("v$it", day = it) }, Direction.OLDEST_FIRST)

    private fun nextUnwatched(videos: List<StoredVideo>): String? =
        nextUnwatchedIndex(videos, log.watchStates(videos), Direction.OLDEST_FIRST)
            ?.let { videos[it].videoId }

    private fun play(video: StoredVideo, shuffle: Boolean) {
        log.startPlay(video, positionSeconds = 30, completed = true, shuffle = shuffle)
    }

    @Test
    fun `watched up to video 50, Shuffle plays video 400, and Next unwatched is still 51`() {
        val videos = oldestFirst(500)
        for (index in 0 until 50) play(videos[index], shuffle = false)
        assertEquals("v51", nextUnwatched(videos))

        play(videos[399], shuffle = true)

        assertEquals("v51", nextUnwatched(videos))
        // It is a Watch all the same: its card is watched and it is Last played.
        assertTrue(log.watchStates(videos).getValue("v400").watched)
        assertEquals("v400", log.lastPlayed("UUx"))
        val landing = nextUnwatchedLanding(videos, log.watchStates(videos), Direction.OLDEST_FIRST) {
            it.canBeLandedOn
        }
        assertEquals(49, landing.highWaterMark)
        // Video 400 is not a gap, so the count is one less than before.
        assertEquals(449, landing.count)
    }

    @Test
    fun `a card pressed by hand while Shuffle is on moves the High-water mark`() {
        val videos = oldestFirst(500)
        for (index in 0 until 50) play(videos[index], shuffle = false)

        play(videos[399], shuffle = false)

        assertEquals("v401", nextUnwatched(videos))
    }

    @Test
    fun `a video played by hand and later by Shuffle is still the mark`() {
        val videos = oldestFirst(10)
        play(videos[0], shuffle = false)
        play(videos[5], shuffle = false)
        play(videos[5], shuffle = true)

        assertEquals("v7", nextUnwatched(videos))
    }

    @Test
    fun `an ordinary play undone by Mark as unwatched stays undone when Shuffle plays it again`() {
        val videos = oldestFirst(10)
        play(videos[0], shuffle = false)
        play(videos[5], shuffle = false)
        log.markJustThis(videos[5], watched = false)
        play(videos[5], shuffle = true)

        assertEquals("v2", nextUnwatched(videos))
        assertFalse(log.watchStates(videos).getValue("v6").countsForHighWaterMark)
    }

    @Test
    fun `a Bulk mark covering a video makes it a mark whatever Shuffle did after`() {
        val videos = oldestFirst(10)
        log.markRange(videos[5], RangeSide.OLDER, watched = true)
        play(videos[5], shuffle = true)

        assertEquals("v7", nextUnwatched(videos))
    }

    @Test
    fun `a Bulk mark unwatching an ordinary play leaves a later Shuffle play no mark`() {
        val videos = oldestFirst(10)
        play(videos[0], shuffle = false)
        play(videos[5], shuffle = false)
        log.markRange(videos[3], RangeSide.NEWER, watched = false)
        play(videos[5], shuffle = true)

        val states = log.watchStates(videos)
        assertTrue(states.getValue("v6").watched)
        assertFalse(states.getValue("v6").countsForHighWaterMark)
        assertEquals("v2", nextUnwatched(videos))
    }

    @Test
    fun `a second copy of a video keeps the mark a Bulk mark gave the first copy`() {
        // The code review of build ticket 69 found it: the second pass over the same video
        // read the direct entries' answer and not the range's.
        val videos = oldestFirst(10)
        log.markJustThis(videos[5], watched = false)
        log.markRange(videos[5], RangeSide.OLDER, watched = true)

        val states = log.watchStates(videos + videos[5])

        assertTrue(states.getValue("v6").watched)
        assertTrue(states.getValue("v6").countsForHighWaterMark)
    }

    @Test
    fun `the Shuffle mark survives the play's position being written again`() {
        val video = testVideo("v1")
        val play = log.startPlay(video, positionSeconds = 10, completed = false, shuffle = true)!!

        log.updatePlay(play, video, positionSeconds = 20, completed = false)

        val entry = store.entries().single()
        assertTrue(entry.played()!!.shuffle)
        assertEquals(20, entry.played()!!.positionSeconds)
    }

    @Test
    fun `an ordinary play's payload is exactly what it was before Shuffle existed`() {
        val payload = playedPayload("v1", "UUx", positionSeconds = 12, completed = false)

        assertFalse(payload.contains("shuffle"))
        assertTrue(playedPayload("v1", "UUx", 12, false, shuffle = true).contains("\"shuffle\":true"))
    }

    @Test
    fun `an old played entry with no Shuffle mark replays as before`() {
        val videos = oldestFirst(10)
        // Written by hand, exactly as a build before ticket 69 wrote it: no `shuffle` field.
        val old = WatchEntry(
            id = "old",
            deviceId = "tv",
            seq = 1,
            at = Instant.parse("2026-09-20T12:00:00Z"),
            kind = WatchKind.PLAYED,
            payload = """{"videoId":"v6","sourceId":"UUx","position":40,"completed":true}""",
        )

        assertFalse(old.played()!!.shuffle)
        val states = deriveWatchStates(listOf(old), videos)
        assertTrue(states.getValue("v6").countsForHighWaterMark)
        assertEquals(
            6,
            nextUnwatchedIndex(videos, states, Direction.OLDEST_FIRST),
        )
    }

    @Test
    fun `a rebuild of the derived table gives the same answer as the live log`() {
        val videos = oldestFirst(20)
        for (index in 0 until 5) play(videos[index], shuffle = false)
        play(videos[15], shuffle = true)

        log.rebuild("test")

        assertEquals("v6", nextUnwatched(videos))
        assertEquals(DERIVATION_VERSION, store.storedDerivationVersion())
    }

    @Test
    fun `a Shuffle play ends Newly listed like any play, by the glossary's own rule`() {
        val sources = InMemorySourceStore()
        sources.saveSource(StoredSource("UUx", title = null, lastWalkedAt = null))
        val newest = testVideo("new", day = 30, newlyListed = true)
        val older = testVideo("old", day = 10)
        sources.replaceVideos("UUx", listOf(newest, older))
        val withSources = WatchLog(InMemoryWatchStore(), sources)

        withSources.startPlay(older, positionSeconds = 20, completed = false, shuffle = true)

        assertFalse(sources.listedVideos("UUx").single { it.videoId == "new" }.newlyListed)
    }
}
