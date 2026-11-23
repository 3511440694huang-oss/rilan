package app.rilan.daily.ui.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.EventKind
import app.rilan.daily.core.Priority
import app.rilan.daily.core.ScheduleEvent
import app.rilan.daily.schedule.Recurrence
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.GlassIconButton
import app.rilan.daily.ui.theme.RilanColors
import java.time.LocalDate
import java.time.YearMonth

// ─────────────────────────────────────────────────────────────────────────────
// 月历卡：周一开头的 6×7 网格。
// 本身不滚动（外层已是 LazyColumn），所以不存在嵌套滚动冲突；
// 左右滑动切月用 pointerInput 里的水平拖拽手势，竖直手势照旧交给外层列表。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CalendarCard(
    events: List<ScheduleEvent>,
    selectedDate: LocalDate,
    month: YearMonth,
    onSelectDate: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 10.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${month.year} 年 ${month.monthValue} 月",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = RilanColors.Text,
                )
                Spacer(Modifier.weight(1f))
                MonthChip("今天") {
                    onMonthChange(YearMonth.from(today))
                    onSelectDate(today)
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    icon = Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "上个月",
                    onClick = { onMonthChange(month.minusMonths(1)) },
                    size = 32.dp,
                    tint = RilanColors.TextSecondary,
                )
                Spacer(Modifier.width(6.dp))
                GlassIconButton(
                    icon = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "下个月",
                    onClick = { onMonthChange(month.plusMonths(1)) },
                    size = 32.dp,
                    tint = RilanColors.TextSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth()) {
                WEEKDAY_LABELS.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (index >= 5) RilanColors.Mint.copy(alpha = 0.7f) else RilanColors.TextTertiary,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            SwipeableMonthGrid(
                events = events,
                month = month,
                selectedDate = selectedDate,
                today = today,
                onSelectDate = onSelectDate,
                onMonthChange = onMonthChange,
            )
        }
    }
}

