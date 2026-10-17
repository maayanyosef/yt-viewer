package local.youtubetv.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The moment every Bulk mark in this file is made. Day 200 of the test calendar. */
private val MARK_TIME: Instant = testDay(200)

/**
 * The far end of a `newer` Bulk mark. Ticket 42.
 *
 * A range with `side = newer` used to cover every video published after its pivot, with no end
 * at all. Every future upload has a later publish date, so it arrived already watched and was
 * never offered as Next unwatched. On a Source that is watched newest first, that made the Source silent for ever after one
 * Bulk mark.
 *
 * The rule now has two ends on the `newer` side. See [Range.coversDate]:
 *
 * 1. the video's publish date is not **before** the pivot, which is the old rule, and
 * 2. the video's publish date is not **after** [Range.newerFarEnd], which is the later of the
 *    moment the Bulk mark was made and the pivot's own publish date.
 *
 * **The floor under the far end is part of the rule, not a detail.** Commit `14ce6e9` added
 * it, because a television whose clock reads earlier than the pivot would otherwise write a
 * Bulk mark that covers nothing at all, not even the video the household pressed the button
 * on. Four tests in this file drive that case, so a reader who takes the far end to be the
 * moment the Bulk mark was made and nothing else would read those four as asserting the wrong
 * thing.
 *
 * Nothing was written to the log to make this work. The bound is the entry's own `at`, which
 * every range has carried since the day it was written.
 */
class NewerRangeBoundTest {

    private val store = InMemoryWatchStore()
    private var clock = MARK_TIME
    private val log = WatchLog(store, now = { clock })

    /** Day 1 to day 5 of one Source, oldest first. All published long before [MARK_TIME]. */
    private val videos = (1..5).map { day -> testVideo("v$day", day = day) }

    private fun tick(seconds: Long) {
        clock = clock.plusSeconds(seconds)
    }

    private fun watchedIds(all: List<StoredVideo>): Set<String> =
        log.watchStates(all).values.filter { it.watched }.mapTo(mutableSetOf()) { it.videoId }

    // ------------------------------------------------ The new end of the newer side

    /**
     * The defect the ticket was raised for, in the smallest form there is.
     *
     * The household bulk-marks everything from day 3 onwards as watched. YouTube then publishes
     * a new video. The new video is not covered, so it is unwatched.
     */
    @Test
    fun `a newer Bulk mark does not cover a video published after it was made`() {
        log.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        val newUpload = testVideo("new", day = 201)

        assertNull(log.watchStates(videos + newUpload)["new"])
        assertEquals(setOf("v3", "v4", "v5"), watchedIds(videos + newUpload))
    }

    /** The bound includes its own instant, exactly as the pivot does. */
    @Test
    fun `a video published at the very instant the Bulk mark was made is still covered`() {
        log.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        val twin = testVideo("twin", day = 200)

        assertTrue(log.watchStates(videos + twin).getValue("twin").watched)
    }

    /** One second past the bound is outside it. */
    @Test
    fun `a video published one second after the Bulk mark is outside it`() {
        log.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        // `testVideo` is the one place a StoredVideo is built, which is what TestVideos.kt
        // asks for. Only the publish date needs a sub-day offset, so only it is overridden.
        val justAfter = testVideo("justAfter", day = 200)
            .copy(videoPublishedAt = MARK_TIME.plusSeconds(1))

        assertNull(log.watchStates(videos + justAfter)["justAfter"])
    }

    /**
     * ADR 0001's central case, on the `newer` side, which the new end must not break.
     *
     * An upload made public months late is listed for the first time by a later walk. Its
     * publish date sits between the pivot and the moment the Bulk mark was made, so the Bulk
     * mark covers it with no second Bulk mark and no fix-up code at walk time.
     */
    @Test
    fun `a video published before the Bulk mark but listed later is still covered`() {
        log.markRange(videos[2], side = RangeSide.NEWER, watched = true)
        val entriesAfterTheMark = store.entries().size

        val foundLater = testVideo("late", day = 150)

        assertTrue(log.watchStates(videos + foundLater).getValue("late").watched)
        assertEquals(entriesAfterTheMark, store.entries().size)
    }

