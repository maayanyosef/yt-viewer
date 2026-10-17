package local.youtubetv.ui

import java.time.Instant
import java.time.ZoneId
import local.youtubetv.data.Availability
import local.youtubetv.data.Direction
import local.youtubetv.data.LiveBroadcastContent
import local.youtubetv.data.StoredSource
import local.youtubetv.data.WatchState
import local.youtubetv.data.testVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every string the Source screen puts on the television, tested on the JVM. */
class SourceScreenTextTest {

    private val london = ZoneId.of("Europe/London")

    // --- the quiet header line ----------------------------------------------------------

    @Test
    fun `the loading line names both numbers, grouped`() {
        assertEquals(
            "loading 400 of 5,813",
            headerLine(SourceScreenState.Walking(loaded = 400, total = 5_813), london).count,
        )
    }

    @Test
    fun `the loading line drops the total until YouTube reports one`() {
        assertEquals(
            "loading 50",
            headerLine(SourceScreenState.Walking(loaded = 50, total = null), london).count,
        )
    }

    @Test
    fun `a ready list counts what the grid lists, and calls them items`() {
        val line = headerLine(
            SourceScreenState.Ready(
                videos = listOf(testVideo("a")),
                updatedAt = null,
                fromStorage = false,
            ),
            london,
        ).count
        // "1 items" would be wrong, and a Source of one video is not far-fetched.
        assertEquals("1 item", line)
    }

    @Test
    fun `the count carries no thousands separator`() {
        // The owner settled this on 2026-09-19: plain digits, so 5303 and not 5,303. The Source
        // on the television shows 588, which reads the same either way, so it is pinned here
        // with a number big enough to tell the two apart.
        val many = List(5_303) { testVideo("v$it") }
        val line = headerLine(
            SourceScreenState.Ready(videos = many, updatedAt = null, fromStorage = true),
            london,
        ).count
        assertEquals("5303 items", line)
    }

    @Test
    fun `the line says nothing about where the list came from, which is not the household's concern`() {
        // Ticket 02 wrote "from storage, no network call" here. The proof that a second launch
        // makes no network call is the logcat line, not text on the television.
        val line = headerLine(
            SourceScreenState.Ready(videos = emptyList(), updatedAt = null, fromStorage = true),
            london,
        ).count
        assertEquals("0 items", line)
    }

    @Test
    fun `the updated time is a separate bracketed part, in day-month hour-minute`() {
        val updated = headerLine(
            SourceScreenState.Ready(
                videos = emptyList(),
                updatedAt = Instant.parse("2026-09-19T20:14:00Z"),
                fromStorage = true,
            ),
            london,
        ).updated
        assertEquals("(updated 19/09 21:14)", updated)
    }

    @Test
    fun `a single digit day and month are padded, so the line never changes width`() {
        val updated = headerLine(
            SourceScreenState.Ready(
                videos = emptyList(),
                updatedAt = Instant.parse("2026-03-05T08:07:00Z"),
                fromStorage = true,
            ),
            london,
        ).updated
        assertEquals("(updated 05/03 08:07)", updated)
    }

    @Test
    fun `a Source that has never been walked has no updated part at all`() {
        assertNull(
            headerLine(
                SourceScreenState.Ready(videos = emptyList(), updatedAt = null, fromStorage = true),
                london,
            ).updated,
        )
        assertNull(headerLine(SourceScreenState.Starting, london).updated)
        assertNull(headerLine(SourceScreenState.Walking(loaded = 1, total = 2), london).updated)
    }

    // --- the exact date, never a relative age -------------------------------------------

    @Test
    fun `a publish date reads as the exact day`() {
        assertEquals("3 Jun 2025", exactDate(Instant.parse("2025-06-03T10:00:00Z"), london))
    }

    @Test
    fun `a date published yesterday still reads as the exact day, never as an age`() {
        // Three letters for most months, four for the one the UK locale spells "Sept".
        val text = exactDate(Instant.now().minusSeconds(86_400), london)
        assertTrue(text, text.matches(Regex("""\d{1,2} \p{L}{3,4} \d{4}""")))
    }

    @Test
    fun `a video with no date says so, because private and deleted videos carry none`() {
        assertEquals("no date", exactDate(null, london))
    }

    // --- the duration badge -------------------------------------------------------------

    @Test
    fun `a duration under an hour reads as minutes and seconds`() {
        assertEquals("10:31", durationBadge(631))
        assertEquals("0:45", durationBadge(45))
    }

