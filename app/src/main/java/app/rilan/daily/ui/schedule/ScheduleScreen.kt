@file:OptIn(ExperimentalLayoutApi::class)

package app.rilan.daily.ui.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.EventKind
import app.rilan.daily.core.Priority
import app.rilan.daily.core.RepeatRule
import app.rilan.daily.core.ScheduleEvent
import app.rilan.daily.ui.components.EmptyState
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.GlassDivider
import app.rilan.daily.ui.components.GlassIconButton
import app.rilan.daily.ui.components.GlassPill
import app.rilan.daily.ui.components.ScreenTitle
import app.rilan.daily.ui.components.SectionLabel
import app.rilan.daily.ui.theme.RilanColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// ─────────────────────────────────────────────────────────────────────────────
// 日程页：标题 + 通知提示 + 月历 + 选中日清单 + 接下来 + 本月统计。
// 不含背景与底部导航，由外层宿主提供；底部留白由 bottomInset 写进滚动内容。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ScheduleScreen(
    events: List<ScheduleEvent>,
    notificationsEnabled: Boolean,
    reminderLabel: String,
    onAdd: (ScheduleEvent) -> Unit,
    onUpdate: (ScheduleEvent) -> Unit,
    onDelete: (String) -> Unit,
    onToggleDone: (String, String) -> Unit,
    onRequestNotification: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    val today = remember { LocalDate.now() }
    var selectedDate by remember { mutableStateOf(today) }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var editorVisible by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<ScheduleEvent?>(null) }

    val upcoming = remember(events, today) { buildUpcoming(events, today) }
    val (monthTotal, monthHigh) = remember(events, month) { countMonth(events, month) }

    val openNew: (LocalDate) -> Unit = { date ->
        editingEvent = null
        selectedDate = date
        month = YearMonth.from(date)
        editorVisible = true
    }
    val openEdit: (ScheduleEvent) -> Unit = { event ->
        editingEvent = event
        editorVisible = true
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            // 底部留白写进滚动内容，内容铺满整屏滚动、最后一条停在导航胶囊之上。
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 4.dp,
                bottom = 24.dp + bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ScreenTitle(
                    eyebrow = "日程",
                    title = "先安排重要的事",
                    subtitle = "提前一点，给在意的事留出位置",
                    actions = {
                        GlassIconButton(
                            icon = Icons.Filled.Add,
                            contentDescription = "新建日程",
                            onClick = { openNew(selectedDate) },
                        )
                    },
                )
            }

            item {
                MonthSummary(
                    total = monthTotal,
                    high = monthHigh,
                    reminderLabel = reminderLabel,
                )
            }

            if (!notificationsEnabled) {
                item {
                    NotificationBanner(
                        reminderLabel = reminderLabel,
                        onRequest = onRequestNotification,
                    )
                }
            }

            item {
                CalendarCard(
                    events = events,
                    selectedDate = selectedDate,
                    month = month,
                    onSelectDate = { date ->
                        selectedDate = date
                        month = YearMonth.from(date)
                    },
                    onMonthChange = { month = it },
                )
            }

            // 选中日：整块一起做切换动画，避免列表 item 各自的动画互相打架
            item {
                AnimatedContent(
                    targetState = selectedDate,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { full -> full / 14 }) togetherWith
                            (fadeOut(tween(140)) + slideOutVertically(tween(180)) { full -> -full / 20 })
                    },
                    label = "dayPanel",
                ) { date ->
                    val dayEvents = remember(events, date) { sortDayEvents(events, date) }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DayHeader(date = date, events = dayEvents)
                        if (dayEvents.isEmpty()) {
                            EmptyDayCard(onAdd = { openNew(date) })
                        } else {
                            dayEvents.forEach { event ->
                                EventRow(
                                    event = event,
                                    date = date,
                                    onOpen = { openEdit(event) },
                                    onToggleDone = { onToggleDone(event.id, date.toString()) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                UpcomingSection(items = upcoming, onOpen = openEdit)
            }

        }
    }

    if (editorVisible) {
        ScheduleEditor(
            event = editingEvent,
            initialDate = selectedDate,
            onDismiss = {
                editorVisible = false
                editingEvent = null
            },
            onAdd = onAdd,
            onUpdate = onUpdate,
            onDelete = onDelete,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 本月概览
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MonthSummary(total: Int, high: Int, reminderLabel: String) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryCell("本月", "$total 个")
            SummaryCell("高重要", "$high 个", RilanColors.Coral)
            SummaryCell("提醒", reminderLabel, RilanColors.Gold)
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, color: Color = RilanColors.Text) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
            letterSpacing = 1.sp,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = color,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 通知提示条
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotificationBanner(
    reminderLabel: String,
    onRequest: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        strong = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "开启通知，重要日程才会按时提醒",
                    style = MaterialTheme.typography.titleSmall,
                    color = RilanColors.Text,
                )
                Text(
                    text = "每天 $reminderLabel 汇总，重要日程提前 3 / 7 天逐日提醒",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(RilanColors.Mint)
                    .clickable(onClick = onRequest)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "开启",
                    style = MaterialTheme.typography.labelLarge,
                    color = RilanColors.Ink,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 选中日
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DayHeader(date: LocalDate, events: List<ScheduleEvent>) {
    val doneCount = events.count { it.isDoneOn(date.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${formatMonthDay(date)} · ${weekdayLabel(date)}",
            style = MaterialTheme.typography.titleLarge,
            color = RilanColors.Text,
        )
        Spacer(Modifier.weight(1f))
        if (events.isNotEmpty()) {
            Text(
                text = if (doneCount > 0) "$doneCount/${events.size} 已完成" else "${events.size} 个安排",
                style = MaterialTheme.typography.labelMedium,
                color = RilanColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun EmptyDayCard(onAdd: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                title = "这一天还空着",
                subtitle = "空着也很好，或者放一件想做的事。",
            )
            Box(
                Modifier
                    .padding(bottom = 20.dp)
                    .clip(CircleShape)
                    .background(RilanColors.Mint.copy(alpha = 0.14f))
                    .border(1.dp, RilanColors.Mint.copy(alpha = 0.42f), CircleShape)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    text = "添加日程",
                    style = MaterialTheme.typography.labelLarge,
                    color = RilanColors.Mint,
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    event: ScheduleEvent,
    date: LocalDate,
    onOpen: () -> Unit,
    onToggleDone: () -> Unit,
) {
    val done = event.isDoneOn(date.toString())
    val accent = priorityColor(event.priority)

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        onClick = onOpen,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .alpha(if (done) 0.62f else 1f),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(42.dp)) {
                Text(
                    text = event.time ?: "全天",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (done) RilanColors.TextTertiary else RilanColors.TextSecondary,
                    maxLines = 1,
                )
            }

            Box(
                Modifier
                    .padding(horizontal = 9.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (done) accent.copy(alpha = 0.35f) else accent),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (done) RilanColors.TextTertiary else RilanColors.Text,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.notes.isNotBlank()) {
                    Text(
                        text = event.notes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = RilanColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GlassPill(
                        text = event.priority.label,
                        color = accent,
                        background = accent.copy(alpha = 0.15f),
                    )
                    if (event.repeat != RepeatRule.NONE) {
                        GlassPill(text = event.repeat.label)
                    }
                    if (event.kind != EventKind.TASK) {
                        GlassPill(
                            text = event.kind.label,
                            color = kindAccent(event.kind),
                            background = kindAccent(event.kind).copy(alpha = 0.14f),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (done) RilanColors.Mint else Color(0x14FFFFFF))
                    .border(1.dp, if (done) RilanColors.Mint else Color(0x33FFFFFF), CircleShape)
                    .clickable(onClick = onToggleDone),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = if (done) "标记为未完成" else "标记为已完成",
                    tint = if (done) RilanColors.Ink else RilanColors.TextTertiary.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 接下来
// ─────────────────────────────────────────────────────────────────────────────

private data class UpcomingItem(
    val event: ScheduleEvent,
    val date: LocalDate,
    val days: Long,
)

@Composable
private fun UpcomingSection(
    items: List<UpcomingItem>,
    onOpen: (ScheduleEvent) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(
            text = "接下来",
            accent = RilanColors.Gold,
            trailing = {
                Text(
                    text = "未来 30 天 · ${items.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
            },
        )
        Spacer(Modifier.height(10.dp))
        if (items.isEmpty()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(18.dp),
            ) {
                Text(
                    text = "未来 30 天还没有安排，可以先松一口气。",
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextTertiary,
                )
            }
        } else {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    items.forEachIndexed { index, item ->
                        UpcomingRow(item = item, onClick = { onOpen(item.event) })
                        if (index != items.lastIndex) {
                            GlassDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(item: UpcomingItem, onClick: () -> Unit) {
    val event = item.event
    val accent = priorityColor(event.priority)
    val dayLabel = when (item.days) {
        0L -> "今天"
        1L -> "明天"
        else -> "还有 ${item.days} 天"
    }
    val soon = item.days <= 1L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (soon) RilanColors.Mint else RilanColors.TextSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${item.date.monthValue}/${item.date.dayOfMonth} ${weekdayLabel(item.date)}",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .width(3.dp)
                .height(28.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
                color = RilanColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaLine(event, item.date),
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        event.time?.let { time ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = time,
                style = MaterialTheme.typography.labelLarge,
                color = RilanColors.TextSecondary,
            )
        }
    }
}

/**
 * 重复事件显示「原始起始年」，避免把很多年前的起始日误当成即将到来的一天。
 */
private fun metaLine(event: ScheduleEvent, date: LocalDate): String {
    val parts = mutableListOf<String>()
    if (event.kind != EventKind.TASK) parts.add(event.kind.label)
    if (event.repeat != RepeatRule.NONE) {
        parts.add(event.repeat.label)
        val startYear = parseDate(event.startDate)?.year
        if (startYear != null && startYear != date.year) {
            parts.add("$startYear 年起")
        }
    }
    if (parts.isEmpty()) parts.add("单次安排")
    return parts.joinToString(" · ")
}

// ─────────────────────────────────────────────────────────────────────────────
// 底部统计
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsFooter(total: Int, high: Int, reminderLabel: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "本月 $total 个安排 · 高重要 $high 个",
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextTertiary,
        )
        Text(
            text = "提醒时间 $reminderLabel · 重要日程提前逐日提醒",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary.copy(alpha = 0.72f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 计算
// ─────────────────────────────────────────────────────────────────────────────

/** 当天发生的事件：全天在前，再按时间、优先级排。 */
private fun sortDayEvents(events: List<ScheduleEvent>, date: LocalDate): List<ScheduleEvent> =
    events.filter { eventOccursOn(it, date) }
        .sortedWith(
            compareBy<ScheduleEvent> { if (it.time == null) 0 else 1 }
                .thenBy { it.time ?: "" }
                .thenByDescending { it.priority.ordinal }
        )

/** 未来 30 天、最多 8 条；生日跨年由 Recurrence 负责。 */
private fun buildUpcoming(events: List<ScheduleEvent>, today: LocalDate): List<UpcomingItem> =
    events.mapNotNull { event ->
        val next = eventNextOccurrence(event, today) ?: return@mapNotNull null
        if (next.isBefore(today)) return@mapNotNull null
        val days = ChronoUnit.DAYS.between(today, next)
        if (days > 30L) return@mapNotNull null
        UpcomingItem(event = event, date = next, days = days)
    }
        .sortedWith(
            compareBy<UpcomingItem>(
                { it.date },
                { if (it.event.time == null) 0 else 1 },
                { it.event.time ?: "" },
            )
        )
        .take(8)

/** 月内「安排」总数与高重要条数（重复事件按每天各算一次）。 */
private fun countMonth(events: List<ScheduleEvent>, month: YearMonth): Pair<Int, Int> {
    var total = 0
    var high = 0
    for (dayNumber in 1..month.lengthOfMonth()) {
        val day = month.atDay(dayNumber)
        for (event in events) {
            if (!eventOccursOn(event, day)) continue
            total += 1
            if (event.priority == Priority.HIGH) high += 1
        }
    }
    return total to high
}
