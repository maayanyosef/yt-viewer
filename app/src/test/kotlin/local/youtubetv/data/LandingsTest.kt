package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A watched video, as the log would report it. */
private fun watchedState(videoId: String, positionSeconds: Int? = null) = WatchState(
    videoId = videoId,
    watched = true,
    positionSeconds = positionSeconds,
    completed = positionSeconds == null,
    lastPlayedAt = Instant.parse("2026-09-20T12:00:00Z"),
)

/** A video the household marked as not watched. It is a gap again. */
private fun unwatchedState(videoId: String) = WatchState(
    videoId = videoId,
    watched = false,
    positionSeconds = null,
    completed = false,
    lastPlayedAt = null,
)

private fun watchMap(vararg states: WatchState): Map<String, WatchState> =
    states.associateBy { it.videoId }

/**
 * Direction: the per-Source order the grid is browsed in.
 *
 * Ordering is always by `videoPublishedAt` and never by playlist position. See spec section
 * 4.2 and `CONTEXT.md`.
 */
class DirectionTest {

    private val videos = listOf(
        testVideo("v3", day = 3),
        testVideo("v1", day = 1),
        testVideo("v2", day = 2),
    )

    @Test
    fun `newest first is the default, and is what every Source did before Direction existed`() {
        assertEquals(
            listOf("v3", "v2", "v1"),
            orderByPublishDate(videos).map { it.videoId },
        )
    }

    @Test
    fun `oldest first turns the list around`() {
        assertEquals(
            listOf("v1", "v2", "v3"),
            orderByPublishDate(videos, Direction.OLDEST_FIRST).map { it.videoId },
        )
    }

    @Test
    fun `an undated video goes last in both Directions`() {
        // A private or deleted video has no date. It is not the oldest thing in the Source
        // merely because oldest-first was chosen.
        val withUndated = videos + testVideo("gone", day = null)

        assertEquals(
            "gone",
            orderByPublishDate(withUndated, Direction.NEWEST_FIRST).last().videoId,
        )
        assertEquals(
            "gone",
            orderByPublishDate(withUndated, Direction.OLDEST_FIRST).last().videoId,
        )
    }

    @Test
    fun `undated videos go in video id order, not the order they arrived in`() {
        // Build ticket 57. SQL cannot keep the playlist's order, so neither does Kotlin.
        val withUndated = listOf(
            testVideo("goneB", day = null),
            testVideo("v1", day = 1),
            testVideo("goneA", day = null),
        )

        assertEquals(
            listOf("v1", "goneA", "goneB"),
            orderByPublishDate(withUndated, Direction.OLDEST_FIRST).map { it.videoId },
        )
    }

    @Test
    fun `flipping twice returns the Source to where it was`() {
        val flipped = Direction.NEWEST_FIRST.flipped()
        assertEquals(Direction.OLDEST_FIRST, flipped)
        assertEquals(Direction.NEWEST_FIRST, flipped.flipped())
    }

    @Test
    fun `a Direction this build cannot read is newest first`() {
        // A Source row written before database version 5 has no Direction stored at all.
        assertEquals(Direction.NEWEST_FIRST, Direction.ofStored(null))
        assertEquals(Direction.NEWEST_FIRST, Direction.ofStored(""))
        assertEquals(Direction.NEWEST_FIRST, Direction.ofStored("sideways"))
        assertEquals(Direction.OLDEST_FIRST, Direction.ofStored("OLDEST_FIRST"))
    }
}

/**
 * Where the chips and the opening land.
 *
 * Both are indexes into the videos the grid shows, in the Source's Direction. Spec section
 * 7.2 and ADR 0001.
 */
class LandingsTest {

    /** Day 1 to day 5, newest first, which is what the grid would hand in. */
    private val videos = (5 downTo 1).map { day -> testVideo("v$day", day = day) }

    @Test
    fun `next unwatched is the first video with no Watch`() {
        val watches = watchMap(watchedState("v5"), watchedState("v4"))

        assertEquals(2, nextUnwatchedIndex(videos, watches, Direction.NEWEST_FIRST))
    }