    @Test
    fun `a duration over an hour reads with hours`() {
        assertEquals("1:04:35", durationBadge(3_875))
    }

    @Test
    fun `an unknown length carries no badge at all, rather than a made-up zero`() {
        assertNull(durationBadge(null))
    }

    @Test
    fun `a stream with no length yet carries no badge, so 0-00 is never shown`() {
        // A stream that is live right now, and a premiere, both report P0D, which is 0 seconds.
        assertNull(durationBadge(0))
    }

    // --- the one badge on the thumbnail -------------------------------------------------

    @Test
    fun `a finished live stream carries no badge, because it is an ordinary video`() {
        // The owner had this removed on 2026-09-19. The badge covered the thumbnail and said
        // almost nothing, which the Hasson Source made obvious: nearly every card had one.
        val vod = testVideo("a", liveEndedAt = Instant.parse("2026-08-31T18:06:40Z"))
        assertNull(cardBadge(vod))
    }

    @Test
    fun `a stream that is live right now says so, which is what a 0-00 length hid`() {
        val live = testVideo("a", liveBroadcastContent = LiveBroadcastContent.LIVE, durationSeconds = 0)
        assertEquals("LIVE NOW", cardBadge(live))
    }

    /**
     * **These two tests asserted a badge until ticket 23**, `UPCOMING` and `UNAVAILABLE`.
     *
     * Both videos are now unlisted, so neither has a card for a badge to sit on. The badge is
     * gone rather than unreachable, because an unreachable branch is a promise the app no
     * longer keeps. Spec section 7.3 was amended to say which badge survives and why:
     * `LIVE NOW` alone, because a stream running right now is a video the household can play.
     */
    @Test
    fun `an unstarted premiere has no badge, because it has no card`() {
        val premiere = testVideo("a", liveBroadcastContent = LiveBroadcastContent.UPCOMING)
        assertNull(cardBadge(premiere))
    }

    @Test
    fun `a private or deleted video has no badge, because it has no card`() {
        assertNull(cardBadge(testVideo("a", availability = Availability.UNAVAILABLE)))
        val gone = testVideo(
            "a",
            availability = Availability.UNAVAILABLE,
            liveEndedAt = Instant.parse("2026-08-31T18:06:40Z"),
        )
        assertNull(cardBadge(gone))
    }

    @Test
    fun `a plain video carries no badge at all`() {
        assertNull(cardBadge(testVideo("a")))
    }

    @Test
    fun `there is no SHORT badge anywhere, because a Short is never shown`() {
        assertNull(cardBadge(testVideo("a", isShort = true)))
    }

    /** The one badge left. Every unlisted kind answers null, and only a live stream does not. */
    @Test
    fun `LIVE NOW is the only badge left`() {
        val live = testVideo("a", liveBroadcastContent = LiveBroadcastContent.LIVE)
        assertEquals("LIVE NOW", cardBadge(live))
        assertNull(cardBadge(testVideo("a", isShort = true)))
        assertNull(cardBadge(testVideo("a", availability = Availability.UNAVAILABLE)))
        assertNull(cardBadge(testVideo("a", liveBroadcastContent = LiveBroadcastContent.UPCOMING)))
    }

    // --- counts -------------------------------------------------------------------------

    @Test
    fun `counts are grouped, because a Source can hold thousands`() {
        assertEquals("5,813", 5_813.grouped())
        assertEquals("7", 7.grouped())
    }

    // --- the watch date beside the focused title ----------------------------------------

    private fun watchState(
        watched: Boolean,
        playedAt: Instant? = Instant.parse("2026-06-03T19:00:00Z"),
    ) = WatchState(
        videoId = "v1",
        watched = watched,
        positionSeconds = null,
        completed = watched,
        lastPlayedAt = playedAt,
    )

    @Test
    fun `a watched video says when it was watched`() {
        assertEquals("Watched 3 Jun 2026", watchDateLine(watchState(watched = true), london))
    }

    @Test
    fun `a video nobody marked says nothing`() {
        assertNull(watchDateLine(null, london))
    }

    @Test
    fun `a video marked unwatched says nothing, even though the play is still in the log`() {
        assertNull(watchDateLine(watchState(watched = false), london))
    }

    @Test
    fun `a watched video that was never played says nothing`() {
        // It cannot happen yet. A Bulk mark in ticket 05 is the first thing that can make a
        // video watched without a play, and then there is no date that is not a guess.
        assertNull(watchDateLine(watchState(watched = true, playedAt = null), london))
    }

