package app.rilan.daily.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 日览 1.3 的视觉基调：黎明前的玻璃天文台。
 *
 * 深靛蓝是夜色，月光蓝是主色，杏桃色是地平线，淡紫只做远处的空气。
 * 旧的 Mint / Gold / Blue 等名字保留为别名，避免其他页面一次性大改，
 * 但新代码请直接用 Moon / Dawn / Lilac。
 */
object RilanColors {
    val Ink = Color(0xFF070912)
    val Deep = Color(0xFF0B0F1C)
    val Surface = Color(0xFF121729)
    val SurfaceRaised = Color(0xFF1B2137)

    val Glass = Color(0x18FFFFFF)
    val GlassStrong = Color(0x24FFFFFF)
    val GlassWeak = Color(0x0CFFFFFF)
    val Stroke = Color(0x38FFFFFF)
    val StrokeSoft = Color(0x1CFFFFFF)
    val Hairline = Color(0x24FFFFFF)

    val Text = Color(0xFFF6EFE3)
    val TextSecondary = Color(0xFFC6C9D8)
    val TextTertiary = Color(0xFF9AA0B4)

    /** 月光蓝：主操作、当前页、可点击。 */
    val Moon = Color(0xFFB5CEFF)
    val MoonDeep = Color(0xFF6E8DDA)
    val MoonInk = Color(0xFF10162A)

    /** 地平线杏桃：温度、提醒、重要但不危险。 */
    val Dawn = Color(0xFFF3B28B)
    val DawnDeep = Color(0xFFC67A57)

    /** 淡紫：远处空气、生日与纪念日。 */
    val Lilac = Color(0xFFC8B7EA)
    val Sky = Color(0xFFA6C8E8)
    val Rose = Color(0xFFEF95A1)
    val Jade = Color(0xFF92D8C2)

    val Danger = Color(0xFFF28B8B)
    val Success = Jade
    val Paper = Text

    // 兼容旧调用；颜色已经全部换掉，不再使用青玉 / 旧金。
    val Mint = Moon
    val MintDeep = MoonDeep
    val Gold = Dawn
    val Blue = Sky
    val Lavender = Lilac
    val Coral = Rose
}

private val colors = darkColorScheme(
    primary = RilanColors.Moon,
    onPrimary = RilanColors.MoonInk,
    primaryContainer = Color(0xFF283A65),
    onPrimaryContainer = Color(0xFFE1EBFF),
    secondary = RilanColors.Dawn,
    onSecondary = Color(0xFF33170C),
    tertiary = RilanColors.Lilac,
    background = RilanColors.Ink,
    onBackground = RilanColors.Text,
    surface = RilanColors.Surface,
    onSurface = RilanColors.Text,
    surfaceVariant = Color(0xFF1A2034),
    onSurfaceVariant = RilanColors.TextSecondary,
    outline = RilanColors.Stroke,
    outlineVariant = RilanColors.StrokeSoft,
    error = RilanColors.Danger,
    onError = Color(0xFF3A0C10),
    errorContainer = Color(0xFF4A171E),
    onErrorContainer = Color(0xFFFFD9DD),
)

private val serif = FontFamily.Serif
private val sans = FontFamily.SansSerif

private val typography = Typography(
    displayLarge = TextStyle(
        fontFamily = serif,
        fontWeight = FontWeight.Light,
        fontSize = 66.sp,
        lineHeight = 68.sp,
        letterSpacing = (-2.4).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = serif,
        fontWeight = FontWeight.Light,
        fontSize = 52.sp,
        lineHeight = 54.sp,
        letterSpacing = (-1.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = serif,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = serif,
        fontWeight = FontWeight.Normal,
        fontSize = 27.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = serif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 29.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.45.sp,
    ),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(13.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun RilanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
