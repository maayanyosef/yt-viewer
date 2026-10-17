package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video the household has a Watch for, as the derived table would report it. */
private fun watched(videoId: String) = WatchState(
    videoId = videoId,
    watched = true,
    positionSeconds = null,
    completed = true,
    lastPlayedAt = Instant.parse("2026-09-20T12:00:00Z"),
)

private fun watches(vararg videoIds: String): Map<String, WatchState> =
    videoIds.associateWith { watched(it) }

/**
 * Build ticket 40: a **Newly listed** video is Next unwatched, until the household moves past
 * it.
 *
 * The rule in one sentence: in a newest-first Source a video the app has only just listed is
 * treated as if it sat immediately after the high-water mark, as the mark stood when the app
 * listed it. See [nextUnwatchedLanding] and `CONTEXT.md`, which calls the state Newly listed.
 *
 * The videos below are the worked example from the ticket. Newest first, so M is the first
 * card and E is the last:
 *
 * | Position | Video | Day | State |
 * | --- | --- | --- | --- |
 * | 0 | M | 80 | newly listed |
 * | 1 | N | 78 | newly listed |
 * | 2 | A | 68 | skipped long ago |
 * | 3 | B | 63 | skipped long ago |
 * | 4 | C | 59 | watched, the high-water mark |
 * | 5 | D | 50 | not watched |
 * | 6 | E | 45 | not watched |
 */
class NewlyListedLandingTest {

    private fun source(
        mNewlyListed: Boolean = true,
        nNewlyListed: Boolean = true,
    ) = listOf(
        testVideo("M", day = 80, newlyListed = mNewlyListed),
        testVideo("N", day = 78, newlyListed = nNewlyListed),
        testVideo("A", day = 68),
        testVideo("B", day = 63),
        testVideo("C", day = 59),
        testVideo("D", day = 50),
        testVideo("E", day = 45),
    )

    private fun landing(
        videos: List<StoredVideo>,
        watched: Map<String, WatchState>,
        direction: Direction = Direction.NEWEST_FIRST,
    ) = nextUnwatchedLanding(
        videos = videos,
        watches = watched,
        direction = direction,
        newlyListed = { it.newlyListed },
    ) { it.canBeLandedOn }

    @Test
    fun `before the two arrived, Next unwatched was D and the badge was 2`() {
        // The ticket's starting position, and the whole of the old rule. It is here so that
        // every number below can be read against something.
        val withoutTheNewOnes = source().filter { it.videoId != "M" && it.videoId != "N" }

        val answer = landing(withoutTheNewOnes, watches("C"))

        assertEquals("D", withoutTheNewOnes[answer.index!!].videoId)
        assertEquals(2, answer.count)
    }

    @Test
    fun `a newly listed video above the mark is Next unwatched`() {
        val videos = source()

        val answer = landing(videos, watches("C"))

        assertEquals("M", videos[answer.index!!].videoId)
        assertEquals(4, answer.count)
        // The mark itself did not move. C is still the furthest the household has worked.
        assertEquals("C", videos[answer.highWaterMark!!].videoId)
    }

    @Test
    fun `the index is into the list that was handed in, not into the effective order`() {
        // The whole point of mapping the answer back: no caller has to know about step 2.
        val videos = source()

        assertEquals(0, landing(videos, watches("C")).index)
    }

    @Test
    fun `playing the newer of two newly listed videos leaves the older one offered`() {
        // M was played, so a play cleared its flag and nothing else: N is older, and a play
        // never reaches a video above it.
        val videos = source(mNewlyListed = false)

        val answer = landing(videos, watches("C", "M"))

        assertEquals("N", videos[answer.index!!].videoId)
        assertEquals(3, answer.count)
    }

    @Test
    fun `playing the older of two newly listed videos skips the newer one for ever`() {
        // N was played, and a play at or past M ends M's Newly listed state too. M is now an
        // ordinary skipped video, exactly as the owner's own principle asks.
        val videos = source(mNewlyListed = false, nNewlyListed = false)

        val answer = landing(videos, watches("C", "N"))

        assertEquals("D", videos[answer.index!!].videoId)
        assertEquals(2, answer.count)
    }

    @Test
    fun `playing D skips both of them`() {
        val videos = source(mNewlyListed = false, nNewlyListed = false)

        val answer = landing(videos, watches("C", "N", "D"))

        assertEquals("E", videos[answer.index!!].videoId)
        assertEquals(1, answer.count)
    }

