package local.youtubetv.refresh

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trigger rules of spec sections 5.1 and 5.4, as pure functions over a clock reading.
 *
 * Every rule here is settled without a device and without a timer, which is the whole reason
 * they are not written inside the Activity.
 */
class RefreshTriggersTest {

    private val jerusalem: ZoneId = ZoneId.of("Asia/Jerusalem")

    // ----------------------------------------------------- the daily full walk, spec 5.1

    @Test
    fun `the refresh day turns over at 4am, so 2am still belongs to the day before`() {
        val lateNight = Instant.parse("2026-09-20T23:10:00Z") // 02:10 on the 21st in Jerusalem
        assertEquals(LocalDate.parse("2026-09-20"), refreshDay(lateNight, jerusalem))
    }

    @Test
    fun `just after 4am belongs to the new day`() {
        val morning = Instant.parse("2026-09-21T01:10:00Z") // 04:10 on the 21st in Jerusalem
        assertEquals(LocalDate.parse("2026-09-21"), refreshDay(morning, jerusalem))
    }

    // ------------------------------------------- the weekly full walk, build ticket 55

    @Test
    fun `a full walk that never completed is owed`() {
        val firstLaunch = Instant.parse("2026-09-21T01:10:00Z")
        assertTrue(fullWalkDue(firstLaunch, jerusalem, lastFullWalkDay = null))
    }

    @Test
    fun `the full walk is not owed again the same day, nor six refresh days later`() {
        val ran = LocalDate.parse("2026-10-03")
        // Later the same day.
        assertFalse(fullWalkDue(Instant.parse("2026-10-03T15:00:00Z"), jerusalem, ran))
        // 04:10 on 2026-10-09 in Jerusalem, which is refresh day 2026-10-09: six days on.
        assertFalse(fullWalkDue(Instant.parse("2026-10-09T01:10:00Z"), jerusalem, ran))
        // 03:50 on 2026-10-10 in Jerusalem is still refresh day 2026-10-09.
        assertFalse(fullWalkDue(Instant.parse("2026-10-10T00:50:00Z"), jerusalem, ran))
    }

    @Test
    fun `the full walk is owed on the first launch after 4am seven refresh days later`() {
        val ran = LocalDate.parse("2026-10-03")
        // 04:10 on 2026-10-10 in Jerusalem.
        assertTrue(fullWalkDue(Instant.parse("2026-10-10T01:10:00Z"), jerusalem, ran))
        // And on any later day, if nobody opened the app on the seventh.
        assertTrue(fullWalkDue(Instant.parse("2026-10-20T12:00:00Z"), jerusalem, ran))
        assertEquals(LocalDate.parse("2026-10-10"), nextFullWalkDay(ran))
    }

    @Test
    fun `a full walk day in the future reads as owed, so a wrong clock cannot hold it off`() {
        val writtenByAClockThatWasAhead = LocalDate.parse("2026-12-01")
        assertTrue(fullWalkDue(Instant.parse("2026-10-03T12:00:00Z"), jerusalem, writtenByAClockThatWasAhead))
    }

    @Test
    fun `only the weekly full walk re-walks every Source in full`() {
        assertTrue(RefreshTrigger.WEEKLY_FULL_WALK.isFullWalk)
        assertFalse(RefreshTrigger.LAUNCH.isFullWalk)
        assertFalse(RefreshTrigger.TIMER.isFullWalk)
        assertFalse(RefreshTrigger.MANUAL.isFullWalk)
    }

    // ------------------------------------------------------- the 30 minute timer, spec 5.1

    @Test
    fun `the timer waits thirty minutes between checks`() {
        val checked = Instant.parse("2026-09-20T10:00:00Z")
        assertEquals(
            30 * 60 * 1000L,
            millisUntilNextCheck(now = checked, lastCheckAt = checked),
        )
        assertEquals(
            10 * 60 * 1000L,
            millisUntilNextCheck(now = checked.plusSeconds(20 * 60), lastCheckAt = checked),
        )
        assertEquals(
            0L,
            millisUntilNextCheck(now = checked.plusSeconds(30 * 60), lastCheckAt = checked),
        )
    }

    @Test
    fun `a check has never run, so one is due now`() {
        assertEquals(0L, millisUntilNextCheck(Instant.parse("2026-09-20T10:00:00Z"), null))
    }

    @Test
    fun `a television that corrected its clock backwards does not park the next check`() {
        // An Android TV can boot with a wrong wall clock and fix it seconds later, which is
        // the same hazard ADR 0001 orders the log by `seq` for.
        val checkedInTheFuture = Instant.parse("2026-09-20T12:00:00Z")
        val correctedNow = Instant.parse("2026-09-20T10:00:00Z")
        assertEquals(0L, millisUntilNextCheck(correctedNow, checkedInTheFuture))
    }

    // ---------------------------------------------------- the quota refusal, spec 5.4

    @Test
    fun `a quota refusal lasts until midnight Pacific Time and no longer`() {
        // 16:30 Pacific on 20 September.
        val refusedAt = Instant.parse("2026-09-20T23:30:00Z")
        val resetsAt = quotaResetsAt(refusedAt)
        assertEquals(Instant.parse("2026-09-21T07:00:00Z"), resetsAt)

        assertTrue(quotaStopped(refusedAt, resetsAt))
        assertTrue(quotaStopped(resetsAt.minusSeconds(1), resetsAt))
        assertFalse(quotaStopped(resetsAt, resetsAt))
        assertFalse(quotaStopped(resetsAt.plusSeconds(1), resetsAt))
    }

    @Test
    fun `a refusal ten minutes before the reset is over ten minutes later, not a day`() {
        val refusedAt = Instant.parse("2026-09-21T06:50:00Z") // 23:50 Pacific
        assertEquals(Instant.parse("2026-09-21T07:00:00Z"), quotaResetsAt(refusedAt))
    }

    @Test
    fun `no refusal means nothing is stopped`() {
        assertFalse(quotaStopped(Instant.parse("2026-09-20T10:00:00Z"), null))
    }

    // -------------------------------------------------------------------- the log lines

    @Test
    fun `each of the four triggers names itself, so the log says which one fired`() {
        val said = RefreshTrigger.entries.map { refreshStartLine(it, sources = 2) }
        assertEquals(4, said.size)
        assertTrue(said[0].contains("launch"))
        assertTrue(said[1].contains("the 30 minute timer"))
        assertTrue(said[2].contains("the weekly full walk"))
        assertTrue(said[3].contains("a manual refresh"))
        // Four different lines, so a logcat read can tell them apart.
        assertEquals(4, said.toSet().size)
    }

    @Test
    fun `the quota line says there is no retry loop`() {
        val line = refreshQuotaExceededLine(
            RefreshTrigger.LAUNCH,
            Instant.parse("2026-09-21T07:00:00Z"),
        )
        assertTrue(line.contains("quotaExceeded"))
        assertTrue(line.contains("No retry loop"))
    }
}
