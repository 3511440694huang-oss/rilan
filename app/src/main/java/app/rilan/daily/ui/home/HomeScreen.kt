@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package app.rilan.daily.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.Category
import app.rilan.daily.core.DayCounter
import app.rilan.daily.core.Direction
import app.rilan.daily.core.EventKind
import app.rilan.daily.core.HomeState
import app.rilan.daily.core.NewsItem
import app.rilan.daily.core.NewsSection
import app.rilan.daily.core.Priority
import app.rilan.daily.core.ScheduleEvent
import app.rilan.daily.core.buildDayCounters
import app.rilan.daily.core.formatHotValue
import app.rilan.daily.core.relativeTimeLabel
import app.rilan.daily.schedule.Recurrence
import app.rilan.daily.ui.components.AiMarkdown
import app.rilan.daily.ui.components.EmptyState
import app.rilan.daily.ui.components.ErrorLine
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.GlassDivider
import app.rilan.daily.ui.components.GlassIconButton
import app.rilan.daily.ui.components.GlassPill
import app.rilan.daily.ui.components.LoadingDots
import app.rilan.daily.ui.components.LoadingLines
import app.rilan.daily.ui.components.SectionLabel
import app.rilan.daily.ui.components.TextAction
import app.rilan.daily.ui.theme.RilanColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

private val PAGE_LABELS = listOf("天气", "简报", "信息", "今天")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日")
private val WEEK_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 悬浮底部导航（56dp 胶囊 + 8dp 间距）会盖住卡片最下面一段。
 * 基础留白 80dp，再叠加系统导航栏高度，三键导航机型也不会把最后一条内容压在胶囊下面。
 */
private val CARD_BOTTOM_BASE_INSET = 80.dp

@Composable
private fun cardBottomInset(): Dp =
    CARD_BOTTOM_BASE_INSET +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

// ─────────────────────────────────────────────────────────────────────────────
// 日报：四张可以左右翻的玻璃卡片
// ─────────────────────────────────────────────────────────────────────────────

/** 入场动画是否已经播过：放在进程级，切 tab 回来不再重播。 */
private var homeIntroPlayed = false