    @Test
    fun `playing a video above a newly listed one leaves it offered`() {
        // A is above N and was skipped long ago. The household changes its mind and plays it.
        // A play only ends what it moved past, so N keeps its flag and is still offered.
        val videos = source(mNewlyListed = false)

        val answer = landing(videos, watches("C", "A"))

        assertEquals("N", videos[answer.index!!].videoId)
        // And the mark did not move backwards onto A.
        assertEquals("C", videos[answer.highWaterMark!!].videoId)
    }

    @Test
    fun `a newly listed video below the mark is an ordinary gap and nothing moves`() {
        // D is after the mark already, so step 2 has nothing to do and the answer is the same
        // whether the flag is set or not.
        val videos = listOf(
            testVideo("A", day = 68),
            testVideo("C", day = 59),
            testVideo("D", day = 50, newlyListed = true),
            testVideo("E", day = 45),
        )
        val plain = videos.map { it.copy(newlyListed = false) }

        assertEquals(landing(plain, watches("C")), landing(videos, watches("C")))
        assertEquals("D", videos[landing(videos, watches("C")).index!!].videoId)
    }

    @Test
    fun `a video skipped before this ticket is still never offered`() {
        // A and B carry no flag. Ticket 30's behaviour is untouched: they are behind the
        // household and no landing reaches them while anything is ahead.
        val videos = source()

        val answer = landing(videos, watches("C"))

        assertFalse(videos[answer.index!!].videoId in setOf("A", "B"))
        // Four videos are still ahead: M, N, D and E. A and B are not among them.
        assertEquals(4, answer.count)
    }

    @Test
    fun `a Source with no Watch at all is unchanged, flag or no flag`() {
        // There is no mark, so there is nothing to sit after. The first gap is the first
        // video either way. `Lofi Girl` is this Source on the household set.
        val videos = source()
        val plain = videos.map { it.copy(newlyListed = false) }

        assertEquals(landing(plain, emptyMap()), landing(videos, emptyMap()))
        assertEquals(0, landing(videos, emptyMap()).index)
        assertEquals(7, landing(videos, emptyMap()).count)
    }

    @Test
    fun `every video watched still means nothing is left, flag or no flag`() {
        val videos = source()
        val everyOne = watches("M", "N", "A", "B", "C", "D", "E")

        val answer = landing(videos, everyOne)

        assertNull(answer.index)
        assertEquals(0, answer.count)
    }

    @Test
    fun `the fallback does not fire while a newly listed video is waiting`() {
        // Everything after the mark is watched, so the old rule would fall back to the first
        // gap in the whole Source, which is A. The newly listed video is ahead of the mark in
        // the effective order, so there is something ahead and the fallback stays off.
        val videos = source(nNewlyListed = false)

        val answer = landing(videos, watches("C", "D", "E", "N"))

        assertEquals("M", videos[answer.index!!].videoId)
        assertEquals(1, answer.count)
    }

    @Test
    fun `the fallback still fires when no newly listed video is left`() {
        val videos = source(mNewlyListed = false, nNewlyListed = false)

        val answer = landing(videos, watches("C", "D", "E", "N"))

        assertEquals("M", videos[answer.index!!].videoId)
        // Every gap in the Source: M, A and B.
        assertEquals(3, answer.count)
    }

    @Test
    fun `a newly listed video a Bulk mark covers arrives watched and is never offered`() {
        // ADR 0001: a range covers a video listed later with no second action. Such a video
        // is not a gap, so nothing offers it, and this needs no code of its own.
        val videos = source(nNewlyListed = false)

        val answer = landing(videos, watches("C", "M"))

        assertEquals("D", videos[answer.index!!].videoId)
        assertEquals(2, answer.count)
        // And the mark has not gone backwards onto the covered video.
        assertEquals("C", videos[answer.highWaterMark!!].videoId)
    }

    @Test
    fun `a Bulk mark that does not cover a newly listed video leaves it offered`() {
        // The household bulk marked the older backlog. The range never reaches M, which was
        // listed today, so M is still a gap and is still offered.
        val videos = source(nNewlyListed = false)

        val answer = landing(videos, watches("C", "D", "E"))

        assertEquals("M", videos[answer.index!!].videoId)
    }

