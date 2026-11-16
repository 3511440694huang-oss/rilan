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
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.AiText
import app.rilan.daily.core.WallpaperInfo
import app.rilan.daily.ui.theme.RilanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// 背景：深靛蓝的夜、月光与地平线的杏桃。壁纸退到远处，只负责给玻璃提供折射。
// ─────────────────────────────────────────────────────────────────────────────

data class GlassBackdrop(
    val photo: ImageBitmap?,
    val softBitmap: ImageBitmap?,
    val size: IntSize,
)

val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

@Composable
fun AppBackdrop(
    wallpaper: WallpaperInfo?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var softBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var backdropSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(wallpaper?.path) {
        val path = wallpaper?.path
        if (path == null) {
            photo = null
            softBitmap = null
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { photoBitmap(path) to frostedBitmap(path) }.getOrNull()
        } ?: return@LaunchedEffect
        photo = loaded.first
        softBitmap = loaded.second
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { backdropSize = it.size }
            .background(RilanColors.Ink)
    ) {
        // 每天一张必应壁纸，铺满整屏。卡片的折射与这里的照片共用同一张图，
        // 所以玻璃里的颜色和背景是同一个世界，而不是一张独立的渐变。
        Crossfade(
            targetState = photo,
            animationSpec = tween(1100),
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

        Canvas(Modifier.fillMaxSize()) {
            // 四角压暗，照片才不会在边缘抢走卡片的注意力。
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color(0x5C070912), Color.Transparent, Color.Transparent, Color(0x4D070912))
                )
            )
            drawRect(brush = GrainBrush, alpha = 0.22f)
        }

        CompositionLocalProvider(
            LocalGlassBackdrop provides GlassBackdrop(
                photo = photo,
                softBitmap = softBitmap,
                size = backdropSize,
            )
        ) {
            content()
        }
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
    Canvas(Modifier.fillMaxSize()) {
        drawRect(RilanColors.Ink)
        val center = Offset(size.width * 0.18f, size.height * 0.08f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(RilanColors.Moon.copy(alpha = 0.08f), Color.Transparent),
                center = center,
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = center,
        )
        drawRect(brush = GrainBrush, alpha = 0.26f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 玻璃：折射 + 厚度 + 细边 + 颗粒 + 一道会随滑动移动的反光
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(30.dp),
    strong: Boolean = false,
    refraction: Boolean = false,
    /**
     * 翻页 / 横向滑动进行中时为 true：跳过最贵的「背景折射重绘」并收小阴影，
     * 滑动结束立刻恢复，静止时的毛玻璃效果完全不变。
     */
    scrolling: Boolean = false,
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
    val backdrop = LocalGlassBackdrop.current
    val origin = remember { mutableStateOf(Offset.Zero) }

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
                // 滑动中收小阴影：大圆角阴影每帧重算很贵，静止时恢复原来的厚度。
                elevation = when {
                    scrolling -> if (strong) 12.dp else 8.dp
                    strong -> 28.dp
                    else -> 18.dp
                },
                shape = shape,
                clip = false,
                ambientColor = Color.Black,
                spotColor = Color(0xFF24345C),
            )
            .clip(shape)
            .onGloballyPositioned {
                // 只在卡片真的换位置时写状态，避免滑动时每帧都触发一次快照提交。
                val position = it.positionInRoot()
                if (position != origin.value) origin.value = position
            }
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
        // 1. 背景的折射：同一张壁纸、同一屏幕坐标，被裁进这张卡片。
        //    这里用 Canvas.drawImage 而不是整屏 Image 图层，位置只在绘制阶段读取，
        //    翻页时不会因为 onGloballyPositioned 触发整张卡片重组。
        //    横向滑动时先跳过这块重绘（滑完立刻恢复），它是一帧里最贵的采样。
        if (refraction && !scrolling && backdrop?.softBitmap != null) {
            val image = backdrop.softBitmap
            val screen = backdrop.size
            Canvas(Modifier.matchParentSize()) {
                if (screen.width <= 0 || screen.height <= 0) return@Canvas
                val scaledW = screen.width * 1.16f
                val scaledH = screen.height * 1.16f
                val left = (screen.width - scaledW) / 2f - origin.value.x
                val top = (screen.height - scaledH) / 2f - origin.value.y - screen.height * 0.07f
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(scaledW.roundToInt(), scaledH.roundToInt()),
                    alpha = 0.46f,
                    filterQuality = FilterQuality.Low,
                )
            }
        }

        // 2. 玻璃本身的颜色。上下不同，像一块有厚度的板。
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to topFill,
                        0.42f to RilanColors.Deep.copy(alpha = if (strong) 0.58f else 0.65f),
                        1f to bottomFill,
                    )
                )
        )

        Canvas(Modifier.matchParentSize()) {
            // 左上冷光、右下暖光，给透明表面一点“被照亮”的方向。
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(RilanColors.Moon.copy(alpha = 0.11f), Color.Transparent),
                    center = Offset(size.width * 0.08f, 0f),
                    radius = size.width * 0.86f,
                ),
                radius = size.width * 0.86f,
                center = Offset(size.width * 0.08f, 0f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(RilanColors.Dawn.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.98f, size.height),
                    radius = size.width * 0.7f,
                ),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.98f, size.height),
            )

            // 随卡片滑动移动的斜向反光。
            val sheenX = size.width * (sheen.coerceIn(0f, 1f) * 1.15f - 0.1f)
            drawRect(
                brush = Brush.linearGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.055f),
                        Color.White.copy(alpha = 0.016f),
                        Color.Transparent,
                    ),
                    start = Offset(sheenX, 0f),
                    end = Offset(sheenX + size.width * 0.46f, size.height),
                )
            )

            // 底部内阴影，玻璃才有厚度。
            drawRect(
                brush = Brush.verticalGradient(
                    0.64f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.18f),
                )
            )

            // 颗粒。用一张 96px 的噪点图平铺，避免每帧画几百个圆。
            drawRect(brush = GrainBrush, alpha = 0.52f)

            // 顶部的一条极细高光，只在玻璃边缘出现。
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.34f),
                        Color.White.copy(alpha = 0.12f),
                        Color.Transparent,
                    ),
                    startX = size.width * 0.08f,
                    endX = size.width * 0.92f,
                ),
                start = Offset(size.width * 0.08f, 1.2.dp.toPx()),
                end = Offset(size.width * 0.92f, 1.2.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }

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
    val alpha by transition.animateFloat(
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
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = alpha * 0.5f))
            )
        }
    }
}

