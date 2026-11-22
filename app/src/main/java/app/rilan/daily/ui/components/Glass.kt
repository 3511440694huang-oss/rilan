package app.rilan.daily.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.AiText
import app.rilan.daily.core.WallpaperInfo
import app.rilan.daily.ui.theme.RilanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// 背景：深靛蓝的夜、月光与地平线的杏桃。壁纸退到远处，只负责给玻璃提供折射。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AppBackdrop(
    wallpaper: WallpaperInfo?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    // 按屏幕长边解码：必应原图动辄 4K，全量解码既占内存又让首帧更慢。
    val metrics = LocalContext.current.resources.displayMetrics
    val targetEdge = maxOf(metrics.widthPixels, metrics.heightPixels)

    LaunchedEffect(wallpaper?.path, targetEdge) {
        val path = wallpaper?.path
        if (path == null) {
            photo = null
            return@LaunchedEffect
        }
        photo = withContext(Dispatchers.IO) {
            runCatching { photoBitmap(path, targetEdge) }.getOrNull()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(RilanColors.Ink)
    ) {
        // 每天一张必应壁纸，铺满整屏；卡片是它上面的一张半透明玻璃。
        Crossfade(
            targetState = photo,
            // 两张全屏位图同时合成是最重的一帧，1.1 秒实在太久，收到 600ms 仍然够柔和。
            animationSpec = tween(600),
            label = "dailyWallpaper",
        ) { bmp ->
            if (bmp != null) {
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.16f
                                scaleY = 1.16f
                                translationY = size.height * -0.07f
                            },
                    )
                    // 一层深靛蓝的夜色压住照片的亮部，保证白字与玻璃上方的可读性。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(BackdropScrim)
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0x14070A18))
                    )
                }
            } else {
                FallbackBackdrop()
            }
        }

        // 背景是整屏最大的一块静态绘制：画刷缓存一次 + 独立图层，
        // 列表滚动时它只被合成，不会跟着每帧重录。
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    // 四角压暗，照片才不会在边缘抢走卡片的注意力。
                    val corners = Brush.horizontalGradient(
                        listOf(Color(0x5C070912), Color.Transparent, Color.Transparent, Color(0x4D070912))
                    )
                    onDrawBehind {
                        drawRect(corners)
                        drawRect(GrainBrush, alpha = 0.22f)
                    }
                }
        )

        content()
    }
}

private val BackdropScrim = Brush.verticalGradient(
    0f to Color(0xA6070A14),
    0.18f to Color(0x66070A14),
    0.52f to Color(0x3D070A14),
    0.76f to Color(0x990A0C18),
    1f to Color(0xE6070912),
)

