package local.youtubetv.ui

import java.time.ZoneId
import local.youtubetv.data.Direction
import local.youtubetv.data.SourceCard
import local.youtubetv.data.StoredVideo
import local.youtubetv.data.testVideo
import local.youtubetv.data.PlaybackSettings
import local.youtubetv.player.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val UTC: ZoneId = ZoneId.of("UTC")

private fun card(
    listedCount: Int = 10,
    unwatchedCount: Int = 4,
    nextUnwatched: StoredVideo? = testVideo("v1", day = 3),
    lastPlayedVideoId: String? = null,
    everythingUnwatchedCount: Int? = listedCount,
    title: String? = null,
    typedName: String? = null,
    direction: Direction = Direction.NEWEST_FIRST,
    walked: Boolean = true,
) = SourceCard(
    sourceId = "UUx",
    title = title,
    typedName = typedName,
    direction = direction,
    listedCount = listedCount,
    unwatchedCount = unwatchedCount,
    nextUnwatched = nextUnwatched,
    lastPlayedVideoId = lastPlayedVideoId,
    everythingUnwatchedCount = everythingUnwatchedCount,
    walked = walked,
)

/**
 * The badge in the corner of a rail card. Spec section 7.1.
 */
class UnwatchedBadgeTest {

    @Test
    fun `a Source with videos left states how many`() {
        assertEquals("4 UNWATCHED", unwatchedBadge(card(unwatchedCount = 4)))
        assertEquals("1 UNWATCHED", unwatchedBadge(card(unwatchedCount = 1)))
    }

    /** A finished Source says so in the badge. It is not dimmed and it does not move. */
    @Test
    fun `a finished Source reads ALL WATCHED`() {
        assertEquals("ALL WATCHED", unwatchedBadge(card(unwatchedCount = 0, nextUnwatched = null)))
    }

    /**
     * The trap this badge exists to avoid. A Source that has never been walked knows of no
     * videos, so a bare count would tell the household it had finished a Source it has never
     * opened.
     */
    @Test
    fun `a Source that has never been walked does not read ALL WATCHED`() {
        val fresh = card(walked = false, listedCount = 0, unwatchedCount = 0, nextUnwatched = null)

        assertEquals("NOT LOADED", unwatchedBadge(fresh))
    }

    /** No thousands separator, the same as every other count in this app. */
    @Test
    fun `a large count carries no thousands separator`() {
        assertEquals("5303 UNWATCHED", unwatchedBadge(card(unwatchedCount = 5303)))
    }
}

/**
 * The quiet line under a Source's name, and the name itself.
 *
 * **The line is a list of parts and never one joined string.** That is ticket 24's rule, and
 * spec section 6.2 says why: one piece of text is laid out as one bidirectional run, and the
 * household's Sources are often named in Hebrew. Ticket 20 is the one that puts a fetched name
 * on this card, so the rule is applied here before a Hebrew name arrives.
 */
class RailCardMetaTest {

    @Test
    fun `it states the Direction in the glossary's words and the video count`() {
        val meta = railCardMeta(card(listedCount = 588))

        assertEquals(listOf("Newest first", "588 videos"), meta.parts)
    }

    @Test
    fun `an oldest-first Source says so`() {
        val flipped = card(listedCount = 19, direction = Direction.OLDEST_FIRST)

        assertEquals(listOf("Oldest first", "19 videos"), railCardMeta(flipped).parts)
    }

    @Test
    fun `one video reads as one video`() {
        assertEquals(listOf("Newest first", "1 video"), railCardMeta(card(listedCount = 1)).parts)
    }

    /** A count the app does not have is not invented. */
    @Test
    fun `a Source that has never been walked states no count`() {
        val fresh = railCardMeta(card(walked = false, listedCount = 0))

        assertEquals(listOf("Newest first"), fresh.parts)
        assertNull(fresh.count)
    }

    /**
     * The count never gives way. Ticket 24's rule, applied to this line.
     *
     * The Direction is two known words and can lose its tail and still be read. A count cut
     * short reads as a smaller number rather than as a cut one, so `5303 videos` must never
     * become `5303 vide`.
     */
    @Test
    fun `the Direction is the part that gives way, and only when there is a count`() {
        assertEquals(0, railCardMeta(card(listedCount = 5303)).shrinkingPart)
        assertNull(railCardMeta(card(walked = false, listedCount = 0)).shrinkingPart)
    }

