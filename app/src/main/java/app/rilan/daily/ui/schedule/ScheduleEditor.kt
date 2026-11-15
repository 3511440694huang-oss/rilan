@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.rilan.daily.ui.schedule

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.EventKind
import app.rilan.daily.core.Priority
import app.rilan.daily.core.RepeatRule
import app.rilan.daily.core.ScheduleEvent
import app.rilan.daily.core.newId
import app.rilan.daily.ui.components.GlassDivider
import app.rilan.daily.ui.components.GlassIconButton
import app.rilan.daily.ui.components.TextAction
import app.rilan.daily.ui.theme.RilanColors
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// 新建 / 编辑日程：深色玻璃的 ModalBottomSheet。
// 只负责表单与校验，数据一律通过回调交给上层，不碰 Prefs / Repository。
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditor(
    event: ScheduleEvent?,
    initialDate: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onAdd: (ScheduleEvent) -> Unit,
    onUpdate: (ScheduleEvent) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }

    val isNew = event == null
    val initialKind = event?.kind ?: EventKind.TASK

    var title by remember(event) { mutableStateOf(event?.title.orEmpty()) }
    var notes by remember(event) { mutableStateOf(event?.notes.orEmpty()) }
    var date by remember(event) { mutableStateOf(parseDate(event?.startDate) ?: initialDate) }
    var time by remember(event) { mutableStateOf(event?.time?.takeIf { parseHHmm(it) != null }) }
    var priority by remember(event) { mutableStateOf(event?.priority ?: Priority.MEDIUM) }
    var kind by remember(event) { mutableStateOf(initialKind) }
    var repeat by remember(event) {
        mutableStateOf(
            if (initialKind == EventKind.BIRTHDAY || initialKind == EventKind.ANNIVERSARY) {
                RepeatRule.YEARLY
            } else {
                event?.repeat ?: RepeatRule.NONE
            }
        )
    }
    var titleError by remember(event) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val repeatLocked = kind == EventKind.BIRTHDAY || kind == EventKind.ANNIVERSARY
    val sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

    // 先让数据落地，再收起面板，列表能立刻反映结果
    val dismissWith: (() -> Unit) -> Unit = { action ->
        action()
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }

    val openDatePicker: () -> Unit = {
        DatePickerDialog(
            context,
            { _, year, monthIndex, dayOfMonth ->
                date = runCatching { LocalDate.of(year, monthIndex + 1, dayOfMonth) }
                    .getOrElse { date }
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth,
        ).show()
    }

    val openTimePicker: () -> Unit = {
        val (hour, minute) = parseHHmm(time) ?: (9 to 0)
        TimePickerDialog(
            context,
            { _, pickedHour, pickedMinute ->
                time = "%02d:%02d".format(pickedHour, pickedMinute)
            },
            hour,
            minute,
            true,
        ).show()
    }

    val submit: () -> Unit = {
        val name = title.trim()
        if (name.isEmpty()) {
            titleError = true
        } else {
            titleError = false
            val saved = ScheduleEvent(
                id = event?.id?.takeIf { it.isNotBlank() } ?: newId(),
                title = name,
                notes = notes.trim(),
                startDate = date.toString(),
                time = time,
                priority = priority,
                kind = kind,
                repeat = repeat,
                doneDates = event?.doneDates.orEmpty(),
                createdAt = event?.createdAt ?: System.currentTimeMillis(),
            )
            dismissWith { if (isNew) onAdd(saved) else onUpdate(saved) }
        }
    }

    // 新建时自动聚焦标题，等面板滑上来再请求，避免键盘把面板顶歪
    LaunchedEffect(event) {
        if (isNew) {
            delay(260)
            runCatching { focusRequester.requestFocus() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = sheetShape,
        containerColor = Color.Transparent,
        contentColor = RilanColors.Text,
        scrimColor = Color(0xB3060812),
        dragHandle = null,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .clip(sheetShape)
                .background(Brush.verticalGradient(listOf(Color(0xF2121729), RilanColors.Ink)))
                .border(0.75.dp, Color(0x26FFFFFF), sheetShape),
        ) {
            // 拖拽把手
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color(0x2EFFFFFF)),
                )
            }

            // 标题行
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (isNew) "新建日程" else "编辑日程",
                        style = MaterialTheme.typography.headlineSmall,
                        color = RilanColors.Text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (isNew) "记下一件值得被提醒的事" else "改完记得保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                }
                GlassIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "关闭",
                    onClick = { dismissWith {} },
                    size = 34.dp,
                    tint = RilanColors.TextSecondary,
                )
            }

            GlassDivider()

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── 标题 ────────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("标题")
                    OutlinedTextField(
                        value = title,
                        onValueChange = { input ->
                            title = input
                            if (titleError && input.isNotBlank()) titleError = false
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        singleLine = true,
                        isError = titleError,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        placeholder = {
                            Text(
                                text = "例如：体检、交房租、妈妈生日",
                                style = MaterialTheme.typography.bodySmall,
                                color = RilanColors.TextTertiary,
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    if (titleError) {
                        Text(
                            text = "先给这件事起个名字",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.Danger,
                        )
                    }
                }

                // ── 类型 ────────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("类型")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EventKind.entries.forEach { option ->
                            SelectChip(
                                text = option.label,
                                selected = kind == option,
                                accent = kindAccent(option),
                                onClick = {
                                    kind = option
                                    if (option == EventKind.BIRTHDAY || option == EventKind.ANNIVERSARY) {
                                        repeat = RepeatRule.YEARLY
                                    } else if (repeat == RepeatRule.YEARLY) {
                                        repeat = RepeatRule.NONE
                                    }
                                },
                            )
                        }
                    }
                    if (repeatLocked) {
                        Text(
                            text = "每年同一天提醒，不需要重新设置",
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.Mint.copy(alpha = 0.85f),
                        )
                    }
                }

                // ── 日期与时间 ──────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("日期与时间")
                    InfoRow(
                        label = "日期",
                        value = formatFullDate(date),
                        onClick = openDatePicker,
                    )
                    InfoRow(
                        label = "时间",
                        value = time ?: "全天",
                        onClick = openTimePicker,
                    )
                    if (time != null) {
                        TextAction(
                            text = "清除时间，改为全天",
                            onClick = { time = null },
                            color = RilanColors.TextTertiary,
                        )
                    }
                }

                // ── 重要程度 ────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("重要程度")
                    for (option in Priority.entries) {
                        PriorityOption(
                            priority = option,
                            selected = priority == option,
                            onClick = { priority = option },
                        )
                    }
                    Text(
                        text = "选「中」或「高」后，提前的每一天都会各提醒一次，一直到当天。",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary.copy(alpha = 0.8f),
                    )
                }

                // ── 重复 ────────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("重复")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RepeatRule.entries.forEach { rule ->
                            SelectChip(
                                text = rule.label,
                                selected = repeat == rule,
                                accent = RilanColors.Mint,
                                enabled = !repeatLocked,
                                onClick = { repeat = rule },
                            )
                        }
                    }
                }

                // ── 备注 ────────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("备注")
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        minLines = 3,
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = {
                            Text(
                                text = "补充一点细节：要带的东西、地点、送给谁",
                                style = MaterialTheme.typography.bodySmall,
                                color = RilanColors.TextTertiary,
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }

            GlassDivider()

            // ── 底部按钮 ────────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!isNew) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(RilanColors.Danger.copy(alpha = 0.12f))
                            .border(1.dp, RilanColors.Danger.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
                            .clickable { confirmDelete = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                tint = RilanColors.Danger,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "删除",
                                style = MaterialTheme.typography.labelLarge,
                                color = RilanColors.Danger,
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .weight(if (isNew) 1f else 1.8f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(RilanColors.Mint)
                        .clickable(onClick = submit),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "保存",
                        style = MaterialTheme.typography.titleMedium,
                        color = RilanColors.Ink,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        val id = event?.id
                        dismissWith { if (id != null) onDelete(id) }
                    },
                ) {
                    Text("删除", color = RilanColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", color = RilanColors.TextSecondary)
                }
            },
            title = { Text("删除这个日程？", color = RilanColors.Text) },
            text = {
                Text(
                    text = "删除后无法恢复，已有的完成记录也会一起清掉。",
                    color = RilanColors.TextSecondary,
                )
            },
            containerColor = RilanColors.SurfaceRaised,
            shape = RoundedCornerShape(24.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 表单小组件
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = RilanColors.TextTertiary,
        letterSpacing = 1.2.sp,
    )
}

@Composable
private fun SelectChip(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (selected) accent.copy(alpha = 0.16f) else Color(0x12FFFFFF))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.6f) else Color(0x24FFFFFF),
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> RilanColors.TextTertiary.copy(alpha = 0.4f)
                selected -> accent
                else -> RilanColors.TextSecondary
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0FFFFFFF))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = RilanColors.TextTertiary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = RilanColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = RilanColors.TextTertiary.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PriorityOption(
    priority: Priority,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = priorityColor(priority)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.13f) else Color(0x0BFFFFFF))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.72f) else Color(0x1FFFFFFF),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = priority.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) accent else RilanColors.Text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = priorityHint(priority),
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