    @Test
    fun `an unlisted newly listed video is never offered`() {
        // A Short, an unavailable video and an upcoming premiere are not landing points, and
        // the flag does not change that. The rule is `canBeLandedOn` and it runs first.
        val videos = listOf(
            testVideo("short", day = 80, isShort = true, newlyListed = true),
            testVideo("A", day = 68),
            testVideo("C", day = 59),
            testVideo("D", day = 50),
        )

        val answer = landing(videos, watches("C"))

        assertEquals("D", videos[answer.index!!].videoId)
    }
}

/**
 * The oldest-first half of build ticket 40: nothing changes at all.
 *
 * It is not a special case in the code. `orderByPublishDate` puts the newest video **last** in
 * an oldest-first Source, so a new video already arrives after the mark and is an ordinary
 * gap. The exception is switched off by Direction, which is the owner's decision of 2026-09-21:
 * a test on position would offer a video published months ago and listed today, and his rule
 * for that Direction is that nothing changes.
 */
class NewlyListedOldestFirstTest {

    /** Oldest first, so the oldest video is the first card. */
    private val videos = listOf(
        testVideo("E", day = 45),
        testVideo("D", day = 50),
        testVideo("C", day = 59),
        testVideo("B", day = 63),
        testVideo("A", day = 68),
        testVideo("N", day = 78),
    )

    private fun landing(videos: List<StoredVideo>) = nextUnwatchedLanding(
        videos = videos,
        watches = watches("C"),
        direction = Direction.OLDEST_FIRST,
        newlyListed = { it.newlyListed },
    ) { it.canBeLandedOn }

    private fun flag(vararg videoIds: String) =
        videos.map { if (it.videoId in videoIds) it.copy(newlyListed = true) else it }

    @Test
    fun `the landing and the count are identical with the flag set above the mark`() {
        // E is above the mark in this Direction: it is an earlier position than C.
        assertEquals(landing(videos), landing(flag("E")))
    }

    @Test
    fun `the landing and the count are identical with the flag set below the mark`() {
        assertEquals(landing(videos), landing(flag("N")))
    }

    @Test
    fun `the landing and the count are identical with the flag set on both sides at once`() {
        assertEquals(landing(videos), landing(flag("E", "N")))
    }

    @Test
    fun `a new video arrives after the mark by itself, which is why no exception is needed`() {
        // The answer is the first gap after C, which is B, and the new video N is simply
        // counted with the rest of the tail.
        val answer = landing(flag("N"))

        assertEquals("B", videos[answer.index!!].videoId)
        assertEquals(3, answer.count)
    }
}

/**
 * The badge and the Next unwatched slot still agree, and both still come from one call.
 *
 * Ticket 23 made them agree and build ticket 30 had to do it again. Build ticket 40 threads
 * the Direction and the flag into the one function both read, [nextUnwatchedLanding], rather
 * than writing a second copy of the rule in `railCardCounts`.
 */
class NewlyListedRailCardTest {

    private val videos = listOf(
        testVideo("M", day = 80, newlyListed = true),
        testVideo("A", day = 68),
        testVideo("C", day = 59),
        testVideo("D", day = 50),
        testVideo("E", day = 45),
    )

    private val stored = StoredSource(
        sourceId = "UUx",
        title = "A Source",
        lastWalkedAt = Instant.parse("2026-09-21T10:00:00Z"),
        direction = Direction.NEWEST_FIRST,
    )

    @Test
    fun `the card names the newly listed video and counts it`() {
        val card = sourceCard(
            sourceId = "UUx",
            stored = stored,
            videos = videos,
            watches = watches("C"),
            lastPlayedVideoId = null,
        )

        assertEquals("M", card.nextUnwatched?.videoId)
        // The card draws the video whole, so its thumbnail and its title are the new video's.
        assertEquals("Video M", card.nextUnwatched?.title)
        // M, D and E are ahead. A is behind the mark.
        assertEquals(3, card.unwatchedCount)
    }