    @Test
    fun `the Source id stands in until something fetches a name`() {
        assertEquals("UUx", railCardName(card(title = null)))
        assertEquals("UUx", railCardName(card(title = "  ")))
        assertEquals("TED", railCardName(card(title = "TED")))
    }

    /**
     * The same rule, read straight from a stored title.
     *
     * Build ticket 29 gave the Source screen's header this function, so the header and the
     * rail card can never disagree about what an unnamed Source is called.
     */
    @Test
    fun `the id stands in for a stored title that is missing or blank`() {
        assertEquals("UUx", sourceNameOrId(null, null, "UUx"))
        assertEquals("UUx", sourceNameOrId(null, "", "UUx"))
        assertEquals("UUx", sourceNameOrId(null, "  ", "UUx"))
        assertEquals("TED", sourceNameOrId(null, "TED", "UUx"))
    }

    /**
     * The typed name wins, and clearing it gives the fetched one back. Build ticket 27.
     *
     * Both directions are here, which is one of that ticket's acceptance criteria. The
     * "clearing" direction is the null in the first argument: nothing else in the app has to
     * happen for YouTube's name to come back, because nothing ever wrote over it.
     */
    @Test
    fun `a typed name wins over the fetched one, and clearing it gives the fetched one back`() {
        // A name typed over a Source YouTube also named.
        assertEquals("Stand-up", sourceNameOrId("Stand-up", "TED", "UUx"))
        // The same Source with the typed name cleared. No API call, no walk.
        assertEquals("TED", sourceNameOrId(null, "TED", "UUx"))
        // A blank typed name is no typed name, at either kind of blank.
        assertEquals("TED", sourceNameOrId("", "TED", "UUx"))
        assertEquals("TED", sourceNameOrId("   ", "TED", "UUx"))
        // A typed name on a Source YouTube has never named. Clearing it gives back the id,
        // which is the honest answer and is what the next change check then replaces.
        assertEquals("Stand-up", sourceNameOrId("Stand-up", null, "UUx"))
        assertEquals("UUx", sourceNameOrId(null, null, "UUx"))
    }

    /** The same two directions through the card, which is what the rail actually draws. */
    @Test
    fun `the card prefers the typed name and falls back through the fetched one`() {
        assertEquals("Stand-up", railCardName(card(title = "TED", typedName = "Stand-up")))
        assertEquals("TED", railCardName(card(title = "TED", typedName = null)))
        assertEquals("TED", railCardName(card(title = "TED", typedName = "  ")))
        assertEquals("UUx", railCardName(card(title = null, typedName = null)))
    }

    /**
     * Ticket 20 fills the title from the change check, so a Hebrew name now reaches this card.
     *
     * The Hebrew is built from code points rather than typed, because the editing tools on
     * this machine decode a \uXXXX escape into the character and the character itself does not
     * survive them. Ticket 24 hit the same wall.
     */
    @Test
    fun `a Hebrew name is the name, and the count is never part of it`() {
        val hebrew = intArrayOf(0x05E2, 0x05E8, 0x05D5, 0x05E5, 0x0020, 0x05DC, 0x05D3, 0x05D5, 0x05D2, 0x05DE, 0x05D4)
            .fold(StringBuilder()) { builder, code -> builder.appendCodePoint(code) }
            .toString()
        val named = card(title = hebrew, listedCount = 588)

        assertEquals(hebrew, railCardName(named))
        // The name is drawn by its own `Text` and the meta line by two more, so nothing here
        // can ever hold a Hebrew name beside a number in one bidirectional run.
        assertEquals(listOf("Newest first", "588 videos"), railCardMeta(named).parts)
    }
}

/**
 * The Next unwatched slot of a rail card.
 *
 * The rule that matters most is what is **not** here: no position, no fraction and no
 * percentage about a single video. Spec section 7.1, decided 2026-09-19.
 */
class NextUnwatchedSlotTest {

    @Test
    fun `it carries the title and the exact publish date`() {
        val next = testVideo("v1", day = 3, title = "A talk")

        val slot = nextUnwatchedSlot(card(nextUnwatched = next), UTC)

        assertEquals("A talk", slot.title)
        assertEquals("4 Jan 2026", slot.date)
    }