    @Test
    fun `next unwatched follows Direction`() {
        val watches = watchMap(watchedState("v1"), watchedState("v2"))
        val oldestFirst = orderByPublishDate(videos, Direction.OLDEST_FIRST)

        // Newest first, the first gap is v5 at the very top. Oldest first, it is v3.
        assertEquals(0, nextUnwatchedIndex(videos, watches, Direction.NEWEST_FIRST))
        val oldestFirstIndex = nextUnwatchedIndex(oldestFirst, watches, Direction.OLDEST_FIRST)
        assertEquals("v3", oldestFirst[oldestFirstIndex!!].videoId)
    }

    @Test
    fun `a part watched video closes its gap`() {
        // Any Watch closes the gap, complete or not. The abandoned video is reached through
        // Last played instead. ADR 0001.
        val watches = watchMap(watchedState("v5", positionSeconds = 42))

        assertEquals(1, nextUnwatchedIndex(videos, watches, Direction.NEWEST_FIRST))
    }

    @Test
    fun `a video marked unwatched is a gap again`() {
        // v4 is a gap once more, and it is counted as one. It is not where the chip lands:
        // v3 below it carries a Watch, so the high-water mark is past v4 and the landing goes
        // on down the list. Build ticket 30.
        val watches = watchMap(watchedState("v5"), unwatchedState("v4"), watchedState("v3"))

        val landing =
            nextUnwatchedLanding(videos, watches, Direction.NEWEST_FIRST) { it.canBeLandedOn }
        assertEquals(2, landing.highWaterMark)
        assertEquals(3, landing.index)
        assertEquals(2, landing.count)
    }

    @Test
    fun `next unwatched skips an unavailable video and an upcoming premiere`() {
        val mixed = listOf(
            testVideo("gone", day = 5, availability = Availability.UNAVAILABLE),
            testVideo("soon", day = 4, liveBroadcastContent = LiveBroadcastContent.UPCOMING),
            testVideo("v3", day = 3),
        )

        assertEquals(2, nextUnwatchedIndex(mixed, emptyMap(), Direction.NEWEST_FIRST))
    }

    @Test
    fun `next unwatched never lands on a Short`() {
        // The grid is handed the listed videos, so a Short should never reach here at all.
        // The rule is repeated rather than trusted, because a landing on a hidden card would
        // leave the highlight nowhere the household can see.
        val withShort = listOf(testVideo("short", day = 5, isShort = true)) + videos

        val index = nextUnwatchedIndex(withShort, emptyMap(), Direction.NEWEST_FIRST)
        assertEquals("v5", withShort[index!!].videoId)
    }

    @Test
    fun `a Source with every video watched has no next unwatched`() {
        val watches = watchMap(*videos.map { watchedState(it.videoId) }.toTypedArray())

        assertNull(nextUnwatchedIndex(videos, watches, Direction.NEWEST_FIRST))
        // It still has to open somewhere, and the top of the list is where the chips are.
        assertEquals(0, openingIndex(videos, watches, Direction.NEWEST_FIRST))
    }

    @Test
    fun `an empty Source has no next unwatched`() {
        assertNull(nextUnwatchedIndex(emptyList(), emptyMap(), Direction.NEWEST_FIRST))
        assertEquals(0, openingIndex(emptyList(), emptyMap(), Direction.NEWEST_FIRST))
    }

    @Test
    fun `a Source opens on its next unwatched video`() {
        val watches = watchMap(watchedState("v5"), watchedState("v4"), watchedState("v3"))

        assertEquals(3, openingIndex(videos, watches, Direction.NEWEST_FIRST))
    }

    @Test
    fun `jump to last played lands on the video the log names`() {
        assertEquals(3, lastPlayedIndex(videos, "v2"))
    }

    @Test
    fun `jump to last played has nowhere to go when the log names nothing`() {
        assertNull(lastPlayedIndex(videos, null))
        assertNull(lastPlayedIndex(videos, ""))
    }

    @Test
    fun `jump to last played has nowhere to go when that video is not on the grid`() {
        // A video a later walk no longer lists. The chip is greyed out rather than taking
        // focus and moving nothing.
        assertNull(lastPlayedIndex(videos, "v9"))
    }

    @Test
    fun `jump to last played never lands on a Short`() {
        val withShort = videos + testVideo("short", day = 0, isShort = true)

        assertNull(lastPlayedIndex(withShort, "short"))
    }

