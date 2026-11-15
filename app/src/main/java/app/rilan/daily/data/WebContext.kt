package app.rilan.daily.data

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CancellationException

/**
 * 给 AI「词条速读」做真实检索的网页上下文。
 *
 * 定位：取证，不直接展示给用户。所以只要正文，不要导航、样式、脚本。
 * 约定：
 * - 任何异常都吞掉返回 null，绝不打断上层对话。
 * - 2 小时内重复的 URL / 查询走内存 LRU（最多 20 条），避免反复抓同一页。
 * - 只缓存成功结果，失败不缓存，下次还有机会重试。
 */
class WebContext {

    // ── 对外 API ────────────────────────────────────────────────────────────

    /** 抓正文：只接受 http/https；非 HTML、正文不足 80 字返回 null；最多 5000 字。 */
    suspend fun articleText(url: String): String? = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (!isHttpUrl(target)) return@withContext null
        cached(ARTICLE_KEY + target)?.let { return@withContext it }

        val text = try {
            fetchArticle(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (text == null) return@withContext null
        store(ARTICLE_KEY + target, text)
        text
    }

    /** 必应搜索结果：最多 5 条「标题 + 摘要」，最多 3500 字；不足 1 条返回 null。 */
    suspend fun searchSnippets(query: String): String? = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext null
        cached(SEARCH_KEY + keyword)?.let { return@withContext it }

        val text = try {
            fetchSnippets(keyword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (text == null) return@withContext null
        store(SEARCH_KEY + keyword, text)
        text
    }

    // ── 抓取 ────────────────────────────────────────────────────────────────

    private suspend fun fetchArticle(url: String): String? {
        val html = Http.getText(
            url,
            headers = mapOf("Accept" to HTML_ACCEPT),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
        )
        if (html.isBlank() || !looksLikeHtml(html)) return null

        // 先粗切一刀再解码实体，避免对上 MB 的页面做全量 Html.fromHtml
        val stripped = stripBlocks(html).replace(tagRegex, " ")
        val decoded = decodeEntities(normalize(stripped.take(RAW_LIMIT)))
        val text = normalize(decoded)
        if (text.length < MIN_ARTICLE_CHARS) return null
        return text.take(MAX_ARTICLE_CHARS)
    }

    private suspend fun fetchSnippets(query: String): String? {
        val encoded = urlEncode(query)
        val html = Http.getText(
            "$BING_SEARCH?q=$encoded&setlang=zh-Hans&cc=CN",
            headers = mapOf("Accept" to HTML_ACCEPT),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 15_000,
        )
        parseResults(html)?.let { return it }

        // 实测：不带市场参数的裸请求，必应偶尔只回「没有与此相关的结果」；
        // 补一次 mkt=zh-CN 再试，仍然没有就放弃（宁可没上下文，也不编造）。
        val retry = Http.getText(
            "$BING_SEARCH?q=$encoded&setlang=zh-Hans&cc=CN&mkt=zh-CN",
            headers = mapOf("Accept" to HTML_ACCEPT),
            connectTimeoutMs = 8_000,
            readTimeoutMs = 10_000,
        )
        return parseResults(retry)
    }

    // ── HTML 处理 ───────────────────────────────────────────────────────────

    private fun stripBlocks(html: String): String {
        var text = html.take(MAX_HTML_CHARS)
        for (pattern in blockPatterns) {
            text = pattern.replace(text, " ")
        }
        return text
    }

    /**
     * Http.getText 不返回 Content-Type，这里用正文特征判断是否 HTML：
     * JSON / 纯文本直接判否，避免把接口当文章喂给 AI。
     */
    private fun looksLikeHtml(raw: String): Boolean {
        val head = raw.take(2048).trimStart().lowercase()
        if (head.startsWith("{") || head.startsWith("[")) return false
        return htmlHints.any { head.contains(it) }
    }

    private fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        return try {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } catch (e: Exception) {
            raw
        }
    }

    /** 去标签后把连续空白（含全角空格、NBSP、零宽字符）压成单个空格。 */
    private fun normalize(raw: String): String = raw.replace(whitespaceRegex, " ").trim()

    // ── 搜索结果解析 ────────────────────────────────────────────────────────

    private fun parseResults(html: String): String? {
        if (html.isBlank()) return null
        val starts = bAlgoRegex.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return null

        val entries = ArrayList<String>(MAX_RESULTS)
        val seen = HashSet<String>()
        for ((index, start) in starts.withIndex()) {
            if (entries.size >= MAX_RESULTS) break
            val next = starts.getOrNull(index + 1) ?: html.length
            val block = html.substring(start, minOf(next, start + BLOCK_MAX_CHARS))

            val title = cleanText(
                titleRegex.find(block)?.groupValues?.get(1)
                    ?: linkRegex.find(block)?.groupValues?.get(1)
            )
            if (title.isEmpty() || !seen.add(title)) continue

            val snippet = cleanText(
                snippetRegex.find(block)?.groupValues?.get(1)
                    ?: captionRegex.find(block)?.groupValues?.get(1)
            )
            entries.add(
                if (snippet.isEmpty()) "标题：$title"
                else "标题：$title\n摘要：$snippet"
            )
        }
        if (entries.isEmpty()) return null
        return entries.joinToString("\n\n").take(MAX_SNIPPET_CHARS)
    }

    /** 把一段 HTML 片段变成干净单行文本。 */
    private fun cleanText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return normalize(decodeEntities(raw.replace(tagRegex, " "))).take(PART_MAX_CHARS)
    }