    /** Never a relative age. The household browses by date. */
    @Test
    fun `the date is never a relative age`() {
        val slot = nextUnwatchedSlot(card(nextUnwatched = testVideo("v1", day = 3)), UTC)

        assertFalse(slot.date.orEmpty().contains("ago"))
    }

    @Test
    fun `a finished Source says there is nothing left, and dates nothing`() {
        val slot = nextUnwatchedSlot(card(unwatchedCount = 0, nextUnwatched = null), UTC)

        assertEquals(NOTHING_LEFT, slot.title)
        assertNull(slot.date)
    }

    @Test
    fun `a Source that has never been walked says to open it`() {
        val slot = nextUnwatchedSlot(card(walked = false, nextUnwatched = null), UTC)

        assertEquals(NOT_LOADED_YET, slot.title)
        assertNull(slot.date)
    }

    /** A video with no title is still identifiable. */
    @Test
    fun `a video with a blank title falls back to its id`() {
        val blank = testVideo("abc123", day = 3, title = "")

        assertEquals("abc123", nextUnwatchedSlot(card(nextUnwatched = blank), UTC).title)
    }
}

/**
 * The picture on a rail card. Spec section 7.1, added 2026-09-20 by ticket 21.
 *
 * The card shows the **Next unwatched** video's thumbnail, which is the video the card already
 * names, so the picture and the title are about the same thing. The rules below are all about
 * the cases where there is no such video.
 */
class RailCardPictureTest {

    @Test
    fun `the picture is the Next unwatched video's`() {
        val next = testVideo("v1", day = 3, thumbnailUrl = "https://i.ytimg.com/vi/v1/hq.jpg")

        val picture = railCardPicture(card(nextUnwatched = next))

        assertEquals("v1", picture?.videoId)
        assertEquals("https://i.ytimg.com/vi/v1/hq.jpg", picture?.storedUrl)
    }

    /**
     * A stored URL is a preference and not a requirement. `YouTubeUrls.thumbnails` builds the
     * addresses to try from the video id alone when the walk stored none.
     */
    @Test
    fun `a video with no stored URL still asks for a picture`() {
        val picture = railCardPicture(card(nextUnwatched = testVideo("v1", day = 3)))

        assertEquals("v1", picture?.videoId)
        assertNull(picture?.storedUrl)
    }

    /**
     * A finished Source has no next video, so there is no picture it could honestly show. It
     * draws the plain tile beside `Nothing left in this Source`. The last video's picture is
     * deliberately not used: the card is about what is next.
     */
    @Test
    fun `a finished Source has no picture`() {
        assertNull(railCardPicture(card(unwatchedCount = 0, nextUnwatched = null)))
    }

    @Test
    fun `a Source that has never been walked has no picture`() {
        assertNull(railCardPicture(card(walked = false, nextUnwatched = null)))
    }

    /**
     * The one rule that ties the two halves of the card together: the picture is there exactly
     * when the slot below it names a real video. A picture beside `Open this Source to load it`
     * would contradict the words under it.
     */
    @Test
    fun `the picture is there exactly when the slot names a video`() {
        val cards = listOf(
            card(nextUnwatched = testVideo("v1", day = 3)),
            card(unwatchedCount = 0, nextUnwatched = null),
            card(walked = false, nextUnwatched = null),
            // Never walked, yet carrying a video. `sourceCard` cannot build this today, and the
            // slot still reads `NOT LOADED`, so the picture must agree with the slot and not
            // with the field.
            card(walked = false, nextUnwatched = testVideo("v1", day = 3)),
        )

        for (each in cards) {
            assertEquals(
                "The picture and the Next unwatched slot disagree on ${unwatchedBadge(each)}.",
                nextUnwatchedSlot(each, UTC).date != null,
                railCardPicture(each) != null,
            )
        }
    }
}

/**
 * The long-press menu on a Source card. Spec section 7.1.
 */
class SourceMenuLinesTest {

    @Test
    fun `the three landings come first and the Source-wide action comes last`() {
        val lines = sourceMenuLines(card(lastPlayedVideoId = "v9"), everythingUnwatchedCount = 10)

        assertEquals(
            listOf(
                SourceMenuAction.OPEN_AT_NEXT_UNWATCHED,
                SourceMenuAction.OPEN_AT_LAST_PLAYED,
                SourceMenuAction.OPEN_AT_NEWEST,
                SourceMenuAction.REFRESH,
                SourceMenuAction.RENAME,
                SourceMenuAction.REMOVE,
                SourceMenuAction.PLAYBACK_SETTINGS,
                SourceMenuAction.APP_SETTINGS,
                SourceMenuAction.MARK_EVERYTHING_UNWATCHED,
            ),
            lines.map { it.action },
        )
    }

