package app.rilan.daily.core

import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// ─────────────────────────────────────────────────────────────────────────────
// 数日子：生日 / 纪念日 / 倒计时的天数是实时算出来的，不落库。
// 这里只依赖 java.time 与 core 里的模型，不碰 Android / Compose，可以直接单测。
// ─────────────────────────────────────────────────────────────────────────────

/** 倒计时方向：已经过去 / 就是今天 / 还没到。 */
enum class Direction { PAST, TODAY, FUTURE }

/**
 * 一条「数日子」的计算结果。
 *
 * [days] 是**非负**的天数：已过去的事件是「已经过去了多少天」，未到的则是「还有多少天」；
 * 方向看 [direction]，不要拿 [days] 的正负去判断过去 / 未来。
 * [ordinal] 是每年重复事件「第几个」（1-based：第一个周年为 1，起始日当天也记 1）；不重复或
 * 按月 / 周 / 天重复时为 null。
 *
 * [days] 是「到下一个锚点的距离」，用于排序和「还有 / 已经」的方向判断；
 * [primaryDays] / [primaryLabel] 才是卡片上的主数字：纪念日要的是「已经过了多少天」，
 * 生日和倒计时要的是「还有多少天」。两者在每年重复的纪念日上会不一样。
 */
data class DayCounter(
    val event: ScheduleEvent,
    val anchor: LocalDate,
    val days: Long,
    val direction: Direction,
    val ordinal: Int?,
    val headline: String,
    val detail: String,
    val primaryDays: Long = days,
    val primaryLabel: String = when (direction) {
        Direction.PAST -> "已经"
        Direction.FUTURE -> "还有"
        Direction.TODAY -> ""
    },
)

/** 两个日期之间的整天数：to 晚于 from 为正，早于 from 为负。 */
fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

/** 只有生日 / 纪念日 / 倒计时参与「数日子」，普通日程和备忘不进来。 */
fun isDayCountKind(kind: EventKind): Boolean = when (kind) {
    EventKind.BIRTHDAY, EventKind.ANNIVERSARY, EventKind.COUNTDOWN -> true
    else -> false
}

/**
 * 算出一个事件的数日子结果；不参与数日子的、日期坏掉的返回 null。
 *
 * 规则：
 * - 不重复：anchor = 起始日，过去显示「已经 N 天」，未来「还有 N 天」，当天「就是今天」。
 * - 每年重复（生日 / 纪念日的典型）：anchor = 今天及以后最近的一次周年日，
 *   当天就是今天；[DayCounter.ordinal] 从原始年份算起（第一个周年为 1）。
 * - 每天 / 每周 / 每月重复的倒计时：anchor = 下一次发生日，显示重复方式与已经过的天数。
 */
fun dayCounter(event: ScheduleEvent, today: LocalDate = LocalDate.now()): DayCounter? {
    if (!isDayCountKind(event.kind)) return null
    val start = parseEventDate(event.startDate) ?: return null
    return when (effectiveRepeat(event)) {
        RepeatRule.NONE -> oneShotCounter(event, start, today)
        RepeatRule.YEARLY -> yearlyCounter(event, start, today)
        RepeatRule.DAILY -> recurringCounter(event, start, today, RepeatRule.DAILY)
        RepeatRule.WEEKLY -> recurringCounter(event, start, today, RepeatRule.WEEKLY)
        RepeatRule.MONTHLY -> recurringCounter(event, start, today, RepeatRule.MONTHLY)
    }
}

/** 把一组事件算成数日子列表并按 [sortCounters] 排好序。 */
fun buildDayCounters(
    events: List<ScheduleEvent>,
    today: LocalDate = LocalDate.now(),
): List<DayCounter> = sortCounters(events.mapNotNull { dayCounter(it, today) })

/**
 * 排序：未到期的在前（按剩余天数升序，今天最先），已经过去的在后（按距今时间升序，
 * 刚过去的先于很久以前的）。用事件 id 兜底，保证同样的输入总是同样的顺序，方便单测。
 */
fun sortCounters(counters: List<DayCounter>): List<DayCounter> =
    counters.sortedWith(
        compareBy<DayCounter>(
            { if (it.direction == Direction.PAST) 1 else 0 },
            { it.days },
            { it.event.id },
        )
    )

// ─────────────────────────────────────────────────────────────────────────────
// 内部实现
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 生日 / 纪念日业务上默认每年。旧数据或 MCP 写进来的事件可能带 NONE，
 * 这里跟 ScheduleStore 的归一化口径保持一致，日历上也会按每年显示。
 */
private fun effectiveRepeat(event: ScheduleEvent): RepeatRule {
    val yearlyByDefault = event.kind == EventKind.BIRTHDAY || event.kind == EventKind.ANNIVERSARY
    return if (yearlyByDefault && event.repeat == RepeatRule.NONE) RepeatRule.YEARLY else event.repeat
}

private fun oneShotCounter(event: ScheduleEvent, start: LocalDate, today: LocalDate): DayCounter {
    val diff = daysBetween(today, start)
    val direction = when {
        diff > 0L -> Direction.FUTURE
        diff < 0L -> Direction.PAST
        else -> Direction.TODAY
    }
    val days = abs(diff)
    val headline = when (direction) {
        Direction.FUTURE -> "还有 $days 天"
        Direction.TODAY -> "就是今天"
        Direction.PAST -> "已经 $days 天"
    }
    val detail = when (direction) {
        Direction.FUTURE -> "单次，不会重复"
        Direction.TODAY -> "从今天开始数"
        // 大数字只有「26 天」，这句补上方向，避免被读成还剩 26 天。
        Direction.PAST -> "从那天起，一直数到现在"
    }
    return DayCounter(event, start, days, direction, null, headline, detail)
}

