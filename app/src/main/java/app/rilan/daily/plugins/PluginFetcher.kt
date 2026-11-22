package app.rilan.daily.plugins

import app.rilan.daily.core.FeedSource
import app.rilan.daily.core.NewsItem
import app.rilan.daily.core.stableId
import app.rilan.daily.data.EntertainmentFilter
import app.rilan.daily.data.Http
import app.rilan.daily.data.HttpException
import app.rilan.daily.data.RssParser
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 单个插件的抓取结果：要么有内容，要么有一句可直接展示的中文错误。 */
data class PluginFetchResult(
    val items: List<NewsItem> = emptyList(),
    val error: String? = null,
)

/** 插件配置自身的问题（例如 mapping.items 没指向数组），文案直接给用户看。 */
private class PluginError(message: String) : Exception(message)

/**
 * 按 [SourcePlugin] 的配置抓取内容。
 *
 * - RSS/Atom 交给 [RssParser]，并用 [FeedSource] 包一层，橘鸦早报等按 url 识别的特殊源仍然生效；
 * - JSON 接口按 `mapping` 的点路径取字段，路径支持 `data.list`、`items.0.list`。
 *
 * [fetch] 不向上抛网络 / 解析异常（协程取消除外），失败只返回中文 [PluginFetchResult.error]；
 * 不打印响应正文。
 */
class PluginFetcher {