    /**
     * A second Bulk mark, made later, reaches what the first one could not.
     *
     * This is how the household gets back to a fully watched Source after a batch of new
     * uploads. It costs one more entry and no migration.
     */
    @Test
    fun `a later Bulk mark covers the videos the earlier one stopped short of`() {
        log.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        val newUpload = testVideo("new", day = 201)
        val all = videos + newUpload

        assertNull(log.watchStates(all)["new"])

        tick(86_400 * 2)
        log.markRange(newUpload, side = RangeSide.NEWER, watched = true)

        assertTrue(log.watchStates(all).getValue("new").watched)
    }

    /** An unwatched `newer` Bulk mark is bounded the same way, and nothing depends on that. */
    @Test
    fun `an unwatched newer Bulk mark is bounded too, and a video outside it is unwatched`() {
        log.markJustThis(videos[4], watched = true)
        tick(60)
        log.markRange(videos[0], side = RangeSide.NEWER, watched = false)

        val newUpload = testVideo("new", day = 201)

        // The video outside the range has no entry of its own, so it is unwatched anyway.
        // That is why bounding an unwatched Bulk mark costs nothing.
        assertNull(log.watchStates(videos + newUpload)["new"])
        assertEquals(emptySet<String>(), watchedIds(videos + newUpload))
    }

    // ------------------------------------------------ The older side is untouched

    @Test
    fun `a watched older Bulk mark covers the same videos as before`() {
        log.markRange(videos[2], side = RangeSide.OLDER, watched = true)

        assertEquals(setOf("v1", "v2", "v3"), watchedIds(videos))
    }

    @Test
    fun `an unwatched older Bulk mark covers the same videos as before`() {
        for (video in videos) log.markJustThis(video, watched = true)
        tick(60)
        log.markRange(videos[2], side = RangeSide.OLDER, watched = false)

        assertEquals(setOf("v4", "v5"), watchedIds(videos))
    }

    /**
     * The `older` side never consults the moment the Bulk mark was made.
     *
     * The case is built with a pivot **after** that moment, which is what a television with a
     * clock set in the past would write. The video sits between the two. A bounded `older`
     * side would drop it. The real `older` side keeps it, because its only end is the pivot.
     */
    @Test
    fun `an older Bulk mark ignores the moment it was made`() {
        val futurePivot = testVideo("future", day = 300)
        log.markRange(futurePivot, side = RangeSide.OLDER, watched = true)

        val afterTheMark = testVideo("afterTheMark", day = 250)

        assertTrue(log.watchStates(listOf(afterTheMark)).getValue("afterTheMark").watched)
    }

    /**
     * Why the `older` side needs no end. Its closed end already faces the direction new videos
     * arrive from, so a future upload can never fall inside it.
     */
    @Test
    fun `a future upload is outside an older Bulk mark for the old reason, not the new one`() {
        log.markRange(videos[2], side = RangeSide.OLDER, watched = true)

        val newUpload = testVideo("new", day = 201)

        assertNull(log.watchStates(videos + newUpload)["new"])
    }

    // ------------------------------------------------ Mark everything unwatched

    /**
     * `Mark everything unwatched` writes `side = newer`, `watched = false`,
     * `clearsLastPlayed = true`, pivoted on the Source's oldest dated video. See
     * [everythingUnwatched]. Every listed video was published before the action, so the new
     * end does not reach any of them.
     */
    @Test
    fun `Mark everything unwatched still marks every listed video unwatched`() {
        for (video in videos) log.markJustThis(video, watched = true)
        tick(60)

        val chosen = everythingUnwatched(videos)!!
        log.markRange(chosen.pivot, side = chosen.side, watched = false, clearsLastPlayed = true)

        assertEquals(5, chosen.count)
        assertEquals(emptySet<String>(), watchedIds(videos))
    }

    @Test
    fun `Mark everything unwatched still clears Last played`() {
        log.startPlay(videos[1], positionSeconds = 30, completed = false)
        tick(60)
        assertEquals("v2", log.lastPlayed("UUx"))

        val chosen = everythingUnwatched(videos)!!
        log.markRange(chosen.pivot, side = chosen.side, watched = false, clearsLastPlayed = true)

        assertNull(log.lastPlayed("UUx"))
    }

