package local.youtubetv.data

import java.time.ZoneId

/**
 * Periods: the blocks a long Source is cut into so the household can jump around it.
 *
 * A Period is a **calendar year or a calendar month**, and nothing else. It is never a
 * television season and never a grouping the channel owner made. See `CONTEXT.md` and spec
 * section 7.4.
 *
 * Everything here is a plain function over a list of videos, with no storage and no Compose
 * in it, so every rule is a JVM unit test. It is the same shape as `data/Landings.kt`, and
 * for the same reason.
 *
 * **Nothing here takes a Direction.** The list handed in is already in the Source's
 * Direction, exactly as `SourceScreenState.Ready.videos` is, and the buckets are built by
 * walking it in order. That is what keeps the panel honest across a flip: a flipped Source
 * hands in a turned-around list and the Periods turn around with it, with nothing cached.
 *
 * Time buckets do **not** become Netflix-style rows anywhere. They are a jump list over one
 * flat sequence, because Next unwatched and Bulk mark both read that sequence.
 */

/** Whether this Source's Periods are calendar years or calendar months. */
enum class PeriodUnit {
    YEAR,
    MONTH,
}

/**
 * One block of a Source's videos.
 *
 * @property unit year or month, chosen once per Source by [periodUnit]. Every Period of one
 *   Source carries the same unit.
 * @property year the calendar year, in the television's zone.
 * @property month the calendar month, 1 to 12, or null for a year Period.
 * @property firstIndex where this Period starts **in the listed videos the grid draws**, which
 *   is what `Choosing a Period` lands on.
 * @property count how many listed videos this Period holds. Hidden Shorts are in none of
 *   these numbers, because a Short is never listed. Spec section 4.3.
 */
data class Period(
    val unit: PeriodUnit,
    val year: Int,
    val month: Int?,
    val firstIndex: Int,
    val count: Int,
)

/**
 * Fewer than this many distinct months, and the Source gets year Periods.
 *
 * One month of videos would be one month bucket, which is a jump list that cannot jump.
 */
const val PERIOD_MONTHS_MIN = 2

/**
 * More than this many distinct months, and the Source gets year Periods.
 *
 * 36 is three years of monthly buckets. Past that the jump list itself would need scrubbing,
 * which is the one thing the panel exists to save.
 */
const val PERIOD_MONTHS_MAX = 36

/** Fewer than this many distinct years, and month Periods are the only way to cut the Source. */
const val PERIOD_YEARS_MIN = 2

/**
 * More videos than this in any single calendar year, and the Source gets month Periods.
 *
 * **It is 80, and it was 40 until 2026-09-19.** At 40, a 126-video Source spanning 28 months
 * was cut into 28 month buckets of three to seven videos each, so the panel needed more
 * scrubbing than the grid did. The owner settled 80 on the television. Spec sections 7.4 and 9.
 *
 * The test is "more than", not "at least": a year holding exactly 80 still gets year Periods.
 */
const val PERIOD_YEAR_MAX = 80

/**
 * Whether this Source is cut into years or into months.
 *
 * The rule is spec section 7.4's, in its own order, and the order matters. A Source inside a
 * single calendar month is answered by the first line and never reaches `years < 2`, which
 * would otherwise cut it into one month bucket.
 *
 * It is computed over the **listed** videos only, so the Shorts filter is never applied
 * twice, and over `videoPublishedAt` only, which is the one date this app orders by.
 *
 * @param videos the videos the grid shows, already in the Source's Direction.
 * @param zone the television's zone. A Period is a calendar block, so a video published late
 *   on 31 December belongs to a different year in Israel than in London.
 */
fun periodUnit(videos: List<StoredVideo>, zone: ZoneId = ZoneId.systemDefault()): PeriodUnit {
    // One (year, month) pair per listed, dated video, in the television's zone. Every count
    // below is taken from this one pass.
    val blocks = videos
        .filter { it.isListed }
        .mapNotNull { it.videoPublishedAt }
        .map { it.atZone(zone) }
        .map { it.year to it.monthValue }

    val months = blocks.distinct().size
    val years = blocks.map { (year, _) -> year }.distinct().size
    val busiestYear = blocks.groupingBy { (year, _) -> year }.eachCount().values.maxOrNull() ?: 0

    return when {
        months < PERIOD_MONTHS_MIN -> PeriodUnit.YEAR
        months > PERIOD_MONTHS_MAX -> PeriodUnit.YEAR
        years < PERIOD_YEARS_MIN -> PeriodUnit.MONTH
        busiestYear > PERIOD_YEAR_MAX -> PeriodUnit.MONTH
        else -> PeriodUnit.YEAR
    }
}

/**
 * The Periods of one Source, in the order the panel lists them.
 *
 * The list is walked in order and a new Period is started whenever the calendar block changes
 * from the video before it, so the Periods come out in the Source's Direction with nothing
 * sorted again. Two Periods can never carry the same block, because the videos handed in are
 * ordered by date and so their blocks only ever move one way.
 *
 * **A video with no publish date is in no Period.** It is private or deleted, so YouTube gave
 * no date, and it has neither a calendar year nor a calendar month. `orderByPublishDate` puts
 * every undated video last in both Directions, so the Periods cover the front of the list and
 * the undated tail sits past the end of the last one. Inventing a Period for them would put a
 * block on the panel that names no time at all.
 *
 * @param videos the videos the grid shows, already in the Source's Direction.
 */
fun periodsOf(videos: List<StoredVideo>, zone: ZoneId = ZoneId.systemDefault()): List<Period> {
    val unit = periodUnit(videos, zone)
    val periods = mutableListOf<Period>()
    videos.forEachIndexed { index, video ->
        if (!video.isListed) return@forEachIndexed
        val at = video.videoPublishedAt?.atZone(zone) ?: return@forEachIndexed
        val month = if (unit == PeriodUnit.MONTH) at.monthValue else null
        val open = periods.lastOrNull()
        if (open != null && open.year == at.year && open.month == month) {
            periods[periods.lastIndex] = open.copy(count = open.count + 1)
        } else {
            periods += Period(
                unit = unit,
                year = at.year,
                month = month,
                firstIndex = index,
                count = 1,
            )
        }
    }
    return periods
}

/**
 * Which Period the panel opens highlighted on: the one holding the focused video.
 *
 * Returns an index into [periods], or `-1` when the Source has no Period at all.
 *
 * Two cases do not have an exact answer, and both fall back to the nearest Period rather than
 * to nothing:
 *
 * - **No video is selected**, so [videoIndex] is `-1`, which `ui/SourceSelection.kt` calls
 *   `NO_SELECTION`. The first Period is where the list starts, so that is where the panel
 *   opens. This is what the `Period` chip asks for, because a chip holding the highlight means
 *   no video is selected. It is also the state before the first card has taken focus. Spec
 *   section 7.4, amended by ticket 16.
 * - **The focused video has no date**, so it is in no Period and sits past the end of the last
 *   one. The last Period is the nearest thing to it in the list.
 *
 * @param periods what [periodsOf] returned for this Source.
 * @param videoIndex the highlighted card, in the listed videos.
 */
fun periodIndexFor(periods: List<Period>, videoIndex: Int): Int {
    if (periods.isEmpty()) return -1
    var found = 0
    periods.forEachIndexed { index, period ->
        if (period.firstIndex <= videoIndex) found = index
    }
    return found
}
