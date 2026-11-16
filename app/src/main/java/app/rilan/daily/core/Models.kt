package app.rilan.daily.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** DeepSeek 官方模型 ID；设置页与请求层共用，避免两边写散。 */
val DEEPSEEK_MODEL_IDS: List<String> = listOf("deepseek-flash", "deepseek-v4-pro")

// ─────────────────────────────────────────────────────────────────────────────
// 内容分类
// ─────────────────────────────────────────────────────────────────────────────

enum class Category(
    val label: String,
    val subtitle: String,
    val accent: Long,
) {
    GENERAL("综合", "正在发生的事", 0xFFAECBFF),
    AI("AI 科技", "模型、产品与未来", 0xFFF3B28B),
    ZHIHU("知乎热榜", "认真讨论的问题", 0xFFC8B7EA),
    WEIBO("微博热搜", "此刻大家在讨论", 0xFFEF95A1),
    BILIBILI("B站热门", "年轻人的兴趣现场", 0xFFE5A6D4),
    DOUYIN("抖音热搜", "短视频里的风向", 0xFF9DCDEB),
    BAIDU("百度热搜", "全网正在搜索", 0xFFA6C8E8),
    SECURITY("网络安全", "漏洞、攻击与防御", 0xFFB5A8E8),
    WORLD("世界局势", "战争、外交与远方", 0xFF9FB4FF),
    DAILY("今日简报", "AI 把一天收拢", 0xFFD8C6F0);

    companion object {
        fun parse(value: String?): Category =
            entries.firstOrNull { it.name == value } ?: GENERAL
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 新闻与内容
// ─────────────────────────────────────────────────────────────────────────────

data class FeedSource(
    val id: String,
    val name: String,
    val url: String,
    val category: Category,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
) {
    companion object {
        val DEFAULTS: List<FeedSource> = listOf(
            FeedSource("ithome", "IT之家", "https://www.ithome.com/rss/", Category.GENERAL, builtIn = true),
            FeedSource("sspai", "少数派", "https://sspai.com/feed", Category.GENERAL, builtIn = true),
            FeedSource("solidot", "Solidot", "https://www.solidot.org/index.rss", Category.GENERAL, builtIn = true),
            FeedSource("ifanr", "爱范儿", "https://www.ifanr.com/feed", Category.GENERAL, builtIn = true),
            FeedSource("chinanews", "中国新闻网", "https://www.chinanews.com.cn/rss/scroll-news.xml", Category.GENERAL, builtIn = true),
            FeedSource("people", "人民网", "https://www.people.com.cn/rss/politics.xml", Category.GENERAL, builtIn = true),
            FeedSource("v2ex", "V2EX", "https://www.v2ex.com/index.xml", Category.GENERAL, builtIn = true),
            FeedSource("juejin", "掘金", "https://juejin.cn/rss", Category.GENERAL, builtIn = true),
            FeedSource("juya", "橘鸦 AI 早报", "https://daily.juya.uk/rss.xml", Category.AI, builtIn = true),
            FeedSource("qbitai", "量子位", "https://www.qbitai.com/feed", Category.AI, builtIn = true),
            FeedSource("gcores", "机核", "https://www.gcores.com/rss", Category.GENERAL, builtIn = true),
            FeedSource("4hou", "四叶草安全", "https://www.4hou.com/feed", Category.SECURITY, builtIn = true),
            FeedSource("seebug", "Seebug Paper", "https://paper.seebug.org/rss/", Category.SECURITY, builtIn = true),
            FeedSource("chinanews-world", "中国新闻网 · 国际", "https://www.chinanews.com.cn/rss/world.xml", Category.WORLD, builtIn = true),
            FeedSource("people-world", "人民网 · 国际", "https://www.people.com.cn/rss/world.xml", Category.WORLD, builtIn = true),
        )
    }
}

data class NewsItem(
    val id: String,
    val title: String,
    val url: String,
    val source: String,
    val category: Category,
    val publishedAt: Long = 0L,
    val snippet: String = "",
    val hotValue: String = "",
    val rank: Int = 0,
) {
    val timeLabel: String
        get() = relativeTimeLabel(publishedAt)
}

data class NewsSection(
    val category: Category,
    val items: List<NewsItem> = emptyList(),
    val updatedAt: Long = 0L,
    val error: String? = null,
)

data class NewsRecord(
    val id: String,
    val title: String,
    val snippet: String,
    val source: String,
    val category: Category,
    val url: String,
    val publishedAt: Long,
    val firstSeenAt: Long,
)

data class AiText(
    val content: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val generatedAt: Long = 0L,
) {
    val hasContent: Boolean get() = content.isNotBlank()
}

/** 设置页「测试 DeepSeek 连接」的一次性结果。 */
data class AiTestState(
    val loading: Boolean = false,
    val ok: Boolean = false,
    val message: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// 天气
// ─────────────────────────────────────────────────────────────────────────────

data class HourlyWeather(
    val time: String,
    val temp: Double,
    val code: Int,
    val precipProb: Int,
)

data class DailyWeather(
    val date: String,
    val code: Int,
    val high: Double,
    val low: Double,
    val precipProb: Int,
    val uvIndex: Double = 0.0,
    val sunrise: String = "",
    val sunset: String = "",
)

data class AirQuality(
    val pm25: Double = 0.0,
    val aqi: Int = 0,
)

data class WeatherData(
    val city: String,
    /** 比市级更细的定位文案，例如「杭州 · 西湖区 · 北山街道」。 */
    val place: String = "",
    val updatedAt: Long,
    val currentTemp: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val windDirection: Int,
    val weatherCode: Int,
    val isDay: Boolean,
    val high: Double,
    val low: Double,
    val yesterdayHigh: Double? = null,
    val yesterdayLow: Double? = null,
    val precipProb: Int = 0,
    val uvIndex: Double = 0.0,
    val sunrise: String = "",
    val sunset: String = "",
    val hourly: List<HourlyWeather> = emptyList(),
    val daily: List<DailyWeather> = emptyList(),
    val air: AirQuality? = null,
) {
    val condition: String get() = weatherLabel(weatherCode)
    val highDelta: Double? get() = yesterdayHigh?.let { high - it }
    val lowDelta: Double? get() = yesterdayLow?.let { low - it }

    /** 卡片标题用的精确地点；旧缓存没有 place 时退回城市。 */
    val locationLabel: String get() = place.ifBlank { city }

    /** 去掉城市后的区县 / 街道，用来在天气卡里展示定位精度。 */
    val localityLabel: String
        get() = place.split(" · ")
            .drop(1)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

data class CityResult(
    val name: String,
    val admin: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
) {
    val display: String
        get() = listOfNotNull(name, admin.takeIf { it.isNotBlank() && it != name }, country)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

// ─────────────────────────────────────────────────────────────────────────────
// 壁纸
// ─────────────────────────────────────────────────────────────────────────────

data class WallpaperInfo(
    val path: String,
    val title: String,
    val copyright: String,
    val date: String,
    val pageUrl: String = "",
)

// ─────────────────────────────────────────────────────────────────────────────
// 日程
// ─────────────────────────────────────────────────────────────────────────────

enum class Priority(val label: String, val short: String, val reminderDays: Int) {
    LOW("低", "轻提醒", 0),
    MEDIUM("中", "提前 3 天", 3),
    HIGH("高", "提前 7 天", 7);

    companion object {
        fun parse(value: String?): Priority =
            entries.firstOrNull { it.name.equals(value, true) } ?: MEDIUM
    }
}

enum class EventKind(val label: String) {
    TASK("日程"),
    BIRTHDAY("生日"),
    ANNIVERSARY("纪念日"),
    MEMO("备忘");

    companion object {
        fun parse(value: String?): EventKind =
            entries.firstOrNull { it.name.equals(value, true) } ?: TASK
    }
}

enum class RepeatRule(val label: String) {
    NONE("不重复"),
    DAILY("每天"),
    WEEKLY("每周"),
    MONTHLY("每月"),
    YEARLY("每年");

    companion object {
        fun parse(value: String?): RepeatRule =
            entries.firstOrNull { it.name.equals(value, true) } ?: NONE
    }
}

data class ScheduleEvent(
    val id: String,
    val title: String,
    val notes: String = "",
    val startDate: String,
    val time: String? = null,
    val priority: Priority = Priority.MEDIUM,
    val kind: EventKind = EventKind.TASK,
    val repeat: RepeatRule = RepeatRule.NONE,
    val doneDates: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun isDoneOn(date: String): Boolean = date in doneDates

    val dateLabel: String
        get() = runCatching {
            LocalDate.parse(startDate).format(DateTimeFormatter.ofPattern("M月d日"))
        }.getOrDefault(startDate)
}

// ─────────────────────────────────────────────────────────────────────────────
// AI 总结与对话
// ─────────────────────────────────────────────────────────────────────────────

enum class SummaryPeriod(val label: String, val days: Int) {
    DAY("日", 1),
    WEEK("周", 7),
    MONTH("月", 30),
    QUARTER("季", 90),
    HALF_YEAR("半年", 182),
    YEAR("年", 365);

    fun startDate(today: LocalDate = LocalDate.now()): LocalDate =
        today.minusDays((days - 1).toLong())

    fun rangeText(today: LocalDate = LocalDate.now()): String {
        val f = DateTimeFormatter.ofPattern("M月d日")
        return "${startDate(today).format(f)} - ${today.format(f)}"
    }

    fun key(today: LocalDate = LocalDate.now()): String = "${name}-$today"
}

/**
 * 周期总结的生成方式：外部 AI 通过 MCP 写回，或应用内用 DeepSeek API Key 直连生成。
 * 默认 MCP，保持旧安装升级后的行为不变。
 */
enum class SummaryMode(val label: String) {
    MCP("MCP 写回"),
    DEEPSEEK("DeepSeek 直连");

    companion object {
        fun parse(value: String?): SummaryMode =
            entries.firstOrNull { it.name.equals(value, true) } ?: MCP
    }
}

data class AiDigest(
    val period: SummaryPeriod,
    val key: String,
    val rangeText: String,
    val content: String,
    val itemCount: Int,
    val createdAt: Long,
    val error: String? = null,
    /** 来源：mcp = 外部 AI 写回；deepseek = 应用内直连生成。旧数据默认 mcp。 */
    val source: String = SOURCE_MCP,
) {
    val sourceLabel: String
        get() = if (source == SOURCE_DEEPSEEK) "DeepSeek 直连" else "外部 AI 写回"

    companion object {
        const val SOURCE_MCP = "mcp"
        const val SOURCE_DEEPSEEK = "deepseek"
    }
}

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val actionLog: List<String> = emptyList(),
    val failed: Boolean = false,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// 应用状态
// ─────────────────────────────────────────────────────────────────────────────

data class AppSettings(
    val city: String = "北京",
    /** 自动定位得到的区县 / 街道文案，只用于天气展示，不参与城市搜索。 */
    val place: String = "",
    val latitude: Double = 39.9042,
    val longitude: Double = 116.4074,
    val timezone: String = "Asia/Shanghai",
    val reminderMinuteOfDay: Int = 8 * 60 + 30,
    /** 每日简报通知：默认关闭（opt-in），用户打开后按 [dailyNoticeMinuteOfDay] 每天发一条。 */
    val dailyNoticeEnabled: Boolean = false,
    /** 每日简报时间，当天第几分钟（默认 08:00 = 480）。 */
    val dailyNoticeMinuteOfDay: Int = 8 * 60,
    /** 开机自启动：开机 / 应用更新后恢复后台服务与每日通知，不直接打开界面。 */
    val autoStartEnabled: Boolean = false,
    val feeds: List<FeedSource> = FeedSource.DEFAULTS,
    val blockedWords: List<String> = emptyList(),
    val lastWeatherAt: Long = 0L,
    val lastContentAt: Long = 0L,
    val onboardingDone: Boolean = false,
    val autoLocation: Boolean = true,
    val locationAsked: Boolean = false,
    val lastLocationAt: Long = 0L,
    val weiboHotEnabled: Boolean = true,
    val bilibiliHotEnabled: Boolean = true,
    val douyinHotEnabled: Boolean = true,
    val mcpEnabled: Boolean = false,
    val mcpPort: Int = 8765,
    val mcpToken: String = "",
    /** DeepSeek API Key，只保存在应用私有 SharedPreferences，不写日志、不上云。 */
    val deepseekApiKey: String = "",
    val deepseekModel: String = "deepseek-flash",
    /** 周期总结的生成方式，见 [SummaryMode]；默认 MCP 写回。 */
    val summaryMode: SummaryMode = SummaryMode.MCP,
) {
    val reminderLabel: String
        get() = "%02d:%02d".format(reminderMinuteOfDay / 60, reminderMinuteOfDay % 60)

    val dailyNoticeLabel: String
        get() = "%02d:%02d".format(dailyNoticeMinuteOfDay / 60, dailyNoticeMinuteOfDay % 60)

    val deepseekConfigured: Boolean get() = deepseekApiKey.isNotBlank()
}

data class HomeState(
    val wallpaper: WallpaperInfo? = null,
    val weather: WeatherData? = null,
    val sections: List<NewsSection> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val lastUpdated: Long = 0L,
    val weatherError: String? = null,
    val contentError: String? = null,
    val weatherInsight: AiText = AiText(),
    val dailyBrief: AiText = AiText(),
    val locating: Boolean = false,
    val locationError: String? = null,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val cityResults: List<CityResult> = emptyList(),
    val citySearching: Boolean = false,
)

data class ReviewState(
    val digests: Map<SummaryPeriod, AiDigest> = emptyMap(),
    val loading: SummaryPeriod? = null,
    val error: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// 时间/格式化工具
// ─────────────────────────────────────────────────────────────────────────────

fun relativeTimeLabel(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return ""
    val diff = (now - epochMillis).coerceAtLeast(0L) / 1000L
    return when {
        diff < 60L -> "刚刚"
        diff < 3600L -> "${diff / 60} 分钟前"
        diff < 86_400L -> "${diff / 3600} 小时前"
        diff < 7 * 86_400L -> "${diff / 86_400} 天前"
        else -> runCatching {
            java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("M月d日"))
        }.getOrDefault("")
    }
}

fun weatherLabel(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "大致晴朗"
    2 -> "多云"
    3 -> "阴"
    45, 48 -> "有雾"
    51, 53, 55 -> "毛毛雨"
    56, 57 -> "冻毛毛雨"
    61, 63, 65 -> "雨"
    66, 67 -> "冻雨"
    71, 73, 75, 77 -> "雪"
    80, 81, 82 -> "阵雨"
    85, 86 -> "阵雪"
    95 -> "雷暴"
    96, 99 -> "雷暴伴冰雹"
    else -> "未知天气"
}

fun windDirectionLabel(degrees: Int): String {
    val names = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    val idx = (((degrees % 360) + 360) % 360 + 22) / 45 % 8
    return "${names[idx]}风"
}

fun formatHotValue(raw: String): String {
    val text = raw.trim().replace(",", "")
    val n = text.toDoubleOrNull()
    if (n != null) {
        return when {
            n >= 100_000_000 -> "%.1f 亿".format(n / 100_000_000)
            n >= 10_000 -> "%.1f 万".format(n / 10_000)
            n >= 1_000 -> "%.1f k".format(n / 1_000)
            else -> n.toInt().toString()
        }
    }
    // 知乎等接口会直接给「964 万热度」，只留数字和单位
    val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(亿|万|千|k|K)").find(text)
    if (match != null) {
        val unit = match.groupValues[2].lowercase()
        return "${match.groupValues[1]} ${if (unit == "k") "k" else unit}"
    }
    return text.removeSuffix("热度").trim()
}

const val STRICT_ENTERTAINMENT_WORDS =
    "娱乐圈|明星|艺人|偶像|爱豆|综艺|真人秀|选秀|演唱会|巡演|剧组|片场|路透|生图|红毯|时装周|塌房|恋情|绯闻|出轨|官宣恋情|官宣结婚|新剧|热播|定档|女团|男团|出道|番位|娱记|狗仔|顶流|影帝|影后|视帝|视后|票房|首映|杂志封面"

const val SOFT_ENTERTAINMENT_WORDS =
    "演员|歌手|恋爱|分手|结婚|离婚|怀孕|产子|男友|女友|造型|颜值|粉丝|电影|电视剧|综艺感|CP|cp|网红|主播|直播|舞台"