    /**
     * **This test said the opposite until ticket 23.**
     *
     * It read "jump to last played still lands on a video that has since gone private", and
     * argued that the household really did play it, so it was still where they left off.
     *
     * Ticket 23 made an unavailable video an unlisted one, so the grid no longer draws a card
     * for it, and spec section 7.2's own rule takes over: a landing point must be a card the
     * grid actually shows, and a chip with no such card is dim. A chip that took focus and
     * moved nothing would be worse.
     *
     * Nothing about the household's record is lost. The Watch log keeps every entry, and the
     * `kept_video` copy keeps the title and the publish date.
     */
    @Test
    fun `jump to last played is dim on a video that has since gone private`() {
        val withGone = listOf(testVideo("gone", day = 6, availability = Availability.UNAVAILABLE)) +
            videos

        assertNull(lastPlayedIndex(withGone, "gone"))
    }

    @Test
    fun `jump to last played follows Direction`() {
        val oldestFirst = orderByPublishDate(videos, Direction.OLDEST_FIRST)

        assertEquals(3, lastPlayedIndex(videos, "v2"))
        assertEquals(1, lastPlayedIndex(oldestFirst, "v2"))
    }
}

/**
 * The high-water mark: Next unwatched never sends the household backwards.
 *
 * Build ticket 30, from the owner's words on 2026-09-20. A gap above the furthest video the
 * household has watched was skipped **on purpose**, so no landing offers it again while
 * anything is still ahead. The count follows the landing, so the badge and the slot can never
 * disagree about what is left.
 *
 * Every row of the ticket's own table is a test here, in both Directions, because no household
 * Source is oldest-first and nothing on the television can prove that half.
 */
class NextUnwatchedHighWaterMarkTest {

    /** Day 1 to day 5, newest first: `v5` at index 0 down to `v1` at index 4. */
    private val newestFirst = (5 downTo 1).map { day -> testVideo("v$day", day = day) }

    /** The same five videos the other way round: `v1` at index 0 up to `v5` at index 4. */
    private val oldestFirst = orderByPublishDate(newestFirst, Direction.OLDEST_FIRST)

    /**
     * [nextUnwatchedLanding] over these videos, in this Direction.
     *
     * **The Direction is spelled out at every call site and is not defaulted here**, so that
     * the two oldest-first cases below say so in their own body. No video in this class
     * carries the Newly listed flag, so the value changes no answer; it is the honest label
     * for the list being handed in.
     */
    private fun landingIn(
        videos: List<StoredVideo>,
        watches: Map<String, WatchState>,
        direction: Direction,
    ) = nextUnwatchedLanding(videos, watches, direction) { it.canBeLandedOn }

    @Test
    fun `a gap above the furthest watched video is not offered again`() {
        // v5 and v3 were watched and v4 was skipped. The mark is v3 at index 2, so the landing
        // is v2 at index 3 and v4 is behind the household.
        val watches = watchMap(watchedState("v5"), watchedState("v3"))

        val landing = landingIn(newestFirst, watches, Direction.NEWEST_FIRST)

        assertEquals(2, landing.highWaterMark)
        assertEquals(3, landing.index)
        // v2 and v1 are ahead, so the badge says two. v4 is a gap and is not counted, because
        // no landing can take the household to it while anything is still ahead.
        assertEquals(2, landing.count)
    }

    @Test
    fun `Direction is followed and not date order`() {
        // The same two Watches, read the other way round. Oldest first the list is
        // v1 v2 v3 v4 v5, so v3 sits at index 2 and v5 at index 4: the mark is now v5, the
        // newest video, and every gap left, v1, v2 and v4, sits above it.
        val watches = watchMap(watchedState("v5"), watchedState("v3"))

        val landing = landingIn(oldestFirst, watches, Direction.OLDEST_FIRST)

        assertEquals(4, landing.highWaterMark)
        // Nothing is after the mark, so the fallback lands on the first gap in the Source.
        assertEquals(0, landing.index)
        assertEquals("v1", oldestFirst[landing.index!!].videoId)
        assertEquals(3, landing.count)
    }

