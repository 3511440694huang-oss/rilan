package app.rilan.daily.ui.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.SectionLabel
import app.rilan.daily.ui.components.TextAction
import app.rilan.daily.ui.theme.RilanColors

/**
 * 「每日通知」与「开机自启动」设置区。
 *
 * 纯展示 + 回调，不直接读写仓库 / SharedPreferences，方便在扩展页任意段落复用。
 *
 * phase2 接线示例：
 * ```
 * NotifyAutoStartSection(
 *     dailyEnabled = settings.dailyNoticeEnabled,
 *     minuteOfDay = settings.dailyNoticeMinuteOfDay,
 *     autoStartEnabled = settings.autoStartEnabled,
 *     notificationsGranted = notificationsGranted,
 *     onDailyEnabledChange = { enabled ->
 *         onSaveSettings(settings.copy(dailyNoticeEnabled = enabled))
 *         DailyBriefNotifier.schedule(context, enabled, settings.dailyNoticeMinuteOfDay / 60, ...)
 *     },
 *     onMinuteOfDayChange = { minute -> ... },
 *     onSendTest = { DailyBriefNotifier.sendNow(context) },
 *     onAutoStartEnabledChange = { enabled -> ... },
 *     onRequestNotifications = ::requestNotifications,
 * )
 * ```
 *
 * @param dailyEnabled 「每日通知」总开关
 * @param minuteOfDay 触发时间，当天第几分钟（0..1439），默认 08:00 = 480
 * @param autoStartEnabled 「开机自启动」开关
 * @param notificationsGranted 通知权限是否可用（调用方用 MainViewModel.notificationsEnabled() 判断）
 */
@Composable
fun NotifyAutoStartSection(
    dailyEnabled: Boolean,
    minuteOfDay: Int,
    autoStartEnabled: Boolean,
    notificationsGranted: Boolean,
    onDailyEnabledChange: (Boolean) -> Unit,
    onMinuteOfDayChange: (Int) -> Unit,
    onSendTest: () -> Unit,
    onAutoStartEnabledChange: (Boolean) -> Unit,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val minute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            strong = true,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel(
                    "每日通知",
                    RilanColors.Moon,
                    trailing = {
                        Text(
                            if (dailyEnabled) formatDailyTime(minute) else "已关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (dailyEnabled) {
                                RilanColors.Moon.copy(alpha = 0.92f)
                            } else {
                                RilanColors.TextTertiary
                            },
                        )
                    },
                )
                NotifySwitchRow(
                    title = "今日简报",
                    subtitle = "每天固定时间发一条天气与待办摘要",
                    checked = dailyEnabled,
                    onChange = onDailyEnabledChange,
                )
                if (dailyEnabled) {
                    NotifyTimeStepper(minute, onMinuteOfDayChange)
                    NotifyPresetRow(minute, onMinuteOfDayChange)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "非精确闹钟，可能晚几分钟；点右边可以立即确认效果。",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                    TextAction("试发一条", onSendTest, color = RilanColors.Mint)
                }
                if (!notificationsGranted) {
                    NotifyPermissionHint(onRequestNotifications)
                }
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("开机自启动", RilanColors.Dawn)
                NotifySwitchRow(
                    title = "开机后自动恢复",
                    subtitle = "恢复后台服务与每日通知，不直接打开界面",
                    checked = autoStartEnabled,
                    onChange = onAutoStartEnabledChange,
                )
                Text(
                    "系统限制：开机后只恢复后台服务与通知，不直接弹出界面；" +
                        "部分手机（MIUI、EMUI、ColorOS 等）还需要在系统设置里允许「自启动」，" +
                        "否则开机后仍可能恢复失败。",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 私有组件：开关行、时间步进、预设时间、权限引导
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotifySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (checked) RilanColors.Text else RilanColors.TextSecondary,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = notifySwitchColors(),
        )
    }
}

@Composable
private fun notifySwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = RilanColors.Ink,
    checkedTrackColor = RilanColors.Mint,
    uncheckedThumbColor = RilanColors.TextTertiary,
    uncheckedTrackColor = Color(0x1AFFFFFF),
    uncheckedBorderColor = Color(0x2AFFFFFF),
)

@Composable
private fun NotifyTimeStepper(minuteOfDay: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NotifySoftButton("- 15 分") {
            onChange((minuteOfDay - 15 + MINUTES_PER_DAY) % MINUTES_PER_DAY)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                formatDailyTime(minuteOfDay),
                style = MaterialTheme.typography.titleLarge,
                color = RilanColors.Text,
            )
        }
        NotifySoftButton("+ 15 分") {
            onChange((minuteOfDay + 15) % MINUTES_PER_DAY)
        }
    }
}

@Composable
private fun NotifyPresetRow(current: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DAILY_TIME_PRESETS.forEach { preset ->
            val selected = preset == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) RilanColors.Moon.copy(alpha = 0.18f) else Color(0x0EFFFFFF)
                    )
                    .border(
                        0.75.dp,
                        if (selected) RilanColors.Moon.copy(alpha = 0.55f) else Color(0x22FFFFFF),
                        RoundedCornerShape(11.dp),
                    )
                    .clickable { onPick(preset) }
                    .heightIn(min = 38.dp)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    formatDailyTime(preset),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) RilanColors.Moon else RilanColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun NotifySoftButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0x0EFFFFFF))
            .border(0.75.dp, Color(0x22FFFFFF), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = RilanColors.TextSecondary)
    }
}

@Composable
private fun NotifyPermissionHint(onRequest: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RilanColors.Dawn.copy(alpha = 0.10f))
            .border(0.75.dp, RilanColors.Dawn.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "通知权限未开启，每日简报不会出现。",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextSecondary,
        )
        TextAction("去开启", onRequest, color = RilanColors.Dawn)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 纯函数 / 常量
// ─────────────────────────────────────────────────────────────────────────────

private const val MINUTES_PER_DAY = 24 * 60

/** 常用预设：07:00 / 08:00 / 09:00 / 12:00。 */
private val DAILY_TIME_PRESETS = listOf(7 * 60, 8 * 60, 9 * 60, 12 * 60)

/** 当天第几分钟 → "HH:mm"。 */
private fun formatDailyTime(minuteOfDay: Int): String {
    val minute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    return "%02d:%02d".format(minute / 60, minute % 60)
}