@Composable
fun HomeScreen(
    state: HomeState,
    aiConfigured: Boolean,
    events: List<ScheduleEvent>,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onItemClick: (NewsItem) -> Unit,
    onOpenAiSettings: () -> Unit,
    onGenerateBrief: () -> Unit,
    onRelocate: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenFeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(state.sections) {
        state.sections.filter { it.items.isNotEmpty() || !it.error.isNullOrBlank() }
    }
    var selected by rememberSaveable { mutableStateOf("ALL") }
    val active = sections.firstOrNull { it.category.name == selected }
    val total = remember(sections) { sections.sumOf { it.items.size } }
    val pager = rememberPagerState(pageCount = { PAGE_LABELS.size })
    val scope = rememberCoroutineScope()
    // 落到「信息流」页时先刷一次，比等下一个 2 分钟周期更快。
    LaunchedEffect(pager.settledPage) {
        if (pager.settledPage == 2) onOpenFeed()
    }
    // 页面底部留白随系统导航栏高度变化，四页共用同一个值，内容不会被胶囊压住。
    val bottomInset = cardBottomInset()
    // 入场动画每个进程只播一次：切 tab 时 HomeScreen 会被销毁重建，
    // 每次回来都让整屏（Pager + 四张玻璃卡）在 560ms 里逐帧半透明合成，刚切回来那一下就会卡。
    var entered by remember { mutableStateOf(homeIntroPlayed) }
    LaunchedEffect(Unit) {
        if (!homeIntroPlayed) {
            homeIntroPlayed = true
            entered = true
        }
    }
    val enter by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(560, easing = FastOutSlowInEasing),
        label = "homeEnter",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .graphicsLayer {
                alpha = enter
                translationY = (1f - enter) * 14.dp.toPx()
            },
    ) {
        HomeHeader(
            state = state,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 4.dp),
                pageSpacing = 6.dp,
            ) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 只在绘制层读取 pager 偏移，避免翻页时四张卡整页重组。
                        // 用公开状态直接算，不再靠 getOffsetFractionForPage + 异常兜底：
                        // 那个 API 在页码与快照不同步时会抛异常，每帧构造一次异常很卡。
                        val distance = (
                            (pager.currentPage - page) + pager.currentPageOffsetFraction
                            ).absoluteValue.coerceIn(0f, 1f)
                        val scale = 1f - 0.042f * distance
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.18f * distance
                        translationY = 7.dp.toPx() * distance
                    },
            ) {
                when (page) {
                    0 -> WeatherHero(
                        weather = state.weather,
                        wallpaper = state.wallpaper,
                        loading = state.loading,
                        refreshing = state.weatherRefreshing,
                        error = state.weatherError,
                        locating = state.locating,
                        locationError = state.locationError,
                        onRefresh = onRefresh,
                        onRelocate = onRelocate,
                        sheen = 0.46f,
                        bottomInset = bottomInset,
                        modifier = Modifier.fillMaxSize(),
                    )

                    1 -> BriefingPage(
                        state = state,
                        aiConfigured = aiConfigured,
                        total = total,
                        onGenerateBrief = onGenerateBrief,
                        onOpenAiSettings = onOpenAiSettings,
                        sheen = 0.46f,
                        bottomInset = bottomInset,
                    )

                    2 -> NewsPage(
                        state = state,
                        sections = sections,
                        total = total,
                        selected = active?.category,
                        onSelectAll = { selected = "ALL" },
                        onSelect = { selected = it.name },
                        onItemClick = onItemClick,
                        onRefresh = onRefresh,
                        sheen = 0.46f,
                        bottomInset = bottomInset,
                    )

                    else -> TodayPage(
                        events = events,
                        onOpenSchedule = onOpenSchedule,
                        sheen = 0.46f,
                        bottomInset = bottomInset,
                    )
                }
            }
            }
            PageRail(
                pager = pager,
                onSelect = { index -> scope.launch { pager.animateScrollToPage(index) } },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 顶部
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HomeHeader(
    state: HomeState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(RilanColors.Moon)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "日 览",
                    style = MaterialTheme.typography.labelMedium,
                    color = RilanColors.Moon,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    todayEyebrow(),
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    greetingFor(LocalTime.now().hour),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                    color = RilanColors.Text,
                    maxLines = 1,
                )
                Text(
                    headerSubtitle(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpinIconButton(spinning = state.refreshing, onClick = onRefresh)
            GlassIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "设置",
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun SpinIconButton(spinning: Boolean, onClick: () -> Unit) {
    // 旋转动画只在图层里读值：刷新时这枚图标每帧只更新一个 rotationZ，
    // 不会把整条顶栏（和下面的卡片）带着重组。
    val angle = if (spinning) {
        rememberInfiniteTransition(label = "refreshSpin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "refreshAngle",
        )
    } else {
        null
    }
    Box(
        Modifier
            .size(44.dp)
            .shadow(8.dp, CircleShape, clip = false, spotColor = Color(0xFF1C2A4A))
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.045f))
                )
            )
            .border(
                0.75.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.07f))
                ),
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = "刷新",
            tint = if (spinning) RilanColors.Moon else RilanColors.Text,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = angle?.value ?: 0f },
        )
    }
}

private fun headerSubtitle(state: HomeState): String {
    val weather = state.weather
    return when {
        state.locating -> "正在确认位置…"
        weather != null -> "${weather.condition} ${weather.currentTemp.toInt()}°"
        state.weatherError != null -> "天气暂时不可用"
        else -> "正在获取天气…"
    }
}