    @Test
    fun `an oldest-first Source walks down the list and never back up it`() {
        // v1 and v2 watched, v3 skipped, v4 watched. Oldest first, the mark is v4 at index 3.
        val watches = watchMap(watchedState("v1"), watchedState("v2"), watchedState("v4"))

        val landing = landingIn(oldestFirst, watches, Direction.OLDEST_FIRST)

        assertEquals(3, landing.highWaterMark)
        assertEquals("v5", oldestFirst[landing.index!!].videoId)
        assertEquals(1, landing.count)
    }

    @Test
    fun `a Source with no Watch at all keeps the old behaviour exactly`() {
        // There is no mark, so nothing is behind the household and the first listed video is
        // both the first gap and the first gap after nothing. `Lofi Girl` is this Source.
        val landing = landingIn(newestFirst, emptyMap(), Direction.NEWEST_FIRST)

        assertNull(landing.highWaterMark)
        assertEquals(0, landing.index)
        assertEquals(5, landing.count)
    }

    @Test
    fun `with nothing ahead it falls back to the first gap in the whole Source`() {
        // Everything from the mark down is watched, and two gaps remain above it. The owner chose
        // the fallback over a dim chip: he does not want to be told there is nothing left
        // while there is.
        val watches = watchMap(watchedState("v5"), watchedState("v2"), watchedState("v1"))

        val landing = landingIn(newestFirst, watches, Direction.NEWEST_FIRST)

        assertEquals(4, landing.highWaterMark)
        assertEquals(1, landing.index)
        assertEquals("v4", newestFirst[landing.index!!].videoId)
        // The count jumps to every gap at the same moment the landing does, so the badge and
        // the slot still agree.
        assertEquals(2, landing.count)
    }

    @Test
    fun `with no gap anywhere there is nothing left in this Source`() {
        val watches = watchMap(*newestFirst.map { watchedState(it.videoId) }.toTypedArray())

        val landing = landingIn(newestFirst, watches, Direction.NEWEST_FIRST)

        assertEquals(4, landing.highWaterMark)
        assertNull(landing.index)
        // Zero is what `ALL WATCHED` is drawn from. No regression from ticket 23.
        assertEquals(0, landing.count)
    }

    @Test
    fun `an unlisted video is neither a gap nor a high-water mark`() {
        // A watched Short below every gap must not become the mark, or the whole Source would
        // read as finished. A private video above them must not be counted as a gap either.
        val mixed = listOf(
            testVideo("gone", day = 9, availability = Availability.UNAVAILABLE),
            testVideo("v5", day = 5),
            testVideo("v4", day = 4),
            testVideo("short", day = 0, isShort = true),
        )
        val watches = watchMap(watchedState("gone"), watchedState("short"))

        val landing = landingIn(mixed, watches, Direction.NEWEST_FIRST)

        assertNull(landing.highWaterMark)
        assertEquals(1, landing.index)
        assertEquals(2, landing.count)
    }

    @Test
    fun `a video made watched by a range counts towards the mark exactly as a play does`() {
        // A real log can hold many `range` entries, and one of them alone can make nearly
        // every Watch of a Source. A range never writes a row per video, so this is
        // the case a test over `played` entries alone would miss.
        val bulkMark = WatchEntry(
            id = "bulk",
            deviceId = "device-a",
            seq = 1,
            at = Instant.parse("2026-09-21T04:00:00Z"),
            kind = WatchKind.RANGE,
            // Day 3 and everything older, which is v3, v2 and v1. `testVideo` puts every video
            // in Source `UUx`.
            payload = rangePayload(
                sourceId = "UUx",
                pivotPublishedAt = testDay(3),
                side = RangeSide.OLDER,
                watched = true,
                clearsLastPlayed = false,
            ),
        )
        val ranged = deriveWatchStates(listOf(bulkMark), newestFirst)
        assertEquals(true, ranged["v3"]?.watched)

        val landing = landingIn(newestFirst, ranged, Direction.NEWEST_FIRST)

        // v1 at index 4 is the furthest the range reaches, and it leaves nothing after it.
        assertEquals(4, landing.highWaterMark)
        // So the fallback offers v5, the first gap in the Source.
        assertEquals(0, landing.index)
        assertEquals(2, landing.count)
    }

    @Test
    fun `an empty Source has no landing and no mark`() {
        val landing = landingIn(emptyList(), emptyMap(), Direction.NEWEST_FIRST)

        assertNull(landing.index)
        assertNull(landing.highWaterMark)
        assertEquals(0, landing.count)
    }
}

