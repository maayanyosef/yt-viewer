package local.youtubetv.data

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every test here pins the zone to UTC.
 *
 * A Period is a **calendar** year or month, so which one a video falls into depends on the
 * television's zone. Leaving that to the machine running the tests would make a video
 * published at 23:30 on 31 December land in a different Period in Israel than in London.
 */
private val UTC: ZoneId = ZoneId.of("UTC")

/** One listed video, published on a named calendar day. */
private fun videoAt(
    videoId: String,
    year: Int,
    month: Int,
    day: Int = 1,
    isShort: Boolean = false,
): StoredVideo = testVideo(videoId, isShort = isShort).copy(
    videoPublishedAt = ZonedDateTime.of(year, month, day, 12, 0, 0, 0, UTC).toInstant(),
)

/** `count` videos inside one calendar month, newest first, the way a Source lists them. */
private fun monthOf(year: Int, month: Int, count: Int, idPrefix: String = ""): List<StoredVideo> =
    (count downTo 1).map { n -> videoAt("$idPrefix$year-$month-$n", year, month, day = n) }

/**
 * Which bucket a Source gets: a calendar year, or a calendar month.
 *
 * The rule is spec section 7.4's, in its order, and it is computed over the **listed** videos
 * only so the Shorts filter is never applied twice.
 */
class PeriodUnitTest {

    @Test
    fun `an empty Source asks for year buckets and has none`() {
        assertEquals(PeriodUnit.YEAR, periodUnit(emptyList(), UTC))
        assertEquals(emptyList<Period>(), periodsOf(emptyList(), UTC))
    }

    @Test
    fun `one video is one year bucket`() {
        val videos = listOf(videoAt("a", 2025, 6))
        assertEquals(PeriodUnit.YEAR, periodUnit(videos, UTC))
        assertEquals(1, periodsOf(videos, UTC).size)
        assertEquals(2025, periodsOf(videos, UTC).single().year)
    }

    @Test
    fun `a Source inside a single calendar month gets a year bucket, not one month bucket`() {
        // months < 2 wins before years < 2 is ever asked, which is the order spec 7.4 writes.
        val videos = monthOf(2025, 6, count = 30)
        assertEquals(PeriodUnit.YEAR, periodUnit(videos, UTC))
        assertEquals(1, periodsOf(videos, UTC).size)
    }

    @Test
    fun `two months inside one year get month buckets`() {
        val videos = monthOf(2025, 6, count = 3) + monthOf(2025, 5, count = 3)
        assertEquals(PeriodUnit.MONTH, periodUnit(videos, UTC))
        assertEquals(listOf(6, 5), periodsOf(videos, UTC).map { it.month })
    }

    @Test
    fun `a Source spanning fifteen years gets year buckets, because the month list would scrub`() {
        // 180 distinct months, far past PERIOD_MONTHS_MAX.
        val videos = (2025 downTo 2011).flatMap { year ->
            (12 downTo 1).flatMap { month -> monthOf(year, month, count = 2) }
        }
        assertEquals(180, monthsSpanned(videos))
        assertEquals(PeriodUnit.YEAR, periodUnit(videos, UTC))
        assertEquals(15, periodsOf(videos, UTC).size)
    }

    @Test
    fun `exactly 36 months still allows month buckets, 37 does not`() {
        // 36 is three years of monthly buckets. Past that the jump list itself would need
        // scrubbing, which is the one thing the panel exists to save. Spec section 7.4.
        val thirtySix = (2025 downTo 2023).flatMap { year ->
            (12 downTo 1).flatMap { month -> monthOf(year, month, count = 7) }
        }
        assertEquals(36, monthsSpanned(thirtySix))
        assertEquals(PeriodUnit.MONTH, periodUnit(thirtySix, UTC))

        val thirtySeven = monthOf(2026, 1, count = 7) + thirtySix
        assertEquals(37, monthsSpanned(thirtySeven))
        assertEquals(PeriodUnit.YEAR, periodUnit(thirtySeven, UTC))
    }

    @Test
    fun `a busy year past PERIOD_YEAR_MAX gets month buckets`() {
        // Two years, 24 months, and one year holding 84 videos.
        val videos = (12 downTo 1).flatMap { month -> monthOf(2025, month, count = 7) } +
            (12 downTo 1).flatMap { month -> monthOf(2024, month, count = 1) }
        val busiest = videos.count { it.videoPublishedAt!!.atZone(UTC).year == 2025 }
        assertEquals(84, busiest)
        assertTrue(busiest > PERIOD_YEAR_MAX)
        assertEquals(PeriodUnit.MONTH, periodUnit(videos, UTC))
    }

    @Test
    fun `a year holding exactly PERIOD_YEAR_MAX videos still gets year buckets`() {
        // The threshold is "more than", not "at least". 80 is the number the owner settled on the
        // television on 2026-09-19, after 40 gave a 126-video Source 28 month buckets.
        val videos = (0 until PERIOD_YEAR_MAX).map { n -> videoAt("y$n", 2025, n % 12 + 1) } +
            monthOf(2024, 1, count = 1)
        assertEquals(PERIOD_YEAR_MAX, videos.count { it.videoPublishedAt!!.atZone(UTC).year == 2025 })
        assertEquals(PeriodUnit.YEAR, periodUnit(videos, UTC))
    }