/** 四段很细的页轨，替代原来占一整行的页码条。 */
@Composable
private fun PageRail(
    pager: PagerState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 页轨自己订阅页码：翻页时只重组这一小段，不会牵动四张卡片。
    val current by remember(pager) { derivedStateOf { pager.currentPage } }
    Row(
        modifier
            .clip(CircleShape)
            .background(RilanColors.Ink.copy(alpha = 0.26f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PAGE_LABELS.indices.forEach { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 9.dp,
                animationSpec = spring(stiffness = 520f, dampingRatio = 0.84f),
                label = "pageRailWidth$index",
            )
            val color by animateColorAsState(
                targetValue = if (active) RilanColors.Moon else Color.White.copy(alpha = 0.22f),
                animationSpec = tween(220),
                label = "pageRailColor$index",
            )
            Box(
                Modifier
                    .width(width + 10.dp)
                    .height(18.dp)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(width)
                        .height(2.5.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 第 2 张：今日简报
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BriefingPage(
    state: HomeState,
    aiConfigured: Boolean,
    total: Int,
    onGenerateBrief: () -> Unit,
    onOpenAiSettings: () -> Unit,
    sheen: Float,
    bottomInset: Dp,
) {
    val brief = state.dailyBrief
    val insight = state.weatherInsight
    val sources = remember(state.sections) {
        state.sections.flatMap { it.items }.map { it.source }.filter { it.isNotBlank() }.distinct().size
    }
    val categories = remember(state.sections) { state.sections.count { it.items.isNotEmpty() } }

    GlassCard(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(32.dp),
        strong = true,
        contentPadding = PaddingValues(0.dp),
        sheen = sheen,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })
                .padding(start = 20.dp, end = 20.dp, top = 38.dp, bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            PageHeading(
                index = "02",
                label = "今日简报",
                title = "把一天收拢",
                hint = "天气、信息流和日程，交给 DeepSeek 读一遍",
                trailing = {
                    GlassPill(
                        text = if (aiConfigured) "DeepSeek" else "未连接",
                        color = if (aiConfigured) RilanColors.Dawn else RilanColors.TextTertiary,
                        background = (if (aiConfigured) RilanColors.Dawn else Color.White)
                            .copy(alpha = 0.10f),
                    )
                },
            )

            BriefAiPanel(
                brief = brief,
                aiConfigured = aiConfigured,
                total = total,
                onGenerate = onGenerateBrief,
                onOpenSettings = onOpenAiSettings,
            )

            GlassDivider()
            OverviewStats(total = total, sources = sources, categories = categories)

            if (insight.hasContent) {
                GlassDivider()
                SectionLabel("天气补充", RilanColors.Dawn) {
                    GlassPill(
                        text = "MCP",
                        color = RilanColors.Dawn.copy(alpha = 0.9f),
                        background = RilanColors.Dawn.copy(alpha = 0.08f),
                    )
                }
                AiMarkdown(
                    text = insight.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                "由 DeepSeek 基于本机缓存的天气、信息流与日程生成，只保存在本机；重要信息请打开原文核对。",
                style = MaterialTheme.typography.bodySmall,
                color = RilanColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun BriefAiPanel(
    brief: app.rilan.daily.core.AiText,
    aiConfigured: Boolean,
    total: Int,
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        RilanColors.Moon.copy(alpha = 0.10f),
                        RilanColors.Surface.copy(alpha = 0.16f),
                    )
                )
            )
            .border(
                0.75.dp,
                RilanColors.Moon.copy(alpha = 0.18f),
                RoundedCornerShape(22.dp),
            )
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (aiConfigured) RilanColors.Dawn else RilanColors.TextTertiary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "今日总览",
                style = MaterialTheme.typography.titleSmall,
                color = RilanColors.Text,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    brief.loading -> "正在生成"
                    brief.hasContent -> "已生成"
                    aiConfigured -> "待生成"
                    else -> "未连接"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    brief.loading -> RilanColors.Dawn
                    brief.hasContent -> RilanColors.Moon
                    else -> RilanColors.TextTertiary
                },
            )
        }

        AnimatedVisibility(
            visible = brief.loading,
            enter = fadeIn(tween(260)) + expandVertically(tween(320)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(220)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                LoadingLines(lines = 4)
                Text(
                    "DeepSeek 正在读今天收下来的 $total 条内容…",
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextTertiary,
                )
            }
        }

        if (brief.hasContent) {
            AiMarkdown(
                text = brief.content,
                style = MaterialTheme.typography.bodyLarge,
                color = RilanColors.TextSecondary,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (brief.generatedAt > 0L) {
                        "生成于 ${relativeTimeLabel(brief.generatedAt)}"
                    } else {
                        "已保存在本机"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                TextAction(
                    if (brief.loading) "正在更新…" else "重新生成",
                    onClick = { if (!brief.loading) onGenerate() },
                    color = RilanColors.Dawn,
                )
            }
        }

        brief.error?.takeIf { it.isNotBlank() }?.let { error ->
            ErrorLine(error)
        }

        if (!brief.hasContent && !brief.loading) {
            Text(
                if (aiConfigured) {
                    "让 DeepSeek 读一遍今天的信息流、天气和日程，写成一段有主线的总览，再给几条能用的建议。"
                } else {
                    "填一个 DeepSeek API Key，日览就能直接总结今天的日报，不需要再配置外部 MCP。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = RilanColors.TextTertiary,
            )
            if (aiConfigured) {
                BriefGenerateButton(text = "用 DeepSeek 总结今天", onClick = onGenerate)
            } else {
                BriefGenerateButton(text = "去设置连接 DeepSeek", onClick = onOpenSettings)
            }
        } else if (!brief.hasContent && brief.loading) {
            // 生成中不再放按钮，避免重复请求
        }
    }
}

@Composable
private fun BriefGenerateButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(RilanColors.Moon, RilanColors.Sky)
                )
            )
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = RilanColors.Ink,
        )
    }
}

