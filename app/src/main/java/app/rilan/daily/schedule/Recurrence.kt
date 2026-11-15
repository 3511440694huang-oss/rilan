package app.rilan.daily.schedule

import app.rilan.daily.core.EventKind
import app.rilan.daily.core.RepeatRule
import app.rilan.daily.core.ScheduleEvent
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 重复规则与「下一次发生日」的计算。
 *
 * 只用 java.time，日期一律按本地时区理解；不依赖 Android / Compose，可在桌面 JVM 上直接单测。
 */
object Recurrence {

    /** 向前试探的上限，只用于兜住坏数据，正常几个月 / 几年内必然命中。 */
    private const val MONTH_LOOKAHEAD = 60
    private const val YEAR_LOOKAHEAD = 12

    /** 容错：允许 2024-1-5 这种非补零写法。 */
    private val FALLBACK_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-M-d")

    /**
     * 返回 >= [from] 的最近一次发生日；不重复且已经过去时返回 null。
     */
    fun nextOccurrence(event: ScheduleEvent, from: LocalDate = LocalDate.now()): LocalDate? {
        val start = parseDate(event.startDate) ?: return null
        if (!from.isAfter(start)) return start
        return when (ruleOf(event)) {
            RepeatRule.NONE -> null
            RepeatRule.DAILY -> from
            RepeatRule.WEEKLY -> weeklyNext(start, from)
            RepeatRule.MONTHLY -> monthlyNext(start, from)
            RepeatRule.YEARLY -> yearlyNext(start, from)
        }
    }

    /**
     * 距离下一次发生日还有几天；今天就是发生日时为 0，没有下一次时返回 null。
     */
    fun daysUntil(event: ScheduleEvent, today: LocalDate = LocalDate.now()): Long? =
        nextOccurrence(event, today)?.let { ChronoUnit.DAYS.between(today, it) }

    /**
     * 精确判断 [date] 是否为发生日。
     */
    fun occursOn(event: ScheduleEvent, date: LocalDate): Boolean {
        val start = parseDate(event.startDate) ?: return false
        if (date.isBefore(start)) return false
        return when (ruleOf(event)) {
            RepeatRule.NONE -> date == start
            RepeatRule.DAILY -> true
            RepeatRule.WEEKLY -> date.dayOfWeek == start.dayOfWeek
            RepeatRule.MONTHLY -> date.dayOfMonth == start.dayOfMonth
            RepeatRule.YEARLY -> yearlyDateIn(start, date.year) == date
        }
    }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    /**
     * 生日业务上默认每年：BIRTHDAY 若被存成 NONE，仍按 YEARLY 算（容错）。
     * 其余情况一律以 event.repeat 为准。
     */
    private fun ruleOf(event: ScheduleEvent): RepeatRule =
        if (event.kind == EventKind.BIRTHDAY && event.repeat == RepeatRule.NONE) {
            RepeatRule.YEARLY
        } else {
            event.repeat
        }

    private fun parseDate(raw: String): LocalDate? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { LocalDate.parse(text) }
            .recoverCatching { LocalDate.parse(text, FALLBACK_DATE) }
            .getOrNull()
    }

    private fun weeklyNext(start: LocalDate, from: LocalDate): LocalDate {
        val shift = (start.dayOfWeek.value - from.dayOfWeek.value + 7) % 7
        return from.plusDays(shift.toLong())
    }

    /** 每月同日；该月没有这一天（如 31 号）就跳过这个月。 */
    private fun monthlyNext(start: LocalDate, from: LocalDate): LocalDate? {
        val day = start.dayOfMonth
        var month = YearMonth.from(from)
        var step = 0
        while (step++ < MONTH_LOOKAHEAD) {
            if (day <= month.lengthOfMonth()) {
                val candidate = month.atDay(day)
                if (!candidate.isBefore(from)) return candidate
            }
            month = month.plusMonths(1)
        }
        return null
    }

    private fun yearlyNext(start: LocalDate, from: LocalDate): LocalDate? {
        var year = from.year
        var step = 0
        while (step++ < YEAR_LOOKAHEAD) {
            val candidate = yearlyDateIn(start, year)
            if (!candidate.isBefore(from)) return candidate
            year++
        }
        return null
    }

    /** 每年同月同日；2/29 在平年退到 2/28。 */
    private fun yearlyDateIn(start: LocalDate, year: Int): LocalDate {
        val leapDayFallback = start.monthValue == 2 && start.dayOfMonth == 29 && !Year.isLeap(year.toLong())
        return if (leapDayFallback) {
            LocalDate.of(year, 2, 28)
        } else {
            LocalDate.of(year, start.monthValue, start.dayOfMonth)
        }
    }
}
