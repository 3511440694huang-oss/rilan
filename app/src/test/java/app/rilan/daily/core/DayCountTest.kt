package app.rilan.daily.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DayCount 只用 java.time，测试不碰 Android / Compose。
 * 固定「今天」= 2026-11-27，所有期望值都按这个日期算出来。
 */
class DayCountTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 27)

    private fun event(
        id: String,
        date: String,
        kind: EventKind = EventKind.COUNTDOWN,
        repeat: RepeatRule = RepeatRule.NONE,
    ): ScheduleEvent = ScheduleEvent(
        id = id,
        title = id,
        startDate = date,
        kind = kind,
        repeat = repeat,
    )

    private fun counterOf(
        date: String,
        kind: EventKind = EventKind.COUNTDOWN,
        repeat: RepeatRule = RepeatRule.NONE,
        id: String = "e",
    ): DayCounter = requireNotNull(dayCounter(event(id, date, kind, repeat), today))

    // ── daysBetween ────────────────────────────────────────────────────────

    @Test
    fun daysBetweenIsSigned() {
        assertEquals(3L, daysBetween(today, today.plusDays(3)))
        assertEquals(-1L, daysBetween(today, today.minusDays(1)))
        assertEquals(0L, daysBetween(today, today))
    }

    // ── 单次事件：过去 / 未来 / 今天 ──────────────────────────────────────

    @Test
    fun oneShotFutureShowsRemainingDays() {
        val result = counterOf("2026-12-07")
        assertEquals(Direction.FUTURE, result.direction)
        assertEquals(10L, result.days)
        assertEquals("还有 10 天", result.headline)
        assertEquals("2026-12-07", result.anchor.toString())
        assertNull(result.ordinal)
    }

    @Test
    fun oneShotPastShowsElapsedDays() {
        val result = counterOf("2026-11-01")
        assertEquals(Direction.PAST, result.direction)
        assertEquals(26L, result.days)
        assertEquals("已经 26 天", result.headline)
        // days 一律是非负的「天数」，方向看 direction。
        assertTrue(result.days >= 0L)
        assertEquals("2026-11-01", result.anchor.toString())
    }

    @Test
    fun oneShotTodayIsToday() {
        val result = counterOf("2026-11-27")
        assertEquals(Direction.TODAY, result.direction)
        assertEquals(0L, result.days)
        assertEquals("就是今天", result.headline)
    }

    // ── 每年重复：下一次与 ordinal ────────────────────────────────────────

    @Test
    fun yearlyBirthdayUsesNextOccurrenceAndOrdinal() {
        val result = counterOf("1990-03-08", kind = EventKind.BIRTHDAY, repeat = RepeatRule.YEARLY)
        // 今年的 3 月 8 日已经过去，下一次是 2027 年。
        assertEquals("2027-03-08", result.anchor.toString())
        assertEquals(101L, result.days)
        assertEquals(Direction.FUTURE, result.direction)
        // 第一个周年为第 1 个，所以 2027 年是第 37 个。
        assertEquals(37, result.ordinal)
        assertTrue(result.detail.contains("第 37 个生日"))
        assertTrue(result.detail.contains("已经 13413 天"))
    }

    @Test
    fun yearlyAnniversaryCrossesYearBoundary() {
        val result = counterOf("2020-05-01", kind = EventKind.ANNIVERSARY, repeat = RepeatRule.YEARLY)
        assertEquals("2027-05-01", result.anchor.toString())
        // 排序仍按「距下一次周年」，但卡片主数字是「已经过了多少天」。
        assertEquals(155L, result.days)
        assertEquals(7, result.ordinal)
        assertEquals(2401L, result.primaryDays)
        assertEquals("已经", result.primaryLabel)
        assertEquals("已经 2401 天", result.headline)
        assertTrue(result.detail.contains("第 7 个纪念日"))
        assertTrue(result.detail.contains("还有 155 天"))
    }

    @Test
    fun birthdayKeepsCountdownAsPrimaryNumber() {
        val result = counterOf("1990-03-08", kind = EventKind.BIRTHDAY, repeat = RepeatRule.YEARLY)
        // 生日的主角是「还有几天」，和纪念日相反。
        assertEquals(101L, result.primaryDays)
        assertEquals("还有", result.primaryLabel)
        assertTrue(result.detail.contains("第 37 个生日"))
        assertTrue(result.detail.contains("已经 13413 天"))
    }

    @Test
    fun yearlyOnTheDayIsToday() {
        val onDay = LocalDate.of(2026, 3, 8)
        val result = requireNotNull(
            dayCounter(event("birthday", "1990-03-08", EventKind.BIRTHDAY, RepeatRule.YEARLY), onDay)
        )
        assertEquals(Direction.TODAY, result.direction)
        assertEquals(0L, result.days)
        assertEquals(36, result.ordinal)
        assertEquals("就是今天", result.headline)
    }

    @Test
    fun birthdayStoredAsNoneStillCountsYearly() {
        // 旧数据 / MCP 写入可能漏了重复规则，生日不应因此变成单次。
        val result = counterOf("1990-03-08", kind = EventKind.BIRTHDAY, repeat = RepeatRule.NONE)
        assertEquals("2027-03-08", result.anchor.toString())
        assertEquals(37, result.ordinal)
    }

    // ── 2 月 29 日 ────────────────────────────────────────────────────────

    @Test
    fun feb29BirthdayClampsToFeb28InCommonYears() {
        val birthday = event("leap", "2020-02-29", EventKind.BIRTHDAY, RepeatRule.YEARLY)

        val before = requireNotNull(dayCounter(birthday, LocalDate.of(2021, 2, 1)))
        assertEquals("2021-02-28", before.anchor.toString())
        assertEquals(27L, before.days)
        assertEquals(1, before.ordinal)

        val onClampedDay = requireNotNull(dayCounter(birthday, LocalDate.of(2021, 2, 28)))
        assertEquals(Direction.TODAY, onClampedDay.direction)
        assertEquals(0L, onClampedDay.days)
    }

    @Test
    fun feb29BirthdayKeepsFeb29InLeapYears() {
        val birthday = event("leap", "2020-02-29", EventKind.BIRTHDAY, RepeatRule.YEARLY)
        val result = requireNotNull(dayCounter(birthday, LocalDate.of(2024, 2, 1)))
        assertEquals("2024-02-29", result.anchor.toString())
        assertEquals(28L, result.days)
        assertEquals(4, result.ordinal)
    }

    // ── 排序 ──────────────────────────────────────────────────────────────

    @Test
    fun sortPutsUpcomingFirstThenPast() {
        val sorted = sortCounters(
            listOf(
                counterOf("2026-10-01", kind = EventKind.COUNTDOWN, id = "past"),
                counterOf("2027-01-01", kind = EventKind.ANNIVERSARY, repeat = RepeatRule.YEARLY, id = "later"),
                counterOf("2026-12-01", kind = EventKind.BIRTHDAY, repeat = RepeatRule.YEARLY, id = "sooner"),
                counterOf("2026-11-27", kind = EventKind.COUNTDOWN, id = "today"),
            )
        )
        // 未过期按剩余天数升序（今天最先），已过去的排在最后。
        assertEquals(listOf("today", "sooner", "later", "past"), sorted.map { it.event.id })
        assertTrue(sorted.take(3).all { it.direction != Direction.PAST })
        assertEquals(Direction.PAST, sorted.last().direction)
    }

    @Test
    fun sortIsDeterministicForSameDay() {
        val a = counterOf("2026-12-05", id = "a")
        val b = counterOf("2026-12-05", id = "b")
        assertEquals(listOf("a", "b"), sortCounters(listOf(b, a)).map { it.event.id })
        assertEquals(listOf("a", "b"), sortCounters(listOf(a, b)).map { it.event.id })
    }

    // ── 只统计纪念日 / 生日 / 倒计时 ──────────────────────────────────────

    @Test
    fun onlyDayCountKindsAreBuilt() {
        val events = listOf(
            event("task", "2026-12-01", kind = EventKind.TASK, repeat = RepeatRule.NONE),
            event("memo", "2026-12-01", kind = EventKind.MEMO, repeat = RepeatRule.NONE),
            event("anniv", "2020-12-01", kind = EventKind.ANNIVERSARY, repeat = RepeatRule.YEARLY),
        )
        val built = buildDayCounters(events, today)
        assertEquals(1, built.size)
        assertEquals("anniv", built.single().event.id)
        assertEquals(Direction.FUTURE, built.single().direction)
    }

    @Test
    fun badDateIsIgnored() {
        assertNull(dayCounter(event("bad", "not-a-date", kind = EventKind.COUNTDOWN), today))
    }
}