@Composable
private fun OverviewStats(total: Int, sources: Int, categories: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OverviewTile("信息", total.toString(), "条", RilanColors.Moon, Modifier.weight(1f))
        OverviewTile("来源", sources.toString(), "个", RilanColors.Dawn, Modifier.weight(1f))
        OverviewTile("分类", categories.toString(), "类", RilanColors.Lilac, Modifier.weight(1f))
    }
}

@Composable
private fun OverviewTile(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .border(0.75.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(unit, style = MaterialTheme.typography.labelSmall, color = RilanColors.TextTertiary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light),
            color = accent,
            maxLines = 1,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 第 3 张：信息流
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NewsPage(
    state: HomeState,
    sections: List<NewsSection>,
    total: Int,
    selected: Category?,
    onSelectAll: () -> Unit,
    onSelect: (Category) -> Unit,
    onItemClick: (NewsItem) -> Unit,
    onRefresh: () -> Unit,
    sheen: Float,
    bottomInset: Dp,
) {
    val active = sections.firstOrNull { it.category == selected }
    var categoriesExpanded by rememberSaveable { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(32.dp),
        // 四张卡统一用同一种玻璃厚度，底部渐隐下四页看起来一致。
        strong = true,
        contentPadding = PaddingValues(0.dp),
        sheen = sheen,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 38.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PageHeading(
                    index = "03",
                    label = "信息流",
                    title = "今天收下来的",
                    hint = updatedText(state.lastUpdated).ifBlank { "等待第一次更新" },
                    trailing = {
                        Text(
                            "$total 条",
                            style = MaterialTheme.typography.titleSmall,
                            color = RilanColors.Moon,
                        )
                    },
                )
                CategoryFilter(
                    sections = sections,
                    selected = selected,
                    expanded = categoriesExpanded,
                    onToggle = { categoriesExpanded = !categoriesExpanded },
                    onSelectAll = {
                        onSelectAll()
                        categoriesExpanded = false
                    },
                    onSelect = {
                        onSelect(it)
                        categoriesExpanded = false
                    },
                )
                if (selected != null && active == null) {
                    Text(
                        "这个分类暂时没有内容，先显示全部。",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                }
                if (!state.contentError.isNullOrBlank()) {
                    ErrorLine(state.contentError)
                }
            }

            AnimatedContent(
                targetState = active?.category,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(230)) + slideInVertically(tween(270)) { it / 18 }) togetherWith
                        (fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it / 24 })
                },
                label = "homeFeed",
            ) { category ->
                val section = sections.firstOrNull { it.category == category }
                // 不再截断：信息流一直向下滑，所有抓到的条目都能看到。
                // 分类页显示该分类全部条目；「全部」把各板块按轮转顺序铺开。
                val items = remember(section, sections, category) {
                    if (section == null) distinctById(interleave(sections))
                    else distinctById(section.items)
                }
                val accent = section?.category?.let { Color(it.accent) } ?: RilanColors.Moon
                val error = section?.error

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomInset),
                ) {
                    if (!error.isNullOrBlank()) {
                        item {
                            ErrorLine(
                                error,
                                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (items.isEmpty()) {
                        item {
                            EmptyState(
                                title = "暂时没有内容",
                                subtitle = "稍后刷新，或到扩展页检查来源。",
                            )
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                TextAction("刷新试试", onRefresh)
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = items,
                            // 稳定 key：id + 分类（id 在不同板块可能重复，分类把它区分开）。
                            key = { _, item -> "${item.id}-${item.category.name}" },
                        ) { index, item ->
                            NewsRow(
                                index = index,
                                item = item,
                                accent = if (category == null) Color(item.category.accent) else accent,
                                showCategory = category == null,
                                onClick = { onItemClick(item) },
                            )
                            if (index != items.lastIndex) {
                                GlassDivider(Modifier.padding(start = 48.dp, end = 20.dp))
                            }
                        }
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (category == null) "已经到底了 · 共 ${items.size} 条"
                                    else "这个分类已全部显示 · ${items.size} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RilanColors.TextTertiary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextAction("刷新", onRefresh, color = accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilter(
    sections: List<NewsSection>,
    selected: Category?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectAll: () -> Unit,
    onSelect: (Category) -> Unit,
) {
    val activeColor = selected?.let { Color(it.accent) } ?: RilanColors.Moon
    val activeLabel = selected?.label ?: "全部"
    val total = sections.sumOf { it.items.size }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.045f))
                .border(0.75.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(activeColor)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "分类",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                activeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = RilanColors.Text,
                maxLines = 1,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "$total 条",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextSecondary,
            )
            Spacer(Modifier.width(3.dp))
            val arrow by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = spring(stiffness = 420f, dampingRatio = 0.82f),
                label = "categoryArrow",
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起分类" else "展开分类",
                tint = RilanColors.TextSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = arrow },
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(stiffness = 380f, dampingRatio = 0.84f),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(220)),
            exit = shrinkVertically(
                animationSpec = spring(stiffness = 420f, dampingRatio = 0.88f),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(160)),
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CategoryChip(
                    label = "全部",
                    count = total,
                    color = RilanColors.Moon,
                    selected = selected == null,
                    onClick = onSelectAll,
                )
                sections.forEach { section ->
                    CategoryChip(
                        label = section.category.label,
                        count = section.items.size,
                        color = Color(section.category.accent),
                        selected = section.category == selected,
                        onClick = { onSelect(section.category) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    count: Int,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (selected) color.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.045f))
            .border(
                0.75.dp,
                if (selected) color.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.10f),
                CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (selected) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(color))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) RilanColors.Text else RilanColors.TextSecondary,
            maxLines = 1,
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) color else RilanColors.TextTertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun NewsRow(
    index: Int,
    item: NewsItem,
    accent: Color,
    showCategory: Boolean,
    onClick: () -> Unit,
) {
    val meta = remember(item, showCategory) {
        buildList {
            if (showCategory) add(item.category.label)
            if (item.source.isNotBlank()) add(item.source)
            if (item.timeLabel.isNotBlank()) add(item.timeLabel)
        }.joinToString("  ·  ")
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            Modifier.width(28.dp).padding(top = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Serif,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
            ),
            color = accent.copy(alpha = if (index < 3) 0.95f else 0.50f),
            maxLines = 1,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = if (index < 3) FontWeight.Medium else FontWeight.Normal,
                ),
                color = if (index < 3) RilanColors.Text else RilanColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.hotValue.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    HotPill(item.hotValue, accent)
                }
            }
        }
    }
}

