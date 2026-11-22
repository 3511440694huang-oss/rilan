package app.rilan.daily.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.rilan.daily.ui.theme.RilanColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// 天气图形：全部用 Canvas 手绘，太阳光线缓慢旋转、云轻微浮动、雨滴下落
// ─────────────────────────────────────────────────────────────────────────────

private val SunCore = Color(0xFFFFC46B)
private val SunHighlight = Color(0xFFFFF2D6)
private val CloudBody = Color(0xFFECF4F8)
private val CloudShade = Color(0xFFB6CBD6)
private val RainColor = Color(0xFF8FC8FF)
private val SnowColor = Color(0xFFEDF8FF)
private val NightColor = Color(0xFFB3BCFF)

private val TWO_PI = (PI * 2.0).toFloat()

internal enum class WeatherGlyphKind {
    SUN,
    SUN_CLOUD,
    CLOUD,
    FOG,
    RAIN,
    SNOW,
    THUNDER,
    MOON,
    MOON_CLOUD,
}

internal fun weatherGlyphKind(code: Int, isDay: Boolean): WeatherGlyphKind = when (code) {
    0 -> if (isDay) WeatherGlyphKind.SUN else WeatherGlyphKind.MOON
    1, 2 -> if (isDay) WeatherGlyphKind.SUN_CLOUD else WeatherGlyphKind.MOON_CLOUD
    3 -> WeatherGlyphKind.CLOUD
    45, 48 -> WeatherGlyphKind.FOG
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherGlyphKind.RAIN
    71, 73, 75, 77, 85, 86 -> WeatherGlyphKind.SNOW
    95, 96, 99 -> WeatherGlyphKind.THUNDER
    else -> if (isDay) WeatherGlyphKind.SUN_CLOUD else WeatherGlyphKind.MOON_CLOUD
}

@Composable
fun WeatherGlyph(
    code: Int,
    isDay: Boolean,
    modifier: Modifier = Modifier,
) {
    val kind = weatherGlyphKind(code, isDay)
    val transition = rememberInfiniteTransition(label = "weatherGlyph")
    // 动画值只拿 State，不在组合阶段解包：解包会让这枚图标每帧重组一次，
    // 进而连带整张卡片重组。下面在 draw 里读 value，只失效这一块画布。
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 56_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glyphSpin",
    )
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glyphDrift",
    )
    val fall = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_450, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glyphFall",
    )
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glyphPulse",
    )

    Canvas(
        modifier
            // 独立绘制层：无限动画每帧只重录这枚 92dp 小图标，
            // 滚动时也不会把整张玻璃卡（渐变 + 噪点）一起重录。
            .graphicsLayer(),
    ) {
        drawFullGlyph(kind, spin.value, drift.value, fall.value, pulse.value)
    }
}