    suspend fun fetch(
        plugin: SourcePlugin,
        blockedWords: List<String> = emptyList(),
    ): PluginFetchResult = try {
        when (plugin.format) {
            PluginFormat.RSS -> fetchRss(plugin)
            PluginFormat.JSON -> fetchJson(plugin, blockedWords)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Exception) {
        // 网络 / JSON / 配置错误都收成一句中文，上层只处理 PluginFetchResult。
        PluginFetchResult(error = shortError(t))
    }

    // ── RSS / Atom ──────────────────────────────────────────────────────────

    private suspend fun fetchRss(plugin: SourcePlugin): PluginFetchResult {
        // RSS 支持 ETag / Last-Modified：命中 304 时复用上次正文，不重复下载整份 XML。
        val xml = Http.getTextCached(
            url = plugin.url,
            headers = plugin.headers,
            cacheKey = "plugin:${plugin.id}|${plugin.url}",
            connectTimeoutMs = 6_000,
            readTimeoutMs = 10_000,
        )
        // 不必把插件 id 写死进 RssParser：按 url 识别的源也能走对分支。
        val source = FeedSource(plugin.id, plugin.name, plugin.url, plugin.category)
        val items = RssParser.parse(xml, source)
        if (items.isEmpty()) return PluginFetchResult(error = "没有可用的条目")
        return PluginFetchResult(items = items.take(maxItemsOf(plugin)))
    }

    // ── JSON 接口 ───────────────────────────────────────────────────────────

    private suspend fun fetchJson(
        plugin: SourcePlugin,
        blockedWords: List<String>,
    ): PluginFetchResult {
        val body = Http.getText(
            plugin.url,
            plugin.headers,
            connectTimeoutMs = 6_000,
            readTimeoutMs = 9_000,
        )
        val root = parseRoot(body)
        val array = resolveArray(root, plugin.mapping.items)
            ?: throw PluginError(
                if (plugin.mapping.items.isBlank()) "响应根不是数组"
                else "mapping.items 没有指向数组：${plugin.mapping.items}"
            )

        val mapping = plugin.mapping
        val items = ArrayList<NewsItem>(minOf(array.length(), maxItemsOf(plugin)))
        for (index in 0 until array.length()) {
            val node = array.optJSONObject(index) ?: continue

            val title = cut(RssParser.plainText(textAt(node, mapping.title)), TITLE_LIMIT)
            if (title.isEmpty()) continue
            if (plugin.filterEntertainment && EntertainmentFilter.shouldBlock(title, blockedWords)) continue

            val url = buildUrl(textAt(node, mapping.url), mapping.linkPrefix)
            val source = textAt(node, mapping.source).trim().ifEmpty { plugin.name }
            val publishedAt = if (mapping.time.isBlank()) 0L else parseTime(valueAt(node, mapping.time))
            val hot = rawNumber(valueAt(node, mapping.hot))
            val snippet = if (mapping.snippet.isBlank()) {
                ""
            } else {
                cut(RssParser.plainText(textAt(node, mapping.snippet)), SNIPPET_LIMIT)
            }

            items += NewsItem(
                id = stableId(plugin.id, url.ifBlank { title }),
                title = title,
                url = url,
                source = source,
                category = plugin.category,
                publishedAt = publishedAt,
                snippet = snippet,
                hotValue = hot,
            )
        }

        if (items.isEmpty()) return PluginFetchResult(error = "没有可用的条目")

        // 有时间按时间倒序；完全不提供时间的纯热榜保持接口顺序。
        val ordered = if (items.any { it.publishedAt > 0L }) {
            items.sortedByDescending { it.publishedAt }
        } else {
            items
        }
        return PluginFetchResult(
            items = ordered.take(maxItemsOf(plugin)).mapIndexed { index, item ->
                item.copy(rank = index + 1)
            },
        )
    }

    // ── JSON 取值 ───────────────────────────────────────────────────────────

    private fun parseRoot(body: String): Any {
        val text = body.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (text.isEmpty()) throw PluginError("接口返回了空内容")
        if (text.startsWith("<")) throw PluginError("接口返回的不是 JSON")
        return if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    /** 路径为空时用根数组；否则按点路径逐层下钻，任一段支持数组下标。 */
    private fun resolveArray(root: Any, path: String): JSONArray? =
        resolvePath(root, path) as? JSONArray

    private fun resolvePath(root: Any, path: String): Any? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return root
        var current: Any? = root
        for (segment in trimmed.split('.')) {
            if (segment.isEmpty()) return null
            current = when (val holder = current) {
                is JSONObject -> holder.optValue(segment)
                is JSONArray -> {
                    val index = segment.toIntOrNull()
                    if (index == null || index < 0 || index >= holder.length()) null
                    else holder.opt(index).takeUnless { it == JSONObject.NULL }
                }
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    private fun valueAt(node: JSONObject, path: String): Any? {
        if (path.isBlank()) return null
        return resolvePath(node, path)
    }

    private fun textAt(node: JSONObject, path: String): String = when (val value = valueAt(node, path)) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> ""
    }

    private fun JSONObject.optValue(key: String): Any? {
        if (!has(key)) return null
        return opt(key).takeUnless { it == JSONObject.NULL }
    }

    /** url 为空就留空；相对路径拼 linkPrefix；已经是完整 http(s) 的不重复加。 */
    private fun buildUrl(value: String, linkPrefix: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            return raw
        }
        val prefix = linkPrefix.trim()
        return if (prefix.isEmpty()) raw else prefix + raw
    }

    /** 热度保留原始数字字符串，展示时再交给 formatHotValue。 */
    private fun rawNumber(value: Any?): String = when (value) {
        is Number -> {
            val number = value.toDouble()
            if (number.isFinite() && number == Math.floor(number)) number.toLong().toString()
            else value.toString()
        }
        is String -> value.trim()
        else -> ""
    }

    // ── 时间 ────────────────────────────────────────────────────────────────

    private fun parseTime(value: Any?): Long {
        val text = when (value) {
            is Number -> value.toString()
            is String -> value.trim()
            else -> return 0L
        }
        if (text.isEmpty()) return 0L

        val numeric = text.toDoubleOrNull()
        if (numeric != null && numeric.isFinite() && numeric > 0.0) {
            // 10 位是秒，13 位是毫秒
            return if (numeric < 10_000_000_000.0) (numeric * 1000.0).toLong() else numeric.toLong()
        }
        return parseDate(text)
    }

    private fun parseDate(text: String): Long {
        runCatching { Instant.parse(text) }.getOrNull()
            ?.let { return it.toEpochMilli() }

        runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrNull()
            ?.let { return it.toInstant().toEpochMilli() }

        runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
            ?.let { return it.toInstant().toEpochMilli() }

        for (pattern in LOCAL_PATTERNS) {
            runCatching { LocalDateTime.parse(text, pattern) }.getOrNull()
                ?.let { return it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        }

        runCatching { LocalDate.parse(text) }.getOrNull()
            ?.let { return it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }

        return 0L
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun maxItemsOf(plugin: SourcePlugin): Int =
        plugin.maxItems.coerceIn(SourcePlugin.MIN_ITEMS, SourcePlugin.MAX_ITEMS)

    /** 按字符截断，不把 emoji 的代理对切成半个。 */
    private fun cut(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end).trimEnd()
    }

    /** 只保留一句中文，绝不把响应正文带出去。 */
    private fun shortError(t: Throwable): String = when (t) {
        is PluginError -> t.message ?: "加载失败"
        is HttpException -> t.message?.takeIf { it.isNotBlank() } ?: "网络请求失败"
        is SocketTimeoutException -> "请求超时"
        is IOException -> "网络连接失败"
        is JSONException -> "数据格式异常"
        is XmlPullParserException -> "内容解析失败"
        else -> "加载失败"
    }
}

/** JSON 接口里没带时区的时间按本机时区解释。 */
private val LOCAL_PATTERNS: List<DateTimeFormatter> = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH),
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH),
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ENGLISH),
    DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss", Locale.ENGLISH),
)

private const val TITLE_LIMIT = 200
private const val SNIPPET_LIMIT = 160