    @Test
    fun `the narrow read the rail really makes gives the same answer`() {
        // `listedVideoKeys` carries the flag as well, which is why it is on [VideoKey]. A
        // card that could not see it would disagree with the Source screen.
        val keys = videos.map {
            ListedVideoKey(
                videoId = it.videoId,
                sourceId = it.sourceId,
                videoPublishedAt = it.videoPublishedAt,
                newlyListed = it.newlyListed,
            )
        }

        val counts = railCardCounts(
            listed = keys,
            watches = watches("C"),
            lastPlayedVideoId = null,
            direction = Direction.NEWEST_FIRST,
        )

        assertEquals("M", counts.nextUnwatchedVideoId)
        assertEquals(3, counts.unwatchedCount)
    }

    @Test
    fun `an oldest-first card does not move`() {
        val oldestFirst = listOf(
            testVideo("E", day = 45),
            testVideo("D", day = 50),
            testVideo("C", day = 59),
            testVideo("A", day = 68),
            testVideo("M", day = 80, newlyListed = true),
        )

        val card = sourceCard(
            sourceId = "UUx",
            stored = stored.copy(direction = Direction.OLDEST_FIRST),
            videos = oldestFirst,
            watches = watches("C"),
            lastPlayedVideoId = null,
        )

        assertEquals("A", card.nextUnwatched?.videoId)
        assertEquals(2, card.unwatchedCount)
    }
}

/**
 * What ends the Newly listed state: a `played` entry, and nothing else.
 *
 * The rule is [SourceStore.clearNewlyListedUpTo] and the two callers are in [WatchLog]. The
 * store here is [InMemorySourceStore], which applies the same two date tests
 * `SqliteSourceStore` writes in SQL.
 */
class NewlyListedClearingTest {

    private val sources = InMemorySourceStore()
    private val watchStore = InMemoryWatchStore()
    private val log = WatchLog(watchStore, sources)

    private val m = testVideo("M", day = 80, newlyListed = true)
    private val n = testVideo("N", day = 78, newlyListed = true)
    private val a = testVideo("A", day = 68)
    private val c = testVideo("C", day = 59)
    private val d = testVideo("D", day = 50)

    private fun open(direction: Direction = Direction.NEWEST_FIRST) {
        sources.saveSource(
            StoredSource(
                sourceId = "UUx",
                title = "A Source",
                lastWalkedAt = Instant.parse("2026-09-21T10:00:00Z"),
                direction = direction,
            ),
        )
        sources.replaceVideos("UUx", listOf(m, n, a, c))
    }

    private fun stillNewlyListed(): Set<String> =
        sources.allVideos("UUx").filter { it.newlyListed }.mapTo(mutableSetOf()) { it.videoId }

    @Test
    fun `a real play ends it for the video played and for everything at or past it`() {
        open()

        log.startPlay(n, positionSeconds = 30, completed = false)

        // Newest first: at or past N means published no earlier than N, which is M and N.
        assertEquals(emptySet<String>(), stillNewlyListed())
    }

    @Test
    fun `a real play above a newly listed video leaves that video alone`() {
        open()

        log.startPlay(m, positionSeconds = 30, completed = false)

        assertEquals(setOf("N"), stillNewlyListed())
    }

    @Test
    fun `Mark as watched on one card ends it, because it writes a played entry`() {
        open()

        log.markJustThis(n, watched = true)

        assertEquals(emptySet<String>(), stillNewlyListed())
    }

    @Test
    fun `Mark as unwatched ends nothing, because it writes no played entry`() {
        open()

        log.markJustThis(n, watched = false)

        assertEquals(setOf("M", "N"), stillNewlyListed())
    }

    @Test
    fun `a Bulk mark ends nothing at all`() {
        // The owner's decision of 2026-09-21. A Bulk mark over an old backlog does not reach a
        // video listed today, and under any other answer that video would be retired for ever
        // without ever having been shown.
        open()

        log.markRange(pivot = a, side = RangeSide.OLDER, watched = true)
        log.markRange(pivot = c, side = RangeSide.NEWER, watched = true)

        assertEquals(setOf("M", "N"), stillNewlyListed())
    }

    @Test
    fun `an oldest-first Source clears the other way round`() {
        open(Direction.OLDEST_FIRST)

        // At or past N in this Direction means published no later than N: N, A and C.
        log.startPlay(n, positionSeconds = 30, completed = false)

        assertEquals(setOf("M"), stillNewlyListed())
    }

    @Test
    fun `playing a video with no publish date writes nothing to the video cache`() {
        // A private or deleted video has no date, so there is no pivot to compare against.
        open()
        val undated = testVideo("gone", day = null)

        log.startPlay(undated, positionSeconds = 30, completed = false)

        assertEquals(setOf("M", "N"), stillNewlyListed())
    }

