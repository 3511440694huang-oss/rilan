package app.rilan.daily.core

import android.content.Context
import java.io.File

/**
 * 轻量本地存储。结构化小数据放 SharedPreferences；可能长大的历史与缓存放 filesDir。
 * 所有内容只保存在应用私有目录，不写日志、不上传。
 */
class Prefs(context: Context) {

    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("rilan_prefs", Context.MODE_PRIVATE)
    private val recordsFile = File(app.filesDir, "history.json")
    private val sectionsFile = File(app.filesDir, "sections.json")

    // ── settings ────────────────────────────────────────────────────────────
    fun loadSettings(): AppSettings = JsonCodec.decodeSettings(sp.getString(KEY_SETTINGS, null))

    fun saveSettings(s: AppSettings) {
        sp.edit().putString(KEY_SETTINGS, JsonCodec.encodeSettings(s)).apply()
    }

    fun loadSchedules(): List<ScheduleEvent> = JsonCodec.decodeSchedules(sp.getString(KEY_SCHEDULES, null))

    fun saveSchedules(list: List<ScheduleEvent>) {
        sp.edit().putString(KEY_SCHEDULES, JsonCodec.encodeSchedules(list)).apply()
    }

    fun loadChat(): List<ChatMessage> = JsonCodec.decodeChat(sp.getString(KEY_CHAT, null))

    fun saveChat(list: List<ChatMessage>) {
        sp.edit().putString(KEY_CHAT, JsonCodec.encodeChat(list)).apply()
    }

    fun loadDigests(): Map<SummaryPeriod, AiDigest> =
        JsonCodec.decodeDigests(sp.getString(KEY_DIGESTS, null))

    fun saveDigests(map: Map<SummaryPeriod, AiDigest>) {
        sp.edit().putString(KEY_DIGESTS, JsonCodec.encodeDigests(map)).apply()
    }

    fun loadWallpaper(): WallpaperInfo? = JsonCodec.decodeWallpaper(sp.getString(KEY_WALLPAPER, null))

    fun saveWallpaper(w: WallpaperInfo) {
        sp.edit().putString(KEY_WALLPAPER, JsonCodec.encodeWallpaper(w)).apply()
    }

    fun loadWeatherCache(): WeatherData? = JsonCodec.decodeWeather(sp.getString(KEY_WEATHER, null))

    fun saveWeatherCache(w: WeatherData) {
        sp.edit().putString(KEY_WEATHER, JsonCodec.encodeWeather(w)).apply()
    }

    fun loadSectionsCache(): List<NewsSection> = JsonCodec.decodeSections(readFile(sectionsFile))

    fun saveSectionsCache(list: List<NewsSection>) {
        writeFile(sectionsFile, JsonCodec.encodeSections(list))
    }

    fun loadRecords(): List<NewsRecord> = JsonCodec.decodeRecords(readFile(recordsFile))

    fun saveRecords(list: List<NewsRecord>) {
        writeFile(recordsFile, JsonCodec.encodeRecords(list))
    }

    // ── AI cache（按天/城市隔离，避免反复消耗 token）────────────────────────
    fun aiCache(key: String): String? = sp.getString("ai_$key", null)

    fun putAiCache(key: String, value: String) {
        sp.edit().putString("ai_$key", value).apply()
    }

    fun removeAiCache(key: String) {
        sp.edit().remove("ai_$key").apply()
    }

    fun lastNotifiedKeys(): Set<String> =
        sp.getStringSet(KEY_NOTIFIED, emptySet())?.toSet().orEmpty()

    fun saveLastNotifiedKeys(keys: Set<String>) {
        // 只保留最近 400 个，避免无限增长
        val trimmed = keys.toList().takeLast(400).toSet()
        sp.edit().putStringSet(KEY_NOTIFIED, trimmed).apply()
    }

    fun clearConversation() {
        sp.edit().remove(KEY_CHAT).apply()
    }

    fun clearAiCaches() {
        val keys = sp.all.keys.filter { it.startsWith("ai_") }
        if (keys.isEmpty()) return
        val editor = sp.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    // ── file helpers ────────────────────────────────────────────────────────
    private fun readFile(file: File): String? = runCatching {
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    private fun writeFile(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_SCHEDULES = "schedules"
        private const val KEY_CHAT = "chat"
        private const val KEY_DIGESTS = "digests"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_WEATHER = "weather_cache"
        private const val KEY_NOTIFIED = "notified_keys"
    }
}