/** 列表里用的小图标：同样的笔触，静态绘制，避免几十个无限动画。 */
@Composable
fun WeatherMiniGlyph(
    code: Int,
    isDay: Boolean,
    modifier: Modifier = Modifier,
) {
    val kind = weatherGlyphKind(code, isDay)
    Canvas(modifier) {
        drawMiniGlyph(kind)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 大图
// ─────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawFullGlyph(
    kind: WeatherGlyphKind,
    spin: Float,
    drift: Float,
    fall: Float,
    pulse: Float,
) {
    val u = size.minDimension / 100f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val sway = (drift - 0.5f) * 5f * u

    when (kind) {
        WeatherGlyphKind.SUN -> drawSun(Offset(cx, cy - 4f * u), 17.5f * u, spin)

        WeatherGlyphKind.MOON -> {
            drawStars(pulse, 0.55f)
            drawMoon(Offset(cx - 2f * u, cy - 3f * u), 19f * u)
        }

        WeatherGlyphKind.SUN_CLOUD -> {
            drawSun(Offset(cx + 15f * u, cy - 18f * u), 12f * u, spin * 0.65f)
            drawCloud(Offset(cx - 4f * u + sway, cy + 12f * u), 62f * u)
        }

        WeatherGlyphKind.MOON_CLOUD -> {
            drawStars(pulse, 0.35f)
            drawMoon(Offset(cx + 15f * u, cy - 19f * u), 11.5f * u)
            drawCloud(Offset(cx - 4f * u + sway, cy + 12f * u), 62f * u)
        }

        WeatherGlyphKind.CLOUD -> drawCloud(Offset(cx + sway, cy), 64f * u)

        WeatherGlyphKind.FOG -> {
            drawCloud(Offset(cx + sway, cy - 12f * u), 58f * u, alpha = 0.5f)
            repeat(3) { index ->
                val width = (54f - index * 6f) * u
                val y = cy + (7f + index * 9f) * u
                val offsetX = (index % 2) * 3f * u + sway * 0.4f
                drawLine(
                    color = Color(0xFFD7E5EC).copy(alpha = 0.72f),
                    start = Offset(cx - width / 2f + offsetX, y),
                    end = Offset(cx + width / 2f + offsetX, y),
                    strokeWidth = 3.4f * u,
                    cap = StrokeCap.Round,
                )
            }
        }

        WeatherGlyphKind.RAIN -> {
            drawCloud(Offset(cx + sway, cy - 1f * u), 62f * u)
            drawRain(Offset(cx, cy - 1f * u), 62f * u, fall)
        }

        WeatherGlyphKind.SNOW -> {
            drawCloud(Offset(cx + sway, cy - 1f * u), 62f * u)
            drawSnow(Offset(cx, cy - 1f * u), 62f * u, fall)
        }

        WeatherGlyphKind.THUNDER -> {
            drawCloud(Offset(cx + sway, cy - 4f * u), 60f * u)
            val flash = 0.42f + 0.58f * abs(sin(pulse * TWO_PI))
            drawBolt(Offset(cx - 9f * u, cy + 14f * u), 30f * u, RilanColors.Gold.copy(alpha = flash))
        }
    }
}

private fun DrawScope.drawSun(
    center: Offset,
    radius: Float,
    spin: Float,
    glow: Float = 0.40f,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(SunCore.copy(alpha = glow), Color.Transparent),
            center = center,
            radius = radius * 2.9f,
        ),
        radius = radius * 2.9f,
        center = center,
    )
    repeat(8) { index ->
        val angle = (index * 45f + spin) * TWO_PI / 360f
        val dx = cos(angle)
        val dy = sin(angle)
        drawLine(
            color = SunCore.copy(alpha = 0.78f),
            start = Offset(center.x + dx * radius * 1.42f, center.y + dy * radius * 1.42f),
            end = Offset(center.x + dx * radius * 1.86f, center.y + dy * radius * 1.86f),
            strokeWidth = radius * 0.17f,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(SunHighlight, SunCore),
            center = Offset(center.x - radius * 0.32f, center.y - radius * 0.36f),
            radius = radius * 1.55f,
        ),
        radius = radius,
        center = center,
    )
}

private fun DrawScope.drawMoon(center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(NightColor.copy(alpha = 0.30f), Color.Transparent),
            center = center,
            radius = radius * 2.8f,
        ),
        radius = radius * 2.8f,
        center = center,
    )
    val disc = Path().apply { addOval(Rect(center = center, radius = radius)) }
    val bite = Path().apply {
        addOval(
            Rect(
                center = Offset(center.x + radius * 0.66f, center.y - radius * 0.34f),
                radius = radius * 0.94f,
            )
        )
    }
    val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
    drawPath(
        path = crescent,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFF7F5FF), NightColor),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius),
        ),
    )
}

private fun DrawScope.drawStars(phase: Float, intensity: Float) {
    val stars = listOf(
        0.22f to 0.24f,
        0.74f to 0.16f,
        0.84f to 0.46f,
        0.34f to 0.62f,
        0.58f to 0.34f,
    )
    stars.forEachIndexed { index, star ->
        val twinkle = 0.35f + 0.55f * abs(sin((phase + index * 0.21f) * TWO_PI))
        drawCircle(
            color = Color.White.copy(alpha = twinkle * intensity),
            radius = size.minDimension * 0.011f,
            center = Offset(size.width * star.first, size.height * star.second),
        )
    }
}

private fun DrawScope.drawCloud(
    center: Offset,
    width: Float,
    alpha: Float = 1f,
) {
    val height = width * 0.46f
    val cx = center.x
    val cy = center.y
    val body = CloudBody.copy(alpha = alpha)
    val shade = CloudShade.copy(alpha = alpha)

    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(body, shade),
            startY = cy - height * 0.42f,
            endY = cy + height * 0.56f,
        ),
        topLeft = Offset(cx - width * 0.5f, cy - height * 0.1f),
        size = Size(width, height * 0.62f),
        cornerRadius = CornerRadius(height * 0.31f, height * 0.31f),
    )
    drawCircle(body, radius = height * 0.36f, center = Offset(cx - width * 0.24f, cy - height * 0.13f))
    drawCircle(body, radius = height * 0.50f, center = Offset(cx + width * 0.02f, cy - height * 0.30f))
    drawCircle(body, radius = height * 0.30f, center = Offset(cx + width * 0.27f, cy - height * 0.09f))
}