    @Test
    fun `a 126-video Source over 28 months gets 3 year buckets, not 28 month buckets`() {
        // The case that moved PERIOD_YEAR_MAX from 40 to 80. At 40 this Source got 28 month
        // buckets of three to seven videos each, so the panel needed more scrubbing than the
        // grid did. Settled on the television on 2026-09-19, spec section 9.
        val months = (0 until 28).map { n -> 2024 + n / 12 to n % 12 + 1 }
        val videos = months.reversed().flatMapIndexed { i, (year, month) ->
            monthOf(year, month, count = if (i < 14) 5 else 4)
        }
        assertEquals(126, videos.size)
        assertEquals(28, monthsSpanned(videos))
        assertEquals(PeriodUnit.YEAR, periodUnit(videos, UTC))
        assertEquals(3, periodsOf(videos, UTC).size)
    }

    /** The distinct calendar months holding at least one listed video, as spec 7.4 counts them. */
    private fun monthsSpanned(videos: List<StoredVideo>): Int =
        videos.mapNotNull { it.videoPublishedAt }
            .map { it.atZone(UTC).let { at -> at.year to at.monthValue } }
            .distinct()
            .size
}

/**
 * The buckets themselves: where each one starts in the grid, and how many videos it holds.
 *
 * The list handed in is already in the Source's Direction, so the buckets are built by walking
 * it in order. Nothing here sorts by date again, which is what keeps a flip honest.
 */
class PeriodsOfTest {

    @Test
    fun `newest first gives newest Periods first`() {
        val videos = monthOf(2026, 1, count = 2) + monthOf(2025, 1, count = 2) +
            monthOf(2024, 1, count = 2)
        val periods = periodsOf(videos, UTC)
        assertEquals(listOf(2026, 2025, 2024), periods.map { it.year })
        assertEquals(listOf(0, 2, 4), periods.map { it.firstIndex })
        assertEquals(listOf(2, 2, 2), periods.map { it.count })
    }

    @Test
    fun `oldest first gives oldest Periods first, over the very same videos`() {
        val videos = orderByPublishDate(
            monthOf(2026, 1, count = 2) + monthOf(2025, 1, count = 2) + monthOf(2024, 1, count = 2),
            Direction.OLDEST_FIRST,
        )
        val periods = periodsOf(videos, UTC)
        assertEquals(listOf(2024, 2025, 2026), periods.map { it.year })
        assertEquals(listOf(0, 2, 4), periods.map { it.firstIndex })
    }

    @Test
    fun `a bucket count excludes a hidden Short`() {
        val videos = listOf(
            videoAt("a", 2025, 3),
            videoAt("short", 2025, 3, isShort = true),
            videoAt("b", 2025, 3),
        )
        assertEquals(2, periodsOf(videos, UTC).single().count)
    }

    @Test
    fun `a Short never starts a bucket either`() {
        val videos = listOf(
            videoAt("short", 2025, 3, isShort = true),
            videoAt("a", 2025, 3),
        )
        assertEquals(1, periodsOf(videos, UTC).single().firstIndex)
    }

    @Test
    fun `a video with no publish date belongs to no Period`() {
        // A private or deleted video has no date, so it has no calendar year and no calendar
        // month. `orderByPublishDate` puts it last in both Directions, so the Periods cover the
        // front of the list and it sits past the end of the last one.
        val videos = listOf(
            videoAt("a", 2025, 3),
            videoAt("b", 2024, 3),
            testVideo("gone", day = null),
        )
        val periods = periodsOf(videos, UTC)
        assertEquals(listOf(2025, 2024), periods.map { it.year })
        assertEquals(listOf(1, 1), periods.map { it.count })
    }

    @Test
    fun `a Source of nothing but undated videos has no Periods at all`() {
        val videos = listOf(testVideo("gone", day = null), testVideo("private", day = null))
        assertEquals(emptyList<Period>(), periodsOf(videos, UTC))
    }

    @Test
    fun `a month bucket carries its month, a year bucket carries none`() {
        val monthly = periodsOf(monthOf(2025, 6, count = 1) + monthOf(2025, 5, count = 1), UTC)
        assertEquals(listOf(PeriodUnit.MONTH, PeriodUnit.MONTH), monthly.map { it.unit })
        assertEquals(listOf(6, 5), monthly.map { it.month })

        val yearly = periodsOf(monthOf(2025, 6, count = 1), UTC)
        assertEquals(PeriodUnit.YEAR, yearly.single().unit)
        assertEquals(null, yearly.single().month)
    }
}

/** Which entry the panel opens highlighted on: the Period holding the focused video. */
class PeriodIndexForTest {

    private val videos = monthOf(2026, 1, count = 2) + monthOf(2025, 1, count = 3) +
        monthOf(2024, 1, count = 2)
    private val periods = periodsOf(videos, UTC)

    @Test
    fun `the first card of a Period opens on that Period`() {
        assertEquals(0, periodIndexFor(periods, 0))
        assertEquals(1, periodIndexFor(periods, 2))
        assertEquals(2, periodIndexFor(periods, 5))
    }

    @Test
    fun `a card in the middle of a Period opens on that Period`() {
        assertEquals(1, periodIndexFor(periods, 3))
        assertEquals(1, periodIndexFor(periods, 4))
        assertEquals(2, periodIndexFor(periods, 6))
    }

    @Test
    fun `an undated card past the last Period opens on the last Period`() {
        // Undated videos sit after every dated one in both Directions, so the nearest Period
        // is the last one. It is the only honest answer: they are in no Period themselves.
        assertEquals(2, periodIndexFor(periods, 7))
        assertEquals(2, periodIndexFor(periods, 99))
    }

    @Test
    fun `a grid with no highlight yet opens on the first Period`() {
        // `focusedIndex` is -1 until a card reports focus.
        assertEquals(0, periodIndexFor(periods, -1))
    }

    @Test
    fun `a Source with no Periods has no entry to open on`() {
        assertEquals(-1, periodIndexFor(emptyList(), 0))
    }
}
