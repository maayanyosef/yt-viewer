package local.youtubetv.refresh

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When a refresh happens, and why. Spec section 5.1.
 *
 * Everything in this file is a pure function over a clock reading, so every trigger rule is a
 * JVM unit test with no device, no timer and no quota. The Activity owns the coroutine that
 * ticks; it owns none of the decisions.
 */

/** The four ways a refresh starts. Spec section 5.1 lists exactly these and no others. */
enum class RefreshTrigger {

    /** The app was opened. One change check over every Source. */
    LAUNCH,

    /** Thirty minutes passed with the app in the foreground and no video playing. */
    TIMER,

    /**
     * One full walk of every Source, owed once a week. Spec section 5.1.
     *
     * A launch never starts it on its own. `SourceRefresh.refreshOnLaunch` runs the plain
     * [LAUNCH] check first and this after it, as a second pass. Build ticket 55.
     */
    WEEKLY_FULL_WALK,

    /** The household chose `Refresh` on a Source card's long-press menu. */
    MANUAL,
}

/** How the trigger reads in a logcat line, so a criterion can be checked without a screenshot. */
val RefreshTrigger.stored: String
    get() = when (this) {
        RefreshTrigger.LAUNCH -> "launch"
        RefreshTrigger.TIMER -> "the 30 minute timer"
        RefreshTrigger.WEEKLY_FULL_WALK -> "the weekly full walk"
        RefreshTrigger.MANUAL -> "a manual refresh"
    }

/** Whether this trigger re-walks every Source in full, whatever the change check says. */
val RefreshTrigger.isFullWalk: Boolean
    get() = this == RefreshTrigger.WEEKLY_FULL_WALK

/** Spec section 5.1: every thirty minutes while the app is in the foreground. */
const val REFRESH_INTERVAL_MINUTES = 30L

/** The hour the refresh day turns over, in local time. Spec section 5.1. */
const val REFRESH_DAY_HOUR = 4

/**
 * How many refresh days lie between two full walks. Spec section 5.1.
 *
 * It was one until 2026-10-03. The owner made the full walk weekly that day. Build ticket 55.
 */
const val FULL_WALK_INTERVAL_DAYS = 7L

/**
 * The key the last completed full walk's refresh day is remembered under, in `app_meta`.
 *
 * **It must not be renamed.** Every daily full walk before build ticket 55 wrote it, and the
 * weekly rule reads the same value. A new key would read as "never ran" on the household's
 * set and cost one full walk for nothing.
 */
const val META_LAST_FULL_WALK_DAY = "refresh.last_full_walk_day"

/** Where YouTube's daily quota resets. Spec sections 4.1 and 5.4. */
val QUOTA_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

/**
 * Which refresh day a moment belongs to.
 *
 * The day turns over at [REFRESH_DAY_HOUR] and not at midnight, so a household still up at
 * 02:00 is on the day that began the previous morning. That is what makes "the first launch
 * after 04:00 local time" the moment a full walk falls due, and not midnight.
 */
fun refreshDay(at: Instant, zone: ZoneId): LocalDate {
    val local = at.atZone(zone)
    return if (local.hour < REFRESH_DAY_HOUR) local.toLocalDate().minusDays(1) else local.toLocalDate()
}

/** The refresh day the next full walk is owed on, after one that completed on [lastFullWalkDay]. */
fun nextFullWalkDay(lastFullWalkDay: LocalDate): LocalDate =
    lastFullWalkDay.plusDays(FULL_WALK_INTERVAL_DAYS)

/**
 * Whether the weekly full walk is owed right now. Spec section 5.1, build ticket 55.
 *
 * It is owed when the last completed full walk is [FULL_WALK_INTERVAL_DAYS] or more refresh
 * days ago, as [refreshDay] counts them. A walk that completed on 2026-10-03 is owed again on
 * the first launch after 04:00 on 2026-10-10, and not on 2026-10-09.
 *
 * **A day in the future reads as owed.** An Android television can boot with a wrong clock. A
 * walk written while the clock was ahead would otherwise hold off the next one for as long as
 * the clock had been wrong, on top of the seven days. One walk then writes a day the clock
 * agrees with, and the rule is weekly again. The daily rule behaved the same way, because it
 * compared with `!=`.
 *
 * @param lastFullWalkDay the refresh day of the last full walk that every Source came through,
 *   or null when one has never completed. A fresh install therefore does one on its first
 *   launch.
 */
fun fullWalkDue(now: Instant, zone: ZoneId, lastFullWalkDay: LocalDate?): Boolean {
    if (lastFullWalkDay == null) return true
    val today = refreshDay(now, zone)
    if (today.isBefore(lastFullWalkDay)) return true
    return !today.isBefore(nextFullWalkDay(lastFullWalkDay))
}

/**
 * How long is left before the next 30 minute check, in milliseconds.
 *
 * Zero means it is due now. It is a duration and not a boolean so that the Activity's loop can
 * sleep exactly as long as it needs to rather than waking every second to ask.
 *
 * @param intervalSeconds how long the gap is. The app ships [REFRESH_INTERVAL_MINUTES], which
 *   is thirty minutes, and the only other caller is the debug extra that shortens it so the
 *   timer can be watched happening. See `EXTRA_REFRESH_SECONDS`.
 *
 * **A clock that went backwards reads as due.** An Android TV can boot with a wrong wall clock
 * and correct it seconds later, which is the same reason ADR 0001 orders the log by `seq`
 * within a device. A negative gap here would otherwise park the next check hours away.
 */
fun millisUntilNextCheck(
    now: Instant,
    lastCheckAt: Instant?,
    intervalSeconds: Long = REFRESH_INTERVAL_MINUTES * 60,
): Long {
    if (lastCheckAt == null) return 0L
    val due = lastCheckAt.plusSeconds(intervalSeconds)
    if (due.isBefore(now) || lastCheckAt.isAfter(now)) return 0L
    return due.toEpochMilli() - now.toEpochMilli()
}

/**
 * When the quota pool refills after a `403 quotaExceeded`. Spec section 5.4.
 *
 * Midnight Pacific Time, which is what Google resets on, and never "in 24 hours". A refusal at
 * 23:50 Pacific is over ten minutes later, and a retry loop that waited a day would leave the
 * household a day behind for nothing.
 */
fun quotaResetsAt(refusedAt: Instant): Instant =
    refusedAt.atZone(QUOTA_ZONE).toLocalDate().plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant()

/**
 * Whether refresh is still stopped by a `403 quotaExceeded`.
 *
 * **There is no retry loop anywhere in this app**, and this is why there does not need to be.
 * A refusal writes down one instant, every later trigger asks this one question, and the
 * answer becomes false by itself when Google's own day turns over.
 */
fun quotaStopped(now: Instant, stoppedUntil: Instant?): Boolean =
    stoppedUntil != null && now.isBefore(stoppedUntil)