@Composable
private fun HotPill(raw: String, accent: Color) {
    Text(
        formatHotValue(raw),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.11f))
            .border(0.6.dp, accent.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 第 4 张：今天
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TodayPage(
    events: List<ScheduleEvent>,
    onOpenSchedule: () -> Unit,
    sheen: Float,
    bottomInset: Dp,
) {
    val today = remember { LocalDate.now() }
    val todayEvents = remember(events, today) {
        events.filter { runCatching { Recurrence.occursOn(it, today) }.getOrDefault(false) }
            .sortedWith(
                compareBy<ScheduleEvent> { if (it.time == null) 0 else 1 }
                    .thenBy { it.time ?: "" }
                    .thenByDescending { it.priority.ordinal }
            )
    }
    val upcoming = remember(events, today) {
        events.mapNotNull { event ->
            val next = runCatching { Recurrence.nextOccurrence(event, today) }.getOrNull()
                ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(today, next)
            if (days in 1..14) Upcoming(event, next, days) else null
        }.sortedWith(compareBy<Upcoming> { it.date }.thenBy { it.event.time ?: "" }).take(4)
    }
    // 纪念日条：复用同一份 events，取最近的 3 条，不新增数据源。
    val dayCounters = remember(events, today) { buildDayCounters(events, today).take(3) }
    val specialCount = todayEvents.count {
        it.kind == EventKind.BIRTHDAY || it.kind == EventKind.ANNIVERSARY
    }

    GlassCard(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(32.dp),
        strong = true,
        contentPadding = PaddingValues(0.dp),
        sheen = sheen,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })
                .padding(start = 20.dp, end = 20.dp, top = 38.dp, bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PageHeading(
                index = "04",
                label = "今天",
                title = "今天要记得的事",
                hint = "${today.format(DAY_FORMAT)} · ${weekdayLabel(today)}",
                trailing = {
                    GlassPill(
                        text = if (todayEvents.isEmpty()) "留白" else "${todayEvents.size} 件",
                        color = if (todayEvents.isEmpty()) RilanColors.TextTertiary else RilanColors.Moon,
                        background = (if (todayEvents.isEmpty()) Color.White else RilanColors.Moon)
                            .copy(alpha = 0.10f),
                    )
                },
            )

            if (specialCount > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(RilanColors.Lilac.copy(alpha = 0.09f))
                        .border(0.75.dp, RilanColors.Lilac.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(RilanColors.Lilac))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "今天有 $specialCount 个生日或纪念日",
                        style = MaterialTheme.typography.bodySmall,
                        color = RilanColors.Lilac,
                    )
                }
            }

            if (todayEvents.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        "今天没有安排。",
                        style = MaterialTheme.typography.headlineSmall,
                        color = RilanColors.Text,
                    )
                    Text(
                        "留白也是日程的一部分。想放一件事进去，就去日程页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = RilanColors.TextTertiary,
                    )
                    TextAction("打开日程", onOpenSchedule, color = RilanColors.Moon)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    todayEvents.forEach { event ->
                        TodayEventRow(event)
                    }
                }
            }

            if (upcoming.isNotEmpty()) {
                GlassDivider()
                SectionLabel("接下来", RilanColors.Dawn) {
                    Text(
                        "未来 14 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    upcoming.forEach { item ->
                        UpcomingRow(item)
                    }
                }
            }

            // 纪念日：放在「接下来」之后、底部入口之前，不挤占今天的主内容；没有就不显示。
            if (dayCounters.isNotEmpty()) {
                GlassDivider()
                SectionLabel("纪念日", RilanColors.Lilac) {
                    Text(
                        "${dayCounters.size} 个日子",
                        style = MaterialTheme.typography.labelSmall,
                        color = RilanColors.TextTertiary,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    dayCounters.forEach { counter ->
                        HomeDayCountRow(counter = counter, onClick = onOpenSchedule)
                    }
                }
            }

            TextAction("去日程页安排", onOpenSchedule, color = RilanColors.Moon)
        }
    }
}