    @Test
    fun `a play in one Source does not reach another Source`() {
        open()
        sources.saveSource(
            StoredSource(sourceId = "UUy", title = null, lastWalkedAt = null),
        )
        sources.replaceVideos("UUy", listOf(testVideo("other", day = 5, newlyListed = true)))

        log.startPlay(n, positionSeconds = 30, completed = false)

        assertTrue(sources.allVideos("UUy").single().newlyListed)
    }

    @Test
    fun `the log is still written when there is no video cache behind it`() {
        // Every test that only exercises the log passes no store. The play still lands.
        val logAlone = WatchLog(InMemoryWatchStore())

        assertTrue(logAlone.startPlay(n, positionSeconds = 30, completed = false) != null)
    }

    @Test
    fun `the household watches C, a walk lists M and N, then a play ends it`() {
        // The whole of the ticket in one test, in the order it really happens.
        sources.saveSource(
            StoredSource(
                sourceId = "UUx",
                title = "A Source",
                lastWalkedAt = Instant.parse("2026-09-21T10:00:00Z"),
                direction = Direction.NEWEST_FIRST,
            ),
        )
        // The Source as it stood: A skipped, C watched, D still ahead, and neither M nor N
        // listed yet.
        sources.replaceVideos("UUx", listOf(a, c, d))
        log.markJustThis(c, watched = true)
        // The walk that lists M and N. `mergeRefresh` is what sets the flag on the real path;
        // here the two videos arrive already carrying it.
        sources.replaceVideos("UUx", listOf(m, n, a, c, d))

        assertEquals("M", landingVideoId())

        // The household plays N, passing over M.
        log.startPlay(n, positionSeconds = 30, completed = false)

        // N is watched and M was passed over, so the household lands on the first gap after
        // the mark, which is D. M is an ordinary skipped video now.
        assertEquals("D", landingVideoId())
        assertFalse(sources.allVideos("UUx").any { it.newlyListed })
    }

    /** Where Next unwatched lands in this Source right now, by video id. */
    private fun landingVideoId(): String? {
        val videos = sources.listedVideos("UUx")
        val landing = nextUnwatchedLanding(
            videos = videos,
            watches = log.watchStates(videos),
            direction = Direction.NEWEST_FIRST,
            newlyListed = { it.newlyListed },
        ) { it.canBeLandedOn }
        return landing.index?.let { videos[it].videoId }
    }
}

/**
 * The in-memory twin of the clearing rule: [clearedNewlyListed].
 *
 * A play clears the Newly listed flag in storage for the video played and everything at or
 * past it. The home rail rebuilds from storage, so it sees that at once. A screen holding its
 * own list of videos does not, and would go on offering a video the rail has already dropped.
 *
 * Found by `/code-review high` on 2026-09-21, after build ticket 40 had been closed. These
 * tests pin the two rules against each other: the SQL in `SqliteSourceStore` uses
 * `video_published_at >= ?` newest-first and `<= ?` oldest-first, and so does this.
 */
class ClearedNewlyListedTest {

    private val newestFirst = listOf(
        testVideo("n5", day = 5, newlyListed = true),
        testVideo("n4", day = 4, newlyListed = true),
        testVideo("n3", day = 3, newlyListed = true),
        testVideo("n2", day = 2),
        testVideo("n1", day = 1),
    )

    private fun flags(videos: List<StoredVideo>) =
        videos.filter { it.newlyListed }.map { it.videoId }.toSet()

    @Test
    fun `newest first clears the video played and everything newer than it`() {
        val after = clearedNewlyListed(newestFirst, Direction.NEWEST_FIRST, testDay(4))

        assertEquals(setOf("n3"), flags(after))
    }

    @Test
    fun `newest first playing the oldest newly listed video clears all of them`() {
        val after = clearedNewlyListed(newestFirst, Direction.NEWEST_FIRST, testDay(3))

        assertEquals(emptySet<String>(), flags(after))
    }

