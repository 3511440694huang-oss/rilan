package app.rilan.daily.ui.review

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rilan.daily.core.AiDigest
import app.rilan.daily.core.Category
import app.rilan.daily.core.NewsRecord
import app.rilan.daily.core.SummaryMode
import app.rilan.daily.core.SummaryPeriod
import app.rilan.daily.data.ExportFormat
import app.rilan.daily.data.ExportOutcome
import app.rilan.daily.data.ExportRange
import app.rilan.daily.data.RecordExporter
import app.rilan.daily.ui.components.AiMarkdown
import app.rilan.daily.ui.components.EmptyState
import app.rilan.daily.ui.components.ErrorLine
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.GlassDivider
import app.rilan.daily.ui.components.GlassIconButton
import app.rilan.daily.ui.components.GlassPill
import app.rilan.daily.ui.components.LoadingDots
import app.rilan.daily.ui.components.LoadingLines
import app.rilan.daily.ui.components.ScreenTitle
import app.rilan.daily.ui.components.SectionLabel
import app.rilan.daily.ui.components.TextAction
import app.rilan.daily.ui.theme.RilanColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// 回望：周期切换 + 本地统计 + AI 周期总结 + 历史片段
// ─────────────────────────────────────────────────────────────────────────────

private val MintInk = RilanColors.MoonInk
private val ChipIdle = Color(0x1FFFFFFF)
private val StampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private data class PeriodRecord(val record: NewsRecord, val date: LocalDate)
private data class ChartBucket(val label: String, val count: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    records: List<NewsRecord>,
    digests: Map<SummaryPeriod, AiDigest>,
    loading: SummaryPeriod?,
    aiConfigured: Boolean,
    onGenerate: (SummaryPeriod) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // 新增的 API Key 直连 / 模式切换参数都带默认值：RilanRoot 接线前后都能编译运行。
    deepseekConfigured: Boolean = false,
    summaryMode: SummaryMode = SummaryMode.MCP,
    onModeChange: (SummaryMode) -> Unit = {},
    onGenerateDirect: (SummaryPeriod) -> Unit = {},
) {
    val today = LocalDate.now()
    val zone = remember { ZoneId.systemDefault() }
    var selected by rememberSaveable { mutableStateOf(SummaryPeriod.WEEK) }
    var showExport by rememberSaveable { mutableStateOf(false) }

    // 与 PeriodBody 里的统计 / 时间线用同一套归档规则，导出内容与界面看到的一致。
    val currentPeriodRecords = remember(records, selected, today, zone) {
        collectPeriodRecords(records, selected.startDate(today), today, zone).map { it.record }
    }
    // 只有当前模式还缺条件时才在顶部提示；另一种已经可用就不再打扰。
    val needsSetup = when (summaryMode) {
        SummaryMode.MCP -> !aiConfigured
        SummaryMode.DEEPSEEK -> !deepseekConfigured
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        ScreenTitle(
            eyebrow = "回望",
            title = "这段时间",
            subtitle = "从你收录的内容里，看看脉络与变化",
            actions = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassPill(
                        text = "已收录 ${records.size} 条",
                        modifier = Modifier.padding(top = 6.dp),
                        color = RilanColors.Mint.copy(alpha = 0.92f),
                        background = Color(0x1FB5CEFF),
                    )
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClick = { showExport = true }),
                    ) {
                        GlassPill(
                            text = "导出收录",
                            color = RilanColors.Mint.copy(alpha = 0.92f),
                            background = Color(0x1FB5CEFF),
                        )
                    }
                }
            },
        )

        if (needsSetup) {
            Spacer(Modifier.height(16.dp))
            AiSetupHint(mode = summaryMode, onOpenSettings = onOpenSettings)
        }

        Spacer(Modifier.height(22.dp))
        PeriodSelector(
            selected = selected,
            loading = loading,
            onSelect = { period -> if (period != selected) selected = period },
        )
        Spacer(Modifier.height(12.dp))
        RangeHeader(period = selected, today = today)
        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (fadeIn(animationSpec = tween(240)) +
                    slideInVertically(animationSpec = tween(260)) { it / 14 })
                    .togetherWith(
                        fadeOut(animationSpec = tween(150)) +
                            slideOutVertically(animationSpec = tween(180)) { -it / 18 },
                    )
            },
            label = "reviewPeriod",
        ) { period ->
            val items = remember(records, period, today, zone) {
                collectPeriodRecords(records, period.startDate(today), today, zone)
            }
            // 只认属于当前周期的 digest；key 里的日期不是今天就是跨天过期
            val digest = digests[period]?.takeIf { it.key.startsWith(period.name) }
            val digestIsCurrent = digest != null &&
                (digest.key == period.key(today) || digest.key.endsWith(today.toString()))

            PeriodBody(
                period = period,
                today = today,
                items = items,
                digest = digest,
                digestIsCurrent = digestIsCurrent,
                loading = loading == period,
                aiConfigured = aiConfigured,
                deepseekConfigured = deepseekConfigured,
                summaryMode = summaryMode,
                onModeChange = onModeChange,
                onGenerate = { onGenerate(period) },
                onGenerateDirect = { onGenerateDirect(period) },
                onOpenSettings = onOpenSettings,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showExport) {
        ModalBottomSheet(
            onDismissRequest = { showExport = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = RilanColors.Surface,
        ) {
            ExportSheet(
                allRecords = records,
                currentRecords = currentPeriodRecords,
                period = selected,
                today = today,
                onDismiss = { showExport = false },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 顶部：AI 接入提示
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AiSetupHint(mode: SummaryMode, onOpenSettings: () -> Unit) {
    val title = when (mode) {
        SummaryMode.MCP -> "MCP 还没启动"
        SummaryMode.DEEPSEEK -> "DeepSeek 还没连接"
    }
    val body = when (mode) {
        SummaryMode.MCP ->
            "启动 MCP 后，外部 AI 可读取历史新闻，并调用 save_period_summary 写回这段时间的脉络。"
        SummaryMode.DEEPSEEK ->
            "在扩展页填一个 DeepSeek API Key，就能在应用内直接生成周期总结，不需要启动 MCP。"
    }
    val action = when (mode) {
        SummaryMode.MCP -> "打开扩展页"
        SummaryMode.DEEPSEEK -> "去扩展页填 Key"
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(RilanColors.Gold))
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = RilanColors.Gold,
                )
            }
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = RilanColors.TextSecondary,
            )
            MintButton(
                text = action,
                onClick = onOpenSettings,
                compact = true,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 周期选择
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PeriodSelector(
    selected: SummaryPeriod,
    loading: SummaryPeriod?,
    onSelect: (SummaryPeriod) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SummaryPeriod.entries.forEach { period ->
            PeriodChip(
                period = period,
                selected = period == selected,
                loading = period == loading,
                onClick = { onSelect(period) },
            )
        }
    }
}

@Composable
private fun PeriodChip(
    period: SummaryPeriod,
    selected: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val chipBackground by animateColorAsState(
        targetValue = if (selected) RilanColors.Mint else ChipIdle,
        animationSpec = tween(220),
        label = "chipBackground",
    )
    val chipContent by animateColorAsState(
        targetValue = if (selected) MintInk else RilanColors.TextSecondary,
        animationSpec = tween(220),
        label = "chipContent",
    )
    Row(
        modifier = Modifier
            .animateContentSize()
            .clip(CircleShape)
            .background(chipBackground)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else Color(0x2BFFFFFF),
                shape = CircleShape,
            )
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = period.label,
            style = MaterialTheme.typography.labelLarge,
            color = chipContent,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (loading) {
            LoadingDots(color = if (selected) MintInk else RilanColors.Mint)
        }
    }
}

@Composable
private fun RangeHeader(period: SummaryPeriod, today: LocalDate) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = period.rangeText(today),
            style = MaterialTheme.typography.titleSmall,
            color = RilanColors.Text,
        )
        Spacer(Modifier.width(8.dp))
        GlassPill(
            text = "近 ${period.days} 天",
            color = RilanColors.Mint.copy(alpha = 0.9f),
            background = Color(0x1FB5CEFF),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "按收录时间",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 内容主体（随周期切换淡入 + 上滑）
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PeriodBody(
    period: SummaryPeriod,
    today: LocalDate,
    items: List<PeriodRecord>,
    digest: AiDigest?,
    digestIsCurrent: Boolean,
    loading: Boolean,
    aiConfigured: Boolean,
    deepseekConfigured: Boolean,
    summaryMode: SummaryMode,
    onModeChange: (SummaryMode) -> Unit,
    onGenerate: () -> Unit,
    onGenerateDirect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatsCard(period = period, today = today, items = items)
        DigestCard(
            digest = digest,
            digestIsCurrent = digestIsCurrent,
            loading = loading,
            aiConfigured = aiConfigured,
            deepseekConfigured = deepseekConfigured,
            summaryMode = summaryMode,
            onModeChange = onModeChange,
            onGenerate = onGenerate,
            onGenerateDirect = onGenerateDirect,
            onOpenSettings = onOpenSettings,
        )
        if (items.isNotEmpty()) {
            HistoryCard(items = items, today = today)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 统计卡：条数 / 来源 / 分类 + Canvas 分类条形图 + 收录节奏
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsCard(
    period: SummaryPeriod,
    today: LocalDate,
    items: List<PeriodRecord>,
) {
    val sources = items.map { it.record.source }.filter { it.isNotBlank() }.distinct().size
    val categories = items.map { it.record.category }.distinct().size

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel(
                text = "本期概览",
                accent = RilanColors.Mint,
                trailing = {
                    Text(
                        text = "本地统计",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = "收录",
                    value = items.size.toString(),
                    unit = "条",
                    accent = RilanColors.Mint,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "来源",
                    value = sources.toString(),
                    unit = "个",
                    accent = RilanColors.Blue,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "分类",
                    value = categories.toString(),
                    unit = "类",
                    accent = RilanColors.Gold,
                    modifier = Modifier.weight(1f),
                )
            }

            if (items.isEmpty()) {
                EmptyState(
                    title = "这段时间还没有记录",
                    subtitle = "回日报刷新后会自动积累",
                )
            } else {
                GlassDivider()
                CategoryDistribution(items = items)
                val buckets = buildBuckets(
                    period = period,
                    today = today,
                    byDate = items.groupingBy { it.date }.eachCount(),
                )
                if (buckets.size >= 2) {
                    GlassDivider()
                    VolumeChart(period = period, buckets = buckets)
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(RilanColors.GlassWeak)
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                maxLines = 1,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryDistribution(items: List<PeriodRecord>) {
    val all = items.groupingBy { it.record.category }.eachCount().entries
        .sortedWith(
            compareByDescending<Map.Entry<Category, Int>> { it.value }
                .thenBy { it.key.ordinal },
        )
    val shown = all.take(6)
    val peak = shown.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "分类分布",
                style = MaterialTheme.typography.labelMedium,
                color = RilanColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${all.size} 类",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
        }
        shown.forEach { entry ->
            val category = entry.key
            val accent = Color(category.accent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = category.label,
                    modifier = Modifier.width(62.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = RilanColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Canvas(Modifier.weight(1f).height(8.dp)) {
                    val radius = size.height / 2f
                    drawRoundRect(
                        color = Color(0x14FFFFFF),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    val barWidth = (size.width * entry.value.toFloat() / peak)
                        .coerceAtLeast(size.height)
                    drawRoundRect(
                        color = accent,
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    text = entry.value.toString(),
                    modifier = Modifier.width(26.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = RilanColors.Text,
                    textAlign = TextAlign.End,
                )
            }
        }
        if (all.size > 6) {
            Text(
                text = "另有 ${all.size - 6} 类未展示",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun VolumeChart(period: SummaryPeriod, buckets: List<ChartBucket>) {
    val peak = buckets.maxOf { it.count }.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = bucketTitle(period),
                style = MaterialTheme.typography.labelMedium,
                color = RilanColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "峰值 $peak 条",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
            val gap = if (buckets.size > 40) 0f else 3.dp.toPx()
            val barWidth = ((size.width - gap * (buckets.size - 1)) / buckets.size)
                .coerceAtLeast(1f)
            val baseline = size.height - 1.dp.toPx()
            drawLine(
                color = Color(0x1AFFFFFF),
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1.dp.toPx(),
            )
            buckets.forEachIndexed { index, bucket ->
                if (bucket.count > 0) {
                    val ratio = bucket.count.toFloat() / peak
                    val barHeight = (size.height - 4.dp.toPx()) * ratio
                    val corner = minOf(barWidth / 2f, 3.dp.toPx())
                    drawRoundRect(
                        color = if (bucket.count == peak) {
                            RilanColors.Mint
                        } else {
                            RilanColors.Mint.copy(alpha = 0.32f)
                        },
                        topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = buckets.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = buckets.last().label,
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AI 周期总结卡
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DigestCard(
    digest: AiDigest?,
    digestIsCurrent: Boolean,
    loading: Boolean,
    aiConfigured: Boolean,
    deepseekConfigured: Boolean,
    summaryMode: SummaryMode,
    onModeChange: (SummaryMode) -> Unit,
    onGenerate: () -> Unit,
    onGenerateDirect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var confirmOverwrite by remember { mutableStateOf(false) }
    // 已有「当前周期且有正文」的总结才需要确认覆盖；失败留下的错误记录直接重试。
    val hasCurrentSummary = digest != null && digestIsCurrent &&
        digest.content.isNotBlank() && digest.error.isNullOrBlank()
    val requestDirect = {
        if (hasCurrentSummary) confirmOverwrite = true else onGenerateDirect()
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        strong = digest != null,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(
                text = when (summaryMode) {
                    SummaryMode.MCP -> "外部 AI 周期总结"
                    SummaryMode.DEEPSEEK -> "DeepSeek 周期总结"
                },
                accent = RilanColors.Gold,
                trailing = {
                    if (digest != null && !loading) {
                        when {
                            summaryMode == SummaryMode.MCP && aiConfigured -> GlassIconButton(
                                icon = Icons.Filled.Refresh,
                                contentDescription = "如何更新",
                                onClick = onGenerate,
                                size = 36.dp,
                                tint = RilanColors.Gold,
                            )
                            summaryMode == SummaryMode.DEEPSEEK && deepseekConfigured -> GlassIconButton(
                                icon = Icons.Filled.Refresh,
                                contentDescription = "重新生成",
                                onClick = requestDirect,
                                size = 36.dp,
                                tint = RilanColors.Gold,
                            )
                        }
                    }
                },
            )

            SummaryModeSelector(
                selected = summaryMode,
                onSelect = onModeChange,
                enabled = !loading,
            )

            when {
                loading -> {
                    LoadingLines(lines = 4)
                    Text(
                        text = "DeepSeek 正在读这段时间的收录…",
                        style = MaterialTheme.typography.bodySmall,
                        color = RilanColors.Mint.copy(alpha = 0.9f),
                    )
                }
                digest != null -> DigestContent(
                    digest = digest,
                    digestIsCurrent = digestIsCurrent,
                    aiConfigured = aiConfigured,
                    deepseekConfigured = deepseekConfigured,
                    summaryMode = summaryMode,
                    onGenerate = onGenerate,
                    onRegenerateDirect = requestDirect,
                    onOpenSettings = onOpenSettings,
                )
                summaryMode == SummaryMode.MCP ->
                    if (aiConfigured) {
                        McpGenerateCta(onGenerate = onGenerate)
                    } else {
                        McpConnectCta(onOpenSettings = onOpenSettings)
                    }
                else ->
                    if (deepseekConfigured) {
                        DirectGenerateCta(onGenerate = onGenerateDirect)
                    } else {
                        DirectConnectCta(onOpenSettings = onOpenSettings)
                    }
            }
        }
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = {
                Text(
                    "覆盖当前总结？",
                    style = MaterialTheme.typography.titleMedium,
                    color = RilanColors.Text,
                )
            },
            text = {
                Text(
                    "这个周期已经有一份总结，重新生成会覆盖它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOverwrite = false
                    onGenerateDirect()
                }) {
                    Text("覆盖生成", color = RilanColors.Mint)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) {
                    Text("取消", color = RilanColors.TextTertiary)
                }
            },
            containerColor = RilanColors.SurfaceRaised,
        )
    }
}

@Composable
private fun DigestContent(
    digest: AiDigest,
    digestIsCurrent: Boolean,
    aiConfigured: Boolean,
    deepseekConfigured: Boolean,
    summaryMode: SummaryMode,
    onGenerate: () -> Unit,
    onRegenerateDirect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stale = !digestIsCurrent
    val content = digest.content.trim()
    val error = digest.error?.takeIf { it.isNotBlank() }
    val configured = when (summaryMode) {
        SummaryMode.MCP -> aiConfigured
        SummaryMode.DEEPSEEK -> deepseekConfigured
    }
    val regenerate = if (summaryMode == SummaryMode.MCP) onGenerate else onRegenerateDirect

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            text = digestMeta(digest = digest, current = digestIsCurrent),
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
        )

        if (stale) {
            StaleNotice(rangeText = digest.rangeText, mode = summaryMode)
        }

        if (content.isNotEmpty()) {
            AiMarkdown(text = content)
            if (error != null) {
                ErrorLine(text = error)
            }
        } else {
            ErrorLine(
                text = error ?: "这份总结没有生成成功，点下方按钮再试一次。",
            )
        }

        GlassDivider()

        if (stale && configured) {
            MintButton(
                text = when (summaryMode) {
                    SummaryMode.MCP -> "如何更新这份总结"
                    SummaryMode.DEEPSEEK -> "用 DeepSeek 重写这段"
                },
                onClick = regenerate,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    stale && summaryMode == SummaryMode.MCP -> "旧内容会保留，直到外部 AI 写回新的一份"
                    stale && summaryMode == SummaryMode.DEEPSEEK -> "旧内容会保留，直到新的一份生成完成"
                    !configured && summaryMode == SummaryMode.MCP -> "打开扩展页，让外部 AI 写一份新的"
                    !configured -> "去扩展页填一个 DeepSeek API Key，就能直接生成"
                    content.isEmpty() -> "这次没有生成成功，可以稍后再试"
                    summaryMode == SummaryMode.DEEPSEEK -> "DeepSeek 直连生成，仅供参考"
                    else -> "外部 AI 写回，仅供参考"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
            if (!configured) {
                TextAction(text = "去扩展页", onClick = onOpenSettings)
            } else if (!stale) {
                TextAction(
                    text = if (summaryMode == SummaryMode.MCP) "如何更新" else "重新生成",
                    onClick = regenerate,
                )
            }
        }
    }
}

@Composable
private fun StaleNotice(rangeText: String, mode: SummaryMode) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x1FF4C97A))
            .border(1.dp, Color(0x2EF4C97A), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when (mode) {
                SummaryMode.MCP -> "这份总结写于更早的时间，让外部 AI 写一份新的？"
                SummaryMode.DEEPSEEK -> "这份总结写于更早的时间，用 DeepSeek 重写这段？"
            },
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.Gold,
        )
        if (rangeText.isNotBlank()) {
            Text(
                text = "原来覆盖 $rangeText",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun SummaryModeSelector(
    selected: SummaryMode,
    onSelect: (SummaryMode) -> Unit,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(RilanColors.GlassWeak)
            .border(1.dp, Color(0x14FFFFFF), shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        SummaryMode.entries.forEach { mode ->
            val active = mode == selected
            val background by animateColorAsState(
                targetValue = if (active) RilanColors.Mint else Color.Transparent,
                animationSpec = tween(180),
                label = "summaryModeBg",
            )
            val content by animateColorAsState(
                targetValue = if (active) MintInk else RilanColors.TextSecondary,
                animationSpec = tween(180),
                label = "summaryModeFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(background)
                    .selectable(
                        selected = active,
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = { if (!active) onSelect(mode) },
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) content else content.copy(alpha = 0.6f),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun McpGenerateCta(onGenerate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "把这段时间的新闻重新读一遍，写成有主线、有变化的一段回顾。",
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextSecondary,
        )
        MintButton(
            text = "如何生成这份总结",
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "在外部 AI 中通过 MCP 读取历史记录，再用 save_period_summary 写回；生成消耗外部模型的 token。",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
        )
    }
}

@Composable
private fun McpConnectCta(onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "还没有外部 AI 写回总结。启动 MCP 后，在外部 AI 中生成并写回。",
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextSecondary,
        )
        MintButton(
            text = "如何生成这份总结",
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
        )
        TextAction(text = "打开扩展页", onClick = onOpenSettings)
    }
}

@Composable
private fun DirectGenerateCta(onGenerate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "用本机收录的内容，让 DeepSeek 直接写成一段有主线的周期回顾。",
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextSecondary,
        )
        MintButton(
            text = "用 DeepSeek 生成",
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "生成时会把这段时间的标题、来源和分类发给 DeepSeek；Key 只保存在本机。",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
        )
    }
}

@Composable
private fun DirectConnectCta(onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "还没有连接 DeepSeek。在扩展页填一个 API Key，就能在应用内直接生成。",
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextSecondary,
        )
        MintButton(
            text = "用 DeepSeek 生成",
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
        )
        TextAction(text = "去扩展页填 Key", onClick = onOpenSettings)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 历史片段
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryCard(items: List<PeriodRecord>, today: LocalDate) {
    val recent = items.take(8)

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(18.dp),
    ) {
        Column {
            SectionLabel(
                text = "历史片段",
                accent = RilanColors.Blue,
                trailing = {
                    if (items.size > recent.size) {
                        GlassPill(
                            text = "最近 8 条",
                            color = RilanColors.TextTertiary,
                        )
                    }
                },
            )
            Spacer(Modifier.height(14.dp))

            recent.forEachIndexed { index, item ->
                TimelineRow(
                    item = item,
                    today = today,
                    isFirst = index == 0,
                    isLast = index == recent.lastIndex,
                )
            }

            if (items.size > recent.size) {
                Spacer(Modifier.height(6.dp))
                GlassDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "共 ${items.size} 条记录",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    item: PeriodRecord,
    today: LocalDate,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val record = item.record
    val accent = Color(record.category.accent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val railX = 48.dp.toPx() + 9.dp.toPx()
                val dotY = 7.dp.toPx()
                if (!isFirst || !isLast) {
                    drawLine(
                        color = Color(0x24FFFFFF),
                        start = Offset(railX, if (isFirst) dotY else 0f),
                        end = Offset(railX, if (isLast) dotY else size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                drawCircle(
                    color = accent.copy(alpha = 0.20f),
                    radius = 6.dp.toPx(),
                    center = Offset(railX, dotY),
                )
                drawCircle(
                    color = accent,
                    radius = 3.dp.toPx(),
                    center = Offset(railX, dotY),
                )
            },
    ) {
        Text(
            text = dateLabel(item.date, today),
            modifier = Modifier.width(48.dp).padding(top = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(18.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 14.dp),
        ) {
            Text(
                text = record.title.ifBlank { "无标题" },
                style = MaterialTheme.typography.bodyMedium,
                color = RilanColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.source.ifBlank { "未知来源" },
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = record.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.8f),
                    maxLines = 1,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 导出收录条目
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExportSheet(
    allRecords: List<NewsRecord>,
    currentRecords: List<NewsRecord>,
    period: SummaryPeriod,
    today: LocalDate,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by rememberSaveable { mutableStateOf(ExportRange.CURRENT) }
    var format by rememberSaveable { mutableStateOf(ExportFormat.MARKDOWN) }
    var exporting by remember { mutableStateOf(false) }

    val source = if (range == ExportRange.CURRENT) currentRecords else allRecords
    val rangeText = if (range == ExportRange.CURRENT) period.rangeText(today) else ""
    val fileName = RecordExporter.fileName(range, format, today)
    val empty = source.isEmpty()

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    fun startShare(file: java.io.File) {
        try {
            context.startActivity(RecordExporter.shareIntent(context, file, format))
        } catch (t: Throwable) {
            toast("没有找到可以分享的应用")
        }
    }

    fun startExport(shareOnly: Boolean) {
        if (exporting || empty) return
        exporting = true
        scope.launch {
            try {
                val outcome = if (shareOnly) {
                    RecordExporter.prepareForShare(context, source, range, format, rangeText)
                } else {
                    RecordExporter.saveToDownloads(context, source, range, format, rangeText)
                }
                when (outcome) {
                    is ExportOutcome.Saved -> {
                        toast("已保存到 ${outcome.displayPath}")
                        onDismiss()
                    }
                    is ExportOutcome.ShareFallback -> {
                        toast(
                            if (shareOnly) {
                                "已生成 ${outcome.fileName}，选择应用分享"
                            } else {
                                "当前系统不能直接写入下载目录，已改为分享文件"
                            }
                        )
                        startShare(outcome.file)
                        onDismiss()
                    }
                    is ExportOutcome.Failed -> toast(outcome.message)
                }
            } catch (t: Throwable) {
                toast("导出失败：${t.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"}")
            } finally {
                exporting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
            .padding(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionLabel(text = "导出收录条目", accent = RilanColors.Mint)
        Text(
            text = "导出的内容来自本机收录记录，字段与回望页看到的一致。",
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextSecondary,
        )

        Text(
            text = "范围",
            style = MaterialTheme.typography.labelMedium,
            color = RilanColors.TextTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip(
                text = "当前周期 · ${currentRecords.size} 条",
                selected = range == ExportRange.CURRENT,
                onClick = { range = ExportRange.CURRENT },
            )
            OptionChip(
                text = "全部 · ${allRecords.size} 条",
                selected = range == ExportRange.ALL,
                onClick = { range = ExportRange.ALL },
            )
        }

        Text(
            text = "格式",
            style = MaterialTheme.typography.labelMedium,
            color = RilanColors.TextTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportFormat.entries.forEach { item ->
                OptionChip(
                    text = item.label,
                    selected = format == item,
                    onClick = { format = item },
                )
            }
        }

        Text(
            text = "文件名：$fileName",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
        )
        if (empty) {
            Text(
                text = "当前范围还没有收录记录，先回日报刷新后再导出。",
                style = MaterialTheme.typography.bodySmall,
                color = RilanColors.Gold,
            )
        }

        GlassDivider()
        MintButton(
            text = if (exporting) "正在导出…" else "保存到下载",
            onClick = { startExport(shareOnly = false) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !empty && !exporting,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextAction(text = "分享", onClick = { startExport(shareOnly = true) })
            Spacer(Modifier.weight(1f))
            TextAction(
                text = "取消",
                onClick = onDismiss,
                color = RilanColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun OptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) RilanColors.Mint else ChipIdle,
        animationSpec = tween(180),
        label = "optionChipBg",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MintInk else RilanColors.TextSecondary,
        animationSpec = tween(180),
        label = "optionChipFg",
    )
    Text(
        text = text,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .border(1.dp, if (selected) Color.Transparent else Color(0x2BFFFFFF), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = content,
        maxLines = 1,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 按钮
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MintButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
    val fill = if (enabled) {
        Brush.horizontalGradient(listOf(Color(0xFFDCE8FF), RilanColors.MoonDeep))
    } else {
        Brush.horizontalGradient(listOf(Color(0x1AFFFFFF), Color(0x0AFFFFFF)))
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, if (enabled) Color.Transparent else Color(0x24FFFFFF), shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = if (compact) 16.dp else 20.dp,
                vertical = if (compact) 9.dp else 15.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (compact) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            },
            color = if (enabled) MintInk else RilanColors.TextTertiary,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 数据与格式工具
// ─────────────────────────────────────────────────────────────────────────────

private fun collectPeriodRecords(
    records: List<NewsRecord>,
    start: LocalDate,
    today: LocalDate,
    zone: ZoneId,
): List<PeriodRecord> = records.mapNotNull { record ->
    // 与仓库的 recordsFor 保持一致：一律按「首次收录时间」归档
    val stamp = record.firstSeenAt
    if (stamp <= 0L) return@mapNotNull null
    val date = Instant.ofEpochMilli(stamp).atZone(zone).toLocalDate()
    if (date.isBefore(start) || date.isAfter(today)) null else PeriodRecord(record, date)
}.sortedByDescending { it.record.firstSeenAt }

private fun buildBuckets(
    period: SummaryPeriod,
    today: LocalDate,
    byDate: Map<LocalDate, Int>,
): List<ChartBucket> {
    val start = period.startDate(today)
    return when {
        period.days <= 31 -> {
            (0 until period.days).map { offset ->
                val date = start.plusDays(offset.toLong())
                ChartBucket(
                    label = "${date.monthValue}/${date.dayOfMonth}",
                    count = byDate[date] ?: 0,
                )
            }
        }
        period.days <= 120 -> {
            val result = ArrayList<ChartBucket>()
            var cursor = start
            while (!cursor.isAfter(today)) {
                val weekEnd = cursor.plusDays(6)
                val end = if (weekEnd.isAfter(today)) today else weekEnd
                var sum = 0
                var day = cursor
                while (!day.isAfter(end)) {
                    sum += byDate[day] ?: 0
                    day = day.plusDays(1)
                }
                result += ChartBucket(
                    label = "${cursor.monthValue}/${cursor.dayOfMonth}",
                    count = sum,
                )
                cursor = end.plusDays(1)
            }
            result
        }
        else -> {
            val result = ArrayList<ChartBucket>()
            var month = YearMonth.from(start)
            val lastMonth = YearMonth.from(today)
            while (!month.isAfter(lastMonth)) {
                var sum = 0
                for (dayOfMonth in 1..month.lengthOfMonth()) {
                    val date = month.atDay(dayOfMonth)
                    if (!date.isBefore(start) && !date.isAfter(today)) {
                        sum += byDate[date] ?: 0
                    }
                }
                val label = if (period == SummaryPeriod.YEAR) {
                    "${month.year % 100}/${month.monthValue}"
                } else {
                    "${month.monthValue}月"
                }
                result += ChartBucket(label = label, count = sum)
                month = month.plusMonths(1)
            }
            result
        }
    }
}

private fun bucketTitle(period: SummaryPeriod): String = when {
    period.days <= 31 -> "每日收录"
    period.days <= 120 -> "每周收录"
    else -> "每月收录"
}

private fun formatStamp(millis: Long): String {
    if (millis <= 0L) return ""
    return runCatching {
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(StampFormat)
    }.getOrDefault("")
}

private fun digestMeta(digest: AiDigest, current: Boolean): String {
    val stamp = formatStamp(digest.createdAt)
    val time = when {
        stamp.isNotBlank() && current -> "$stamp 生成"
        stamp.isNotBlank() -> "写于 $stamp"
        else -> "生成时间未知"
    }
    return "${digest.sourceLabel} · $time · ${digest.itemCount} 条素材"
}

private fun dateLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "今天"
    date == today.minusDays(1) -> "昨天"
    date.year == today.year -> "${date.monthValue}/${date.dayOfMonth}"
    else -> "${date.year % 100}/${date.monthValue}/${date.dayOfMonth}"
}