private data class Upcoming(
    val event: ScheduleEvent,
    val date: LocalDate,
    val days: Long,
)

@Composable
private fun TodayEventRow(event: ScheduleEvent) {
    val accent = when (event.kind) {
        EventKind.BIRTHDAY -> RilanColors.Lilac
        EventKind.ANNIVERSARY -> RilanColors.Dawn
        else -> priorityColor(event.priority)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(0.6.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            event.time ?: "全天",
            Modifier.width(44.dp).padding(top = 1.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (event.time == null) RilanColors.TextTertiary else RilanColors.TextSecondary,
            maxLines = 1,
        )
        Box(
            Modifier
                .padding(horizontal = 8.dp)
                .width(2.dp)
                .height(34.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                event.title,
                style = MaterialTheme.typography.titleSmall,
                color = RilanColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (event.kind != EventKind.TASK) {
                    GlassPill(
                        text = event.kind.label,
                        color = accent,
                        background = accent.copy(alpha = 0.12f),
                    )
                }
                GlassPill(
                    text = event.priority.label,
                    color = accent.copy(alpha = 0.9f),
                    background = accent.copy(alpha = 0.09f),
                )
            }
            if (event.notes.isNotBlank()) {
                Text(
                    event.notes.trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UpcomingRow(item: Upcoming) {
    val dayLabel = when (item.days) {
        1L -> "明天"
        else -> "还有 ${item.days} 天"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            dayLabel,
            Modifier.width(56.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (item.days <= 1) RilanColors.Dawn else RilanColors.TextTertiary,
            maxLines = 1,
        )
        Text(
            "${item.date.monthValue}/${item.date.dayOfMonth}",
            Modifier.width(36.dp),
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
            maxLines = 1,
        )
        Text(
            item.event.title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.event.time?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = RilanColors.TextTertiary)
        }
    }
}

/**
 * 首页的纪念日紧凑行：大数字天 + 标题 + 日期 / 还有多少天。
 * 点整行打开日程页；页内再切到「纪念日」分段就能看到卡片与详情。
 */
@Composable
private fun HomeDayCountRow(counter: DayCounter, onClick: () -> Unit) {
    val isToday = counter.direction == Direction.TODAY
    val accent = when {
        isToday -> RilanColors.Mint
        counter.direction == Direction.PAST -> RilanColors.TextTertiary
        counter.event.kind == EventKind.BIRTHDAY -> RilanColors.Lilac
        counter.event.kind == EventKind.ANNIVERSARY -> RilanColors.Dawn
        else -> RilanColors.Sky
    }
    // 未来只显示日期和第几个（天数已经在左边的大数字里）；过去补一句「已经 N 天」表方向。
    val dateText = "${counter.anchor.monthValue}月${counter.anchor.dayOfMonth}日"
    val metaText = if (counter.direction == Direction.PAST) {
        "$dateText · 已经过去"
    } else {
        val ordinalText = counter.ordinal?.let { ordinal ->
            when (counter.event.kind) {
                EventKind.BIRTHDAY -> " · 第 $ordinal 个生日"
                EventKind.ANNIVERSARY -> " · 第 $ordinal 个纪念日"
                else -> " · 第 $ordinal 次"
            }
        }.orEmpty()
        dateText + ordinalText
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(0.6.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(70.dp)) {
            if (!isToday) {
                // 纪念日是「已经」，生日 / 倒计时是「还有」；方向必须和大数字一起出现。
                Text(
                    text = counter.primaryLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = accent.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (isToday) "今天" else counter.primaryDays.toString(),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = if (isToday) 17.sp else 22.sp,
                        lineHeight = 24.sp,
                    ),
                    color = accent,
                    maxLines = 1,
                )
                if (!isToday) {
                    Text(
                        text = "天",
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent.copy(alpha = 0.72f),
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = counter.event.title,
                style = MaterialTheme.typography.titleSmall,
                color = RilanColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun priorityColor(priority: Priority): Color = when (priority) {
    Priority.LOW -> RilanColors.Moon
    Priority.MEDIUM -> RilanColors.Dawn
    Priority.HIGH -> RilanColors.Rose
}

// ─────────────────────────────────────────────────────────────────────────────
// 卡片标题与页码
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageHeading(
    index: String,
    label: String,
    title: String,
    hint: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    index,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = 12.sp,
                    ),
                    color = RilanColors.Moon.copy(alpha = 0.8f),
                )
                Spacer(Modifier.width(7.dp))
                Box(
                    Modifier
                        .width(16.dp)
                        .height(1.dp)
                        .background(RilanColors.Moon.copy(alpha = 0.38f))
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.Moon.copy(alpha = 0.84f),
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = RilanColors.Text,
            )
            if (hint != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        trailing?.invoke()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 文案与工具
// ─────────────────────────────────────────────────────────────────────────────

internal fun greetingFor(hour: Int): String = when (hour) {
    in 5..8 -> "清晨好"
    in 9..11 -> "上午好"
    in 12..16 -> "午后好"
    in 17..18 -> "傍晚好"
    else -> "晚上好"
}

internal fun todayEyebrow(): String {
    val today = LocalDate.now()
    return "${today.format(DAY_FORMAT)} · ${WEEK_LABELS[today.dayOfWeek.value - 1]}"
}

internal fun updatedText(epochMillis: Long): String =
    if (epochMillis <= 0L) "" else "更新于 ${relativeTimeLabel(epochMillis)}"

private fun weekdayLabel(date: LocalDate): String =
    WEEK_LABELS[(date.dayOfWeek.value - 1).coerceIn(0, 6)]

/** 同一板块里 id+分类 只保留一条：LazyColumn 的 key 重复会直接崩掉整个界面。 */
private fun distinctById(items: List<NewsItem>): List<NewsItem> {
    if (items.size < 2) return items
    val seen = HashSet<String>(items.size * 2)
    return items.filter { seen.add("${it.id}-${it.category.name}") }
}

private fun interleave(sections: List<NewsSection>): List<NewsItem> {
    val lists = sections.map { it.items }
    val max = lists.maxOfOrNull { it.size } ?: 0
    val out = ArrayList<NewsItem>()
    for (index in 0 until max) {
        lists.forEach { list ->
            list.getOrNull(index)?.let { out.add(it) }
        }
    }
    return out
}