    /**
     * Newest-first, playing an **old** video clears every Newly listed video above it.
     *
     * This reads backwards until Direction is held in mind. The browse order runs newest to
     * oldest, so an older video is **further along** it. Playing day 1 therefore means the
     * household has worked past days 3, 4 and 5, and all three stop being offered. It is the
     * same rule the SQL states as `video_published_at >= ?`.
     */
    @Test
    fun `newest first playing the oldest video of all clears every newly listed video`() {
        val after = clearedNewlyListed(newestFirst, Direction.NEWEST_FIRST, testDay(1))

        assertEquals(emptySet<String>(), flags(after))
    }

    /** Playing the newest video of all clears only that one. Nothing sits above it. */
    @Test
    fun `newest first playing the newest video clears only that video`() {
        val after = clearedNewlyListed(newestFirst, Direction.NEWEST_FIRST, testDay(5))

        assertEquals(setOf("n3", "n4"), flags(after))
    }

    @Test
    fun `oldest first clears the video played and everything older than it`() {
        val oldestFirst = listOf(
            testVideo("o1", day = 1, newlyListed = true),
            testVideo("o2", day = 2, newlyListed = true),
            testVideo("o3", day = 3, newlyListed = true),
            testVideo("o4", day = 4),
        )

        val after = clearedNewlyListed(oldestFirst, Direction.OLDEST_FIRST, testDay(2))

        assertEquals(setOf("o3"), flags(after))
    }

    @Test
    fun `a video with no publish date is never cleared`() {
        val withUndated = newestFirst + testVideo("undated", day = null, newlyListed = true)

        val after = clearedNewlyListed(withUndated, Direction.NEWEST_FIRST, testDay(1))

        assertTrue(after.first { it.videoId == "undated" }.newlyListed)
    }

    @Test
    fun `a play with no publish date clears nothing`() {
        val after = clearedNewlyListed(newestFirst, Direction.NEWEST_FIRST, null)

        assertEquals(setOf("n3", "n4", "n5"), flags(after))
    }

    @Test
    fun `a list with no flag at all comes back as the very same list`() {
        val plain = newestFirst.map { it.copy(newlyListed = false) }

        val after = clearedNewlyListed(plain, Direction.NEWEST_FIRST, testDay(3))

        assertSame(plain, after)
    }

    @Test
    fun `nothing but the flag changes`() {
        val after = clearedNewlyListed(newestFirst, Direction.NEWEST_FIRST, testDay(3))

        assertEquals(
            newestFirst.map { it.copy(newlyListed = false) },
            after,
        )
    }
}

/**
 * The two statements of the clearing rule, held against each other.
 *
 * The rule — a play ends the Newly listed state of the video played and of everything at or
 * past it, in the Source's Direction — is written down **three** times in this app:
 *
 * - `SqliteSourceStore.clearNewlyListedUpTo`, as one SQL `UPDATE` whose comparison turns
 *   around with the Direction. That is what runs on the television.
 * - `InMemorySourceStore.clearNewlyListedUpTo`, the JVM twin that every test above drives.
 * - [clearedNewlyListed], added on 2026-09-21 so that the Source screen can do to its own
 *   list of videos what a play has already done in storage.
 *
 * `ClearedNewlyListedTest` above checks the third against expectations written out by hand,
 * and `NewlyListedClearingTest` checks the second the same way. Neither compares one against
 * the other, so an edit to one of them would leave the other green and the two would drift.
 * The screen and the home rail would then disagree about which video is Next unwatched, which
 * is the exact defect `d33b1e2` was written to close.
 *
 * These tests close that. Every case runs both and asserts they flag the same videos. The
 * comparison is a **set of video ids**, not a list, because the store hands its videos back
 * in its own order; that the two agree on a set is also the proof of [clearedNewlyListed]'s
 * own claim that it reads dates and never list positions.
 *
 * The SQL is not reachable from a JVM test. It is covered by
 * `app/src/androidTest/kotlin/local/youtubetv/data/ReplaceVideosNewlyListedTest.kt`.
 */
class ClearingRuleAgreesWithTheStoreTest {

    private val videos = listOf(
        testVideo("d5", day = 5, newlyListed = true),
        testVideo("d4", day = 4, newlyListed = true),
        testVideo("d3", day = 3, newlyListed = true),
        testVideo("d2", day = 2, newlyListed = true),
        testVideo("d1", day = 1, newlyListed = true),
    )

    private val withUndated = videos + testVideo("undated", day = null, newlyListed = true)

