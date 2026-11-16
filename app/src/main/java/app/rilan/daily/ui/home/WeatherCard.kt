package app.rilan.daily.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rilan.daily.core.DailyWeather
import app.rilan.daily.core.HourlyWeather
import app.rilan.daily.core.WallpaperInfo
import app.rilan.daily.core.WeatherData
import app.rilan.daily.core.windDirectionLabel
import app.rilan.daily.ui.components.ErrorLine
import app.rilan.daily.ui.components.GlassCard
import app.rilan.daily.ui.components.GlassDivider
import app.rilan.daily.ui.components.GlassPill
import app.rilan.daily.ui.components.LoadingDots
import app.rilan.daily.ui.components.LoadingLines
import app.rilan.daily.ui.components.TextAction
import app.rilan.daily.ui.theme.RilanColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val DAY_LABEL = DateTimeFormatter.ofPattern("M/d")
private val WEEK_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

// ─────────────────────────────────────────────────────────────────────────────
// 天气主卡：一张透着天色的玻璃窗
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun WeatherHero(
    weather: WeatherData?,
    wallpaper: WallpaperInfo?,
    loading: Boolean,
    refreshing: Boolean,
    error: String?,
    locating: Boolean,
    locationError: String?,
    onRefresh: () -> Unit,
    onRelocate: () -> Unit,
    sheen: Float = 0.4f,
    scrolling: Boolean = false,
    bottomInset: Dp = 96.dp,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(32.dp),
        strong = true,
        refraction = true,
        scrolling = scrolling,
        sheen = sheen,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            WeatherAtmosphere(weather)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 38.dp, bottom = bottomInset),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                WeatherTop(
                    weather = weather,
                    loading = loading,
                    refreshing = refreshing,
                    locating = locating,
                    locationError = locationError,
                    scrolling = scrolling,
                    onRelocate = onRelocate,
                )
                if (error != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ErrorLine(error, Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        TextAction("重试", onRefresh, color = RilanColors.Dawn)
                    }
                }
                when {
                    weather == null && loading -> WeatherSkeleton()
                    weather == null -> WeatherMissing(onRefresh = onRefresh)
                    else -> WeatherBody(weather)
                }
                if (wallpaper != null && wallpaper.title.isNotBlank()) {
                    GlassDivider()
                    WallpaperCredit(wallpaper)
                }
            }
        }
    }
}

@Composable
private fun WeatherAtmosphere(weather: WeatherData?) {
    val accent = weather?.let { weatherTint(it) } ?: RilanColors.Moon
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.82f, size.height * 0.10f),
                radius = size.width * 0.78f,
            ),
            radius = size.width * 0.78f,
            center = Offset(size.width * 0.82f, size.height * 0.10f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(RilanColors.Dawn.copy(alpha = 0.07f), Color.Transparent),
                center = Offset(size.width * 0.02f, size.height * 0.92f),
                radius = size.width * 0.66f,
            ),
            radius = size.width * 0.66f,
            center = Offset(size.width * 0.02f, size.height * 0.92f),
        )
        // 一条很淡的地平线，把天气卡和其他卡区分开
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.18f), Color.Transparent)
            ),
            start = Offset(size.width * 0.12f, size.height * 0.49f),
            end = Offset(size.width * 0.88f, size.height * 0.49f),
            strokeWidth = 0.8.dp.toPx(),
        )
    }
}

