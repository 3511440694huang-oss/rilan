package app.rilan.daily.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** 所有持久化都走这里；不依赖反射，R8 下也安全。 */
object JsonCodec {

    // ── settings ────────────────────────────────────────────────────────────
    fun encodeSettings(s: AppSettings): String = JSONObject().apply {
        put("city", s.city)
        put("place", s.place)
        put("latitude", s.latitude)
        put("longitude", s.longitude)
        put("timezone", s.timezone)
        put("reminderMinuteOfDay", s.reminderMinuteOfDay)
        put("dailyNoticeEnabled", s.dailyNoticeEnabled)
        put("dailyNoticeMinuteOfDay", s.dailyNoticeMinuteOfDay)
        put("autoStartEnabled", s.autoStartEnabled)
        put("blockedWords", JSONArray(s.blockedWords))
        put("lastWeatherAt", s.lastWeatherAt)
        put("lastContentAt", s.lastContentAt)
        put("onboardingDone", s.onboardingDone)
        put("autoLocation", s.autoLocation)
        put("locationAsked", s.locationAsked)
        put("lastLocationAt", s.lastLocationAt)
        put("weiboHotEnabled", s.weiboHotEnabled)
        put("bilibiliHotEnabled", s.bilibiliHotEnabled)
        put("douyinHotEnabled", s.douyinHotEnabled)
        put("mcpEnabled", s.mcpEnabled)
        put("mcpPort", s.mcpPort)
        put("mcpToken", s.mcpToken)
        put("deepseekApiKey", s.deepseekApiKey)
        put("deepseekModel", s.deepseekModel)
        put("summaryMode", s.summaryMode.name)
        put("feeds", JSONArray().apply {
            s.feeds.forEach { put(encodeFeed(it)) }
        })
    }.toString()

