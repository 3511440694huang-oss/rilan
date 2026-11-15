package app.rilan.daily.data

import android.util.Xml
import app.rilan.daily.core.Category
import app.rilan.daily.core.FeedSource
import app.rilan.daily.core.NewsItem
import app.rilan.daily.core.stableId
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * RSS 2.0 / Atom 解析器，只依赖 XmlPullParser（org.xmlpull.v1），不引第三方库。
 *
 * 支持字段：title、link（含 Atom 的 link href）、description、content:encoded、
 * pubDate、dc:date、updated、published、guid。
 *
 * 橘鸦 AI 早报（daily.juya.uk）的正文是一整篇 HTML，里面用 `<li>` 列出当天的多条新闻，
 * 这种源会被拆成多条 NewsItem（见 [juyaItems]），否则 AI 分类只有一条日期标题。
 */
object RssParser {

    /** 单个源最多解析多少条，防止异常源把内存和后续排序拖垮。 */
    private const val MAX_ITEMS_PER_FEED = 60

    /** 摘要截断长度（字符）。 */
    private const val SNIPPET_LIMIT = 160

    /** 标题截断长度（字符），只用于防御异常长的标题。 */
    private const val TITLE_LIMIT = 200

    private const val JUYA_SOURCE = "橘鸦 AI 早报"

    // ── 预编译正则（object 初始化时编译一次，多线程共享只读）───────────────────

    private val SCRIPT_OR_STYLE = Regex(
        "<(script|style)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("[\\s\\u00A0\\u3000\\u200B\\uFEFF]+")

    /** 早报里的 `<li>…</li>`。 */
    private val LIST_ITEM = Regex(
        "<li\\b[^>]*>(.*?)</li\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<li>` 里的第一个链接，group(1) 是 href。 */
    private val FIRST_ANCHOR = Regex(
        "<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ANY_ANCHOR = Regex(
        "<a\\b[^>]*>.*?</a\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 序号标记 `<code>#3</code>`。 */
    private val CODE_TAG = Regex(
        "<code\\b[^>]*>.*?</code\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 行尾残留的 ↗ / #3 / [3] 之类标记。 */
    private val TRAILING_MARKER = Regex("(?:\\s*[↗↑→]+|\\s*#\\d{1,3}|\\s*\\[\\d{1,3}])+\\s*$")

    private val ENTITY = Regex("&(#[xX]?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]{1,9});")
    private val UNKNOWN_ENTITY = Regex("&([A-Za-z][A-Za-z0-9]{1,9});")
    private val XML_PREDEFINED = setOf("amp", "lt", "gt", "quot", "apos")

    private val MONTH_DAY_CN = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日")
    private val ISO_DAY = Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})")

    private val MONTH_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

    /** RFC 1123 之外的常见日期写法（带时区名/偏移）。 */
    private val ZONED_PATTERNS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH),
    )