@Composable
private fun WeatherTop(
    weather: WeatherData?,
    loading: Boolean,
    refreshing: Boolean,
    locating: Boolean,
    locationError: String?,
    scrolling: Boolean,
    onRelocate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "01",
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
                    "天气",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.Moon.copy(alpha = 0.84f),
                )
                Spacer(Modifier.weight(1f))
                if (refreshing) LoadingDots(RilanColors.Moon)
            }

            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 只有正在定位时才跑无限脉冲；平时用固定透明度，省掉每帧一次重组。
                val pinAlpha = if (locating) {
                    val pinPulse = rememberInfiniteTransition(label = "pin")
                    val value by pinPulse.animateFloat(
                        0.45f, 1f,
                        infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
                        label = "pinAlpha",
                    )
                    value
                } else {
                    0.9f
                }
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(RilanColors.Moon.copy(alpha = pinAlpha))
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    weather?.city ?: if (locating) "正在定位" else "天气",
                    style = MaterialTheme.typography.headlineSmall,
                    color = RilanColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    locating -> "正在获取你所在的位置…"
                    weather == null && loading -> "正在读取天气"
                    weather == null -> "还没有天气数据"
                    else -> listOf(
                        weather.localityLabel,
                        updatedText(weather.updatedAt).ifBlank { "刚刚更新" },
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                },
                style = MaterialTheme.typography.labelSmall,
                color = RilanColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (locationError != null) {
                Spacer(Modifier.height(4.dp))
                TextAction(
                    if ("权限" in locationError) "开启定位权限" else "定位失败 · 重试",
                    onRelocate,
                    color = RilanColors.Dawn,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (weather != null) {
            HeroWeatherGlyph(
                code = weather.weatherCode,
                isDay = weather.isDay,
                scrolling = scrolling,
                modifier = Modifier.size(92.dp),
            )
        } else {
            Box(
                Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.045f))
                    .border(0.75.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            )
        }
    }
}

/**
 * 天气大图标：静止时用带光线旋转的动态版，横向滑动中换成同样笔触的静态版。
 * 无限动画会让整张卡片的绘制层每帧重录，翻页时这是最贵的一块。
 */
@Composable
private fun HeroWeatherGlyph(
    code: Int,
    isDay: Boolean,
    scrolling: Boolean,
    modifier: Modifier = Modifier,
) {
    if (scrolling) {
        WeatherMiniGlyph(code = code, isDay = isDay, modifier = modifier)
    } else {
        WeatherGlyph(code = code, isDay = isDay, modifier = modifier)
    }
}

@Composable
private fun WeatherBody(weather: WeatherData) {
    val accent = weatherTint(weather)
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    weather.currentTemp.roundToInt().toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = RilanColors.Text,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    "°",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    color = accent.copy(alpha = 0.9f),
                    modifier = Modifier
                        .alignByBaseline()
                        .padding(start = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 7.dp),
            ) {
                Text(
                    weather.condition,
                    style = MaterialTheme.typography.titleMedium,
                    color = RilanColors.Text,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "最高 ${weather.high.roundToInt()}°  最低 ${weather.low.roundToInt()}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextSecondary,
                    maxLines = 1,
                )
                Text(
                    "体感 ${weather.feelsLike.roundToInt()}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = RilanColors.TextTertiary,
                    maxLines = 1,
                )
            }
        }

        val highDelta = weather.highDelta
        val lowDelta = weather.lowDelta
        if (highDelta != null || lowDelta != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (highDelta != null) DeltaPill("最高", highDelta)
                if (lowDelta != null) DeltaPill("最低", lowDelta)
            }
        }

        if (weather.hourly.isNotEmpty()) {
            GlassDivider()
            HourlyStrip(weather, accent)
        }

        if (weather.daily.isNotEmpty()) {
            GlassDivider()
            DailyStrip(weather.daily)
        }

        GlassDivider()
        MetricGrid(weather)
    }
}