    /**
     * `clearsLastPlayed` is still not date-tested, and the new end must not be consulted there
     * either. ADR 0001 says such a range clears Last played for the whole Source, and that its
     * side and its pivot are not looked at.
     *
     * The case is built so that a date test would give the wrong answer: the video that was
     * played is published **after** the moment the Bulk mark was made, so the range does not
     * cover it. Last played is still cleared.
     */
    @Test
    fun `a clearing range still clears Last played for a video its dates do not cover`() {
        val future = testVideo("future", day = 300)
        log.startPlay(future, positionSeconds = 30, completed = false)
        tick(60)
        assertEquals("future", log.lastPlayed("UUx"))

        log.markRange(
            videos[0],
            side = RangeSide.NEWER,
            watched = false,
            clearsLastPlayed = true,
        )

        // The range really does not cover the played video: day 300 is after the moment the
        // range was made, so the play still stands and the video is still watched.
        assertTrue(log.watchStates(listOf(future)).getValue("future").watched)
        // Last played is cleared all the same.
        assertNull(log.lastPlayed("UUx"))
    }

    // ------------------------------------------------ Nothing was written to the log

    /**
     * The bound is the entry's own `at` and is not a payload field. So the payload a Bulk mark
     * writes is byte-identical to the one the old build wrote, and a range already in the
     * household's log carries the bound with no migration.
     */
    @Test
    fun `the payload gained no field, and madeAt comes from the entry`() {
        val entry = log.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        assertNotNull(entry)
        assertEquals(
            rangePayload(
                sourceId = "UUx",
                pivotPublishedAt = testDay(3),
                side = RangeSide.NEWER,
                watched = true,
                clearsLastPlayed = false,
            ),
            entry!!.payload,
        )
        assertEquals(MARK_TIME, entry.range()!!.madeAt)
        assertEquals(entry.at, entry.range()!!.madeAt)
    }

    /**
     * The version bump rebuilds the derived table, and the rebuild writes nothing to the log.
     *
     * The bound is read out of the log at derivation time, so a television that upgrades to
     * this build re-derives from rows it never changes. Only the on-device half of this is
     * left: the same check against the household's own set.
     */
    @Test
    fun `a rebuild leaves every log entry exactly as it was`() {
        log.markJustThis(videos[0], watched = true)
        tick(60)
        log.markRange(videos[2], side = RangeSide.NEWER, watched = true)
        log.openAndDerive()
        val before = store.entries()

        store.setDerivationVersion(DERIVATION_VERSION - 1)
        val outcome = log.openAndDerive()

        assertTrue(outcome is Derivation.Rebuilt)
        assertEquals(DERIVATION_VERSION, store.storedDerivationVersion())
        assertEquals(before, store.entries())
    }

    /** Two Bulk marks made at different moments get different ends, from their own rows. */
    @Test
    fun `each range carries its own end`() {
        val first = log.markRange(videos[0], side = RangeSide.NEWER, watched = true)!!
        tick(86_400 * 10)
        val second = log.markRange(videos[0], side = RangeSide.NEWER, watched = true)!!

        assertEquals(MARK_TIME, first.range()!!.madeAt)
        assertEquals(MARK_TIME.plusSeconds(86_400 * 10), second.range()!!.madeAt)
    }

    // ------------------------------------------------ What the household sees

    /**
     * The whole point, read off the home rail card rather than off the derivation.
     *
     * This is the shape of a real newest-first Source on 2026-09-21: a `newer` watched Bulk mark
     * over the newest videos, and then a new upload. Before ticket 42 the card said 0 unwatched
     * and offered nothing. Now it offers the new video.
     */
    @Test
    fun `the card offers a new upload after a newer Bulk mark`() {
        log.markRange(videos[0], side = RangeSide.NEWER, watched = true)

        val newUpload = testVideo("new", day = 201)
        val listed = listOf(newUpload) + videos.reversed()
        val stored = StoredSource(
            sourceId = "UUx",
            title = "ערוץ לדוגמה - כל הסרטונים",
            lastWalkedAt = MARK_TIME,
            direction = Direction.NEWEST_FIRST,
        )

        val card = sourceCard(
            sourceId = "UUx",
            stored = stored,
            videos = listed,
            watches = log.watchStates(listed),
            lastPlayedVideoId = null,
        )

        assertEquals(1, card.unwatchedCount)
        assertEquals("new", card.nextUnwatched?.videoId)
    }

