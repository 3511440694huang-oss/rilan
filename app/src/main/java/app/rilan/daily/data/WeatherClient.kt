package app.rilan.daily.data

import app.rilan.daily.core.AirQuality
import app.rilan.daily.core.AppSettings
import app.rilan.daily.core.CityResult
import app.rilan.daily.core.DailyWeather
import app.rilan.daily.core.HourlyWeather
import app.rilan.daily.core.WeatherData
import app.rilan.daily.core.WeatherNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import java.util.concurrent.CancellationException

/**
 * Open-Meteo 天气源（免 Key，不需要配额）。
 *
 * 设计约定：
 * - 只拉取与解析，不做缓存（缓存归仓库管）。
 * - 失败抛 [HttpException] 或带中文信息的 [IOException]，由仓库捕获后展示。
 * - 「今天 / 昨天」以接口返回的 timezone 为准（用户设备时区可能与城市不同）。
 */
class WeatherClient {

    /**
     * 空气质量是可选模块：放进独立作用域并行拉取。
     *
     * 之前 air 和天气在同一个 coroutineScope 里，`await` 即使超时也会被结构化并发
     * 拖到子任务结束，空气慢 5–7 秒就等于天气慢 5–7 秒。现在只等 [AIR_WAIT_MS]，
     * 等不到就丢掉这一小块数据，天气照常成功返回。
     */
    private val optionalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 空气质量是小时级数据，30 分钟内复用，避免每轮全量天气都多打一次接口。 */
    @Volatile private var cachedAir: AirQuality? = null
    @Volatile private var cachedAirAt = 0L
    @Volatile private var cachedAirLat = Double.NaN
    @Volatile private var cachedAirLon = Double.NaN