    fun decodeSettings(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            val feeds = mutableListOf<FeedSource>()
            o.optJSONArray("feeds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    decodeFeed(arr.optJSONObject(i))?.let(feeds::add)
                }
            }
            val words = mutableListOf<String>()
            o.optJSONArray("blockedWords")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(words::add)
            }
            AppSettings(
                city = o.optString("city", "北京"),
                place = o.optString("place"),
                latitude = o.optDouble("latitude", 39.9042),
                longitude = o.optDouble("longitude", 116.4074),
                timezone = o.optString("timezone", "Asia/Shanghai"),
                reminderMinuteOfDay = o.optInt("reminderMinuteOfDay", 8 * 60 + 30),
                // 旧版本没有这三个字段：默认关闭每日简报、08:00、不自启动。
                dailyNoticeEnabled = o.optBoolean("dailyNoticeEnabled", false),
                dailyNoticeMinuteOfDay = o.optInt("dailyNoticeMinuteOfDay", 8 * 60)
                    .coerceIn(0, 24 * 60 - 1),
                autoStartEnabled = o.optBoolean("autoStartEnabled", false),
                feeds = feeds.ifEmpty { FeedSource.DEFAULTS },
                blockedWords = words,
                lastWeatherAt = o.optLong("lastWeatherAt", 0L),
                lastContentAt = o.optLong("lastContentAt", 0L),
                onboardingDone = o.optBoolean("onboardingDone", false),
                autoLocation = o.optBoolean("autoLocation", true),
                locationAsked = o.optBoolean("locationAsked", false),
                lastLocationAt = o.optLong("lastLocationAt", 0L),
                weiboHotEnabled = o.optBoolean("weiboHotEnabled", true),
                bilibiliHotEnabled = o.optBoolean("bilibiliHotEnabled", true),
                douyinHotEnabled = o.optBoolean("douyinHotEnabled", true),
                mcpEnabled = o.optBoolean("mcpEnabled", false),
                mcpPort = o.optInt("mcpPort", 8765).coerceIn(1024, 65535),
                mcpToken = o.optString("mcpToken"),
                deepseekApiKey = o.optString("deepseekApiKey"),
                deepseekModel = o.optString("deepseekModel", "deepseek-flash")
                    .takeIf { it in DEEPSEEK_MODEL_IDS } ?: "deepseek-flash",
                // 旧版本没有这个字段，落到默认的 MCP 写回。
                summaryMode = SummaryMode.parse(o.optString("summaryMode")),
            )
        }.getOrElse { AppSettings() }
    }

    private fun encodeFeed(f: FeedSource): JSONObject = JSONObject().apply {
        put("id", f.id)
        put("name", f.name)
        put("url", f.url)
        put("category", f.category.name)
        put("enabled", f.enabled)
        put("builtIn", f.builtIn)
    }

    private fun decodeFeed(o: JSONObject?): FeedSource? {
        if (o == null) return null
        val url = o.optString("url")
        if (url.isBlank()) return null
        return FeedSource(
            id = o.optString("id", stableId(url)),
            name = o.optString("name", "RSS"),
            url = url,
            category = Category.parse(o.optString("category")),
            enabled = o.optBoolean("enabled", true),
            builtIn = o.optBoolean("builtIn", false),
        )
    }

    // ── schedule ───────────────────────────────────────────────────────────
    fun encodeSchedules(list: List<ScheduleEvent>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("notes", e.notes)
                put("startDate", e.startDate)
                put("time", e.time ?: JSONObject.NULL)
                put("priority", e.priority.name)
                put("kind", e.kind.name)
                put("repeat", e.repeat.name)
                put("doneDates", JSONArray(e.doneDates))
                put("createdAt", e.createdAt)
            })
        }
    }.toString()

    fun decodeSchedules(raw: String?): List<ScheduleEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val done = mutableListOf<String>()
                o.optJSONArray("doneDates")?.let { d ->
                    for (j in 0 until d.length()) done.add(d.optString(j))
                }
                ScheduleEvent(
                    id = o.optString("id", newId()),
                    title = o.optString("title", "未命名日程"),
                    notes = o.optString("notes"),
                    startDate = o.optString("startDate"),
                    time = o.optString("time").takeIf { it.isNotBlank() && it != "null" },
                    priority = Priority.parse(o.optString("priority")),
                    kind = EventKind.parse(o.optString("kind")),
                    repeat = RepeatRule.parse(o.optString("repeat")),
                    doneDates = done,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            }
        }.getOrElse { emptyList() }
    }

    // ── chat ───────────────────────────────────────────────────────────────
    fun encodeChat(list: List<ChatMessage>): String = JSONArray().apply {
        list.takeLast(120).forEach { m ->
            put(JSONObject().apply {
                put("id", m.id)
                put("role", m.role.name)
                put("content", m.content)
                put("createdAt", m.createdAt)
                put("failed", m.failed)
                put("actions", JSONArray(m.actionLog))
            })
        }
    }.toString()

    fun decodeChat(raw: String?): List<ChatMessage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val acts = mutableListOf<String>()
                o.optJSONArray("actions")?.let { a ->
                    for (j in 0 until a.length()) acts.add(a.optString(j))
                }
                ChatMessage(
                    id = o.optString("id", newId()),
                    role = if (o.optString("role") == ChatRole.USER.name) ChatRole.USER else ChatRole.ASSISTANT,
                    content = o.optString("content"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    actionLog = acts,
                    failed = o.optBoolean("failed", false),
                )
            }
        }.getOrElse { emptyList() }
    }

    // ── records ────────────────────────────────────────────────────────────
    fun encodeRecords(list: List<NewsRecord>): String = JSONArray().apply {
        list.forEach { r ->
            put(JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("snippet", r.snippet)
                put("source", r.source)
                put("category", r.category.name)
                put("url", r.url)
                put("publishedAt", r.publishedAt)
                put("firstSeenAt", r.firstSeenAt)
            })
        }
    }.toString()

    fun decodeRecords(raw: String?): List<NewsRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                NewsRecord(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    snippet = o.optString("snippet"),
                    source = o.optString("source"),
                    category = Category.parse(o.optString("category")),
                    url = o.optString("url"),
                    publishedAt = o.optLong("publishedAt"),
                    firstSeenAt = o.optLong("firstSeenAt"),
                )
            }
        }.getOrElse { emptyList() }
    }

    // ── sections cache ─────────────────────────────────────────────────────
    fun encodeSections(list: List<NewsSection>): String = JSONArray().apply {
        list.forEach { s ->
            put(JSONObject().apply {
                put("category", s.category.name)
                put("updatedAt", s.updatedAt)
                put("error", s.error ?: JSONObject.NULL)
                put("items", JSONArray().apply {
                    s.items.forEach { put(encodeItem(it)) }
                })
            })
        }
    }.toString()

    fun decodeSections(raw: String?): List<NewsSection> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val items = mutableListOf<NewsItem>()
                o.optJSONArray("items")?.let { a ->
                    for (j in 0 until a.length()) decodeItem(a.optJSONObject(j))?.let(items::add)
                }
                NewsSection(
                    category = Category.parse(o.optString("category")),
                    // 旧版本写下的缓存可能带重复条目；同一板块内 id 必须唯一，
                    // 否则信息流 LazyColumn 的 key 会撞车、整页崩掉。
                    items = items.distinctBy { it.id },
                    updatedAt = o.optLong("updatedAt"),
                    error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }.getOrElse { emptyList() }
    }

    fun encodeItem(n: NewsItem): JSONObject = JSONObject().apply {
        put("id", n.id)
        put("title", n.title)
        put("url", n.url)
        put("source", n.source)
        put("category", n.category.name)
        put("publishedAt", n.publishedAt)
        put("snippet", n.snippet)
        put("hotValue", n.hotValue)
        put("rank", n.rank)
    }

    fun decodeItem(o: JSONObject?): NewsItem? {
        if (o == null) return null
        val title = o.optString("title")
        if (title.isBlank()) return null
        return NewsItem(
            id = o.optString("id", stableId(title)),
            title = title,
            url = o.optString("url"),
            source = o.optString("source"),
            category = Category.parse(o.optString("category")),
            publishedAt = o.optLong("publishedAt"),
            snippet = o.optString("snippet"),
            hotValue = o.optString("hotValue"),
            rank = o.optInt("rank"),
        )
    }

    // ── weather ────────────────────────────────────────────────────────────
    fun encodeWeather(w: WeatherData): String = JSONObject().apply {
        put("city", w.city)
        put("place", w.place)
        put("updatedAt", w.updatedAt)
        put("currentTemp", w.currentTemp)
        put("feelsLike", w.feelsLike)
        put("humidity", w.humidity)
        put("windSpeed", w.windSpeed)
        put("windDirection", w.windDirection)
        put("weatherCode", w.weatherCode)
        put("isDay", w.isDay)
        put("high", w.high)
        put("low", w.low)
        w.yesterdayHigh?.let { put("yesterdayHigh", it) }
        w.yesterdayLow?.let { put("yesterdayLow", it) }
        put("precipProb", w.precipProb)
        put("uvIndex", w.uvIndex)
        put("sunrise", w.sunrise)
        put("sunset", w.sunset)
        put("hourly", JSONArray().apply {
            w.hourly.forEach { h ->
                put(JSONObject().apply {
                    put("time", h.time); put("temp", h.temp)
                    put("code", h.code); put("precipProb", h.precipProb)
                })
            }
        })
        put("daily", JSONArray().apply {
            w.daily.forEach { d ->
                put(JSONObject().apply {
                    put("date", d.date); put("code", d.code)
                    put("high", d.high); put("low", d.low)
                    put("precipProb", d.precipProb); put("uvIndex", d.uvIndex)
                    put("sunrise", d.sunrise); put("sunset", d.sunset)
                })
            }
        })
        w.air?.let { a -> put("air", JSONObject().apply { put("pm25", a.pm25); put("aqi", a.aqi) }) }
    }.toString()

    fun decodeWeather(raw: String?): WeatherData? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val hourly = mutableListOf<HourlyWeather>()
            o.optJSONArray("hourly")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val h = arr.optJSONObject(i) ?: continue
                    hourly.add(HourlyWeather(h.optString("time"), h.optDouble("temp"), h.optInt("code"), h.optInt("precipProb")))
                }
            }
            val daily = mutableListOf<DailyWeather>()
            o.optJSONArray("daily")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    daily.add(
                        DailyWeather(
                            d.optString("date"), d.optInt("code"), d.optDouble("high"), d.optDouble("low"),
                            d.optInt("precipProb"), d.optDouble("uvIndex"), d.optString("sunrise"), d.optString("sunset"),
                        )
                    )
                }
            }
            val air = o.optJSONObject("air")?.let { AirQuality(it.optDouble("pm25"), it.optInt("aqi")) }
            WeatherData(
                city = o.optString("city", "北京"),
                place = o.optString("place"),
                updatedAt = o.optLong("updatedAt"),
                currentTemp = o.optDouble("currentTemp"),
                feelsLike = o.optDouble("feelsLike"),
                humidity = o.optInt("humidity"),
                windSpeed = o.optDouble("windSpeed"),
                windDirection = o.optInt("windDirection"),
                weatherCode = o.optInt("weatherCode"),
                isDay = o.optBoolean("isDay", true),
                high = o.optDouble("high"),
                low = o.optDouble("low"),
                yesterdayHigh = if (o.has("yesterdayHigh")) o.optDouble("yesterdayHigh") else null,
                yesterdayLow = if (o.has("yesterdayLow")) o.optDouble("yesterdayLow") else null,
                precipProb = o.optInt("precipProb"),
                uvIndex = o.optDouble("uvIndex"),
                sunrise = o.optString("sunrise"),
                sunset = o.optString("sunset"),
                hourly = hourly,
                daily = daily,
                air = air,
            )
        }.getOrNull()
    }

    // ── wallpaper ──────────────────────────────────────────────────────────
    fun encodeWallpaper(w: WallpaperInfo): String = JSONObject().apply {
        put("path", w.path); put("title", w.title); put("copyright", w.copyright)
        put("date", w.date); put("pageUrl", w.pageUrl)
    }.toString()

    fun decodeWallpaper(raw: String?): WallpaperInfo? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            WallpaperInfo(o.optString("path"), o.optString("title"), o.optString("copyright"), o.optString("date"), o.optString("pageUrl"))
        }.getOrNull()?.takeIf { it.path.isNotBlank() }
    }

    // ── digest ─────────────────────────────────────────────────────────────
    fun encodeDigests(map: Map<SummaryPeriod, AiDigest>): String = JSONArray().apply {
        map.values.forEach { d ->
            put(JSONObject().apply {
                put("period", d.period.name); put("key", d.key); put("range", d.rangeText)
                put("content", d.content); put("count", d.itemCount); put("createdAt", d.createdAt)
                put("source", d.source)
                put("error", d.error ?: JSONObject.NULL)
            })
        }
    }.toString()

    fun decodeDigests(raw: String?): Map<SummaryPeriod, AiDigest> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val map = mutableMapOf<SummaryPeriod, AiDigest>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val period = SummaryPeriod.entries.firstOrNull { it.name == o.optString("period") } ?: continue
                map[period] = AiDigest(
                    period = period,
                    key = o.optString("key"),
                    rangeText = o.optString("range"),
                    content = o.optString("content"),
                    itemCount = o.optInt("count"),
                    createdAt = o.optLong("createdAt"),
                    error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
                    // 旧数据没有 source 字段，按 MCP 写回处理。
                    source = o.optString("source")
                        .takeIf { it.isNotBlank() }
                        ?: AiDigest.SOURCE_MCP,
                )
            }
            map
        }.getOrElse { emptyMap() }
    }
}

fun newId(): String = UUID.randomUUID().toString()

fun stableId(vararg parts: String): String {
    val raw = parts.joinToString("|")
    val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(20)
}