@Composable
private fun FallbackBackdrop() {
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawWithCache {
                val center = Offset(size.width * 0.18f, size.height * 0.08f)
                val radius = size.width * 0.9f
                val glow = Brush.radialGradient(
                    listOf(RilanColors.Moon.copy(alpha = 0.08f), Color.Transparent),
                    center = center,
                    radius = radius,
                )
                onDrawBehind {
                    drawRect(RilanColors.Ink)
                    drawCircle(glow, radius = radius, center = center)
                    drawRect(GrainBrush, alpha = 0.26f)
                }
            }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 玻璃：折射 + 厚度 + 细边 + 颗粒 + 一道会随滑动移动的反光
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(30.dp),
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    sheen: Float = 0.34f,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.992f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.78f),
        label = "glassPress",
    )

    val topFill = if (strong) {
        RilanColors.SurfaceRaised.copy(alpha = 0.54f)
    } else {
        RilanColors.SurfaceRaised.copy(alpha = 0.46f)
    }
    val bottomFill = if (strong) {
        RilanColors.Ink.copy(alpha = 0.70f)
    } else {
        RilanColors.Ink.copy(alpha = 0.76f)
    }

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                // 阴影固定：静止和翻页时是同一块玻璃，不会在松手那一刻突然变样。
                elevation = if (strong) 28.dp else 18.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color(0xFF24345C),
            )
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            ),
    ) {
        // 玻璃本体 + 双层边放在同一个独立图层里：
        // 滚动 / 翻页时这一整块静态绘制只做合成，不会跟着每帧重录。
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer(),
        ) {
            // 1~2. 玻璃的颜色、冷光、反光、内阴影、颗粒、顶部高光，全部画进一块
            //      drawWithCache 的画布里：
            //      · 渐变画刷只在尺寸变化时构造一次，滚动时不再每帧 new 一堆 Brush/Shader；
            //      · 外面套一层独立图层，滚动/翻页时这块最贵的绘制不会被重录，
            //        只重录真正在动的内容，滑动手感才跟得上手指。
            //      不再在卡片里额外铺一层壁纸折射：那层图只在静止时出现，
            //      滑动时会整个消失，卡片看起来像「停下就被加了半透明遮罩」。
            val deepMid = RilanColors.Deep.copy(alpha = if (strong) 0.58f else 0.65f)
            Spacer(
                Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val fill = Brush.verticalGradient(
                            0f to topFill,
                            0.42f to deepMid,
                            1f to bottomFill,
                        )
                        // 左上冷光、右下暖光，给透明表面一点“被照亮”的方向。
                        val coldLight = Brush.radialGradient(
                            listOf(RilanColors.Moon.copy(alpha = 0.11f), Color.Transparent),
                            center = Offset(w * 0.08f, 0f),
                            radius = w * 0.86f,
                        )
                        val warmLight = Brush.radialGradient(
                            listOf(RilanColors.Dawn.copy(alpha = 0.07f), Color.Transparent),
                            center = Offset(w * 0.98f, h),
                            radius = w * 0.7f,
                        )
                        // 斜向反光：sheen 由调用方固定，尺寸不变时位置也不变。
                        val sheenX = w * (sheen.coerceIn(0f, 1f) * 1.15f - 0.1f)
                        val sheenBrush = Brush.linearGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.055f),
                                Color.White.copy(alpha = 0.016f),
                                Color.Transparent,
                            ),
                            start = Offset(sheenX, 0f),
                            end = Offset(sheenX + w * 0.46f, h),
                        )
                        // 底部内阴影，玻璃才有厚度。
                        val innerShadow = Brush.verticalGradient(
                            0.64f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.18f),
                        )
                        val topLine = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.34f),
                                Color.White.copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                            startX = w * 0.08f,
                            endX = w * 0.92f,
                        )
                        val lineY = 1.2.dp.toPx()
                        onDrawBehind {
                            drawRect(fill)
                            drawCircle(coldLight, radius = w * 0.86f, center = Offset(w * 0.08f, 0f))
                            drawCircle(warmLight, radius = w * 0.7f, center = Offset(w * 0.98f, h))
                            drawRect(sheenBrush)
                            drawRect(innerShadow)
                            // 颗粒。用一张 96px 的噪点图平铺，避免每帧画几百个圆。
                            drawRect(GrainBrush, alpha = 0.52f)
                            // 顶部的一条极细高光，只在玻璃边缘出现。
                            drawLine(
                                brush = topLine,
                                start = Offset(w * 0.08f, lineY),
                                end = Offset(w * 0.92f, lineY),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }
            )

            // 3. 双层边：外层是环境光，内层是玻璃的倒角。
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.30f),
                                Color.White.copy(alpha = 0.055f),
                                RilanColors.Dawn.copy(alpha = 0.13f),
                                Color.White.copy(alpha = 0.14f),
                            ),
                        ),
                        shape = shape,
                    )
            )
            Box(
                Modifier
                    .matchParentSize()
                    .padding(1.4.dp)
                    .border(
                        width = 0.6.dp,
                        color = Color.White.copy(alpha = 0.075f),
                        shape = shape,
                    )
            )

        }

        Box(Modifier.padding(contentPadding), content = content)
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// 小部件
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RilanColors.TextSecondary,
    background: Color = Color(0x16FFFFFF),
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(background)
            .border(0.75.dp, Color.White.copy(alpha = 0.13f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        leading?.invoke()
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = RilanColors.Text,
    size: Dp = 44.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(stiffness = 560f, dampingRatio = 0.72f),
        label = "iconPress",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(8.dp, CircleShape, clip = false, spotColor = Color(0xFF1C2A4A))
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.13f),
                        Color.White.copy(alpha = 0.045f),
                    )
                )
            )
            .border(
                0.75.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.07f))
                ),
                CircleShape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.43f),
        )
    }
}