    @Test
    fun `the landings are worded as the glossary words them`() {
        val lines = sourceMenuLines(card(lastPlayedVideoId = "v9"), everythingUnwatchedCount = 10)

        assertEquals("Open at Next unwatched", lines[0].label)
        assertEquals("Open at Last played", lines[1].label)
        assertEquals("Open at the newest video", lines[2].label)
    }

    /** The same three cases that grey the Source screen's own chip out. */
    @Test
    fun `Open at Last played is dim when the Source has nowhere to land`() {
        val lines = sourceMenuLines(card(lastPlayedVideoId = null), everythingUnwatchedCount = 10)

        assertFalse(lines[1].enabled)
        assertTrue(lines[0].enabled)
    }

    @Test
    fun `Open at Last played is live when the Source has somewhere to land`() {
        val lines = sourceMenuLines(card(lastPlayedVideoId = "v9"), everythingUnwatchedCount = 10)

        assertTrue(lines[1].enabled)
    }

    /** The line states its count, so a press that changes four hundred cards says so first. */
    @Test
    fun `Mark everything unwatched states its count`() {
        val lines = sourceMenuLines(card(), everythingUnwatchedCount = 588)

        assertEquals("Mark everything unwatched (588 videos)", lines.last().label)
        assertTrue(lines.last().enabled)
    }

    @Test
    fun `Mark everything unwatched is dim when it can reach nothing`() {
        val lines = sourceMenuLines(card(), everythingUnwatchedCount = null)

        assertEquals("Mark everything unwatched", lines.last().label)
        assertFalse(lines.last().enabled)
    }

    /**
     * Opening a Source is what walks it, so a Source that has never been walked must still be
     * openable. Only the two lines that really have nowhere to go are dim.
     */
    @Test
    fun `a Source that has never been walked can still be opened`() {
        val fresh = card(walked = false, listedCount = 0, unwatchedCount = 0, nextUnwatched = null)

        val lines = sourceMenuLines(fresh, everythingUnwatchedCount = null)

        assertTrue(lines[0].enabled)
        assertFalse(lines[1].enabled)
        assertTrue(lines[2].enabled)
        // `Refresh` is dim on a Source that has never been walked: there is no cached list to
        // catch up, and opening the Source is what fills it. Ticket 13.
        assertFalse(lines[3].enabled)
        // `Rename` is live on a Source that has never been walked, and that is the Source it
        // matters most on: its card is headed by a raw playlist id. Build ticket 27.
        assertTrue(lines[4].enabled)
        // `Remove` is live on a Source that has never been walked. Spec section 6.6 says so,
        // and a Source added by mistake is exactly the one a household wants off its rail.
        assertTrue(lines[5].enabled)
        // The two panels only open a panel, so they are live on every Source. Fork 0.5.
        assertTrue(lines[6].enabled)
        assertTrue(lines[7].enabled)
        assertFalse(lines[8].enabled)
    }

    /**
     * `Refresh` keeps its place above the two lines that change the Source itself.
     *
     * Ticket 10 reserved the last place for the destructive line and ticket 13 put `Refresh`
     * immediately above it. Build ticket 27 put `Rename` between them, which is the owner's
     * decision of 2026-09-20 and is a safety argument: a slip that overshoots `Refresh` lands
     * on the line that asks a question, not on `Remove`.
     */
    @Test
    fun `Rename sits between Refresh and Remove, and each names what it acts on`() {
        val lines = sourceMenuLines(card(lastPlayedVideoId = "v9"), everythingUnwatchedCount = 10)

        assertEquals(SourceMenuAction.REFRESH, lines[3].action)
        assertEquals(SourceMenuAction.RENAME, lines[4].action)
        assertEquals(SourceMenuAction.REMOVE, lines[5].action)
        assertEquals("Refresh this Source", lines[3].label)
        assertEquals("Rename", lines[4].label)
        assertEquals("Remove this Source", lines[5].label)
        assertTrue(lines[3].enabled)
        assertTrue(lines[4].enabled)
    }