    suspend fun fetch(settings: AppSettings): WeatherData = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val reusableAir = cachedAir?.takeIf {
            now - cachedAirAt < AIR_CACHE_TTL_MS &&
                abs(cachedAirLat - settings.latitude) < 1e-4 &&
                abs(cachedAirLon - settings.longitude) < 1e-4
        }
        val airJob = if (reusableAir == null) {
            optionalScope.async { runCatching { fetchAir(settings) }.getOrNull() }
        } else {
            null
        }
        try {
            val data = fetchForecast(settings)
            val air = reusableAir ?: withTimeoutOrNull(AIR_WAIT_MS) { airJob?.await() }
            if (airJob != null) {
                if (air == null) {
                    airJob.cancel()
                } else {
                    cachedAir = air
                    cachedAirAt = System.currentTimeMillis()
                    cachedAirLat = settings.latitude
                    cachedAirLon = settings.longitude
                }
            }
            if (air != null) data.copy(air = air) else data
        } catch (t: Throwable) {
            airJob?.cancel()
            throw t
        }
    }

    /**
     * 轻量实时轮询：只取 `current` 字段，响应只有几百字节。
     *
     * 全量预报（逐小时 / 7 天 / 空气质量）仍然走 [fetch]，由仓库每 10 分钟拉一次；
     * 这个接口只负责让卡片上的温度、体感、风、天气现象尽快跟上最新值。
     */
    suspend fun fetchCurrent(settings: AppSettings): WeatherNow? = withContext(Dispatchers.IO) {
        val root = json(
            currentUrl(settings),
            "获取实时天气失败",
            connectTimeoutMs = FAST_CONNECT_TIMEOUT_MS,
            readTimeoutMs = FAST_READ_TIMEOUT_MS,
        )
        val current = root.optJSONObject("current") ?: return@withContext null
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return@withContext null
        WeatherNow(
            temp = temp,
            feelsLike = current.optDouble("apparent_temperature", temp),
            humidity = current.optInt("relative_humidity_2m", 0),
            windSpeed = current.optDouble("wind_speed_10m", 0.0),
            windDirection = current.optInt("wind_direction_10m", 0),
            weatherCode = current.optInt("weather_code", UNKNOWN_CODE),
            isDay = current.optInt("is_day", 1) != 0,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun fetchForecast(settings: AppSettings): WeatherData {
        val root = json(
            forecastUrl(settings),
            "获取天气失败",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        val current = root.optJSONObject("current")
        if (current == null || !current.has("temperature_2m")) {
            throw IOException("天气数据不完整，请稍后重试")
        }

        val zone = resolveZone(root, settings)
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate()
        val todayKey = today.toString()
        val yesterdayKey = today.minusDays(1).toString()

        // daily 带 past_days=1：昨天 + 今天 + 未来 6 天，共 8 条
        val daily = parseDaily(root.optJSONObject("daily"))
        val todayDaily = daily.firstOrNull { it.date == todayKey }
        val yesterdayDaily = daily.firstOrNull { it.date == yesterdayKey }
        val hourly = parseHourly(root.optJSONObject("hourly"), now.toLocalDateTime())

        val temp = current.optDouble("temperature_2m", 0.0)
        return WeatherData(
            city = settings.city,
            place = settings.place,
            updatedAt = System.currentTimeMillis(),
            currentTemp = temp,
            feelsLike = current.optDouble("apparent_temperature", temp),
            humidity = current.optInt("relative_humidity_2m", 0),
            windSpeed = current.optDouble("wind_speed_10m", 0.0),
            windDirection = current.optInt("wind_direction_10m", 0),
            weatherCode = current.optInt("weather_code", -1),
            isDay = current.optInt("is_day", 1) != 0,
            // 今日高低温取 daily 里今天的条目（past_days 让昨天也可用，日界不会被跨时区算错）
            high = todayDaily?.high ?: temp,
            low = todayDaily?.low ?: temp,
            yesterdayHigh = yesterdayDaily?.high,
            yesterdayLow = yesterdayDaily?.low,
            precipProb = todayDaily?.precipProb ?: 0,
            uvIndex = todayDaily?.uvIndex ?: 0.0,
            sunrise = todayDaily?.sunrise.orEmpty(),
            sunset = todayDaily?.sunset.orEmpty(),
            hourly = hourly,
            // 对外只暴露今天起的 7 天；昨天只用来算昨天对比
            daily = daily.filter { it.date >= todayKey }.take(FORECAST_DAYS),
            air = null,
        )
    }

    suspend fun searchCity(query: String): List<CityResult> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext emptyList()

        val body = text(
            "https://geocoding-api.open-meteo.com/v1/search?name=${urlEncode(keyword)}" +
                "&count=6&language=zh&format=json",
            "搜索城市失败",
            connectTimeoutMs = 6_000,
            readTimeoutMs = 10_000,
        )
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("城市搜索结果无法解析", e)
        }
        val results = root.optJSONArray("results") ?: return@withContext emptyList()

        val out = ArrayList<CityResult>(MAX_CITY_RESULTS)
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            // 缺经纬度的结果点了也没法查天气，直接过滤
            if (name.isEmpty() || !item.has("latitude") || !item.has("longitude")) continue
            val latitude = item.optDouble("latitude", Double.NaN)
            val longitude = item.optDouble("longitude", Double.NaN)
            if (latitude.isNaN() || longitude.isNaN()) continue
            out.add(
                CityResult(
                    name = name,
                    admin = item.optString("admin1").trim(),
                    country = item.optString("country").trim(),
                    latitude = latitude,
                    longitude = longitude,
                    timezone = item.optString("timezone").trim()
                        .ifEmpty { DEFAULT_TIMEZONE },
                )
            )
            if (out.size >= MAX_CITY_RESULTS) break
        }
        out
    }

    // ── 请求 ────────────────────────────────────────────────────────────────

    private suspend fun json(
        url: String,
        action: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): JSONObject {
        val body = text(url, action, connectTimeoutMs, readTimeoutMs)
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("$action：返回内容无法解析", e)
        }
    }

    /**
     * 网络请求：连接 / 读超时都收紧到移动端可接受的范围，失败自动重试一次；
     * HTTP 状态码错误（401 / 429 / 5xx 等）不重试，直接把中文原因交给仓库。
     */
    private suspend fun text(
        url: String,
        action: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String {
        var lastError: IOException? = null
        for (attempt in 0..REQUEST_RETRIES) {
            try {
                return requestTextOnce(url, action, connectTimeoutMs, readTimeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt < REQUEST_RETRIES) delay(RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IOException("$action：网络连接异常")
    }

    private suspend fun requestTextOnce(
        url: String,
        action: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String {
        val body = try {
            Http.getText(
                url,
                headers = mapOf("Accept" to "application/json"),
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            // 401 / 429 / 5xx 等已有中文说明，原样交给仓库
            throw e
        } catch (e: Exception) {
            throw IOException("$action：${networkReason(e)}", e)
        }
        if (body.isBlank()) throw IOException("$action：服务返回为空")
        return body
    }

    private fun networkReason(e: Exception): String = when (e) {
        is SocketTimeoutException -> "天气服务响应慢，已自动重试，请稍后再试"
        is UnknownHostException -> "网络不可用，请检查连接"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "网络连接异常"
    }

    private suspend fun fetchAir(settings: AppSettings): AirQuality? {
        val body = try {
            Http.getText(
                "https://air-quality-api.open-meteo.com/v1/air-quality" +
                    "?latitude=${coord(settings.latitude)}&longitude=${coord(settings.longitude)}" +
                    "&current=pm2_5,us_aqi&timezone=auto",
                headers = mapOf("Accept" to "application/json"),
                // 空气质量是可选模块，超时短一点，别拖慢主天气
                connectTimeoutMs = 4_000,
                readTimeoutMs = 6_000,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }

        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        val current = root.optJSONObject("current") ?: return null
        val pm25 = current.optDouble("pm2_5", -1.0)
        val aqi = current.optInt("us_aqi", -1)
        if (pm25 < 0.0 && aqi < 0) return null
        return AirQuality(
            pm25 = if (pm25 < 0.0) 0.0 else pm25,
            aqi = if (aqi < 0) 0 else aqi,
        )
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    private fun parseDaily(source: JSONObject?): List<DailyWeather> {
        if (source == null) return emptyList()
        val times = source.optJSONArray("time") ?: return emptyList()
        val codes = source.optJSONArray("weather_code")
        val highs = source.optJSONArray("temperature_2m_max")
        val lows = source.optJSONArray("temperature_2m_min")
        val rains = source.optJSONArray("precipitation_probability_max")
        val uvs = source.optJSONArray("uv_index_max")
        val sunrises = source.optJSONArray("sunrise")
        val sunsets = source.optJSONArray("sunset")

        val out = ArrayList<DailyWeather>(times.length())
        for (i in 0 until times.length()) {
            val date = times.optString(i).trim()
            if (date.length != 10) continue
            out.add(
                DailyWeather(
                    date = date,
                    code = if (codes == null) UNKNOWN_CODE else codes.optInt(i, UNKNOWN_CODE),
                    high = if (highs == null) 0.0 else highs.optDouble(i, 0.0),
                    low = if (lows == null) 0.0 else lows.optDouble(i, 0.0),
                    precipProb = if (rains == null) 0 else rains.optInt(i, 0),
                    uvIndex = if (uvs == null) 0.0 else uvs.optDouble(i, 0.0),
                    sunrise = clock(sunrises?.optString(i)),
                    sunset = clock(sunsets?.optString(i)),
                )
            )
        }
        return out
    }

    /**
     * 只保留 now 之后的 12 个小时。
     * 深夜时剩余小时数不足 12，自然跨到明天，正好是「接下来 12 小时」。
     */
    private fun parseHourly(source: JSONObject?, now: LocalDateTime): List<HourlyWeather> {
        if (source == null) return emptyList()
        val times = source.optJSONArray("time") ?: return emptyList()
        val temps = source.optJSONArray("temperature_2m")
        val codes = source.optJSONArray("weather_code")
        val rains = source.optJSONArray("precipitation_probability")

        val out = ArrayList<HourlyWeather>(HOURS_AHEAD)
        for (i in 0 until times.length()) {
            val at = parseDateTime(times.optString(i)) ?: continue
            if (!at.isAfter(now)) continue
            out.add(
                HourlyWeather(
                    // 展示用 HH:mm，跨午夜也不会歧义
                    time = String.format(Locale.US, "%02d:%02d", at.hour, at.minute),
                    temp = if (temps == null) 0.0 else temps.optDouble(i, 0.0),
                    code = if (codes == null) UNKNOWN_CODE else codes.optInt(i, UNKNOWN_CODE),
                    precipProb = if (rains == null) 0 else rains.optInt(i, 0),
                )
            )
            if (out.size >= HOURS_AHEAD) break
        }
        return out
    }

    private fun parseDateTime(raw: String): LocalDateTime? = try {
        LocalDateTime.parse(raw)
    } catch (e: DateTimeParseException) {
        null
    }

    /** "2026-11-02T06:10" → "06:10" */
    private fun clock(raw: String?): String {
        if (raw == null || raw.length < 16 || raw[10] != 'T') return raw.orEmpty()
        return raw.substring(11, 16)
    }

    /** 时区优先级：接口时区 → 设置时区 → 接口 UTC 偏移 → 系统时区 */
    private fun resolveZone(root: JSONObject, settings: AppSettings): ZoneId {
        val names = listOf(root.optString("timezone"), settings.timezone)
        for (name in names) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) continue
            runCatching { ZoneId.of(trimmed) }.getOrNull()?.let { return it }
        }
        val offset = root.optInt("utc_offset_seconds", MISSING_INT)
        if (offset != MISSING_INT) {
            runCatching { ZoneOffset.ofTotalSeconds(offset) }.getOrNull()?.let { return it }
        }
        return ZoneId.systemDefault()
    }

    // ── URL ─────────────────────────────────────────────────────────────────

    /** 只是 current 的极简请求；毫秒桶做 cache buster，避免拿到中间层缓存。 */
    private fun currentUrl(settings: AppSettings): String = buildString {
        append("https://api.open-meteo.com/v1/forecast")
        append("?latitude=").append(coord(settings.latitude))
        append("&longitude=").append(coord(settings.longitude))
        append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,")
        append("precipitation,weather_code,wind_speed_10m,wind_direction_10m")
        append("&timezone=auto&forecast_days=1")
        append("&_=").append(System.currentTimeMillis() / 15_000L)
    }

    private fun forecastUrl(settings: AppSettings): String = buildString {
        append("https://api.open-meteo.com/v1/forecast")
        append("?latitude=").append(coord(settings.latitude))
        append("&longitude=").append(coord(settings.longitude))
        append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,")
        append("precipitation,weather_code,wind_speed_10m,wind_direction_10m")
        append("&hourly=temperature_2m,weather_code,precipitation_probability")
        append("&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,")
        append("uv_index_max,precipitation_probability_max")
        append("&timezone=auto&forecast_days=").append(FORECAST_DAYS)
        append("&past_days=").append(PAST_DAYS)
    }

    /** 固定小数位，避免某些区域设置把小数点写成逗号 */
    private fun coord(value: Double): String =
        String.format(Locale.US, "%.4f", value.coerceIn(-180.0, 180.0))

    private fun urlEncode(query: String): String =
        URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    private companion object {
        const val FORECAST_DAYS = 7
        const val PAST_DAYS = 1
        const val HOURS_AHEAD = 12
        const val MAX_CITY_RESULTS = 6
        const val UNKNOWN_CODE = -1
        const val MISSING_INT = Int.MIN_VALUE
        const val DEFAULT_TIMEZONE = "Asia/Shanghai"

        /** 移动网络下 4.5 秒连不上基本可以判定为弱网，不再死等；失败重试一次。 */
        const val CONNECT_TIMEOUT_MS = 4_500
        const val READ_TIMEOUT_MS = 9_000

        /** 轻量实时轮询：弱网也别拖住下一次，超时更短、不阻塞。 */
        const val FAST_CONNECT_TIMEOUT_MS = 3_000
        const val FAST_READ_TIMEOUT_MS = 5_000
        const val REQUEST_RETRIES = 1
        const val RETRY_DELAY_MS = 350L

        /** 主天气到手后，最多再等空气质量这么久，等不到就放弃这块可选数据。 */
        const val AIR_WAIT_MS = 2_200L

        /** CAMS 空气质量是小时级，30 分钟复用一次足够。 */
        const val AIR_CACHE_TTL_MS = 30 * 60_000L
    }
}
