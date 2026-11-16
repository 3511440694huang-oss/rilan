package app.rilan.daily.core

import android.content.Context
import app.rilan.daily.BuildConfig
import app.rilan.daily.data.ContentFetcher
import app.rilan.daily.data.DeepSeekClient
import app.rilan.daily.data.Http
import app.rilan.daily.data.HttpException
import app.rilan.daily.data.LocationClient
import app.rilan.daily.data.PlaceInfo
import app.rilan.daily.data.WallpaperClient
import app.rilan.daily.data.WeatherClient
import app.rilan.daily.mcp.McpHost
import app.rilan.daily.mcp.McpServerState
import app.rilan.daily.mcp.McpTestState
import app.rilan.daily.mcp.McpService
import app.rilan.daily.mcp.RilanMcpServer
import app.rilan.daily.plugins.SourcePlugin
import app.rilan.daily.plugins.SourcePluginStore
import app.rilan.daily.schedule.DailyBriefNotifier
import app.rilan.daily.schedule.NotificationHelper
import app.rilan.daily.schedule.Recurrence
import app.rilan.daily.schedule.ReminderScheduler
import app.rilan.daily.schedule.ScheduleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class AppRepository(context: Context) : McpHost {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initialSettings = ensureMcpToken(prefs.loadSettings())

    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _home = MutableStateFlow(buildInitialHome())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _records = MutableStateFlow(prefs.loadRecords())
    val records: StateFlow<List<NewsRecord>> = _records.asStateFlow()

    private val _digests = MutableStateFlow(prefs.loadDigests())
    val digests: StateFlow<Map<SummaryPeriod, AiDigest>> = _digests.asStateFlow()

    /** 正在用 DeepSeek 直连生成的周期；null 表示空闲。 */
    private val _summaryLoading = MutableStateFlow<SummaryPeriod?>(null)
    val summaryLoading: StateFlow<SummaryPeriod?> = _summaryLoading.asStateFlow()

    private val _cityResults = MutableStateFlow<List<CityResult>>(emptyList())
    val cityResults: StateFlow<List<CityResult>> = _cityResults.asStateFlow()

    private val _citySearching = MutableStateFlow(false)
    val citySearching: StateFlow<Boolean> = _citySearching.asStateFlow()

    private val scheduleStore = ScheduleStore(appContext)
    val schedules: StateFlow<List<ScheduleEvent>> = scheduleStore.events

    private val pluginStore = SourcePluginStore(appContext)
    val plugins: StateFlow<List<SourcePlugin>> = pluginStore.plugins
    val pluginErrors: StateFlow<List<String>> = pluginStore.errors
    val pluginDirectory: String get() = pluginStore.directoryPath

    private val weatherClient = WeatherClient()
    private val locationClient = LocationClient(appContext)
    private val contentFetcher = ContentFetcher()
    private val wallpaperClient = WallpaperClient(appContext)
    private val deepSeekClient = DeepSeekClient()

    private val _aiTest = MutableStateFlow(AiTestState())
    val aiTest: StateFlow<AiTestState> = _aiTest.asStateFlow()

    private val mcpServer = RilanMcpServer(this, initialSettings.mcpToken, initialSettings.mcpPort)
    val mcpState: StateFlow<McpServerState> = mcpServer.state
    private val _mcpTest = MutableStateFlow(McpTestState())
    val mcpTest: StateFlow<McpTestState> = _mcpTest.asStateFlow()

    private val settingsLock = Any()
    private val refreshLock = Any()
    private var activeRefreshes = 0

    @Volatile private var weatherJob: Job? = null
    @Volatile private var contentJob: Job? = null
    @Volatile private var wallpaperJob: Job? = null
    @Volatile private var aiJob: Job? = null
    @Volatile private var summaryJob: Job? = null
    private val bootstrapped = AtomicBoolean(false)

    init {
        // 保证旧安装升级后也拥有一枚 MCP token；没有 token 的服务永远不会启动。
        if (initialSettings.mcpToken != prefs.loadSettings().mcpToken) {
            prefs.saveSettings(initialSettings)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 启动与刷新
    // ────────────────────────────────────────────────────────────────────────

    fun bootstrap() {
        if (!bootstrapped.compareAndSet(false, true)) return
        NotificationHelper.ensureChannels(appContext)
        ReminderScheduler.ensureDaily(appContext)
        // 每日简报以 AppSettings 为准同步一次闹钟：升级 / 首次启动后设置与已注册闹钟不打架。
        val current = _settings.value
        DailyBriefNotifier.schedule(
            appContext,
            current.dailyNoticeEnabled,
            current.dailyNoticeMinuteOfDay / 60,
            current.dailyNoticeMinuteOfDay % 60,
        )
        refreshDailyAiForToday()
        scope.launch {
            // 壁纸、天气、信息流互不阻塞：壁纸最慢，不能挡在天气前面。
            launch { refreshWallpaperIfStale() }
            if (isWeatherStale()) refreshWeather()
            if (isContentStale()) triggerContentRefresh()
        }
    }

    fun onForeground() {
        refreshDailyAiForToday()
        if (isWeatherStale()) refreshWeather()
        if (isContentStale()) triggerContentRefresh()
        refreshWallpaperIfStale()
        ensureMcpRunningIfEnabled()
    }

    fun refreshAll() {
        refreshWeatherInternal(forceLocate = true)
        triggerContentRefresh()
        refreshWallpaper()
    }

    /** 跨过零点后，昨天的总览不能继续冒充今天。 */
    private fun refreshDailyAiForToday() {
        val today = LocalDate.now()
        val cached = prefs.aiCache("daily_$today")?.takeIf { it.isNotBlank() }
        if (cached != null) {
            if (_home.value.dailyBrief.content != cached) {
                _home.update { it.copy(dailyBrief = AiText(content = cached)) }
            }
            return
        }
        val generatedAt = _home.value.dailyBrief.generatedAt
        if (generatedAt <= 0L) return
        val generatedDate = runCatching {
            Instant.ofEpochMilli(generatedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrNull()
        if (generatedDate != null && generatedDate != today) {
            _home.update { it.copy(dailyBrief = AiText()) }
        }
    }

    private fun isWeatherStale(): Boolean {
        val last = _settings.value.lastWeatherAt
        return last <= 0L || System.currentTimeMillis() - last > 3 * 60_000L
    }

    private fun isContentStale(): Boolean {
        val last = _settings.value.lastContentAt
        return last <= 0L || System.currentTimeMillis() - last > 6 * 60_000L
    }

    fun refreshWeather() {
        refreshWeatherInternal(forceLocate = false)
    }

    /** 用户在界面上点「重新定位」时调用；无条件重新取一次位置。 */
    fun locateNow() {
        // 首次启动的天气请求可能已经占着 weatherJob，定位授权回来时要让它让位
        weatherJob?.cancel()
        weatherJob = null
        refreshWeatherInternal(forceLocate = true)
    }

    fun setAutoLocation(enabled: Boolean) {
        mutateSettings {
            it.copy(
                autoLocation = enabled,
                locationAsked = true,
                place = if (enabled) it.place else "",
            )
        }
        if (enabled) {
            weatherJob?.cancel()
            weatherJob = null
            refreshWeatherInternal(forceLocate = true)
        } else {
            weatherJob?.cancel()
            weatherJob = null
            _home.update { it.copy(locating = false, locationError = null) }
            refreshWeatherInternal(forceLocate = false)
        }
    }

    fun markLocationAsked() {
        if (!_settings.value.locationAsked) {
            mutateSettings { it.copy(locationAsked = true) }
        }
    }

    private fun isLocationStale(): Boolean {
        val last = _settings.value.lastLocationAt
        return last <= 0L || System.currentTimeMillis() - last > 30 * 60_000L
    }

    private fun refreshWeatherInternal(forceLocate: Boolean) {
        if (weatherJob?.isActive == true) return
        weatherJob = scope.launch {
            beginRefresh()
            var early: Deferred<WeatherData>? = null
            try {
                val settings = _settings.value
                val auto = settings.autoLocation
                val permitted = locationClient.hasPermission()

                if (auto && !permitted && settings.lastLocationAt <= 0L) {
                    _home.update {
                        it.copy(
                            locating = false,
                            locationError = "未开启定位权限",
                            weatherError = "开启定位，或到设置里手动选择城市",
                            loading = false,
                        )
                    }
                    return@launch
                }

                // 先用上次坐标（或手动城市）立刻拉天气；定位与区县反查在下面并行补，
                // 位置服务慢时不再把天气卡在 loading 里十几秒。
                if (!auto || settings.lastLocationAt > 0L) {
                    early = async { weatherClient.fetch(settings) }
                }

                var place: PlaceInfo? = null
                if (auto && permitted && (forceLocate || isLocationStale())) {
                    _home.update { it.copy(locating = true, locationError = null) }
                    place = withTimeoutOrNull(8_000L) {
                        runCatching { locationClient.currentPlace() }.getOrNull()
                    }
                    if (place != null) {
                        applyPlace(place)
                        _home.update { it.copy(locating = false, locationError = null) }
                    } else {
                        val hint = "定位没有成功，请确认系统定位已打开"
                        _home.update {
                            it.copy(
                                locating = false,
                                locationError = hint,
                                weatherError = if (settings.lastLocationAt <= 0L) hint else it.weatherError,
                            )
                        }
                        // 从没定位成功过时不要拿默认坐标（北京）冒充当前位置
                        if (settings.lastLocationAt <= 0L) {
                            early?.cancel()
                            _home.update { it.copy(loading = false) }
                            return@launch
                        }
                    }
                } else if (auto && !permitted) {
                    _home.update { it.copy(locating = false, locationError = "未开启定位权限") }
                    // 从未定位成功过时，不用「北京」这种默认坐标冒充当前位置
                    if (settings.lastLocationAt <= 0L) {
                        early?.cancel()
                        _home.update {
                            it.copy(
                                weatherError = "开启定位，或到设置里手动选择城市",
                                loading = false,
                            )
                        }
                        return@launch
                    }
                }

                val located = place
                val moved = located != null && (
                    abs(located.latitude - settings.latitude) > 1e-4 ||
                        abs(located.longitude - settings.longitude) > 1e-4
                    )
                var data: WeatherData? = null
                val pending = early
                if (pending != null) {
                    if (moved) {
                        // 位置变了，旧坐标的天气作废，换新坐标再拉一次
                        pending.cancel()
                    } else {
                        data = pending.await()
                    }
                }
                val fresh = data ?: weatherClient.fetch(_settings.value)
                prefs.saveWeatherCache(fresh)
                _home.update {
                    it.copy(
                        weather = fresh,
                        weatherError = null,
                        loading = false,
                        lastUpdated = maxOf(it.lastUpdated, System.currentTimeMillis()),
                    )
                }
                mutateSettings { it.copy(lastWeatherAt = System.currentTimeMillis()) }
            } catch (cancel: CancellationException) {
                early?.cancel()
                throw cancel
            } catch (t: Throwable) {
                early?.cancel()
                _home.update {
                    it.copy(
                        weatherError = friendlyError(t),
                        loading = false,
                        locating = false,
                    )
                }
            } finally {
                endRefresh()
            }
        }
    }

    private fun applyPlace(place: PlaceInfo) {
        mutateSettings {
            it.copy(
                city = place.city.ifBlank { "当前位置" },
                place = place.label,
                latitude = place.latitude,
                longitude = place.longitude,
                timezone = ZoneId.systemDefault().id,
                lastLocationAt = System.currentTimeMillis(),
                autoLocation = true,
            )
        }
    }

    fun triggerContentRefresh() {
        if (contentJob?.isActive == true) return
        contentJob = scope.launch {
            beginRefresh()
            try {
                val fetched = contentFetcher.fetchAll(
                    plugins = plugins.value,
                    blockedWords = _settings.value.blockedWords,
                    includeWeibo = _settings.value.weiboHotEnabled,
                    includeBilibili = _settings.value.bilibiliHotEnabled,
                    includeDouyin = _settings.value.douyinHotEnabled,
                )
                if (fetched.isEmpty()) {
                    _home.update {
                        it.copy(contentError = "暂时没有抓到内容，请检查插件与网络后重试", loading = false)
                    }
                    return@launch
                }
                val previous = _home.value.sections.associateBy { it.category }
                val merged = fetched.map { section ->
                    if (section.items.isEmpty() && section.error != null) {
                        previous[section.category]?.copy(error = section.error, updatedAt = System.currentTimeMillis())
                            ?: section
                    } else section
                }
                val hasItems = merged.any { it.items.isNotEmpty() }
                _home.update {
                    it.copy(
                        sections = merged,
                        contentError = if (hasItems) null else merged.firstOrNull { s -> s.error != null }?.error,
                        loading = false,
                        lastUpdated = maxOf(it.lastUpdated, System.currentTimeMillis()),
                    )
                }
                if (hasItems) {
                    prefs.saveSectionsCache(merged)
                    recordHistory(merged)
                    mutateSettings { it.copy(lastContentAt = System.currentTimeMillis()) }
                }
            } catch (t: Throwable) {
                _home.update { it.copy(contentError = friendlyError(t), loading = false) }
            } finally {
                endRefresh()
            }
        }
    }

    fun refreshWallpaper() {
        if (wallpaperJob?.isActive == true) return
        wallpaperJob = scope.launch {
            val wall = runCatching { wallpaperClient.loadDaily() }.getOrNull() ?: return@launch
            prefs.saveWallpaper(wall)
            _home.update { it.copy(wallpaper = wall) }
        }
    }

    private fun refreshWallpaperIfStale() {
        if (isWallpaperStale()) refreshWallpaper()
    }

    private fun beginRefresh() {
        synchronized(refreshLock) {
            activeRefreshes++
            _home.update { it.copy(refreshing = true) }
        }
    }

    private fun endRefresh() {
        synchronized(refreshLock) {
            activeRefreshes = (activeRefreshes - 1).coerceAtLeast(0)
            if (activeRefreshes == 0) {
                _home.update { it.copy(refreshing = false, loading = false) }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 设置 / 城市 / 来源插件
    // ────────────────────────────────────────────────────────────────────────

    fun saveSettings(newSettings: AppSettings) {
        val old = _settings.value
        val sanitized = newSettings.copy(
            city = newSettings.city.trim().ifBlank { old.city },
            mcpPort = newSettings.mcpPort.coerceIn(1024, 65535),
        )
        val cityChanged = sanitized.city != old.city ||
            sanitized.latitude != old.latitude ||
            sanitized.longitude != old.longitude
        val reminderChanged = sanitized.reminderMinuteOfDay != old.reminderMinuteOfDay
        val dailyNoticeChanged = sanitized.dailyNoticeEnabled != old.dailyNoticeEnabled ||
            sanitized.dailyNoticeMinuteOfDay != old.dailyNoticeMinuteOfDay
        val autoLocationChanged = sanitized.autoLocation != old.autoLocation
        val aiChanged = sanitized.deepseekApiKey != old.deepseekApiKey ||
            sanitized.deepseekModel != old.deepseekModel
        val normalized = if (cityChanged || !sanitized.autoLocation) {
            sanitized.copy(place = "")
        } else {
            sanitized
        }
        mutateSettings { normalized.copy(onboardingDone = true) }

        if (aiChanged) {
            _aiTest.value = AiTestState()
            _home.update { it.copy(dailyBrief = it.dailyBrief.copy(error = null)) }
        }

        if (autoLocationChanged) {
            if (normalized.autoLocation) {
                refreshWeatherInternal(forceLocate = true)
            } else {
                _home.update { it.copy(locating = false, locationError = null) }
            }
        }
        if (cityChanged) {
            _home.update { it.copy(weatherInsight = AiText()) }
            refreshWeather()
            triggerContentRefresh()
        }
        if (reminderChanged) {
            ReminderScheduler.reschedule(appContext, normalized.reminderMinuteOfDay)
        }
        // 每日简报开关 / 时间变化后立刻重挂闹钟（设置本身已由 mutateSettings 持久化）。
        if (dailyNoticeChanged) {
            DailyBriefNotifier.schedule(
                appContext,
                normalized.dailyNoticeEnabled,
                normalized.dailyNoticeMinuteOfDay / 60,
                normalized.dailyNoticeMinuteOfDay % 60,
            )
        }
    }

    fun searchCity(query: String) {
        val q = query.trim()
        if (q.isBlank()) {
            _cityResults.value = emptyList()
            return
        }
        scope.launch {
            _citySearching.value = true
            try {
                _cityResults.value = weatherClient.searchCity(q)
            } catch (_: Throwable) {
                _cityResults.value = emptyList()
            } finally {
                _citySearching.value = false
            }
        }
    }

    fun chooseCity(result: CityResult) {
        mutateSettings {
            it.copy(
                city = result.name,
                place = "",
                latitude = result.latitude,
                longitude = result.longitude,
                timezone = result.timezone.ifBlank { it.timezone },
                autoLocation = false,
            )
        }
        _cityResults.value = emptyList()
        _home.update { it.copy(weatherInsight = AiText(), locationError = null) }
        refreshWeather()
        triggerContentRefresh()
    }

    fun clearAiCaches() {
        aiJob?.cancel()
        aiJob = null
        summaryJob?.cancel()
        summaryJob = null
        _summaryLoading.value = null
        prefs.clearAiCaches()
        _home.update { it.copy(weatherInsight = AiText(), dailyBrief = AiText()) }
        _digests.value = emptyMap()
        prefs.saveDigests(emptyMap())
        _aiTest.value = AiTestState()
    }

    // ────────────────────────────────────────────────────────────────────────
    // DeepSeek 直连：今日总览
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 把今天的天气、信息流和日程收成一段可读的总览，并给出具体建议。
     * 只在用户点按时调用；结果按「日期」缓存在本机，避免重复消耗 token。
     */
    fun generateDailyBrief() {
        if (aiJob?.isActive == true) return
        val settings = _settings.value
        if (!settings.deepseekConfigured) {
            _home.update {
                it.copy(
                    dailyBrief = it.dailyBrief.copy(
                        loading = false,
                        error = "还没有填写 DeepSeek API Key，先到设置里连接。",
                    ),
                )
            }
            return
        }

        aiJob = scope.launch {
            _home.update {
                it.copy(dailyBrief = it.dailyBrief.copy(loading = true, error = null))
            }
            try {
                val content = deepSeekClient.complete(
                    apiKey = settings.deepseekApiKey,
                    model = settings.deepseekModel,
                    system = DAILY_BRIEF_SYSTEM,
                    user = buildDailyBriefPrompt(),
                    maxTokens = 1_400,
                ).trim().take(6_000)

                prefs.putAiCache("daily_${LocalDate.now()}", content)
                _home.update {
                    it.copy(
                        dailyBrief = AiText(
                            content = content,
                            generatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _home.update {
                    it.copy(
                        dailyBrief = it.dailyBrief.copy(
                            loading = false,
                            error = t.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "生成失败，请稍后重试",
                        ),
                    )
                }
            }
        }
    }

    /** 设置页的轻量测试；不写入日报缓存，只返回一次结果。 */
    fun testDeepSeek(apiKey: String, model: String) {
        if (_aiTest.value.loading) return
        if (apiKey.isBlank()) {
            _aiTest.value = AiTestState(ok = false, message = "先填一个 API Key")
            return
        }
        scope.launch {
            _aiTest.value = AiTestState(loading = true)
            _aiTest.value = try {
                deepSeekClient.test(apiKey, model)
                AiTestState(ok = true, message = "连接成功 · $model")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                AiTestState(
                    ok = false,
                    message = t.message?.takeIf { it.isNotBlank() } ?: "连接失败，请稍后重试",
                )
            }
        }
    }

    private fun buildDailyBriefPrompt(): String {
        val today = LocalDate.now()
        val weather = _home.value.weather
        val sections = _home.value.sections.filter { it.items.isNotEmpty() }
        val todayEvents = schedules.value
            .filter { runCatching { Recurrence.occursOn(it, today) }.getOrDefault(false) }
            .sortedWith(
                compareBy<ScheduleEvent> { if (it.time == null) 0 else 1 }
                    .thenBy { it.time ?: "" }
                    .thenByDescending { it.priority.ordinal }
            )

        val prompt = StringBuilder(4_000)
        prompt.appendLine("日期：$today")
        prompt.appendLine()

        prompt.appendLine("【天气】")
        if (weather == null) {
            prompt.appendLine("暂时没有获取到天气数据。")
        } else {
            prompt.appendLine(
                "${weather.locationLabel}，${weather.condition}，当前 ${weather.currentTemp.toInt()}°C，" +
                    "体感 ${weather.feelsLike.toInt()}°C，今天 ${weather.low.toInt()}–${weather.high.toInt()}°C。"
            )
            prompt.appendLine(
                "降水概率 ${weather.precipProb}%，湿度 ${weather.humidity}%，" +
                    "风速 ${weather.windSpeed.toInt()} km/h，紫外线 ${weather.uvIndex.toInt()}。"
            )
            weather.air?.let { air ->
                prompt.appendLine("空气质量：PM2.5 ${air.pm25.toInt()}，AQI ${air.aqi}。")
            }
            if (weather.sunrise.isNotBlank() && weather.sunset.isNotBlank()) {
                prompt.appendLine("日出 ${weather.sunrise}，日落 ${weather.sunset}。")
            }
        }
        prompt.appendLine()

        prompt.appendLine("【今天的日程】")
        if (todayEvents.isEmpty()) {
            prompt.appendLine("今天没有日程安排。")
        } else {
            todayEvents.take(12).forEach { event ->
                prompt.appendLine(
                    "- ${event.time ?: "全天"} ${event.title}（${event.kind.label}，${event.priority.label}重要）" +
                        event.notes.takeIf { it.isNotBlank() }?.let { "：${it.take(80)}" }.orEmpty()
                )
            }
        }
        prompt.appendLine()

        prompt.appendLine("【今天收下来的内容】")
        val items = sections.flatMap { section ->
            section.items.take(4).map { item -> section.category.label to item }
        }.take(20)
        if (items.isEmpty()) {
            prompt.appendLine("暂时没有信息流内容。")
        } else {
            items.forEach { (category, item) ->
                prompt.appendLine(
                    "- [$category] ${item.title}" +
                        item.source.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                )
            }
        }
        prompt.appendLine()

        prompt.appendLine(
            """
            请基于以上材料，用中文写今天的日报总览。要求：
            1. 开头用一句话概括今天，不超过 45 字。
            2. 用 **今天值得关注** 列出 3 条，每条一句话，优先选与天气、日程或重要新闻有关的。
            3. 用 **给你的建议** 列出 2–3 条具体可执行的建议，覆盖穿衣、出行或时间安排。
            4. 只能使用材料里的事实，不要编造；材料不足就直接说“今天的信息还不够”。
            5. 不要一级标题，不要表格，不要寒暄，全文 350–550 字。
            """.trimIndent()
        )
        return prompt.toString().take(7_000)
    }

    private companion object {
        const val DAILY_BRIEF_SYSTEM =
            "你是「日览」的中文编辑，擅长把碎片信息收拢成一天的主线。" +
                "语气克制、具体、不空泛；只输出正文，不解释你的写作过程。"

        const val PERIOD_SUMMARY_SYSTEM =
            "你是「日览」的中文编辑，擅长从一个人的阅读收录里找到一段时间的脉络与变化。" +
                "语气克制、具体、不空泛；只使用材料里的事实；只输出正文，不解释你的写作过程。"
    }

    // ────────────────────────────────────────────────────────────────────────
    // DeepSeek 直连：周期总结（与 MCP 写回共用同一份 _digests）
    // ────────────────────────────────────────────────────────────────────────

    /** 切换周期总结的生成方式，并立即持久化。 */
    fun setSummaryMode(mode: SummaryMode) {
        if (_settings.value.summaryMode == mode) return
        mutateSettings { it.copy(summaryMode = mode) }
    }

    /**
     * 用本机真实的收录记录拼提示词，调用 DeepSeek 直连生成周期总结，写回与 MCP 相同的 _digests。
     * 失败时把中文错误写进 AiDigest.error，保留旧内容，不抛给界面。
     */
    fun generatePeriodSummaryDirect(period: SummaryPeriod) {
        if (summaryJob?.isActive == true) return
        val settings = _settings.value
        val today = LocalDate.now()
        if (!settings.deepseekConfigured) {
            updateDigest(
                AiDigest(
                    period = period,
                    key = period.key(today),
                    rangeText = period.rangeText(today),
                    content = _digests.value[period]?.content.orEmpty(),
                    itemCount = _digests.value[period]?.itemCount ?: 0,
                    createdAt = System.currentTimeMillis(),
                    error = "还没有填写 DeepSeek API Key，先到扩展页连接 DeepSeek。",
                    source = AiDigest.SOURCE_DEEPSEEK,
                )
            )
            return
        }

        summaryJob = scope.launch {
            _summaryLoading.value = period
            val items = recordsFor(period.startDate(today), today)
            try {
                if (items.isEmpty()) {
                    throw IllegalStateException("这段时间还没有收录记录，先去日报刷新几次再生成。")
                }
                val content = deepSeekClient.complete(
                    apiKey = settings.deepseekApiKey,
                    model = settings.deepseekModel,
                    system = PERIOD_SUMMARY_SYSTEM,
                    user = buildPeriodSummaryPrompt(period, today, items),
                    maxTokens = 1_800,
                ).trim().take(12_000)

                updateDigest(
                    AiDigest(
                        period = period,
                        key = period.key(today),
                        rangeText = period.rangeText(today),
                        content = content,
                        itemCount = items.size,
                        createdAt = System.currentTimeMillis(),
                        source = AiDigest.SOURCE_DEEPSEEK,
                    )
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                val old = _digests.value[period]
                updateDigest(
                    AiDigest(
                        period = period,
                        key = period.key(today),
                        rangeText = period.rangeText(today),
                        content = old?.content.orEmpty(),
                        itemCount = items.size,
                        createdAt = old?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        error = t.message?.takeIf { it.isNotBlank() } ?: "生成失败，请稍后重试",
                        source = AiDigest.SOURCE_DEEPSEEK,
                    )
                )
            } finally {
                _summaryLoading.value = null
            }
        }
    }

    /** 只把真实记录交给模型；按天分组，附分类与来源，明确禁止编造。 */
    private fun buildPeriodSummaryPrompt(
        period: SummaryPeriod,
        today: LocalDate,
        items: List<NewsRecord>,
    ): String {
        val zone = ZoneId.systemDefault()
        val dateFormat = DateTimeFormatter.ofPattern("M月d日")
        val shown = items.sortedByDescending { it.firstSeenAt }.take(260)
        val byDate = shown.groupBy {
            Instant.ofEpochMilli(it.firstSeenAt).atZone(zone).toLocalDate()
        }.toSortedMap(compareByDescending { it })

        val prompt = StringBuilder(8_000)
        prompt.appendLine("周期：${period.label}（${period.rangeText(today)}）")
        prompt.appendLine("素材：本机收录的 ${items.size} 条记录，按首次收录时间归档；只能使用这些事实。")
        prompt.appendLine()
        byDate.forEach { (date, records) ->
            prompt.appendLine("【${date.format(dateFormat)}】")
            records.forEach { record ->
                val source = record.source.takeIf { it.isNotBlank() } ?: "未知来源"
                prompt.appendLine("- [${record.category.label}] $source：${record.title.take(160)}")
            }
            prompt.appendLine()
        }
        if (items.size > shown.size) {
            prompt.appendLine("（另有 ${items.size - shown.size} 条较早记录未列出）")
            prompt.appendLine()
        }
        prompt.appendLine(
            """
            请基于以上材料，用中文写这段时间的回顾总结。要求：
            1. 开头用一句话概括这段时间的主线，不超过 45 字。
            2. 用 **看什么** 列出 3–5 条最值得记住的事，每条一句话，尽量注明大概日期或来源。
            3. 用 **趋势与变化** 写 2–4 句：哪些话题反复出现、关注点怎么移动。
            4. 用 **给你的提醒** 写 1–3 条基于这些内容的行动建议。
            5. 只能使用材料里的事实，不要编造、不要补充材料以外的知识；材料不足就直接说“这段时间收录的内容还不够”。
            6. 不要一级标题，不要表格，不要寒暄，全文 400–700 字。
            """.trimIndent()
        )
        return prompt.toString().take(12_000)
    }

    fun savePlugin(rawJson: String, originalId: String? = null): String? {
        val result = pluginStore.saveJson(rawJson, originalId)
        return result.fold(
            onSuccess = {
                triggerContentRefresh()
                null
            },
            onFailure = { it.message?.takeIf { msg -> msg.isNotBlank() } ?: "插件保存失败" },
        )
    }

    fun deletePlugin(id: String) {
        pluginStore.delete(id)
        triggerContentRefresh()
    }

    fun togglePlugin(id: String, enabled: Boolean) {
        pluginStore.setEnabled(id, enabled)
        triggerContentRefresh()
    }

    fun setHotListEnabled(category: Category, enabled: Boolean) {
        mutateSettings {
            when (category) {
                Category.WEIBO -> it.copy(weiboHotEnabled = enabled)
                Category.BILIBILI -> it.copy(bilibiliHotEnabled = enabled)
                Category.DOUYIN -> it.copy(douyinHotEnabled = enabled)
                else -> it
            }
        }
        triggerContentRefresh()
    }

    fun reloadPlugins() {
        pluginStore.reload()
    }

    // ────────────────────────────────────────────────────────────────────────
    // MCP 生命周期
    // ────────────────────────────────────────────────────────────────────────

    fun startMcp(): Result<Unit> {
        val result = startMcpServerOnly()
        if (result.isSuccess) {
            runCatching { McpService.start(appContext) }
        }
        return result
    }

    fun stopMcp() {
        stopMcpServerOnly(markDisabled = true)
        runCatching { McpService.stop(appContext) }
        _mcpTest.value = McpTestState()
    }

    fun startMcpServerOnly(): Result<Unit> {
        val result = mcpServer.start()
        if (result.isSuccess) {
            mutateSettings { it.copy(mcpEnabled = true) }
        }
        return result
    }

    fun stopMcpServerOnly(markDisabled: Boolean) {
        mcpServer.stop()
        _mcpTest.value = McpTestState()
        if (markDisabled) {
            mutateSettings { it.copy(mcpEnabled = false) }
        }
    }

    /** App 被重新打开时，如果用户上次启用了 MCP，则恢复前台服务。 */
    fun ensureMcpRunningIfEnabled() {
        if (!_settings.value.mcpEnabled) return
        if (mcpState.value.running) return
        val result = startMcpServerOnly()
        if (result.isSuccess) {
            runCatching { McpService.start(appContext) }
        }
    }

    /**
     * 从 App 内部走一遍完整回环：启动 → initialize → tools/list。
     * 这样用户不用先配外部客户端，就能知道端口、令牌和协议是不是正常。
     */
    fun testMcp() {
        if (_mcpTest.value.loading) return
        _mcpTest.value = McpTestState(loading = true)
        scope.launch {
            try {
                if (!mcpState.value.running) {
                    val started = startMcp()
                    if (started.isFailure) {
                        throw IllegalStateException(started.exceptionOrNull()?.message ?: "MCP 启动失败")
                    }
                }
                delay(120L)
                val state = mcpState.value
                val token = state.token
                if (token.isBlank()) throw IllegalStateException("MCP 令牌为空，请重启服务")

                val initialize = postMcp(
                    state.endpoint,
                    token,
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"rilan-self-test","version":"1"}}}""",
                )
                val initRoot = JSONObject(initialize)
                initRoot.optJSONObject("error")?.let {
                    throw IllegalStateException(it.optString("message").ifBlank { "初始化失败" })
                }
                val result = initRoot.optJSONObject("result")
                    ?: throw IllegalStateException("初始化没有返回结果")
                val protocol = result.optString("protocolVersion").ifBlank { "未知协议" }

                postMcp(
                    state.endpoint,
                    token,
                    """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""",
                )

                val toolsRaw = postMcp(
                    state.endpoint,
                    token,
                    """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
                )
                val tools = JSONObject(toolsRaw).optJSONObject("result")
                    ?.optJSONArray("tools")?.length() ?: 0
                if (tools <= 0) throw IllegalStateException("服务已响应，但没有返回工具")

                _mcpTest.value = McpTestState(
                    ok = true,
                    message = "本机连接正常 · $tools 个工具 · $protocol",
                    toolCount = tools,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _mcpTest.value = McpTestState(
                    ok = false,
                    message = t.message?.takeIf { it.isNotBlank() } ?: "连接检测失败",
                )
            }
        }
    }

    private suspend fun postMcp(endpoint: String, token: String, body: String): String =
        Http.postJson(
            url = endpoint,
            body = body,
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json, text/event-stream",
            ),
            connectTimeoutMs = 5_000,
            readTimeoutMs = 8_000,
        )

    // ────────────────────────────────────────────────────────────────────────
    // 日程
    // ────────────────────────────────────────────────────────────────────────

    fun addSchedule(event: ScheduleEvent) {
        scheduleStore.add(event)
        afterScheduleChange()
    }

    fun updateSchedule(event: ScheduleEvent) {
        scheduleStore.update(event)
        afterScheduleChange()
    }

    fun deleteScheduleEvent(id: String) {
        scheduleStore.delete(id)
        afterScheduleChange()
    }

    fun toggleScheduleDone(id: String, date: String) {
        scheduleStore.toggleDone(id, date)
    }

    fun sendTestNotification() {
        ReminderScheduler.sendTestNotification(appContext)
    }

    private fun afterScheduleChange() {
        NotificationHelper.ensureChannels(appContext)
        ReminderScheduler.ensureDaily(appContext)
    }

    // ────────────────────────────────────────────────────────────────────────
    // McpHost：读
    // ────────────────────────────────────────────────────────────────────────

    override fun serverVersion(): String = BuildConfig.VERSION_NAME

    override fun briefing(): JSONObject {
        val weather = _home.value.weather
        val root = JSONObject()
        root.put("date", LocalDate.now().toString())
        root.put("generated_at", System.currentTimeMillis())
        root.put("city", weather?.city ?: _settings.value.city)
        root.put("weather", weatherJson(weather))
        root.put("weather_insight", _home.value.weatherInsight.content)
        root.put("daily_brief", _home.value.dailyBrief.content)
        root.put("sources", plugins.value.size)
        root.put("mcp_endpoint", mcpState.value.endpoint)

        val sections = JSONArray()
        _home.value.sections.filter { it.items.isNotEmpty() }.forEach { section ->
            sections.put(JSONObject().apply {
                put("category", section.category.name)
                put("label", section.category.label)
                put("updated_at", section.updatedAt)
                put("count", section.items.size)
                put("items", JSONArray().apply {
                    section.items.take(8).forEach { put(itemJson(it)) }
                })
            })
        }
        root.put("sections", sections)

        root.put("schedules", JSONArray().apply {
            upcomingSchedules(8).forEach { put(scheduleJson(it)) }
        })
        return root
    }

    override fun weather(): JSONObject? = weatherJson(_home.value.weather)

    private fun weatherJson(w: WeatherData?): JSONObject {
        if (w == null) return JSONObject().apply { put("available", false) }
        return JSONObject().apply {
            put("available", true)
            put("city", w.city)
            put("updated_at", w.updatedAt)
            put("condition", w.condition)
            put("weather_code", w.weatherCode)
            put("temperature", w.currentTemp)
            put("feels_like", w.feelsLike)
            put("high", w.high)
            put("low", w.low)
            put("yesterday_high", w.yesterdayHigh ?: JSONObject.NULL)
            put("yesterday_low", w.yesterdayLow ?: JSONObject.NULL)
            put("high_delta", w.highDelta ?: JSONObject.NULL)
            put("low_delta", w.lowDelta ?: JSONObject.NULL)
            put("humidity", w.humidity)
            put("wind_kmh", w.windSpeed)
            put("wind_direction", w.windDirection)
            put("precipitation_probability", w.precipProb)
            put("uv_index", w.uvIndex)
            put("sunrise", w.sunrise)
            put("sunset", w.sunset)
            put("is_day", w.isDay)
            w.air?.let { a ->
                put("air", JSONObject().apply {
                    put("pm25", a.pm25)
                    put("us_aqi", a.aqi)
                })
            }
            put("hourly", JSONArray().apply {
                w.hourly.take(12).forEach { h ->
                    put(JSONObject().apply {
                        put("time", h.time)
                        put("temperature", h.temp)
                        put("weather_code", h.code)
                        put("precipitation_probability", h.precipProb)
                    })
                }
            })
            put("daily", JSONArray().apply {
                w.daily.take(7).forEach { d ->
                    put(JSONObject().apply {
                        put("date", d.date)
                        put("weather_code", d.code)
                        put("high", d.high)
                        put("low", d.low)
                        put("precipitation_probability", d.precipProb)
                    })
                }
            })
        }
    }

    override fun news(category: String?, limit: Int): JSONArray {
        val capped = limit.coerceIn(1, 40)
        val wanted = category?.takeIf { it.isNotBlank() }?.let { raw ->
            Category.entries.firstOrNull { it.name.equals(raw, true) || it.label == raw }
                ?: throw IllegalArgumentException("未知分类：$category")
        }
        val out = JSONArray()
        val sections = if (wanted == null) _home.value.sections
        else _home.value.sections.filter { it.category == wanted }
        for (section in sections) {
            for (item in section.items) {
                if (out.length() >= capped) return out
                out.put(itemJson(item))
            }
        }
        return out
    }

    private fun itemJson(item: NewsItem): JSONObject = JSONObject().apply {
        put("title", item.title)
        put("url", item.url)
        put("source", item.source)
        put("category", item.category.name)
        put("published_at", item.publishedAt)
        put("hot", item.hotValue)
        put("snippet", item.snippet)
        put("rank", item.rank)
    }

    override fun searchNews(query: String, days: Int): JSONArray {
        val q = query.trim()
        if (q.isEmpty()) throw IllegalArgumentException("query 不能为空")
        val span = days.coerceIn(1, 365)
        val from = LocalDate.now().minusDays((span - 1).toLong())
        val list = recordsFor(from, LocalDate.now())
            .filter { it.title.contains(q, true) || it.snippet.contains(q, true) }
            .takeLast(60)
        return JSONArray().apply {
            list.forEach { r ->
                put(JSONObject().apply {
                    put("title", r.title)
                    put("source", r.source)
                    put("category", r.category.name)
                    put("url", r.url)
                    put("published_at", r.publishedAt)
                    put("first_seen_at", r.firstSeenAt)
                    put("snippet", r.snippet)
                })
            }
        }
    }

    override fun listSchedules(): JSONArray = JSONArray().apply {
        schedules.value.sortedBy { Recurrence.nextOccurrence(it)?.toEpochDay() ?: Long.MAX_VALUE }
            .forEach { put(scheduleJson(it)) }
    }

    private fun upcomingSchedules(limit: Int): List<ScheduleEvent> =
        schedules.value
            .sortedBy { Recurrence.nextOccurrence(it)?.toEpochDay() ?: Long.MAX_VALUE }
            .take(limit)

    private fun scheduleJson(event: ScheduleEvent): JSONObject = JSONObject().apply {
        put("id", event.id)
        put("title", event.title)
        put("notes", event.notes)
        put("date", event.startDate)
        put("time", event.time ?: JSONObject.NULL)
        put("priority", event.priority.name)
        put("kind", event.kind.name)
        put("repeat", event.repeat.name)
        put("done_dates", JSONArray(event.doneDates))
        val next = Recurrence.nextOccurrence(event)
        put("next_occurrence", next?.toString() ?: JSONObject.NULL)
        put("days_until", Recurrence.daysUntil(event) ?: JSONObject.NULL)
    }

    override fun listSources(): JSONArray = JSONArray().apply {
        plugins.value.forEach { plugin ->
            put(JSONObject().apply {
                put("id", plugin.id)
                put("name", plugin.name)
                put("category", plugin.category.name)
                put("format", plugin.format.name.lowercase())
                put("url", plugin.url)
                put("enabled", plugin.enabled)
                put("built_in", plugin.builtIn)
                put("max_items", plugin.maxItems)
                put("filter_entertainment", plugin.filterEntertainment)
            })
        }
    }

    override fun records(period: String): JSONArray {
        val summaryPeriod = SummaryPeriod.entries.firstOrNull { it.name.equals(period, true) }
            ?: throw IllegalArgumentException("period 必须是 DAY/WEEK/MONTH/QUARTER/HALF_YEAR/YEAR")
        val today = LocalDate.now()
        val list = recordsFor(summaryPeriod.startDate(today), today)
            .sortedByDescending { it.firstSeenAt }
            .take(400)
        return JSONArray().apply {
            list.forEach { r ->
                put(JSONObject().apply {
                    put("title", r.title)
                    put("source", r.source)
                    put("category", r.category.name)
                    put("url", r.url)
                    put("published_at", r.publishedAt)
                    put("first_seen_at", r.firstSeenAt)
                    put("snippet", r.snippet)
                })
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // McpHost：写
    // ────────────────────────────────────────────────────────────────────────

    override suspend fun createSchedule(args: JSONObject): JSONObject {
        val title = args.optString("title").trim()
        if (title.isBlank()) throw IllegalArgumentException("title 不能为空")
        val date = runCatching { LocalDate.parse(args.optString("date")) }.getOrNull()
            ?: throw IllegalArgumentException("date 必须是 yyyy-MM-dd")
        val kind = EventKind.parse(args.optString("kind"))
        val repeat = if (kind == EventKind.BIRTHDAY || kind == EventKind.ANNIVERSARY) {
            RepeatRule.YEARLY
        } else RepeatRule.parse(args.optString("repeat"))
        val event = ScheduleEvent(
            id = newId(),
            title = title,
            notes = args.optString("notes").trim(),
            startDate = date.toString(),
            time = normalizeTime(args.optString("time")),
            priority = Priority.parse(args.optString("priority")),
            kind = kind,
            repeat = repeat,
        )
        scheduleStore.add(event)
        afterScheduleChange()
        return scheduleJson(event)
    }

    override suspend fun updateSchedule(args: JSONObject): JSONObject {
        val id = args.optString("id").trim()
        val old = scheduleStore.get(id) ?: throw IllegalArgumentException("找不到 id=$id 的日程")
        var next = old
        if (args.has("title") && args.optString("title").isNotBlank()) next = next.copy(title = args.optString("title").trim())
        if (args.has("date")) {
            val date = runCatching { LocalDate.parse(args.optString("date")) }.getOrNull()
                ?: throw IllegalArgumentException("date 必须是 yyyy-MM-dd")
            next = next.copy(startDate = date.toString())
        }
        if (args.has("time")) next = next.copy(time = normalizeTime(args.optString("time")))
        if (args.has("priority")) next = next.copy(priority = Priority.parse(args.optString("priority")))
        if (args.has("kind")) next = next.copy(kind = EventKind.parse(args.optString("kind")))
        if (args.has("repeat")) next = next.copy(repeat = RepeatRule.parse(args.optString("repeat")))
        if (args.has("notes")) next = next.copy(notes = args.optString("notes").trim())
        if (next.kind == EventKind.BIRTHDAY || next.kind == EventKind.ANNIVERSARY) {
            next = next.copy(repeat = RepeatRule.YEARLY)
        }
        scheduleStore.update(next)
        afterScheduleChange()
        return scheduleJson(next)
    }

    override suspend fun deleteSchedule(id: String): Boolean {
        val exists = scheduleStore.get(id) != null
        if (exists) {
            scheduleStore.delete(id)
            afterScheduleChange()
        }
        return exists
    }

    override suspend fun addSource(pluginJson: String): JSONObject {
        val result = pluginStore.saveJson(pluginJson)
        val plugin = result.getOrElse { throw IllegalArgumentException(it.message ?: "插件保存失败") }
        triggerContentRefresh()
        return JSONObject(plugin.toJson())
    }

    override suspend fun removeSource(id: String): Boolean {
        if (pluginStore.get(id) == null) return false
        pluginStore.delete(id)
        triggerContentRefresh()
        return true
    }

    override suspend fun setSourceEnabled(id: String, enabled: Boolean): Boolean {
        if (pluginStore.get(id) == null) return false
        pluginStore.setEnabled(id, enabled)
        triggerContentRefresh()
        return true
    }

    override suspend fun refreshContent(): String {
        triggerContentRefresh()
        val job = contentJob
        val finished = if (job != null) {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) { job.join() } != null
        } else false
        return if (finished) "刷新完成，可以读取最新内容了。" else "已开始刷新，但抓取仍在进行；稍后再读取。"
    }

    override suspend fun setCity(city: String): String {
        val query = city.trim()
        if (query.isEmpty()) throw IllegalArgumentException("city 不能为空")
        val result = weatherClient.searchCity(query).firstOrNull()
            ?: throw IllegalArgumentException("没有找到城市「$query」")
        mutateSettings {
            it.copy(
                city = result.name,
                latitude = result.latitude,
                longitude = result.longitude,
                timezone = result.timezone.ifBlank { it.timezone },
                autoLocation = false,
            )
        }
        _home.update { it.copy(weatherInsight = AiText()) }
        refreshWeather()
        triggerContentRefresh()
        return "已把城市改为「${result.display}」，正在更新天气与内容。"
    }

    override suspend fun setReminderTime(hour: Int, minute: Int): String {
        if (hour !in 0..23 || minute !in 0..59) {
            throw IllegalArgumentException("hour 0-23，minute 0-59")
        }
        val total = hour * 60 + minute
        mutateSettings { it.copy(reminderMinuteOfDay = total) }
        ReminderScheduler.reschedule(appContext, total)
        return "已把日程提醒时间改为 %02d:%02d".format(hour, minute)
    }

    override suspend fun saveWeatherInsight(text: String): Boolean {
        val content = text.trim()
        if (content.length < 10) throw IllegalArgumentException("text 太短")
        if (content.length > 6000) throw IllegalArgumentException("text 不能超过 6000 字")
        val city = _home.value.weather?.city ?: _settings.value.city
        prefs.putAiCache("weather_${city}_${LocalDate.now()}", content)
        _home.update {
            it.copy(weatherInsight = AiText(content = content, generatedAt = System.currentTimeMillis()))
        }
        return true
    }

    override suspend fun saveDailyBrief(text: String): Boolean {
        val content = text.trim()
        if (content.length < 10) throw IllegalArgumentException("text 太短")
        if (content.length > 8000) throw IllegalArgumentException("text 不能超过 8000 字")
        prefs.putAiCache("daily_${LocalDate.now()}", content)
        _home.update {
            it.copy(dailyBrief = AiText(content = content, generatedAt = System.currentTimeMillis()))
        }
        return true
    }

    override suspend fun savePeriodSummary(period: String, content: String): JSONObject {
        val summaryPeriod = SummaryPeriod.entries.firstOrNull { it.name.equals(period, true) }
            ?: throw IllegalArgumentException("period 必须是 DAY/WEEK/MONTH/QUARTER/HALF_YEAR/YEAR")
        val text = content.trim()
        if (text.length < 20) throw IllegalArgumentException("content 太短")
        if (text.length > 12000) throw IllegalArgumentException("content 不能超过 12000 字")
        val today = LocalDate.now()
        val range = summaryPeriod.rangeText(today)
        val digest = AiDigest(
            period = summaryPeriod,
            key = summaryPeriod.key(today),
            rangeText = range,
            content = text,
            itemCount = recordsFor(summaryPeriod.startDate(today), today).size,
            createdAt = System.currentTimeMillis(),
            source = AiDigest.SOURCE_MCP,
        )
        updateDigest(digest)
        return JSONObject().apply {
            put("period", digest.period.name)
            put("range", digest.rangeText)
            put("item_count", digest.itemCount)
            put("created_at", digest.createdAt)
        }
    }

    private fun updateDigest(digest: AiDigest) {
        _digests.update { it + (digest.period to digest) }
        prefs.saveDigests(_digests.value)
    }

    // ────────────────────────────────────────────────────────────────────────
    // 记录与内部工具
    // ────────────────────────────────────────────────────────────────────────

    private fun recordHistory(sections: List<NewsSection>) {
        val now = System.currentTimeMillis()
        val existing = _records.value.associateBy { it.id }.toMutableMap()
        sections.forEach { section ->
            section.items.forEach { item ->
                val id = stableId(item.category.name, item.title)
                val old = existing[id]
                existing[id] = NewsRecord(
                    id = id,
                    title = item.title,
                    snippet = item.snippet.take(200),
                    source = item.source,
                    category = item.category,
                    url = item.url,
                    publishedAt = item.publishedAt,
                    firstSeenAt = old?.firstSeenAt ?: now,
                )
            }
        }
        val trimmed = existing.values.sortedByDescending { it.firstSeenAt }.take(6000)
        _records.value = trimmed
        prefs.saveRecords(trimmed)
    }

    fun recordsFor(start: LocalDate, end: LocalDate): List<NewsRecord> {
        val zone = ZoneId.systemDefault()
        return _records.value.filter { record ->
            val date = Instant.ofEpochMilli(record.firstSeenAt).atZone(zone).toLocalDate()
            !date.isBefore(start) && !date.isAfter(end)
        }
    }

    private fun mutateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(settingsLock) {
            val updated = transform(_settings.value)
            _settings.value = updated
            prefs.saveSettings(updated)
            return updated
        }
    }

    private fun buildInitialHome(): HomeState {
        val wallpaper = prefs.loadWallpaper()?.takeIf { File(it.path).length() > 30_000 }
        val sections = prefs.loadSectionsCache()
        val weather = prefs.loadWeatherCache()?.takeIf {
            !initialSettings.autoLocation || initialSettings.lastLocationAt > 0L
        }
        val today = todayString()
        val weatherAi = weather?.let { w ->
            prefs.aiCache("weather_${w.city}_$today")?.takeIf { it.isNotBlank() }?.let {
                AiText(content = it, generatedAt = System.currentTimeMillis())
            }
        } ?: AiText()
        val brief = prefs.aiCache("daily_$today")?.takeIf { it.isNotBlank() }?.let {
            AiText(content = it, generatedAt = System.currentTimeMillis())
        } ?: AiText()
        return HomeState(
            wallpaper = wallpaper,
            weather = weather,
            sections = sections,
            loading = sections.isEmpty() && weather == null,
            lastUpdated = sections.maxOfOrNull { it.updatedAt } ?: 0L,
            weatherInsight = weatherAi,
            dailyBrief = brief,
        )
    }

    private fun todayString(): String = LocalDate.now().toString()

    private fun isWallpaperStale(): Boolean {
        val wall = _home.value.wallpaper ?: return true
        val todayCompact = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        return wall.date != todayCompact && wall.date != todayString()
    }

    private fun normalizeTime(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value == "null" || value == "全天") return null
        return runCatching {
            val t = LocalTime.parse(value)
            "%02d:%02d".format(t.hour, t.minute)
        }.getOrNull()
    }

    private fun friendlyError(t: Throwable): String = when (t) {
        is HttpException -> t.message ?: "网络请求失败"
        is UnknownHostException -> "网络不可用，请检查连接"
        is SocketTimeoutException -> "请求超时，已保留上次数据"
        else -> t.message?.takeIf { it.isNotBlank() } ?: "加载失败，请稍后重试"
    }

    private fun ensureMcpToken(settings: AppSettings): AppSettings {
        if (settings.mcpToken.length >= 24) return settings
        return settings.copy(mcpToken = randomToken())
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