    /**
     * The menu is nine lines now, and nothing may quietly grow it again. Fork 0.5 folded
     * the four app-level lines into one `App settings…` panel when the menu reached eleven,
     * which is more than a screen holds, and added `Playback for this Source…` beside it. Build ticket 64 added
     * the eighth, `API key: replace or remove`, on purpose and above the last line. The
     * `Video quality` line is the ninth, above `API key`, the other line about the whole app.
     *
     * The count is asserted on its own because the panel's whole D-pad walk is derived from
     * the dim flags of these lines, and because `Mark everything unwatched` must stay last:
     * that is the one line whose place ticket 10 fixed and every ticket since has kept.
     */
    @Test
    fun `the menu holds nine lines and the destructive one is still last`() {
        val lines = sourceMenuLines(card(lastPlayedVideoId = "v9"), everythingUnwatchedCount = 10)

        assertEquals(9, lines.size)
        assertEquals(SourceMenuAction.MARK_EVERYTHING_UNWATCHED, lines.last().action)
    }

    /**
     * The D-pad's walk over the real seven lines, in both directions.
     *
     * Ticket 31 made the panel derive its whole walk from these dim flags through
     * [menuNeighbour], so a seventh line needs no focus wiring. **This test is what says that
     * is still true**, and it is driven from [sourceMenuLines] rather than from a hand-written
     * list of flags, so the walk and the menu cannot drift apart.
     */
    @Test
    fun `the D-pad walks all nine lines of a walked Source, and stops at both ends`() {
        val live = sourceMenuLines(card(lastPlayedVideoId = "v9"), everythingUnwatchedCount = 10)
            .map { it.enabled }

        val down = generateSequence(0) { menuNeighbour(live, it, MenuMove.DOWN) }.toList()
        val up = generateSequence(8) { menuNeighbour(live, it, MenuMove.UP) }.toList()

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), down)
        assertEquals(listOf(8, 7, 6, 5, 4, 3, 2, 1, 0), up)
    }

    /**
     * The same walk on the menu of a Source that has never been walked, which is the hard one.
     *
     * Three of its seven lines are dim, and one of the dim ones is the **last** drawn line,
     * which is the case ticket 31 found unguarded. `Rename` is live there, so the household can
     * name a card that is headed by a raw playlist id before opening it.
     */
    @Test
    fun `the walk skips every dim line on a Source that has never been walked`() {
        val fresh = card(walked = false, listedCount = 0, unwatchedCount = 0, nextUnwatched = null)
        val live = sourceMenuLines(fresh, everythingUnwatchedCount = null).map { it.enabled }

        assertEquals(listOf(true, false, true, false, true, true, true, true, false), live)

        val down = generateSequence(0) { menuNeighbour(live, it, MenuMove.DOWN) }.toList()
        val up = generateSequence(7) { menuNeighbour(live, it, MenuMove.UP) }.toList()

        assertEquals(listOf(0, 2, 4, 5, 6, 7), down)
        assertEquals(listOf(7, 6, 5, 4, 2, 0), up)
    }

    /** The app settings panel's lines say the setting each is on, so a press is never a guess. */
    @Test
    fun `the app settings panel names every setting`() {
        val lines = appSettingsLines(VideoQuality.P720, childMode = true)

        assertEquals(
            listOf(
                AppSettingsAction.VIDEO_QUALITY to "Video quality: ‹ 720p ›",
                AppSettingsAction.CHILD_MODE to "Child mode: On",
                AppSettingsAction.PHONE_REMOTE to PHONE_REMOTE_LINE,
                AppSettingsAction.API_KEY to API_KEY_LINE,
            ),
            lines,
        )
    }

    /** The playback panel's lines carry arrows only where `Left` or `Right` can still move. */
    @Test
    fun `the playback panel names every setting with its arrows`() {
        val lines = playbackSettingsLines(PlaybackSettings())

        assertEquals("Picture: App setting ›", lines[0].second)
        assertEquals("Speed: ‹ 1× ›", lines[1].second)
        assertEquals("Skip the start: Off ›", lines[2].second)
        assertEquals("Skip the end: Off ›", lines[3].second)

        val set = PlaybackSettings(qualityName = "P480", rate = 2f, skipStartSeconds = 180, skipEndSeconds = 15)
        val lines2 = playbackSettingsLines(set)
        assertEquals("Picture: ‹ 480p", lines2[0].second)
        assertEquals("Speed: ‹ 2×", lines2[1].second)
        assertEquals("Skip the start: ‹ 180 s", lines2[2].second)
        assertEquals("Skip the end: ‹ 15 s ›", lines2[3].second)
    }

    @Test
    fun `the picture line walks from the app setting through every cap and stops`() {
        var p = PlaybackSettings()
        val seen = mutableListOf<String?>()
        repeat(6) { p = stepPicture(p, right = true); seen += p.qualityName }
        assertEquals(listOf("AUTO", "P1080", "P720", "P480", "P480", "P480"), seen)
        assertEquals(null, stepPicture(PlaybackSettings(qualityName = "AUTO"), right = false).qualityName)
    }

    /** Only one line of this menu is ever dim on a Source the household has actually walked. */
    @Test
    fun `a walked Source with a Last played has every line live`() {
        val lines = sourceMenuLines(card(lastPlayedVideoId = "v9"), everythingUnwatchedCount = 10)

        assertEquals(emptyList<SourceMenuAction>(), lines.filterNot { it.enabled }.map { it.action })
    }
}