    /** The ids still carrying the flag, whatever order the list is in. */
    private fun flagged(videos: List<StoredVideo>) =
        videos.filter { it.newlyListed }.map { it.videoId }.toSet()

    /**
     * Runs both statements of the rule over the same videos and gives back the store's answer,
     * having first asserted that the two agree.
     *
     * The store is built fresh each time, because `clearNewlyListedUpTo` writes.
     */
    private fun bothAgree(
        videos: List<StoredVideo>,
        direction: Direction,
        pivot: Instant?,
    ): Set<String> {
        val store = InMemorySourceStore()
        store.saveSource(
            StoredSource(
                sourceId = "UUx",
                title = "A Source",
                lastWalkedAt = null,
                direction = direction,
            ),
        )
        store.replaceVideos("UUx", videos)
        store.clearNewlyListedUpTo("UUx", pivot)
        val fromTheStore = flagged(store.allVideos("UUx"))

        val inMemory = flagged(clearedNewlyListed(videos, direction, pivot))

        assertEquals(
            "clearedNewlyListed and SourceStore.clearNewlyListedUpTo disagree",
            fromTheStore,
            inMemory,
        )
        return fromTheStore
    }

    @Test
    fun `newest first, a pivot in the middle`() {
        assertEquals(
            setOf("d2", "d1"),
            bothAgree(videos, Direction.NEWEST_FIRST, testDay(3)),
        )
    }

    @Test
    fun `newest first, the newest video of all`() {
        assertEquals(
            setOf("d4", "d3", "d2", "d1"),
            bothAgree(videos, Direction.NEWEST_FIRST, testDay(5)),
        )
    }

    @Test
    fun `newest first, the oldest video of all clears every one of them`() {
        assertEquals(
            emptySet<String>(),
            bothAgree(videos, Direction.NEWEST_FIRST, testDay(1)),
        )
    }

    @Test
    fun `oldest first, a pivot in the middle`() {
        assertEquals(
            setOf("d5", "d4"),
            bothAgree(videos, Direction.OLDEST_FIRST, testDay(3)),
        )
    }

    @Test
    fun `oldest first, the newest video of all clears every one of them`() {
        assertEquals(
            emptySet<String>(),
            bothAgree(videos, Direction.OLDEST_FIRST, testDay(5)),
        )
    }

    @Test
    fun `oldest first, the oldest video of all`() {
        assertEquals(
            setOf("d5", "d4", "d3", "d2"),
            bothAgree(videos, Direction.OLDEST_FIRST, testDay(1)),
        )
    }

    @Test
    fun `a video with no publish date is left alone by both, in both Directions`() {
        assertTrue("undated" in bothAgree(withUndated, Direction.NEWEST_FIRST, testDay(1)))
        assertTrue("undated" in bothAgree(withUndated, Direction.OLDEST_FIRST, testDay(5)))
    }

    @Test
    fun `a play with no publish date clears nothing, in both Directions`() {
        val everyId = withUndated.map { it.videoId }.toSet()

        assertEquals(everyId, bothAgree(withUndated, Direction.NEWEST_FIRST, null))
        assertEquals(everyId, bothAgree(withUndated, Direction.OLDEST_FIRST, null))
    }

    /**
     * The sweep. Every video's own date as the pivot, in both Directions, with the undated
     * video in the list.
     *
     * This is the test that actually stops the two drifting, because it needs no author to
     * think of the case that broke. A boundary is covered by construction: every pivot here is
     * some video's exact publish date, and "at or past" includes "at".
     */
    @Test
    fun `the two agree on every pivot in the Source, in both Directions`() {
        for (direction in Direction.entries) {
            for (video in withUndated) {
                bothAgree(withUndated, direction, video.videoPublishedAt)
            }
        }
    }

    /**
     * [clearedNewlyListed] reads dates and never list positions, so the order it is handed
     * the videos in cannot change its answer.
     *
     * The KDoc claims this in as many words, and the claim is what lets the Source screen call
     * it on whatever order the grid happens to be holding after a Direction flip.
     */
    @Test
    fun `the answer does not depend on the order the list is handed in`() {
        for (direction in Direction.entries) {
            for (video in withUndated) {
                val pivot = video.videoPublishedAt
                assertEquals(
                    flagged(clearedNewlyListed(withUndated, direction, pivot)),
                    flagged(clearedNewlyListed(withUndated.reversed(), direction, pivot)),
                )
                assertEquals(
                    flagged(clearedNewlyListed(withUndated, direction, pivot)),
                    flagged(clearedNewlyListed(withUndated.shuffled(), direction, pivot)),
                )
            }
        }
    }
}

