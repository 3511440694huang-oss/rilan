package app.rilan.daily.data

import android.content.Context
import app.rilan.daily.core.Prefs
import app.rilan.daily.core.WallpaperInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException

/**
 * 必应每日壁纸。
 *
 * - 图片按「设备当天」命名：wallpaper_YYYYMMDD.jpg，同一天复用，避免重复下载。
 * - 下载先写 .tmp 再 rename，中途失败不会留下半张图。
 * - 只落盘不解码，省内存；旧图保留最新 2 张。
 * - 任何失败都返回 null（不抛），让首页安静地退回纯色背景。
 */
class WallpaperClient(context: Context) {

    private val app = context.applicationContext
    private val prefs = Prefs(app)

    suspend fun loadDaily(force: Boolean = false): WallpaperInfo? = withContext(Dispatchers.IO) {
        try {
            load(force)
        } catch (e: CancellationException) {
            // 协程取消要继续往上抛，否则会让上层作用域卡住
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun load(force: Boolean): WallpaperInfo? {
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val target = File(app.filesDir, "wallpaper_$today.jpg")
        val cached = prefs.loadWallpaper()

        if (!force && target.isFile && target.length() > MIN_BYTES) {
            return cached?.takeIf { it.path == target.absolutePath }
                ?: WallpaperInfo(
                    path = target.absolutePath,
                    title = FALLBACK_TITLE,
                    copyright = "",
                    date = today,
                )
        }

        val meta = fetchMeta(today) ?: return null
        val bytes = try {
            Http.getBytes(meta.imageUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        // 太小基本是错误页 / 占位图，存下来下次也会被判为无效，直接放弃
        if (bytes.size < MIN_BYTES) return null

        val tmp = File(app.filesDir, "${target.name}.tmp")
        try {
            tmp.writeBytes(bytes)
        } catch (e: Exception) {
            tmp.delete()
            return null
        }
        if (target.isFile && !target.delete()) {
            tmp.delete()
            return null
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return null
        }

        // 收尾的清理与落盘都不该影响“图已经有了”这件事
        runCatching { prune() }

        val info = WallpaperInfo(
            path = target.absolutePath,
            title = meta.title,
            copyright = meta.copyright,
            date = meta.date,
            pageUrl = meta.pageUrl,
        )
        runCatching { prefs.saveWallpaper(info) }
        return info
    }

    private suspend fun fetchMeta(today: String): ImageMeta? {
        val body = try {
            Http.getText(BING_URL, headers = mapOf("Accept" to "application/json"))
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
        val images = root.optJSONArray("images") ?: return null
        val image = images.optJSONObject(0) ?: return null

        val raw = image.optString("url").trim()
        if (raw.isEmpty()) return null
        // 接口给的是相对路径，可能已经带 _1920x1080
        val imageUrl = when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("/") -> BING_HOST + raw
            else -> "$BING_HOST/$raw"
        }

        val start = image.optString("startdate").trim()
        return ImageMeta(
            imageUrl = imageUrl,
            title = image.optString("title").trim().ifEmpty { FALLBACK_TITLE },
            copyright = image.optString("copyright").trim(),
            date = if (start.length == 8 && start.all { it.isDigit() }) start else today,
            pageUrl = image.optString("copyrightlink").trim(),
        )
    }

    /** 只保留最新 2 张（今天 + 昨天），顺手清掉残留的 .tmp */
    private fun prune() {
        val files = app.filesDir.listFiles() ?: return
        files.filter { it.isFile && IMAGE_NAME.matches(it.name) }
            .sortedByDescending { it.name }
            .drop(KEEP_COUNT)
            .forEach { it.delete() }
        files.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".jpg.tmp") }
            .forEach { it.delete() }
    }

    private data class ImageMeta(
        val imageUrl: String,
        val title: String,
        val copyright: String,
        val date: String,
        val pageUrl: String,
    )

    private companion object {
        const val BING_HOST = "https://www.bing.com"
        const val BING_URL =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"
        const val PREFIX = "wallpaper_"
        const val FALLBACK_TITLE = "必应每日壁纸"
        const val MIN_BYTES = 30 * 1024
        const val KEEP_COUNT = 2
        val IMAGE_NAME = Regex("^wallpaper_\\d{8}\\.jpg$")
    }
}