/**
 * The confirmation in front of `Mark everything unwatched`.
 */
class MarkEverythingUnwatchedTextTest {

    @Test
    fun `the question names the count and the Source`() {
        val detail = markEverythingUnwatchedDetail("TED", 588)

        assertTrue(detail, detail.contains("588 videos"))
        assertTrue(detail, detail.contains("TED"))
    }

    /** The household is told the other thing the action does, not only the count. */
    @Test
    fun `the question says that Last played is lost too`() {
        val detail = markEverythingUnwatchedDetail("TED", 588)

        assertTrue(detail, detail.contains("last played"))
    }

    @Test
    fun `the line that acts states the count again`() {
        assertEquals("Yes, mark 588 videos unwatched", markEverythingUnwatchedYes(588))
        assertEquals("Yes, mark 1 video unwatched", markEverythingUnwatchedYes(1))
    }
}

/**
 * The one logcat line that proves the rail's order without a screenshot.
 */
class RailLogLineTest {

    @Test
    fun `it names every card in the order it was drawn`() {
        val first = card(unwatchedCount = 4)
        val second = SourceCard(
            sourceId = "UUy",
            title = null,
            direction = Direction.NEWEST_FIRST,
            listedCount = 3,
            unwatchedCount = 0,
            nextUnwatched = null,
            lastPlayedVideoId = null,
            everythingUnwatchedCount = 3,
            walked = true,
        )

        assertEquals(
            "Home rail, most recently played first: UUx (4 UNWATCHED), UUy (ALL WATCHED).",
            railLogLine(listOf(first, second)),
        )
    }
}

/**
 * The two lines the rail writes about what it cost.
 *
 * [railReadLine] is the video read alone and [railLoadLine] is the whole build. Build ticket
 * 22 lost a session to the difference: 1,327 ms of `railReadLine` had another 1,670 to 1,820
 * ms sitting beside it that no line reported.
 */
class RailCostLineTest {

    @Test
    fun `the read line names the Sources, the videos and the milliseconds`() {
        assertEquals(
            "Home rail launch read: 6051 listed videos across 4 Sources in 462 ms.",
            railReadLine(sources = 4, videos = 6051, millis = 462),
        )
    }

    @Test
    fun `the read line says video and Source in the singular when there is one of each`() {
        assertEquals(
            "Home rail launch read: 1 listed video across 1 Source in 3 ms.",
            railReadLine(sources = 1, videos = 1, millis = 3),
        )
    }

    @Test
    fun `the load line names every part, and the total is their sum`() {
        val parts = RailLoadParts(derive = 29, sources = 9, videos = 482, watches = 603, cards = 126)

        assertEquals(1249, parts.total)
        assertEquals(
            "Home rail built in 1249 ms: derive 29, Sources 9, videos 482, watches 603, " +
                "cards 126.",
            railLoadLine(parts),
        )
    }

    @Test
    fun `the stale card line names the Source and the video that could not be read`() {
        assertEquals(
            "Home rail read UUx again: it counted v9 as its Next unwatched and the store no " +
                "longer lists that video.",
            railStaleCardLine("UUx", "v9"),
        )
    }
}