    // ── URL ─────────────────────────────────────────────────────────────────

    private fun isHttpUrl(raw: String): Boolean {
        if (!raw.startsWith("http://", ignoreCase = true) &&
            !raw.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        return try {
            val url = URL(raw)
            !url.host.isNullOrBlank() || !url.authority.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    private fun urlEncode(query: String): String =
        URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    // ── 2 小时 LRU ──────────────────────────────────────────────────────────

    private data class CacheEntry(val value: String, val at: Long)

    private val lock = Any()

    private val cache = LinkedHashMap<String, CacheEntry>()

    private fun cached(key: String): String? = synchronized(lock) {
        val entry = cache.remove(key) ?: return@synchronized null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            null
        } else {
            // 重新插入，等于把它挪到「最近使用」的一端
            cache[key] = entry
            entry.value
        }
    }

    private fun store(key: String, value: String) {
        synchronized(lock) {
            cache.remove(key)
            cache[key] = CacheEntry(value, System.currentTimeMillis())
            while (cache.size > MAX_ENTRIES) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
    }

    // ── 常量 ────────────────────────────────────────────────────────────────

    private val tagRegex = Regex("(?s)<[^>]*>")
    private val bAlgoRegex = Regex("(?i)<(?:li|div|article)\\b[^>]{0,200}?\\bb_algo\\b")
    private val titleRegex = Regex("(?is)<h2\\b[^>]*>(.*?)</h2>")
    private val linkRegex = Regex("(?is)<a\\b[^>]*>(.*?)</a>")
    private val snippetRegex = Regex("(?is)<p\\b[^>]*>(.*?)</p>")
    private val captionRegex = Regex("(?is)<div[^>]*\\bb_caption\\b[^>]*>(.*?)</div>")
    private val whitespaceRegex = Regex("[\\s\u00A0\u200B\uFEFF\u3000]+")

    private val blockPatterns = listOf(
        Regex("(?is)<script\\b[^>]*>.*?</script\\s*>"),
        Regex("(?is)<style\\b[^>]*>.*?</style\\s*>"),
        Regex("(?is)<noscript\\b[^>]*>.*?</noscript\\s*>"),
        Regex("(?is)<head\\b[^>]*>.*?</head\\s*>"),
        Regex("(?is)<nav\\b[^>]*>.*?</nav\\s*>"),
        Regex("(?is)<footer\\b[^>]*>.*?</footer\\s*>"),
        Regex("(?is)<header\\b[^>]*>.*?</header\\s*>"),
        Regex("(?is)<aside\\b[^>]*>.*?</aside\\s*>"),
        Regex("(?is)<form\\b[^>]*>.*?</form\\s*>"),
        Regex("(?is)<iframe\\b[^>]*>.*?</iframe\\s*>"),
        Regex("(?is)<svg\\b[^>]*>.*?</svg\\s*>"),
        Regex("(?s)<!--.*?-->"),
    )

    private val htmlHints = listOf(
        "<html", "<!doctype", "<head", "<body", "<div", "<p>", "<p ",
        "<article", "<main", "<span", "<a ", "<script", "<meta", "<title", "<link",
    )

    private companion object {
        const val ARTICLE_KEY = "a:"
        const val SEARCH_KEY = "s:"
        const val TTL_MS = 2 * 60 * 60 * 1000L
        const val MAX_ENTRIES = 20
        const val MAX_ARTICLE_CHARS = 5000
        const val RAW_LIMIT = 24_000
        const val MIN_ARTICLE_CHARS = 80
        const val MAX_SNIPPET_CHARS = 3500
        const val MAX_RESULTS = 5
        const val BLOCK_MAX_CHARS = 6000
        const val PART_MAX_CHARS = 300
        const val MAX_HTML_CHARS = 200_000
        const val HTML_ACCEPT = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"
        const val BING_SEARCH = "https://www.bing.com/search"
    }
}
