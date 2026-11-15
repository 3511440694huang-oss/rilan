package app.rilan.daily.schedule

import app.rilan.daily.core.EventKind
import app.rilan.daily.core.Priority
import app.rilan.daily.core.RepeatRule
import app.rilan.daily.core.ScheduleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurrenceTest {

    private fun event(
        date: String,
        repeat: RepeatRule = RepeatRule.NONE,
        kind: EventKind = EventKind.TASK,
        priority: Priority = Priority.MEDIUM,
    ) = ScheduleEvent(
        id = "test",
        title = "测试",
        startDate = date,
        priority = priority,
        kind = kind,
        repeat = repeat,
    )

    @Test
    fun oneTimeFutureAndPast() {
        val e = event("2026-12-01")
        assertEquals(LocalDate.of(2026, 12, 1), Recurrence.nextOccurrence(e, LocalDate.of(2026, 11, 2)))
        assertNull(Recurrence.nextOccurrence(e, LocalDate.of(2026, 12, 2)))
    }

    @Test
    fun dailyAlwaysToday() {
        val e = event("2026-01-01", RepeatRule.DAILY)
        assertEquals(LocalDate.of(2026, 11, 2), Recurrence.nextOccurrence(e, LocalDate.of(2026, 11, 2)))
        assertEquals(0L, Recurrence.daysUntil(e, LocalDate.of(2026, 11, 2)))
    }

    @Test
    fun weeklyNextSameWeekday() {
        val e = event("2026-10-26", RepeatRule.WEEKLY)
        val next = Recurrence.nextOccurrence(e, LocalDate.of(2026, 11, 2))
        assertEquals(LocalDate.of(2026, 11, 2), next)
        assertEquals(6L, Recurrence.daysUntil(e, LocalDate.of(2026, 11, 3)))
    }

    @Test
    fun monthlySkipsMissingDay() {
        val e = event("2026-01-31", RepeatRule.MONTHLY)
        assertEquals(
            LocalDate.of(2026, 3, 31),
            Recurrence.nextOccurrence(e, LocalDate.of(2026, 2, 1)),
        )
    }

    @Test
    fun yearlyBirthdayRollsOver() {
        val e = event("1990-05-12", RepeatRule.YEARLY, EventKind.BIRTHDAY, Priority.HIGH)
        assertEquals(
            LocalDate.of(2027, 5, 12),
            Recurrence.nextOccurrence(e, LocalDate.of(2026, 5, 13)),
        )
        assertEquals(7L, Recurrence.daysUntil(e, LocalDate.of(2027, 5, 5)))
    }

    @Test
    fun leapDayFallsBackToFeb28() {
        val e = event("2000-02-29", RepeatRule.YEARLY)
        assertEquals(
            LocalDate.of(2027, 2, 28),
            Recurrence.nextOccurrence(e, LocalDate.of(2027, 1, 1)),
        )
        assertTrue(Recurrence.occursOn(e, LocalDate.of(2027, 2, 28)))
    }

    @Test
    fun birthdayWithNoneStillYearly() {
        val e = event("1998-08-08", RepeatRule.NONE, EventKind.BIRTHDAY)
        assertEquals(
            LocalDate.of(2027, 8, 8),
            Recurrence.nextOccurrence(e, LocalDate.of(2026, 9, 1)),
        )
    }

    @Test
    fun reminderWindowIncludesEachDay() {
        val e = event("2026-11-09", repeat = RepeatRule.NONE, priority = Priority.HIGH)
        for (offset in 0L..7L) {
            val today = LocalDate.of(2026, 11, 9).minusDays(offset)
            assertEquals(offset, Recurrence.daysUntil(e, today))
        }
        // 提前第 8 天不该进入高重要提醒窗口
        assertEquals(8L, Recurrence.daysUntil(e, LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun monthlyOccurrenceCheck() {
        val e = event("2026-01-15", RepeatRule.MONTHLY)
        assertTrue(Recurrence.occursOn(e, LocalDate.of(2026, 11, 15)))
        assertTrue(!Recurrence.occursOn(e, LocalDate.of(2026, 11, 16)))
    }
}