@Composable
fun LoadingDots(color: Color = RilanColors.Moon) {
    val transition = rememberInfiniteTransition(label = "dots")
    val p by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "dotP",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a = 0.22f + 0.78f * ((kotlin.math.sin((p * 2 * Math.PI + i).toFloat()) + 1f) / 2f)
            Box(Modifier.size(4.dp).clip(CircleShape).background(color.copy(alpha = a)))
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

/** 背景照片：只在 IO 线程解码一次；超宽图降到 2x，避免占太多内存。 */
private fun photoBitmap(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = if (longest > 2200) 2 else 1
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val source = BitmapFactory.decodeFile(path, options) ?: return null
    return source.asImageBitmap()
}

/**
 * 壁纸在 IO 线程里降到 ~1/5 分辨率，再做一次可分离盒式模糊。
 *
 * 背景和卡片折射共用这一张图：API 26–30 没有 RenderEffect，Modifier.blur 会是
 * no-op；预先模糊既保证老机器也有磨砂感，又不需要在每张卡上跑全屏高斯模糊。
 */
private fun frostedBitmap(path: String): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inSampleSize = 5 }
    val source = BitmapFactory.decodeFile(path, options) ?: return null
    val width = source.width
    val height = source.height
    if (width <= 0 || height <= 0) {
        source.recycle()
        return null
    }
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    source.recycle()

    val blurred = boxBlur(boxBlur(pixels, width, height, 3), width, height, 3)
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    output.setPixels(blurred, 0, width, 0, 0, width, height)
    return output.asImageBitmap()
}

private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
    if (radius <= 0 || width <= 0 || height <= 0) return source
    val horizontal = IntArray(source.size)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (dx in -radius..radius) {
                val sx = (x + dx).coerceIn(0, width - 1)
                val color = source[row + sx]
                a += color ushr 24
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
                count++
            }
            horizontal[row + x] = ((a / count) shl 24) or
                ((r / count) shl 16) or
                ((g / count) shl 8) or
                (b / count)
        }
    }

    val vertical = IntArray(source.size)
    for (x in 0 until width) {
        for (y in 0 until height) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (dy in -radius..radius) {
                val sy = (y + dy).coerceIn(0, height - 1)
                val color = horizontal[sy * width + x]
                a += color ushr 24
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
                count++
            }
            vertical[y * width + x] = ((a / count) shl 24) or
                ((r / count) shl 16) or
                ((g / count) shl 8) or
                (b / count)
        }
    }
    return vertical
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