/**
 * The third landing of the home rail's long-press menu: the newest video.
 *
 * Added by ticket 10. It is the one landing that does not follow Direction, so most of what is
 * worth testing is that it means the same video whichever way round the Source is.
 */
class NewestIndexTest {

    private val videos = listOf(
        testVideo("v4", day = 4),
        testVideo("v3", day = 3),
        testVideo("v2", day = 2),
        testVideo("v1", day = 1),
    )

    @Test
    fun `in a newest-first Source it is the first card`() {
        assertEquals(0, newestIndex(videos))
    }

    @Test
    fun `in an oldest-first Source it is the last dated card`() {
        val oldestFirst = orderByPublishDate(videos, Direction.OLDEST_FIRST)

        assertEquals(3, newestIndex(oldestFirst))
        assertEquals("v4", oldestFirst[3].videoId)
    }

    /** An undated video sorts last in both Directions and is never the newest. */
    @Test
    fun `an undated video is never the newest`() {
        val oldestFirst = orderByPublishDate(videos + testVideo("gone", day = null), Direction.OLDEST_FIRST)

        assertEquals("v4", oldestFirst[newestIndex(oldestFirst)!!].videoId)
    }

    /**
     * **This test said the opposite until ticket 23.**
     *
     * It read "an upcoming premiere can be the newest video", and argued that the premiere
     * really is the newest thing in the Source and that this landing only moves the highlight.
     *
     * The premiere has no card on the grid any more, so there is nowhere to move the highlight
     * to. `newest` lands on the newest video the grid actually draws. See spec section 4.4.
     */
    @Test
    fun `an upcoming premiere is never the newest video`() {
        val withPremiere = listOf(
            testVideo("soon", day = 9, liveBroadcastContent = LiveBroadcastContent.UPCOMING),
        ) + videos

        assertEquals("v4", withPremiere[newestIndex(withPremiere)!!].videoId)
    }

    @Test
    fun `a Source with no dated video has no newest`() {
        assertNull(newestIndex(listOf(testVideo("gone", day = null))))
        assertNull(newestIndex(emptyList()))
    }
}

/**
 * Next and previous inside the player, which follow the Source's **Direction**.
 *
 * In a newest-first Source the next video is the older one; in an oldest-first Source it is
 * the newer one. The list is already in the Direction, so next is the next index, and none of
 * these tests consults a date. Spec sections 8.5 and 8.6.
 */
class PlayerStepTest {

    /** Newest first, so v4 is at index 0 and v1 at index 3. */
    private val newestFirst = orderByPublishDate(
        listOf(
            testVideo("v1", day = 1),
            testVideo("v2", day = 2),
            testVideo("v3", day = 3),
            testVideo("v4", day = 4),
        ),
        Direction.NEWEST_FIRST,
    )

    private val oldestFirst = orderByPublishDate(newestFirst, Direction.OLDEST_FIRST)

    @Test
    fun `in newest-first Direction, next plays the older video`() {
        assertEquals("v3", nextPlayableVideo(newestFirst, "v4", PlayStep.NEXT)?.videoId)
        assertEquals("v2", nextPlayableVideo(newestFirst, "v3", PlayStep.NEXT)?.videoId)
    }

    @Test
    fun `in newest-first Direction, previous plays the newer video`() {
        assertEquals("v4", nextPlayableVideo(newestFirst, "v3", PlayStep.PREVIOUS)?.videoId)
    }

    @Test
    fun `flipping Direction changes what next plays`() {
        // The same video, the same press, the other answer. This is the whole of the
        // ticket's "Flipping Direction changes what next plays".
        assertEquals("v2", nextPlayableVideo(newestFirst, "v3", PlayStep.NEXT)?.videoId)
        assertEquals("v4", nextPlayableVideo(oldestFirst, "v3", PlayStep.NEXT)?.videoId)
    }

    @Test
    fun `there is no wrap-around at either end`() {
        assertNull(nextPlayableVideo(newestFirst, "v1", PlayStep.NEXT))
        assertNull(nextPlayableVideo(newestFirst, "v4", PlayStep.PREVIOUS))
        assertNull(nextPlayableVideo(oldestFirst, "v4", PlayStep.NEXT))
        assertNull(nextPlayableVideo(oldestFirst, "v1", PlayStep.PREVIOUS))
    }