@Composable
private fun SwipeableMonthGrid(
    events: List<ScheduleEvent>,
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
) {
    // pointerInput 的 key 是 Unit，用 rememberUpdatedState 拿到最新的回调，避免手势里读到旧闭包
    val currentMonth by rememberUpdatedState(month)
    val changeMonth by rememberUpdatedState(onMonthChange)

    Box(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                // 跨机型手势仲裁：用框架的方向锁原语代替手写阈值判定。
                // awaitHorizontalTouchSlopOrCancellation 用系统 touchSlop 锁定方向，
                // 横向未占优时返回 null 并把整串位移让给父 LazyColumn——斜着上下滑
                // 不会再「一边翻月一边滚列表」；不同机型 density / ROM touchSlop
                // 差异也由框架统一处理，不再出现某些机型滑不动。
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // 多指（缩放 / 系统手势）介入时直接放弃本次手势，不消费任何位移。
                    if (awaitPointerEvent().changes.count { it.pressed } > 1) return@awaitEachGesture
                    var totalX = 0f
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume() // 只有在横向锁定后才消费，纵向一律留给父容器
                        totalX += over
                    }
                    if (drag == null) return@awaitEachGesture
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        totalX += change.positionChange().x
                        change.consume()
                        if (!change.pressed) break
                    }
                    // 翻月阈值按网格宽度取比例，小屏 / 平板所需距离一致。
                    val step = size.width * 0.22f
                    if (totalX <= -step) changeMonth(currentMonth.plusMonths(1))
                    else if (totalX >= step) changeMonth(currentMonth.minusMonths(1))
                }
            },
    ) {
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(tween(260)) { full -> full / 3 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(220)) { full -> -full / 3 } + fadeOut(tween(140)))
                } else {
                    (slideInHorizontally(tween(260)) { full -> -full / 3 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(220)) { full -> full / 3 } + fadeOut(tween(140)))
                }
            },
            label = "calendarMonth",
        ) { targetMonth ->
            val days = remember(targetMonth) { gridDaysOf(targetMonth) }
            val marks = remember(targetMonth, events) { buildDayMarks(events, days) }

            Column(Modifier.fillMaxWidth()) {
                for (row in 0 until 6) {
                    Row(Modifier.fillMaxWidth()) {
                        for (column in 0 until 7) {
                            val day = days[row * 7 + column]
                            DayCell(
                                date = day,
                                inMonth = YearMonth.from(day) == targetMonth,
                                selected = day == selectedDate,
                                isToday = day == today,
                                mark = marks[day],
                                onClick = { onSelectDate(day) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    mark: DayMark?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dim = if (inMonth) 1f else 0.38f
    Box(
        modifier
            .height(46.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .then(if (selected) Modifier.background(RilanColors.Mint) else Modifier)
                    .then(
                        if (isToday && !selected) {
                            Modifier.border(1.dp, RilanColors.Mint.copy(alpha = 0.75f), CircleShape)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    fontWeight = if (selected || isToday) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        selected -> RilanColors.Ink
                        !inMonth -> RilanColors.TextTertiary.copy(alpha = 0.55f)
                        isToday -> RilanColors.Mint
                        else -> RilanColors.Text
                    },
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(
                Modifier.height(9.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (mark?.hasBirthday == true) {
                    CakeMark(RilanColors.Lavender.copy(alpha = dim))
                }
                val top = mark?.topPriority
                if (top != null) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(priorityColor(top).copy(alpha = if (selected) 0.85f else dim)),
                    )
                }
            }
        }
    }
}

/** 生日标记：小蛋糕（Canvas 画，不依赖 emoji 字体）。 */
@Composable
private fun CakeMark(color: Color) {
    Canvas(Modifier.size(9.dp)) {
        val w = size.width
        val h = size.height
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.08f),
            end = Offset(w * 0.5f, h * 0.42f),
            strokeWidth = w * 0.1f,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = RilanColors.Gold.copy(alpha = color.alpha),
            radius = w * 0.11f,
            center = Offset(w * 0.5f, h * 0.07f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.46f),
            size = Size(w * 0.84f, h * 0.5f),
            cornerRadius = CornerRadius(w * 0.14f),
        )
    }
}

@Composable
private fun MonthChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(RilanColors.Mint.copy(alpha = 0.14f))
            .border(1.dp, RilanColors.Mint.copy(alpha = 0.36f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = RilanColors.Mint,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 网格数据
// ─────────────────────────────────────────────────────────────────────────────

private data class DayMark(
    val hasBirthday: Boolean,
    val topPriority: Priority?,
)

/** 固定 42 格：本月 1 号所在周的周一作为第一格。 */
private fun gridDaysOf(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    return List(42) { index -> start.plusDays(index.toLong()) }
}

/** 每一天的最高优先级（高 > 中 > 低）和是否含生日，用于小圆点与蛋糕标记。 */
private fun buildDayMarks(
    events: List<ScheduleEvent>,
    days: List<LocalDate>,
): Map<LocalDate, DayMark> {
    val marks = HashMap<LocalDate, DayMark>(days.size)
    for (day in days) {
        var birthday = false
        var top: Priority? = null
        for (event in events) {
            if (!eventOccursOn(event, day)) continue
            if (event.kind == EventKind.BIRTHDAY) birthday = true
            val current = top
            if (current == null || event.priority.ordinal > current.ordinal) {
                top = event.priority
            }
        }
        if (birthday || top != null) marks[day] = DayMark(birthday, top)
    }
    return marks
}

// ─────────────────────────────────────────────────────────────────────────────
// 跨文件共用的小工具
// ─────────────────────────────────────────────────────────────────────────────

internal val WEEKDAY_LABELS: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")

internal fun weekdayLabel(date: LocalDate): String =
    "周" + WEEKDAY_LABELS[(date.dayOfWeek.value - 1).coerceIn(0, 6)]

internal fun formatMonthDay(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

internal fun formatFullDate(date: LocalDate): String =
    "${date.year}年${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabel(date)}"

internal fun parseDate(raw: String?): LocalDate? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { LocalDate.parse(text) }.getOrNull()
}

/** "HH:mm" → (时, 分)，非法值返回 null。 */
internal fun parseHHmm(raw: String?): Pair<Int, Int>? {
    val parts = raw?.split(":") ?: return null
    if (parts.size != 2) return null
    val hour = parts[0].trim().toIntOrNull() ?: return null
    val minute = parts[1].trim().toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

/** 优先级配色：高珊瑚 / 中金 / 低薄荷。 */
internal fun priorityColor(priority: Priority): Color = when (priority) {
    Priority.LOW -> RilanColors.Mint
    Priority.MEDIUM -> RilanColors.Gold
    Priority.HIGH -> RilanColors.Coral
}

internal fun priorityHint(priority: Priority): String = when (priority) {
    Priority.LOW -> "当天提醒"
    Priority.MEDIUM -> "提前 3 天，这 3 天每天提醒"
    Priority.HIGH -> "提前 7 天，这 7 天每天提醒"
}

internal fun kindAccent(kind: EventKind): Color = when (kind) {
    EventKind.TASK -> RilanColors.Mint
    EventKind.BIRTHDAY -> RilanColors.Lavender
    EventKind.ANNIVERSARY -> RilanColors.Gold
    EventKind.MEMO -> RilanColors.Blue
}

/**
 * 包一层 runCatching：日程数据来自本地 JSON，坏数据不应该让整个月历崩掉。
 * 正常路径完全交给 [Recurrence]。
 */
internal fun eventOccursOn(event: ScheduleEvent, date: LocalDate): Boolean =
    runCatching { Recurrence.occursOn(event, date) }
        .getOrElse { parseDate(event.startDate) == date }

internal fun eventNextOccurrence(event: ScheduleEvent, from: LocalDate): LocalDate? =
    runCatching { Recurrence.nextOccurrence(event, from) }.getOrNull()