private fun DrawScope.drawRain(center: Offset, width: Float, progress: Float) {
    val height = width * 0.46f
    val top = center.y + height * 0.58f
    val bottom = center.y + height * 1.52f
    repeat(3) { index ->
        val phase = (progress + index * 0.33f) % 1f
        val x = center.x + (index - 1) * width * 0.23f
        val y = top + (bottom - top) * phase
        drawLine(
            color = RainColor.copy(alpha = 0.9f * (1f - phase * 0.35f)),
            start = Offset(x, y),
            end = Offset(x - width * 0.035f, y + height * 0.30f),
            strokeWidth = width * 0.030f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawSnow(center: Offset, width: Float, progress: Float) {
    val height = width * 0.46f
    val top = center.y + height * 0.62f
    val bottom = center.y + height * 1.50f
    repeat(3) { index ->
        val phase = (progress + index * 0.33f) % 1f
        val x = center.x + (index - 1) * width * 0.23f + sin(phase * TWO_PI + index) * width * 0.035f
        val y = top + (bottom - top) * phase
        drawCircle(
            color = SnowColor.copy(alpha = 0.88f * (1f - phase * 0.30f)),
            radius = width * 0.036f,
            center = Offset(x, y),
        )
    }
}

private fun DrawScope.drawBolt(origin: Offset, height: Float, color: Color) {
    val width = height * 0.62f
    val points = listOf(
        0.54f to 0f,
        0.06f to 0.50f,
        0.40f to 0.50f,
        0.18f to 1f,
        0.94f to 0.42f,
        0.52f to 0.42f,
    )
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = origin.x + point.first * width
        val y = origin.y + point.second * height
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

// ─────────────────────────────────────────────────────────────────────────────
// 小图（小时 / 7 天 / 简报）
// ─────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawMiniGlyph(kind: WeatherGlyphKind) {
    val u = size.minDimension / 100f
    val cx = size.width / 2f
    val cy = size.height / 2f

    when (kind) {
        WeatherGlyphKind.SUN -> drawSun(Offset(cx, cy), 19f * u, 0f, glow = 0.16f)

        WeatherGlyphKind.MOON -> {
            drawMoon(Offset(cx, cy), 23f * u)
            drawCircle(
                color = Color.White.copy(alpha = 0.7f),
                radius = 2.4f * u,
                center = Offset(size.width * 0.26f, size.height * 0.24f),
            )
        }

        WeatherGlyphKind.SUN_CLOUD -> {
            drawSun(Offset(cx + 11f * u, cy - 13f * u), 12f * u, 0f, glow = 0.12f)
            drawCloud(Offset(cx - 5f * u, cy + 14f * u), 64f * u)
        }

        WeatherGlyphKind.MOON_CLOUD -> {
            drawMoon(Offset(cx + 11f * u, cy - 14f * u), 12f * u)
            drawCloud(Offset(cx - 5f * u, cy + 14f * u), 64f * u)
        }

        WeatherGlyphKind.CLOUD -> drawCloud(Offset(cx, cy), 74f * u)

        WeatherGlyphKind.FOG -> {
            drawCloud(Offset(cx, cy - 10f * u), 66f * u, alpha = 0.5f)
            repeat(2) { index ->
                val y = cy + (12f + index * 12f) * u
                drawLine(
                    color = Color(0xFFD7E5EC).copy(alpha = 0.75f),
                    start = Offset(cx - 24f * u, y),
                    end = Offset(cx + 24f * u, y),
                    strokeWidth = 5f * u,
                    cap = StrokeCap.Round,
                )
            }
        }

        WeatherGlyphKind.RAIN -> {
            drawCloud(Offset(cx, cy - 7f * u), 62f * u)
            drawRain(Offset(cx, cy - 7f * u), 62f * u, 0.45f)
        }

        WeatherGlyphKind.SNOW -> {
            drawCloud(Offset(cx, cy - 7f * u), 62f * u)
            drawSnow(Offset(cx, cy - 7f * u), 62f * u, 0.35f)
        }

        WeatherGlyphKind.THUNDER -> {
            drawCloud(Offset(cx, cy - 10f * u), 60f * u)
            drawBolt(Offset(cx - 10f * u, cy + 10f * u), 34f * u, RilanColors.Gold)
        }
    }
}