    @Test
    fun `a Short, an unavailable video and an upcoming premiere are all stepped over`() {
        // Spec section 8.6: autoplay skips what the list skips. Next and previous use the
        // same rule, because a next that stopped on a private video would answer the press
        // with player error 100.
        val videos = listOf(
            testVideo("start", day = 5),
            testVideo("short", day = 4, isShort = true),
            testVideo("gone", day = 3, availability = Availability.UNAVAILABLE),
            testVideo("soon", day = 2, liveBroadcastContent = LiveBroadcastContent.UPCOMING),
            testVideo("real", day = 1),
        )
        assertEquals("real", nextPlayableVideo(videos, "start", PlayStep.NEXT)?.videoId)
        assertEquals("start", nextPlayableVideo(videos, "real", PlayStep.PREVIOUS)?.videoId)
    }

    @Test
    fun `a Source whose remaining videos are all unplayable has no next`() {
        val videos = listOf(
            testVideo("start", day = 3),
            testVideo("gone", day = 2, availability = Availability.UNAVAILABLE),
            testVideo("soon", day = 1, liveBroadcastContent = LiveBroadcastContent.UPCOMING),
        )
        assertNull(nextPlayableVideo(videos, "start", PlayStep.NEXT))
    }

    @Test
    fun `a video the list no longer holds has no next`() {
        // The player knows which video it is showing, not an index into a list a walk could
        // have changed underneath it. Null reads as the end of the Source and stops
        // playback, rather than moving the household somewhere they did not ask to go.
        assertNull(nextPlayableVideo(newestFirst, "never-here", PlayStep.NEXT))
        assertNull(nextPlayableVideo(newestFirst, null, PlayStep.NEXT))
        assertNull(nextPlayableVideo(emptyList(), "v1", PlayStep.NEXT))
    }

    @Test
    fun `an index off either end answers nothing rather than guessing`() {
        assertNull(nextPlayableIndex(newestFirst, fromIndex = -1, step = PlayStep.NEXT))
        assertNull(nextPlayableIndex(newestFirst, fromIndex = 9, step = PlayStep.NEXT))
    }
}

/**
 * Where a Source screen lands for each of the three landing requests. Build ticket 55.
 *
 * The screen asks this once when its grid appears, and again when a refresh lands before the
 * household has pressed a key. Both answers have to come from one rule.
 */
class OpeningLandingTest {

    // Newest first: "d" is the newest, and the household has watched "c" and "b".
    private val videos = listOf(
        testVideo("d", day = 4),
        testVideo("c", day = 3),
        testVideo("b", day = 2),
        testVideo("a", day = 1),
    )
    private val watches = watchMap(watchedState("c"), watchedState("b", positionSeconds = 30))

    @Test
    fun `Next unwatched lands on the first gap after the high-water mark`() {
        assertEquals(3, openingLanding(Landing.NEXT_UNWATCHED, videos, watches, Direction.NEWEST_FIRST, "b"))
    }

    @Test
    fun `Next unwatched lands on a newly listed video above the mark in a newest-first Source`() {
        val withNew = listOf(testVideo("new", day = 5, newlyListed = true)) + videos
        assertEquals(0, openingLanding(Landing.NEXT_UNWATCHED, withNew, watches, Direction.NEWEST_FIRST, "b"))
    }

    @Test
    fun `Last played lands on it, and falls back to Next unwatched when there is none`() {
        assertEquals(2, openingLanding(Landing.LAST_PLAYED, videos, watches, Direction.NEWEST_FIRST, "b"))
        assertEquals(3, openingLanding(Landing.LAST_PLAYED, videos, watches, Direction.NEWEST_FIRST, null))
    }

    @Test
    fun `Newest lands on the newest video, and falls back to Next unwatched when none is dated`() {
        assertEquals(0, openingLanding(Landing.NEWEST, videos, watches, Direction.NEWEST_FIRST, null))
        val undated = listOf(testVideo("x", day = null))
        assertEquals(0, openingLanding(Landing.NEWEST, undated, emptyMap(), Direction.NEWEST_FIRST, null))
    }
}