@Composable
private fun WallpaperCredit(wallpaper: WallpaperInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "今日壁纸",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            wallpaper.title.takeIf { title -> title.any { it in '\u4e00'..'\u9fff' } }
                ?: "必应每日壁纸",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 小时 / 一周 / 指标
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HourlyStrip(weather: WeatherData, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "接下来 6 小时",
                style = MaterialTheme.typography.labelMedium,
                color = RilanColors.TextSecondary,
                letterSpacing = 0.4.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "降水 ${weather.precipProb}%",
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.9f),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            weather.hourly.take(6).forEach { hour ->
                HourCell(
                    hour = hour,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HourCell(
    hour: HourlyWeather,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            hour.time,
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
            maxLines = 1,
        )
        // 小时条目的图标用静态绘制：7 天 × 12 小时十多个无限动画是滑动卡顿的主因之一。
        WeatherMiniGlyph(
            code = hour.code,
            isDay = true,
            modifier = Modifier.size(24.dp),
        )
        Text(
            "${hour.temp.roundToInt()}°",
            style = MaterialTheme.typography.titleSmall,
            color = RilanColors.Text,
            maxLines = 1,
        )
        if (hour.precipProb >= 20) {
            Text(
                "${hour.precipProb}%",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = accent.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun DailyStrip(days: List<DailyWeather>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        days.take(7).forEach { day ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    dayLabel(day.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                    maxLines = 1,
                )
                WeatherMiniGlyph(
                    code = day.code,
                    isDay = true,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    "${day.high.roundToInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = RilanColors.Text,
                    maxLines = 1,
                )
                Text(
                    "${day.low.roundToInt()}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = RilanColors.TextTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MetricGrid(weather: WeatherData) {
    val metrics = mutableListOf<Pair<String, String>>()
    metrics += "湿度" to "${weather.humidity}%"
    metrics += "风" to "${windDirectionLabel(weather.windDirection)} ${"%.0f".format(weather.windSpeed)}"
    val air = weather.air
    if (air != null && air.aqi > 0) {
        metrics += "空气" to aqiLabel(air.aqi)
    }
    if (weather.uvIndex > 0.0) {
        metrics += "紫外线" to uvLabel(weather.uvIndex)
    }
    if (metrics.size < 4) {
        metrics += "日照" to daylightLabel(weather.sunrise, weather.sunset).ifBlank { "—" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.045f))
                            .border(0.65.dp, Color.White.copy(alpha = 0.075f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = RilanColors.TextTertiary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            value,
                            style = MaterialTheme.typography.titleSmall,
                            color = RilanColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DeltaPill(label: String, delta: Double) {
    val rounded = delta.roundToInt()
    val color = when {
        rounded > 0 -> RilanColors.Dawn
        rounded < 0 -> RilanColors.Moon
        else -> RilanColors.TextSecondary
    }
    val text = if (rounded == 0) {
        "$label 持平"
    } else {
        "$label ${if (rounded > 0) "+" else "-"}${abs(rounded)}°"
    }
    GlassPill(text = text, color = color, background = color.copy(alpha = 0.10f))
}

@Composable
private fun WeatherSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingLines(lines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.04f + index * 0.012f))
                        .border(0.6.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                )
            }
        }
        Text(
            "正在读取天气…",
            style = MaterialTheme.typography.labelSmall,
            color = RilanColors.TextTertiary,
        )
    }
}

@Composable
private fun WeatherMissing(onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "还没有天气数据。检查网络，或点下面的按钮重新获取。",
            style = MaterialTheme.typography.bodySmall,
            color = RilanColors.TextTertiary,
        )
        TextAction("重新获取天气", onRefresh)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 色彩与格式
// ─────────────────────────────────────────────────────────────────────────────

private fun weatherTint(weather: WeatherData): Color {
    return when (weatherGlyphKind(weather.weatherCode, weather.isDay)) {
        WeatherGlyphKind.SUN -> RilanColors.Dawn
        WeatherGlyphKind.SUN_CLOUD -> Color(0xFFEBC49B)
        WeatherGlyphKind.CLOUD -> Color(0xFFB7C2D9)
        WeatherGlyphKind.FOG -> Color(0xFFAEB7CA)
        WeatherGlyphKind.RAIN -> Color(0xFF9AC1F2)
        WeatherGlyphKind.SNOW -> Color(0xFFD4E7F5)
        WeatherGlyphKind.THUNDER -> Color(0xFFB7A7E8)
        WeatherGlyphKind.MOON -> RilanColors.Moon
        WeatherGlyphKind.MOON_CLOUD -> Color(0xFF9AA8D8)
    }
}

private fun dayLabel(raw: String): String {
    val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return raw.takeLast(5)
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> WEEK_LABELS[date.dayOfWeek.value - 1].removePrefix("周")
    }
}

private fun uvLabel(uv: Double): String {
    val value = uv.roundToInt()
    return when {
        uv < 3.0 -> "低 $value"
        uv < 6.0 -> "中 $value"
        uv < 8.0 -> "偏高 $value"
        uv < 11.0 -> "很高 $value"
        else -> "极高 $value"
    }
}

private fun aqiLabel(aqi: Int): String = when {
    aqi <= 50 -> "优 $aqi"
    aqi <= 100 -> "良 $aqi"
    aqi <= 150 -> "轻度 $aqi"
    else -> "较差 $aqi"
}

private fun daylightLabel(sunrise: String, sunset: String): String = runCatching {
    val start = LocalTime.parse(sunrise)
    val end = LocalTime.parse(sunset)
    val minutes = java.time.Duration.between(start, end).toMinutes()
    if (minutes <= 0L) "" else "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}m"
}.getOrDefault("")