private fun yearlyCounter(event: ScheduleEvent, start: LocalDate, today: LocalDate): DayCounter {
    val anchor = nextYearlyDate(start, today)
    val days = daysBetween(today, anchor)
    val direction = if (days == 0L) Direction.TODAY else Direction.FUTURE
    // 第几个生日 / 纪念日按年差算：2021 年过 2020 年的纪念日就是第 1 个。
    // 起始日当天（年差 0）也记第 1 个，避免出现「第 0 个」。
    val ordinal = (anchor.year - start.year).coerceAtLeast(1)
    // 距起始日已经走过的天数：生日是「活了多少天」，纪念日是「认识 / 在一起多少天」。
    val elapsed = daysBetween(start, today).coerceAtLeast(0L)
    // 纪念日的主角是「已经一起走过多少天」，生日的主角是「还有几天到」——
    // 同一个每年重复的模型，两种日子的主数字不是同一个。
    val anniversary = event.kind == EventKind.ANNIVERSARY
    val primaryDays = if (anniversary) elapsed else days
    val primaryLabel = when {
        direction == Direction.TODAY -> ""
        anniversary -> "已经"
        else -> "还有"
    }
    val headline = when {
        direction == Direction.TODAY -> "就是今天"
        anniversary -> "已经 $elapsed 天"
        else -> "还有 $days 天"
    }
    val detail = buildString {
        when (event.kind) {
            EventKind.BIRTHDAY -> append("第 $ordinal 个生日")
            EventKind.ANNIVERSARY -> {
                append("第 $ordinal 个纪念日")
                // 主数字是已经过的天数，下一次周年的倒计时放在副行，信息不丢。
                if (days > 0L) append(" · 还有 $days 天") else append(" · 就是今天")
                return@buildString
            }
            else -> append("第 $ordinal 次")
        }
        if (elapsed > 0L) {
            append(" · 已经 $elapsed 天")
        } else {
            append(" · 从 ${formatDate(start)} 开始")
        }
    }
    return DayCounter(
        event = event,
        anchor = anchor,
        days = days,
        direction = direction,
        ordinal = ordinal,
        headline = headline,
        detail = detail,
        primaryDays = primaryDays,
        primaryLabel = primaryLabel,
    )
}

private fun recurringCounter(
    event: ScheduleEvent,
    start: LocalDate,
    today: LocalDate,
    rule: RepeatRule,
): DayCounter {
    val anchor = when (rule) {
        RepeatRule.DAILY -> if (start.isAfter(today)) start else today
        RepeatRule.WEEKLY -> nextWeeklyDate(start, today)
        RepeatRule.MONTHLY -> nextMonthlyDate(start, today)
        else -> today
    }
    val days = daysBetween(today, anchor).coerceAtLeast(0L)
    val direction = if (days == 0L) Direction.TODAY else Direction.FUTURE
    val elapsed = daysBetween(start, today).coerceAtLeast(0L)
    val headline = if (direction == Direction.TODAY) "就是今天" else "还有 $days 天"
    val detail = buildString {
        append(rule.label)
        append("重复")
        if (elapsed > 0L) append(" · 已经 $elapsed 天")
    }
    return DayCounter(event, anchor, days, direction, null, headline, detail)
}

/** 下一次生日 / 纪念日：今天及以后的最近一次。 */
private fun nextYearlyDate(start: LocalDate, today: LocalDate): LocalDate {
    val year = maxOf(start.year, today.year)
    val candidate = yearlyDateIn(start, year)
    return if (candidate.isBefore(today)) yearlyDateIn(start, year + 1) else candidate
}

/**
 * 每年同月同日；2 月 29 日在平年钳制到 2 月 28 日。
 * 闰日出生 / 纪念的人不能因为当年没有 2/29 就整年消失，所以按 2/28 过。
 */
private fun yearlyDateIn(start: LocalDate, year: Int): LocalDate {
    val clampLeapDay = start.monthValue == 2 && start.dayOfMonth == 29 && !Year.isLeap(year.toLong())
    return if (clampLeapDay) {
        LocalDate.of(year, 2, 28)
    } else {
        LocalDate.of(year, start.monthValue, start.dayOfMonth)
    }
}

private fun nextWeeklyDate(start: LocalDate, today: LocalDate): LocalDate {
    if (start.isAfter(today)) return start
    val shift = (start.dayOfWeek.value - today.dayOfWeek.value + 7) % 7
    return today.plusDays(shift.toLong())
}

/** 每月同一天；该月没有这一天（如 31 号）就顺延到下一个月。 */
private fun nextMonthlyDate(start: LocalDate, today: LocalDate): LocalDate {
    if (start.isAfter(today)) return start
    val day = start.dayOfMonth
    var month = YearMonth.from(today)
    repeat(13) {
        if (day <= month.lengthOfMonth()) {
            val candidate = month.atDay(day)
            if (!candidate.isBefore(today)) return candidate
        }
        month = month.plusMonths(1)
    }
    return today
}

private val LOOSE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-M-d")

/** 允许 2024-1-5 这种非补零写法；坏数据返回 null，不让列表崩掉。 */
private fun parseEventDate(raw: String?): LocalDate? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { LocalDate.parse(text) }
        .recoverCatching { LocalDate.parse(text, LOOSE_DATE) }
        .getOrNull()
}

private fun formatDate(date: LocalDate): String =
    "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