/**
 * A walk cannot put back a Newly listed flag a play has already cleared.
 *
 * `SourceRefresh.refreshOne` reads the stored videos, then spends minutes on network calls on
 * a full walk, then writes the merged list back. `mergeRefresh` carries `newlyListed` forward
 * from that old snapshot, and `replaceVideos` deletes every row of the Source and inserts them
 * again. A play during the walk clears the flag in storage, and the stale `true` was then
 * written straight back, so the video was offered as Next unwatched a second time.
 *
 * Commit `2838f1c` put the rule in `SqliteSourceStore.replaceVideos` and said no JVM test
 * could reach it. That was true only because [InMemorySourceStore.replaceVideos] was a plain
 * assignment and did not apply the rule, which `/code-review high` found on 2026-09-21. The
 * fake applies it now, so the rule is tested here as well as on the emulator.
 *
 * The SQL itself is covered by
 * `app/src/androidTest/kotlin/local/youtubetv/data/ReplaceVideosNewlyListedTest.kt`, which
 * asserts the same six things against the real store.
 */
class ReplaceVideosKeepsClearedFlagsClearedTest {

    private val store = InMemorySourceStore()

    private val fresh = listOf(
        testVideo("v3", day = 3, newlyListed = true),
        testVideo("v2", day = 2, newlyListed = true),
        testVideo("v1", day = 1, newlyListed = true),
    )

    private fun flagged() =
        store.allVideos("UUx").filter { it.newlyListed }.map { it.videoId }.toSet()

    @Test
    fun `a first listing keeps the flag the walk gave it`() {
        // Nothing is stored, so there is no cleared flag to respect.
        store.replaceVideos("UUx", fresh)

        assertEquals(setOf("v3", "v2", "v1"), flagged())
    }

    @Test
    fun `a walk cannot set the flag again on a video whose flag is already cleared`() {
        store.replaceVideos("UUx", fresh)
        // The play during the walk. It clears v2 and everything newer, newest-first.
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = null))
        store.clearNewlyListedUpTo("UUx", testDay(2))
        assertEquals(setOf("v1"), flagged())

        // The walk finishes and writes the snapshot it took before the play.
        store.replaceVideos("UUx", fresh)

        assertEquals(setOf("v1"), flagged())
    }

    @Test
    fun `the flag travels one way only, however many walks run`() {
        store.replaceVideos("UUx", fresh)
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = null))
        store.clearNewlyListedUpTo("UUx", testDay(1))
        assertEquals(emptySet<String>(), flagged())

        repeat(3) { store.replaceVideos("UUx", fresh) }

        assertEquals(emptySet<String>(), flagged())
    }

    @Test
    fun `a video the walk lists for the first time is unaffected by the rule`() {
        store.replaceVideos("UUx", fresh)
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = null))
        store.clearNewlyListedUpTo("UUx", testDay(1))

        // v4 has no stored row at all, so it has no cleared flag to respect.
        val withNewOne = listOf(testVideo("v4", day = 4, newlyListed = true)) + fresh
        store.replaceVideos("UUx", withNewOne)

        assertEquals(setOf("v4"), flagged())
    }

    @Test
    fun `the rule does not reach another Source`() {
        store.replaceVideos("UUx", fresh)
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = null))
        store.clearNewlyListedUpTo("UUx", testDay(1))

        val other = listOf(testVideo("v1", day = 1, sourceId = "UUy", newlyListed = true))
        store.replaceVideos("UUy", other)

        // The same video id, cleared in `UUx`, keeps its flag in `UUy`.
        assertTrue(store.allVideos("UUy").single().newlyListed)
    }

    @Test
    fun `nothing but the flag is rewritten`() {
        store.replaceVideos("UUx", fresh)
        store.saveSource(StoredSource("UUx", title = null, lastWalkedAt = null))
        store.clearNewlyListedUpTo("UUx", testDay(1))

        store.replaceVideos("UUx", fresh)

        assertEquals(
            fresh.map { it.copy(newlyListed = false) }.sortedBy { it.videoId },
            store.allVideos("UUx").sortedBy { it.videoId },
        )
    }
}