    @Test
    fun `the date is the play's own, never a relative age`() {
        val line = watchDateLine(watchState(watched = true), london)!!
        assertTrue(line, line.contains("2026"))
        assertFalse(line, line.contains("ago"))
    }
}

/**
 * The chip row: what it says, and what it says when a chip cannot act.
 *
 * The wording is `CONTEXT.md`'s. Direction is never called a sort order, and never ascending
 * or descending. See spec section 7.2.
 */
class ChipTextTest {

    @Test
    fun `the Order chip names the Direction the Source is in now`() {
        assertEquals("Order: Newest first", orderChipLabel(Direction.NEWEST_FIRST))
        assertEquals("Order: Oldest first", orderChipLabel(Direction.OLDEST_FIRST))
    }

    @Test
    fun `the Autoplay chip names its state in words`() {
        // Ticket 26. Ticket 18 cut this to the single word `Autoplay` and left the state to
        // the chip's fill. The owner was shown the two fills on the household television on
        // 2026-09-20 and could not tell them apart from the sofa, so the words came back.
        assertEquals("Autoplay: On", autoplayChipLabel(autoplayNext = true))
        assertEquals("Autoplay: Off", autoplayChipLabel(autoplayNext = false))
    }

    @Test
    fun `before the Source's row is read, the chip reads the default, which is off`() {
        // There is no third label. Until the `sources` row has been read there is no value
        // yet, and `SourceScreen` passes `AUTOPLAY_NEXT_DEFAULT` for that moment, exactly as
        // it did when the fill was the only signal. Ticket 18 set that default and ticket 26
        // does not touch it.
        assertFalse("a new Source starts with autoplay-next off", StoredSource.AUTOPLAY_NEXT_DEFAULT)
        assertEquals(
            "Autoplay: Off",
            autoplayChipLabel(StoredSource.AUTOPLAY_NEXT_DEFAULT),
        )
    }

    @Test
    fun `the default's label is wrong for a stored Source with a null column, for that moment`() {
        // Named rather than hidden. `/code-review` found it on ticket 26. The two answers
        // differ on purpose: a NEW Source starts off, and a row written before the column
        // existed reads as ON. So the label shown before the row arrives is the default's,
        // and for a null-column Source it says the opposite of what is stored, until `Ready`.
        // Two of the household's four Sources are null. See the KDoc on `autoplayChipLabel`.
        assertEquals("Autoplay: Off", autoplayChipLabel(StoredSource.AUTOPLAY_NEXT_DEFAULT))
        assertTrue("a null column still means on", StoredSource.autoplayNextOfStored(null))
        assertEquals("Autoplay: On", autoplayChipLabel(StoredSource.autoplayNextOfStored(null)))
    }

    @Test
    fun `the word and the fill always say the same thing`() {
        // One boolean feeds both, so a chip that reads `On` can never carry the quiet fill.
        // That is the whole point of ticket 26: two signals, never in disagreement.
        for (autoplayNext in listOf(true, false)) {
            val saysOn = autoplayChipLabel(autoplayNext) == "Autoplay: On"
            val looksOn = autoplayChipFill(autoplayNext) == ChipFill.ACCENT
            assertEquals("the word and the fill disagree at $autoplayNext", saysOn, looksOn)
        }
    }

    @Test
    fun `no chip uses a word the glossary forbids`() {
        val onScreen = listOf(
            orderChipLabel(Direction.NEWEST_FIRST),
            orderChipLabel(Direction.OLDEST_FIRST),
            JUMP_TO_LAST_PLAYED_CHIP,
            NEXT_UNWATCHED_CHIP,
            PERIOD_CHIP,
            autoplayChipLabel(autoplayNext = true),
            autoplayChipLabel(autoplayNext = false),
            NO_NEXT_UNWATCHED,
        )
        val forbidden = listOf("sort", "ascend", "descend", "resume", "bookmark", "up next")
        for (line in onScreen) {
            for (word in forbidden) {
                assertFalse("$line says $word", line.lowercase().contains(word))
            }
        }
    }

    @Test
    fun `a landing says which video it reached`() {
        assertEquals(
            "Next unwatched landed on v3, at index 2 of 588 listed videos.",
            landingLogLine(NEXT_UNWATCHED_CHIP, videoId = "v3", index = 2, count = 588),
        )
    }

    @Test
    fun `a landing with nowhere to go says so`() {
        assertEquals(
            "Jump to last played had nowhere to land among 588 listed videos.",
            landingLogLine(JUMP_TO_LAST_PLAYED_CHIP, videoId = null, index = null, count = 588),
        )
    }
}
