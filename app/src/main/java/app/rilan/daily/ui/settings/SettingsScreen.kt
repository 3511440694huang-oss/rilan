package app.rilan.daily.ui.settings

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rilan.daily.BuildConfig
import app.rilan.daily.core.AiTestState
import app.rilan.daily.core.AppSettings
import app.rilan.daily.core.CityResult
import app.rilan.daily.core.Priority
import app.rilan.daily.core.SettingsUiState
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.GlassDivider
import app.rilan.daily.ui.components.GlassIconButton
import app.rilan.daily.ui.components.GlassPill
import app.rilan.daily.ui.components.LoadingDots
import app.rilan.daily.ui.components.SectionLabel
import app.rilan.daily.ui.components.TextAction
import app.rilan.daily.ui.theme.RilanColors

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onSearchCity: (String) -> Unit,
    onChooseCity: (CityResult) -> Unit,
    aiTest: AiTestState,
    onTestDeepSeek: (String, String) -> Unit,
    onClearAiContent: () -> Unit,
    onTestNotification: () -> Unit,
    onOpenExtensions: () -> Unit,
    onRelocate: () -> Unit,
    onAutoLocationChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cityQuery by remember { mutableStateOf("") }
    var confirmClearAi by remember { mutableStateOf(false) }
    val settings = state.settings

    fun saved() {
        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(
                    icon = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.headlineLarge,
                        color = RilanColors.Text,
                    )
                    Text(
                        "位置、提醒与本地数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = RilanColors.TextTertiary,
                    )
                }
            }
        }

        // ── 位置 ─────────────────────────────────────────────────────────────
        item {
            GlassCard(Modifier.fillMaxWidth(), strong = true) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("位置", RilanColors.Mint, Modifier.weight(1f))
                        GlassPill(
                            text = if (settings.autoLocation) "自动" else "手动",
                            color = if (settings.autoLocation) RilanColors.Mint else RilanColors.TextTertiary,
                            background = (if (settings.autoLocation) RilanColors.Mint else Color.White)
                                .copy(alpha = 0.10f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                settings.city,
                                style = MaterialTheme.typography.headlineSmall,
                                color = RilanColors.Text,
                            )
                            Text(
                                "%.2f, %.2f".format(settings.latitude, settings.longitude),
                                style = MaterialTheme.typography.labelSmall,
                                color = RilanColors.TextTertiary,
                            )
                        }
                        Switch(
                            checked = settings.autoLocation,
                            onCheckedChange = { onAutoLocationChange(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = RilanColors.Ink,
                                checkedTrackColor = RilanColors.Mint,
                                uncheckedThumbColor = RilanColors.TextTertiary,
                                uncheckedTrackColor = Color(0x18FFFFFF),
                                uncheckedBorderColor = Color(0x2AFFFFFF),
                            ),
                        )
                    }
                    Text(
                        if (settings.autoLocation) {
                            "天气按系统位置更新，大约每 30 分钟校准一次。位置只用于天气，不会上传。"
                        } else {
                            "当前使用手动城市。打开自动定位，或搜索下面的城市。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = RilanColors.TextTertiary,
                    )

                    if (settings.autoLocation) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton(text = "重新定位", onClick = onRelocate)
                            TextAction(
                                "改为手动",
                                onClick = { onAutoLocationChange(false) },
                                color = RilanColors.TextSecondary,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = cityQuery,
                                onValueChange = { cityQuery = it },
                                placeholder = { Text("搜索城市，如 上海 / 成都") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { onSearchCity(cityQuery) }),
                                leadingIcon = {
                                    Icon(Icons.Filled.Search, contentDescription = null, tint = RilanColors.TextTertiary)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(15.dp),
                                colors = fieldColors(),
                            )
                            Spacer(Modifier.width(8.dp))
                            SecondaryButton(
                                text = "搜索",
                                onClick = { onSearchCity(cityQuery) },
                                enabled = cityQuery.isNotBlank() && !state.citySearching,
                            )
                        }
                        if (state.citySearching) LoadingDots(RilanColors.Mint)
                        if (state.cityResults.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.cityResults.forEach { result ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(Color(0x0FFFFFFF))
                                            .clickable {
                                                onChooseCity(result)
                                                cityQuery = ""
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            result.display,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = RilanColors.Text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("选择", style = MaterialTheme.typography.labelSmall, color = RilanColors.Mint)
                                    }
                                }
                            }
                        }
                        TextAction(
                            "打开自动定位",
                            onClick = { onAutoLocationChange(true) },
                            color = RilanColors.Mint,
                        )
                    }
                }
            }
        }

        // ── 提醒 ─────────────────────────────────────────────────────────────
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("日程提醒", RilanColors.Gold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每天检查时间", style = MaterialTheme.typography.titleSmall, color = RilanColors.Text)
                            Text(
                                "到点后逐日提醒高重要日程",
                                style = MaterialTheme.typography.labelSmall,
                                color = RilanColors.TextTertiary,
                            )
                        }
                        SecondaryButton(
                            text = settings.reminderLabel,
                            onClick = {
                                val hour = settings.reminderMinuteOfDay / 60
                                val minute = settings.reminderMinuteOfDay % 60
                                TimePickerDialog(
                                    context,
                                    { _, h, m ->
                                        onSave(settings.copy(reminderMinuteOfDay = h * 60 + m))
                                        saved()
                                    },
                                    hour,
                                    minute,
                                    true,
                                ).show()
                            },
                        )
                    }
                    GlassDivider()
                    PriorityHelp()
                    SecondaryButton(
                        text = "发送测试通知",
                        onClick = onTestNotification,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "低重要当天提醒；中重要提前 3 天逐日提醒；高重要提前 7 天逐日提醒。生日与纪念日每年重复。",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                }
            }
        }

        // ── MCP ──────────────────────────────────────────────────────────────
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("AI / MCP", RilanColors.Gold)
                    Text(
                        "DeepSeek 负责首页的今日总览；MCP 让外部 AI 读取日报、写入解读、操作日程和来源。两条路可以单独使用，API Key 与 MCP 配置都在扩展页管理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = RilanColors.TextSecondary,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(RilanColors.Gold.copy(alpha = 0.08f))
                            .border(0.75.dp, RilanColors.Gold.copy(alpha = 0.22f), RoundedCornerShape(15.dp))
                            .clickable(onClick = onOpenExtensions)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                            tint = RilanColors.Gold,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("打开扩展页", style = MaterialTheme.typography.titleSmall, color = RilanColors.Text)
                            Text(
                                "启动 MCP、管理来源与 DeepSeek Key",
                                style = MaterialTheme.typography.labelSmall,
                                color = RilanColors.TextTertiary,
                            )
                        }
                        Text("→", color = RilanColors.Gold)
                    }
                }
            }
        }

        // ── 数据与关于 ───────────────────────────────────────────────────────
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("数据与关于", RilanColors.TextTertiary)
                    TextAction("清空 AI 生成的解读与总结", onClick = { confirmClearAi = true })
                    GlassDivider()
                    InfoRow("定位", "仅用于天气，不上传")
                    InfoRow("来源", "RSS / JSON 插件，扩展页管理")
                    InfoRow("壁纸", "Bing 每日壁纸，仅作背景")
                    InfoRow("版本", "日览 ${BuildConfig.VERSION_NAME} · 本地优先")
                    Text(
                        "外部 AI 生成内容可能不准确，重要信息请打开原文核对。日报内容与插件仅保存在本机。",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                }
            }
        }
    }

    if (confirmClearAi) {
        ConfirmDialog(
            title = "清空 AI 内容",
            text = "会删除 DeepSeek 生成的今日总览，以及外部 AI 写入的天气解读和周期总结；日程和插件不受影响。",
            confirmText = "清空",
            onDismiss = { confirmClearAi = false },
            onConfirm = {
                onClearAiContent()
                confirmClearAi = false
                Toast.makeText(context, "AI 内容已清空", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun PriorityHelp() {
    Priority.entries.forEach { p ->
        val color = when (p) {
            Priority.LOW -> RilanColors.Mint
            Priority.MEDIUM -> RilanColors.Gold
            Priority.HIGH -> RilanColors.Coral
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                "${p.label}重要 · ${
                    when (p) {
                        Priority.LOW -> "当天提醒"
                        Priority.MEDIUM -> "提前 3 天，每天提醒"
                        Priority.HIGH -> "提前 7 天，每天提醒"
                    }
                }",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = RilanColors.TextTertiary)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x14FFFFFF))
            .border(0.75.dp, Color(0x24FFFFFF), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) RilanColors.Text else RilanColors.TextTertiary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = RilanColors.Text,
    unfocusedTextColor = RilanColors.Text,
    focusedBorderColor = RilanColors.Mint.copy(alpha = 0.7f),
    unfocusedBorderColor = Color(0x28FFFFFF),
    focusedLabelColor = RilanColors.Mint,
    unfocusedLabelColor = RilanColors.TextTertiary,
    cursorColor = RilanColors.Mint,
    focusedContainerColor = Color(0x0DFFFFFF),
    unfocusedContainerColor = Color(0x08FFFFFF),
    focusedPlaceholderColor = RilanColors.TextTertiary,
    unfocusedPlaceholderColor = RilanColors.TextTertiary,
)

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RilanColors.SurfaceRaised,
        titleContentColor = RilanColors.Text,
        textContentColor = RilanColors.TextSecondary,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = RilanColors.Danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = RilanColors.TextTertiary) }
        },
    )
}