@Composable
fun SectionLabel(
    text: String,
    accent: Color = RilanColors.Moon,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.95f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = accent.copy(alpha = 0.92f),
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.75.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.13f),
                        Color.White.copy(alpha = 0.045f),
                        Color.Transparent,
                    )
                )
            )
    )
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(0.8.dp, RilanColors.TextTertiary.copy(alpha = 0.5f), CircleShape)
            )
            Box(
                Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(RilanColors.Moon.copy(alpha = 0.75f))
            )
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = RilanColors.TextSecondary)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = RilanColors.Danger.copy(alpha = 0.92f),
        style = MaterialTheme.typography.bodySmall,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// AI 文本展示（内容由外部 MCP 写入）
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AiMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RilanColors.TextSecondary,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val lines = remember(text) { text.lines() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("###") -> Text(
                    inlineMarkdown(line.removePrefix("###").trim()),
                    style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * 1.04f),
                    color = RilanColors.Text,
                )
                line.startsWith("##") -> Text(
                    inlineMarkdown(line.removePrefix("##").trim()),
                    style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * 1.08f),
                    color = RilanColors.Text,
                )
                line.startsWith("#") -> Text(
                    inlineMarkdown(line.removePrefix("#").trim()),
                    style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = style.fontSize * 1.12f),
                    color = RilanColors.Text,
                )
                line.startsWith("- ") || line.startsWith("• ") -> Row {
                    Text("·", color = RilanColors.Moon, style = style, modifier = Modifier.padding(end = 8.dp))
                    Text(inlineMarkdown(line.drop(2)), style = style, color = color)
                }
                else -> Text(inlineMarkdown(line), style = style, color = color)
            }
        }
    }
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    val regex = Regex("\\*\\*(.+?)\\*\\*")
    regex.findAll(text).forEach { match ->
        append(text.substring(index, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = RilanColors.Text)) {
            append(match.groupValues[1])
        }
        index = match.range.last + 1
    }
    if (index < text.length) append(text.substring(index))
}

@Composable
fun AiBlock(
    ai: AiText,
    emptyHint: String,
    modifier: Modifier = Modifier,
    onGenerate: (() -> Unit)? = null,
    buttonLabel: String = "AI 解读",
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel("AI 解读", RilanColors.Dawn)
        when {
            ai.loading -> LoadingLines()
            ai.hasContent -> AiMarkdown(ai.content)
            ai.error != null -> {
                ErrorLine(ai.error)
                if (onGenerate != null) TextAction(buttonLabel, onGenerate)
            }
            else -> {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextTertiary,
                )
                if (onGenerate != null) TextAction(buttonLabel, onGenerate)
            }
        }
    }
}

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    color: Color = RilanColors.Moon,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

@Composable
fun LoadingLines(modifier: Modifier = Modifier, lines: Int = 3) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    // 只拿到 State，在 graphicsLayer 里读 value：每帧只改图层 alpha，
    // 不触发重组、也不触发重绘，刷新时滑动不会被几行省略号拖慢。
    val shimmer = transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "shimmerAlpha",
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(lines) { index ->
            Box(
                Modifier
                    .fillMaxWidth(if (index == lines - 1) 0.62f else 1f - index * 0.07f)
                    .height(10.dp)
                    .graphicsLayer { alpha = shimmer.value * 0.5f }
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun LoadingDots(color: Color = RilanColors.Moon) {
    val transition = rememberInfiniteTransition(label = "dots")
    val p = transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "dotP",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(4.dp)
                    .graphicsLayer {
                        val phase = (p.value * 2 * Math.PI + i).toFloat()
                        alpha = 0.22f + 0.78f * ((kotlin.math.sin(phase) + 1f) / 2f)
                    }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
fun ScreenTitle(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(20.dp)
                        .height(1.dp)
                        .background(RilanColors.Moon.copy(alpha = 0.65f))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    color = RilanColors.Moon.copy(alpha = 0.82f),
                    letterSpacing = 0.8.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    shadow = Shadow(Color(0x66000000), Offset(0f, 2f), 14f)
                ),
                color = RilanColors.Text,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(5.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextTertiary,
                )
            }
        }
        actions?.invoke()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 材质工具
// ─────────────────────────────────────────────────────────────────────────────

/** 背景照片：只在 IO 线程解码一次，并缩到屏幕长边附近，避免大图 OOM。 */
private fun photoBitmap(path: String, targetEdge: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    val limit = if (targetEdge > 0) targetEdge else 2200
    var sample = 1
    // 卡片和背景都有 1.16 倍放大，留一点余量；inSampleSize 只能是 2 的幂。
    while (longest / sample > limit * 1.25f) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val source = BitmapFactory.decodeFile(path, options) ?: return null
    return source.asImageBitmap()
}

private val Grain: ImageBitmap by lazy {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val random = Random(71)
    val pixels = IntArray(size * size)
    for (index in pixels.indices) {
        val roll = random.nextInt(100)
        val alpha = when {
            roll < 3 -> 40
            roll < 12 -> 22
            roll < 30 -> 9
            else -> 0
        }
        val rgb = if (random.nextBoolean()) 0xFFFFFF else 0x000000
        pixels[index] = (alpha shl 24) or rgb
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    bitmap.asImageBitmap()
}

private val GrainBrush: ShaderBrush by lazy {
    ShaderBrush(ImageShader(Grain, TileMode.Mirror, TileMode.Mirror))
}