    /** 不带时区的本地时间写法，按系统时区解释。 */
    private val LOCAL_PATTERNS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss", Locale.ENGLISH),
    )

    // ── 对外入口 ─────────────────────────────────────────────────────────────

    /**
     * 把一份 RSS/Atom 文档解析成新闻条目。
     *
     * 解析失败（XML 不合法、不是 feed）会抛 [org.xmlpull.v1.XmlPullParserException]，
     * 由调用方转成简短错误；条目级别的脏数据（缺标题、缺链接、日期非法）只跳过或降级。
     */
    fun parse(xml: String, feed: FeedSource): List<NewsItem> {
        val body = sanitizeEntities(xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n'))
        if (body.isEmpty()) return emptyList()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(body))

        val items = ArrayList<NewsItem>(16)
        var draft: ItemDraft? = null
        var field: String? = null
        var fieldTag: String? = null
        val buffer = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val rawName = parser.name ?: ""
                    val local = localName(rawName)
                    if (local == "item" || local == "entry") {
                        draft = ItemDraft()
                        field = null
                        fieldTag = null
                        buffer.setLength(0)
                    } else {
                        val open = draft
                        if (open != null) {
                            if (local == "link") {
                                // Atom：<link rel="alternate" href="…"/>；RSS：<link>文本</link>
                                val href = parser.getAttributeValue(null, "href")
                                val rel = parser.getAttributeValue(null, "rel")
                                if (!href.isNullOrBlank()) {
                                    if (open.link.isBlank() && (rel == null || rel.equals("alternate", true))) {
                                        open.link = href.trim()
                                    }
                                } else if (open.link.isBlank()) {
                                    field = "link"
                                    fieldTag = rawName.lowercase(Locale.ROOT)
                                    buffer.setLength(0)
                                }
                            } else {
                                val name = fieldNameFor(rawName)
                                if (name != null) {
                                    field = name
                                    fieldTag = rawName.lowercase(Locale.ROOT)
                                    buffer.setLength(0)
                                }
                            }
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (field != null) buffer.append(parser.text ?: "")
                }

                XmlPullParser.END_TAG -> {
                    val rawName = parser.name ?: ""
                    val local = localName(rawName)
                    if (local == "item" || local == "entry") {
                        val finished = draft
                        if (finished != null) {
                            items += buildItems(finished, feed)
                            draft = null
                        }
                        field = null
                        fieldTag = null
                        buffer.setLength(0)
                        if (items.size >= MAX_ITEMS_PER_FEED) break
                    } else {
                        val name = field
                        val open = draft
                        if (name != null && open != null && rawName.equals(fieldTag, ignoreCase = true)) {
                            assign(open, name, buffer.toString())
                            field = null
                            fieldTag = null
                            buffer.setLength(0)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return items.take(MAX_ITEMS_PER_FEED)
    }

    // ── 条目组装 ─────────────────────────────────────────────────────────────

    private fun buildItems(draft: ItemDraft, feed: FeedSource): List<NewsItem> {
        val publishedAt = firstDate(draft.pub, draft.dcDate, draft.updated, draft.published)

        if (isJuya(feed)) {
            // 早报先按 <li> 拆分，拆不出来才退回“整期一条”
            return juyaItems(draft, feed, publishedAt)
        }

        val title = plainText(draft.title).let { if (it.length > TITLE_LIMIT) cut(it, TITLE_LIMIT) else it }
        if (title.isEmpty()) return emptyList()

        val url = pickUrl(draft, feed.url)
        return listOf(
            NewsItem(
                id = stableId(feed.id, url.ifBlank { title }),
                title = title,
                url = url,
                source = feed.name,
                category = feed.category,
                publishedAt = publishedAt,
                snippet = snippetOf(draft.description.ifBlank { draft.content }),
            )
        )
    }

    /** 橘鸦 AI 早报：把正文 HTML 里的每个 `<li>` 拆成一条独立新闻。 */
    private fun juyaItems(draft: ItemDraft, feed: FeedSource, publishedAt: Long): List<NewsItem> {
        val html = draft.content.ifBlank { draft.description }
        val link = pickUrl(draft, feed.url)
        val label = dateLabel(draft.title, publishedAt).ifBlank { "今日" }

        val parsed = extractListItems(html).mapNotNull { entry ->
            val title = entry.first
            if (title.isEmpty()) return@mapNotNull null
            // 没有原文链接时退回该期早报的链接，保证条目可点
            val url = httpOnly(resolveUrl(feed.url, entry.second)).ifBlank { link }
            NewsItem(
                id = stableId(feed.id, url.ifBlank { title }),
                title = title,
                url = url,
                source = JUYA_SOURCE,
                category = Category.AI,
                publishedAt = publishedAt,
            )
        }
        if (parsed.isNotEmpty()) return parsed.take(MAX_ITEMS_PER_FEED)

        // 兜底：li 解析失败时，整期早报作为一条
        return listOf(
            NewsItem(
                id = stableId(feed.id, link.ifBlank { label }),
                title = "AI 早报 · $label",
                url = link,
                source = JUYA_SOURCE,
                category = Category.AI,
                publishedAt = publishedAt,
                snippet = snippetOf(draft.description.ifBlank { html }),
            )
        )
    }

    /**
     * 从早报 HTML 里逐条提取标题与原文链接。
     * 形状大致是：`<li>新闻标题 <a href="原文">↗</a> <code>#3</code></li>`。
     */
    private fun extractListItems(html: String): List<Pair<String, String>> {
        if (html.isBlank()) return emptyList()
        val result = ArrayList<Pair<String, String>>(16)
        for (match in LIST_ITEM.findAll(html)) {
            if (result.size >= MAX_ITEMS_PER_FEED) break
            val inner = match.groupValues[1]
            val href = FIRST_ANCHOR.find(inner)?.groupValues?.getOrNull(1).orEmpty()
            // 锚文本（↗）和序号标记不该混进标题
            val textHtml = inner.replace(ANY_ANCHOR, " ").replace(CODE_TAG, " ")
            val title = cleanListTitle(textHtml)
            if (title.isNotEmpty()) result += title to href
        }
        return result
    }

    private fun cleanListTitle(html: String): String {
        var text = plainText(html)
        var guard = 0
        while (guard < 4) {
            val next = text.replace(TRAILING_MARKER, "").trim()
            if (next == text) break
            text = next
            guard++
        }
        return if (text.length > TITLE_LIMIT) cut(text, TITLE_LIMIT) else text
    }

    // ── 文本 / HTML 处理 ────────────────────────────────────────────────────

    /**
     * 去标签 + 解实体 + 压缩空白，得到可直接展示的纯文本。
     * 标题、摘要以及热搜词都用它清洗（热搜里偶尔带 `&amp;` 之类）。
     */
    fun plainText(html: String): String {
        if (html.isEmpty()) return ""
        var text = html
        if (text.contains('<')) {
            text = text.replace(SCRIPT_OR_STYLE, " ").replace(TAG, " ")
        }
        if (text.contains('&')) text = decodeEntities(text)
        return text.replace(WHITESPACE, " ").trim()
    }

    /** 摘要：去标签后截断到 160 字。 */
    private fun snippetOf(html: String): String {
        val text = plainText(html)
        if (text.length <= SNIPPET_LIMIT) return text
        return cut(text, SNIPPET_LIMIT) + "…"
    }

    /** 按「字符」截断，避免把 emoji 的代理对切成半个字符。 */
    private fun cut(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit.coerceAtLeast(1)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end).trimEnd()
    }

    private fun decodeEntities(input: String): String =
        ENTITY.replace(input) { match -> entityValue(match.groupValues[1]) ?: match.value }

    private fun entityValue(token: String): String? {
        if (token.startsWith("#")) {
            val hex = token.length > 1 && (token[1] == 'x' || token[1] == 'X')
            val code = if (hex) token.substring(2).toIntOrNull(16) else token.substring(1).toIntOrNull()
            if (code == null || code <= 0 || code > 0x10FFFF) return ""
            return runCatching { String(Character.toChars(code)) }.getOrDefault("")
        }
        return NAMED_ENTITIES[token.lowercase(Locale.ROOT)]
    }

    /**
     * 有些源直接写 `&nbsp;` 这种 HTML 实体却没在 XML 里声明，
     * 严格解析器会直接失败。解析前把它们换成合法的数字实体；
     * 完全不认识的实体转义成纯文本（显示 &foo; 而不是整条源解析失败）。
     */
    private fun sanitizeEntities(xml: String): String {
        if (!xml.contains('&')) return xml
        return UNKNOWN_ENTITY.replace(xml) { match ->
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            if (name in XML_PREDEFINED) {
                match.value
            } else {
                val value = NAMED_ENTITIES[name]
                if (value == null) "&amp;$name;" else value.map { "&#${it.code};" }.joinToString("")
            }
        }
    }

    // ── 字段 / 链接 / 日期 ──────────────────────────────────────────────────

    private fun localName(rawName: String): String {
        val colon = rawName.lastIndexOf(':')
        return (if (colon >= 0) rawName.substring(colon + 1) else rawName).lowercase(Locale.ROOT)
    }

    /**
     * 元素名 → 内部字段名。只认 RSS/Atom/DC 的常用前缀，
     * 其余命名空间（media:、itunes:、slash: 等）返回 null，避免 `media:title` 覆盖标题。
     */
    private fun fieldNameFor(rawName: String): String? {
        val lower = rawName.lowercase(Locale.ROOT)
        val colon = lower.indexOf(':')
        if (colon > 0) {
            val prefix = lower.substring(0, colon)
            if (prefix != "dc" && prefix != "content" && prefix != "atom") return null
        }
        val name = if (colon >= 0) lower.substring(colon + 1) else lower
        return when (name) {
            "title" -> "title"
            "description", "summary" -> "description"
            "content", "encoded" -> "content"
            "pubdate" -> "pub"
            "date" -> "dcDate"
            "updated", "modified" -> "updated"
            "published", "issued" -> "published"
            "guid", "id" -> "guid"
            else -> null
        }
    }

    private fun assign(draft: ItemDraft, field: String, value: String) {
        when (field) {
            "title" -> draft.title = value
            "link" -> draft.link = value.trim()
            "description" -> draft.description = value
            "content" -> draft.content = value
            "pub" -> draft.pub = value
            "dcDate" -> draft.dcDate = value
            "updated" -> draft.updated = value
            "published" -> draft.published = value
            "guid" -> draft.guid = value
        }
    }

    private fun pickUrl(draft: ItemDraft, base: String): String {
        val fromLink = httpOnly(resolveUrl(base, draft.link))
        if (fromLink.isNotEmpty()) return fromLink
        return httpOnly(resolveUrl(base, draft.guid))
    }

    private fun resolveUrl(base: String, href: String): String {
        val raw = href.trim()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
        if (raw.startsWith("//")) return "https:$raw"
        return runCatching { URI(base).resolve(raw).toString() }.getOrDefault("")
    }

    private fun httpOnly(url: String): String {
        val value = url.trim()
        return if (value.startsWith("http://", true) || value.startsWith("https://", true)) value else ""
    }

    private fun firstDate(vararg values: String): Long {
        for (value in values) {
            val parsed = parseDate(value)
            if (parsed > 0L) return parsed
        }
        return 0L
    }

    /** RFC 1123 / ISO 8601 / `yyyy-MM-dd HH:mm:ss` 等；解析不出来就给 0L。 */
    private fun parseDate(raw: String?): Long {
        val rawText = raw?.trim().orEmpty()
        if (rawText.isEmpty()) return 0L
        val text = rawText.replace(WHITESPACE, " ")

        runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME) }
            .getOrNull()?.let { return it.toInstant().toEpochMilli() }

        for (pattern in ZONED_PATTERNS) {
            runCatching { ZonedDateTime.parse(text, pattern) }
                .getOrNull()?.let { return it.toInstant().toEpochMilli() }
        }

        runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
            .getOrNull()?.let { return it.toInstant().toEpochMilli() }

        runCatching { Instant.parse(text) }
            .getOrNull()?.let { return it.toEpochMilli() }

        for (pattern in LOCAL_PATTERNS) {
            runCatching { LocalDateTime.parse(text, pattern) }
                .getOrNull()?.let {
                    return it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
        }

        runCatching { LocalDate.parse(text) }
            .getOrNull()?.let {
                return it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }

        return 0L
    }

    private fun isJuya(feed: FeedSource): Boolean =
        feed.id == "juya" || feed.url.contains("daily.juya.uk", ignoreCase = true)

    /** 早报标题只有日期，兜底条目的标题需要 `M月d日`。 */
    private fun dateLabel(rawTitle: String, publishedAt: Long): String {
        val text = plainText(rawTitle)
        MONTH_DAY_CN.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull()
            val day = match.groupValues[2].toIntOrNull()
            if (month != null && day != null && month in 1..12 && day in 1..31) {
                return "${month}月${day}日"
            }
        }
        ISO_DAY.find(text)?.let { match ->
            val month = match.groupValues[2].toIntOrNull()
            val day = match.groupValues[3].toIntOrNull()
            if (month != null && day != null && month in 1..12 && day in 1..31) {
                return "${month}月${day}日"
            }
        }
        val stamp = if (publishedAt > 0L) publishedAt else System.currentTimeMillis()
        return runCatching {
            Instant.ofEpochMilli(stamp).atZone(ZoneId.systemDefault()).format(MONTH_DAY_FORMAT)
        }.getOrDefault("")
    }

    /** 解析过程中的临时条目，字段随事件流逐步填充。 */
    private class ItemDraft {
        var title: String = ""
        var link: String = ""
        var description: String = ""
        var content: String = ""
        var pub: String = ""
        var dcDate: String = ""
        var updated: String = ""
        var published: String = ""
        var guid: String = ""
    }

    /** 常用 HTML 实体；不在表里的保持原样（宁可显示 &foo; 也不乱码）。 */
    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "bull" to "•", "copy" to "©",
        "reg" to "®", "trade" to "™", "deg" to "°", "plusmn" to "±",
        "times" to "×", "divide" to "÷", "euro" to "€", "pound" to "£",
        "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
        "sup1" to "¹", "sup2" to "²", "sup3" to "³", "micro" to "µ",
        "prime" to "′", "dagger" to "†", "permil" to "‰",
    )
}