    /** The same card before the new upload arrives: the Bulk mark still covers everything. */
    @Test
    fun `the card still says nothing is unwatched while no new upload has arrived`() {
        log.markRange(videos[0], side = RangeSide.NEWER, watched = true)

        val listed = videos.reversed()
        val stored = StoredSource(
            sourceId = "UUx",
            title = "ערוץ לדוגמה - כל הסרטונים",
            lastWalkedAt = MARK_TIME,
            direction = Direction.NEWEST_FIRST,
        )

        val card = sourceCard(
            sourceId = "UUx",
            stored = stored,
            videos = listed,
            watches = log.watchStates(listed),
            lastPlayedVideoId = null,
        )

        assertEquals(0, card.unwatchedCount)
        assertNull(card.nextUnwatched)
    }

    /**
     * A real Source's numbers, in miniature.
     *
     * A Bulk mark pivots on one video and covers about a quarter of the Source's videos.
     * Every one of those was published between the pivot and the moment the
     * mark was made, so the new end does not reach any of them. Only a video published after
     * the mark changes side.
     */
    @Test
    fun `the videos a newer Bulk mark covers today are all still covered`() {
        // Days 100 to 199 stand for the 132 videos between the pivot and the mark.
        val covered = (100..199).map { day -> testVideo("c$day", day = day) }
        // Days 1 to 99 stand for the 456 older videos the mark never reached.
        val older = (1..99).map { day -> testVideo("o$day", day = day) }
        val all = older + covered

        log.markRange(covered.first(), side = RangeSide.NEWER, watched = true)

        val states = log.watchStates(all)
        assertEquals(100, states.values.count { it.watched })
        for (video in covered) assertTrue(video.videoId, states.getValue(video.videoId).watched)
        for (video in older) assertNull(video.videoId, states[video.videoId])
    }

    /**
     * A television whose clock is behind still marks the video the household chose.
     *
     * Found by `/code-review high` on 2026-09-21, after ticket 42 had been built and closed.
     *
     * An Android TV can boot with a clock set in the past and correct it over the network
     * seconds later, which is the whole reason `seq` exists. If the far end were `madeAt`
     * alone, a Bulk mark made in that window would have a far end **earlier than its own
     * pivot**, so it would cover nothing at all: not the videos above the pivot, and not even
     * the video the household pressed the button on. The button would look broken, and
     * pressing it again would do nothing until the clock was fixed.
     *
     * The far end is therefore `maxOf(madeAt, pivotPublishedAt)`. The worst a wrong clock can
     * now do is shrink the Bulk mark to the one chosen video.
     */
    @Test
    fun `a Bulk mark made with a clock behind the pivot still covers the chosen video`() {
        val behind = InMemoryWatchStore()
        // Day 2 is before the pivot video's own publish date, which is day 3.
        val slowLog = WatchLog(behind, now = { testDay(2) })

        slowLog.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        val states = slowLog.watchStates(videos)
        assertTrue("the chosen video", states.getValue("v3").watched)
    }

    /** The same wrong clock does not reach past the chosen video. */
    @Test
    fun `a Bulk mark made with a clock behind the pivot reaches no further than the pivot`() {
        val behind = InMemoryWatchStore()
        val slowLog = WatchLog(behind, now = { testDay(2) })

        slowLog.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        val states = slowLog.watchStates(videos)
        assertNull("day 4 is past the far end", states["v4"])
        assertNull("day 5 is past the far end", states["v5"])
    }

    /** And it still does not reach below the pivot, which is the `newer` side's own rule. */
    @Test
    fun `a Bulk mark made with a clock behind the pivot covers nothing older`() {
        val behind = InMemoryWatchStore()
        val slowLog = WatchLog(behind, now = { testDay(2) })

        slowLog.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        val states = slowLog.watchStates(videos)
        assertNull("day 1 is below the pivot", states["v1"])
        assertNull("day 2 is below the pivot", states["v2"])
    }

    /** A clock that is merely a little slow still covers everything up to where it thinks it is. */
    @Test
    fun `a clock behind the newest video but past the pivot keeps its ordinary far end`() {
        val behind = InMemoryWatchStore()
        // Day 4 is past the pivot at day 3, but before the newest video at day 5.
        val slowLog = WatchLog(behind, now = { testDay(4) })

        slowLog.markRange(videos[2], side = RangeSide.NEWER, watched = true)

        val states = slowLog.watchStates(videos)
        assertTrue("the chosen video", states.getValue("v3").watched)
        assertTrue("day 4 is at the far end", states.getValue("v4").watched)
        assertNull("day 5 is past the far end", states["v5"])
    }
}
