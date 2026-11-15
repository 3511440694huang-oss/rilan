package app.rilan.daily.ui.review

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rilan.daily.core.AiDigest
import app.rilan.daily.core.Category
import app.rilan.daily.core.NewsRecord
import app.rilan.daily.core.SummaryPeriod
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

@Composable
fun ReviewScreen(
    records: List<NewsRecord>,
    digests: Map<SummaryPeriod, AiDigest>,
    loading: SummaryPeriod?,
    aiConfigured: Boolean,
    onGenerate: (SummaryPeriod) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val zone = remember { ZoneId.systemDefault() }
    var selected by rememberSaveable { mutableStateOf(SummaryPeriod.WEEK) }

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
                GlassPill(
                    text = "已收录 ${records.size} 条",
                    modifier = Modifier.padding(top = 6.dp),
                    color = RilanColors.Mint.copy(alpha = 0.92f),
                    background = Color(0x1FB5CEFF),
                )
            },
        )

        if (!aiConfigured) {
            Spacer(Modifier.height(16.dp))
            AiSetupHint(onOpenSettings = onOpenSettings)
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
                onGenerate = { onGenerate(period) },
                onOpenSettings = onOpenSettings,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 顶部：AI 接入提示
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AiSetupHint(onOpenSettings: () -> Unit) {
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
                    "MCP 还没启动",
                    style = MaterialTheme.typography.labelMedium,
                    color = RilanColors.Gold,
                )
            }
            Text(
                "启动 MCP 后，外部 AI 可读取历史新闻，并调用 save_period_summary 写回这段时间的脉络。",
                style = MaterialTheme.typography.bodySmall,
                color = RilanColors.TextSecondary,
            )
            MintButton(
                text = "打开扩展页",
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
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatsCard(period = period, today = today, items = items)
        DigestCard(
            digest = digest,
            digestIsCurrent = digestIsCurrent,
            loading = loading,
            aiConfigured = aiConfigured,
            onGenerate = onGenerate,
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
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        strong = digest != null,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(
                text = "外部 AI 周期总结",
                accent = RilanColors.Gold,
                trailing = {
                    if (digest != null && aiConfigured && !loading) {
                        GlassIconButton(
                            icon = Icons.Filled.Refresh,
                            contentDescription = "如何更新",
                            onClick = onGenerate,
                            size = 36.dp,
                            tint = RilanColors.Gold,
                        )
                    }
                },
            )

            when {
                loading -> {
                    LoadingLines(lines = 4)
                    Text(
                        text = "正在等待外部 AI 写入…",
                        style = MaterialTheme.typography.bodySmall,
                        color = RilanColors.Mint.copy(alpha = 0.9f),
                    )
                }
                digest != null -> DigestContent(
                    digest = digest,
                    digestIsCurrent = digestIsCurrent,
                    aiConfigured = aiConfigured,
                    onGenerate = onGenerate,
                    onOpenSettings = onOpenSettings,
                )
                aiConfigured -> GenerateCta(onGenerate = onGenerate)
                else -> ConnectCta(onOpenSettings = onOpenSettings)
            }
        }
    }
}

@Composable
private fun DigestContent(
    digest: AiDigest,
    digestIsCurrent: Boolean,
    aiConfigured: Boolean,
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stale = !digestIsCurrent
    val content = digest.content.trim()
    val error = digest.error?.takeIf { it.isNotBlank() }

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
            StaleNotice(rangeText = digest.rangeText)
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

        if (stale && aiConfigured) {
            MintButton(
                text = "如何更新这份总结",
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    stale && aiConfigured -> "旧内容会保留，直到新的一份生成完成"
                    !aiConfigured -> "打开扩展页，让外部 AI 写一份新的"
                    content.isEmpty() -> "这次没有生成成功，可以稍后再试"
                    else -> "内容由 AI 生成，仅供参考"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
            if (stale && !aiConfigured) {
                TextAction(text = "打开扩展页", onClick = onOpenSettings)
            }
            if (!stale && aiConfigured) {
                TextAction(text = "如何更新", onClick = onGenerate)
            }
        }
    }
}

@Composable
private fun StaleNotice(rangeText: String) {
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
            text = "这份总结写于更早的时间，生成新的一份？",
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
private fun GenerateCta(onGenerate: () -> Unit) {
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
            text = "在外部 AI 中读取历史记录，再用 save_period_summary 写回；生成消耗外部模型的 token。",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
        )
    }
}

@Composable
private fun ConnectCta(onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "还没有外部 AI 写入总结。启动 MCP 后，在外部 AI 中生成并写回。",
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
    return "$time · ${digest.itemCount} 条素材"
}

private fun dateLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "今天"
    date == today.minusDays(1) -> "昨天"
    date.year == today.year -> "${date.monthValue}/${date.dayOfMonth}"
    else -> "${date.year % 100}/${date.monthValue}/${date.dayOfMonth}"
}
