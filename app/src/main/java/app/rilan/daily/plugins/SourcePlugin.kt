package app.rilan.daily.plugins

import app.rilan.daily.core.Category
import app.rilan.daily.core.FeedSource
import org.json.JSONArray
import org.json.JSONObject

enum class PluginFormat(val label: String) {
    RSS("RSS / Atom"),
    JSON("JSON 接口");

    companion object {
        fun parse(raw: String?): PluginFormat? = when (raw?.trim()?.lowercase()) {
            "rss", "atom", "xml" -> RSS
            "json", "api" -> JSON
            else -> null
        }
    }
}

data class JsonFieldMap(
    val items: String = "",
    val title: String = "title",
    val url: String = "",
    val source: String = "",
    val time: String = "",
    val hot: String = "",
    val snippet: String = "",
    val linkPrefix: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("items", items)
        put("title", title)
        if (url.isNotBlank()) put("url", url)
        if (source.isNotBlank()) put("source", source)
        if (time.isNotBlank()) put("time", time)
        if (hot.isNotBlank()) put("hot", hot)
        if (snippet.isNotBlank()) put("snippet", snippet)
        if (linkPrefix.isNotBlank()) put("linkPrefix", linkPrefix)
    }
}

/**
 * 来源插件。文件即插件，一份 JSON 描述一个 RSS / JSON 接口。
 *
 * 目录：Android/data/app.rilan.daily/files/plugins/sources/<id>.json
 * 应用内可启停、编辑、删除，也可以通过 MCP 添加/删除。
 */
data class SourcePlugin(
    val id: String,
    val name: String,
    val category: Category,
    val format: PluginFormat,
    val url: String,
    val enabled: Boolean = true,
    val maxItems: Int = 15,
    val headers: Map<String, String> = emptyMap(),
    val mapping: JsonFieldMap = JsonFieldMap(),
    val filterEntertainment: Boolean = category in setOf(
        Category.WEIBO, Category.BILIBILI, Category.DOUYIN, Category.ZHIHU, Category.BAIDU
    ),
    val builtIn: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("schema", 1)
        put("id", id)
        put("name", name)
        put("category", category.name)
        put("type", format.name.lowercase())
        put("url", url)
        put("enabled", enabled)
        put("maxItems", maxItems)
        if (headers.isNotEmpty()) {
            put("headers", JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } })
        }
        if (format == PluginFormat.JSON) put("mapping", mapping.toJson())
    }.toString(2)

    fun withEnabled(value: Boolean): SourcePlugin = copy(enabled = value)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_NAME = 48
        const val MAX_ITEMS = 40
        const val MIN_ITEMS = 1
        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{1,47}$")

        val DEFAULTS: List<SourcePlugin> = buildList {
            FeedSource.DEFAULTS.forEach { feed ->
                add(
                    SourcePlugin(
                        id = feed.id,
                        name = feed.name,
                        category = feed.category,
                        format = PluginFormat.RSS,
                        url = feed.url,
                        enabled = true,
                        maxItems = when (feed.category) {
                            Category.GENERAL -> 12
                            Category.SECURITY, Category.WORLD -> 8
                            else -> 15
                        },
                        builtIn = true,
                    )
                )
            }
            add(
                SourcePlugin(
                    id = "zhihu-hot",
                    name = "知乎热榜",
                    category = Category.ZHIHU,
                    format = PluginFormat.JSON,
                    url = "https://api.zhihu.com/topstory/hot-lists/total?limit=50&desktop=true",
                    maxItems = 15,
                    headers = mapOf(
                        "Referer" to "https://www.zhihu.com/hot",
                        "Accept" to "application/json",
                    ),
                    mapping = JsonFieldMap(
                        items = "data",
                        title = "target.title",
                        url = "target.id",
                        linkPrefix = "https://www.zhihu.com/question/",
                        hot = "detail_text",
                        snippet = "target.excerpt",
                    ),
                    filterEntertainment = true,
                    builtIn = true,
                )
            )
            add(
                SourcePlugin(
                    id = "baidu-hot",
                    name = "百度热搜",
                    category = Category.BAIDU,
                    format = PluginFormat.JSON,
                    url = "https://top.baidu.com/api/board?platform=wise&tab=realtime",
                    maxItems = 15,
                    headers = mapOf(
                        "Referer" to "https://top.baidu.com/board?tab=realtime",
                        "Accept" to "application/json",
                    ),
                    mapping = JsonFieldMap(
                        items = "data.cards.0.content.0.content",
                        title = "word",
                        url = "url",
                    ),
                    filterEntertainment = true,
                    builtIn = true,
                )
            )
        }

        val TEMPLATE_RSS: String = SourcePlugin(
            id = "my-rss",
            name = "我的 RSS",
            category = Category.GENERAL,
            format = PluginFormat.RSS,
            url = "https://example.com/feed.xml",
            maxItems = 12,
        ).toJson()

        val TEMPLATE_JSON: String = SourcePlugin(
            id = "my-json-hot",
            name = "我的 JSON 热榜",
            category = Category.WEIBO,
            format = PluginFormat.JSON,
            url = "https://api.example.com/hot/list",
            maxItems = 15,
            headers = mapOf("Referer" to "https://example.com/"),
            mapping = JsonFieldMap(
                items = "data.list",
                title = "title",
                url = "url",
                source = "author.name",
                time = "created_at",
                hot = "hot_value",
                snippet = "description",
                linkPrefix = "",
            ),
        ).toJson()

        val FORMAT_HELP: String = """
            {
              "schema": 1,
              "id": "my-json-hot",
              "name": "我的 JSON 热榜",
              "category": "WEIBO",
              "type": "json",
              "url": "https://api.example.com/hot/list",
              "enabled": true,
              "maxItems": 15,
              "headers": {},
              "mapping": {
                "items": "data.list",
                "title": "title",
                "url": "url",
                "source": "author.name",
                "time": "created_at",
                "hot": "hot_value",
                "snippet": "description",
                "linkPrefix": "https://example.com/item/"
              }
            }
            字段：
            id 必填，2-48 位小写字母/数字/._-，文件名必须是 <id>.json。
            category: GENERAL/AI/ZHIHU/WEIBO/BILIBILI/DOUYIN/BAIDU/SECURITY/WORLD。
            type: rss 或 json；rss 只需要 url，json 必须提供 mapping.title，mapping.items 留空表示根数组。
            mapping.items 支持点路径与数组下标（data.list、items.0.list）；留空表示响应根就是数组。
            time 支持秒/毫秒时间戳与 ISO/RFC1123 文本；hot 原样保留数字。
            热榜分类默认过滤明星娱乐，可用 filterEntertainment=false 关闭。
        """.trimIndent()

        fun parse(raw: String): Result<SourcePlugin> = runCatching {
            val root = JSONObject(raw.trim().removePrefix("\uFEFF"))
            parseObject(root)
        }.recoverCatching { t ->
            throw IllegalArgumentException(t.message?.takeIf { it.isNotBlank() } ?: "JSON 格式不正确")
        }

        fun parseObject(root: JSONObject): SourcePlugin {
            val schema = root.optInt("schema", SCHEMA_VERSION)
            require(schema == SCHEMA_VERSION) { "不支持的 schema 版本：$schema" }

            val id = root.optString("id").trim()
            require(ID_REGEX.matches(id)) {
                "id 必须为 2-48 位小写字母、数字、点、下划线或短横线，且以字母/数字开头"
            }

            val name = root.optString("name").trim()
            require(name.isNotEmpty()) { "name 不能为空" }
            require(name.length <= MAX_NAME) { "name 不能超过 $MAX_NAME 个字符" }

            val categoryName = root.optString("category").trim().uppercase()
            val category = Category.entries.firstOrNull { it.name == categoryName }
                ?: throw IllegalArgumentException("category 必须是 GENERAL/AI/ZHIHU/WEIBO/BILIBILI/DOUYIN/BAIDU/SECURITY/WORLD")

            val format = PluginFormat.parse(root.optString("type"))
                ?: throw IllegalArgumentException("type 必须是 rss 或 json")

            val url = root.optString("url").trim()
            require(url.startsWith("https://") || url.startsWith("http://")) {
                "url 必须以 http:// 或 https:// 开头"
            }

            val maxItems = root.optInt("maxItems", 15)
                .coerceIn(MIN_ITEMS, MAX_ITEMS)

            val headers = mutableMapOf<String, String>()
            root.optJSONObject("headers")?.let { h ->
                val keys = h.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = h.optString(key).trim()
                    if (key.isNotBlank() && value.isNotBlank() && key.length <= 80 && value.length <= 500) {
                        headers[key] = value
                    }
                }
            }

            val defaultFilter = category in setOf(Category.WEIBO, Category.BILIBILI, Category.DOUYIN)
            val filter = if (root.has("filterEntertainment")) {
                root.optBoolean("filterEntertainment", defaultFilter)
            } else defaultFilter

            val mapping = if (format == PluginFormat.JSON) {
                val m = root.optJSONObject("mapping")
                    ?: throw IllegalArgumentException("type=json 时必须提供 mapping")
                val map = JsonFieldMap(
                    items = m.optString("items").trim(),
                    title = m.optString("title", "title").trim().ifBlank { "title" },
                    url = m.optString("url").trim(),
                    source = m.optString("source").trim(),
                    time = m.optString("time").trim(),
                    hot = m.optString("hot").trim(),
                    snippet = m.optString("snippet").trim(),
                    linkPrefix = m.optString("linkPrefix").trim(),
                )
                require(map.title.isNotBlank()) { "mapping.title 不能为空" }
                map
            } else JsonFieldMap()

            return SourcePlugin(
                id = id,
                name = name,
                category = category,
                format = format,
                url = url,
                enabled = root.optBoolean("enabled", true),
                maxItems = maxItems,
                headers = headers,
                mapping = mapping,
                filterEntertainment = filter,
                builtIn = false,
            )
        }

        fun headersJson(headers: Map<String, String>): JSONObject = JSONObject().apply {
            headers.forEach { (k, v) -> put(k, v) }
        }

        fun arrayOf(list: List<SourcePlugin>): JSONArray = JSONArray().apply {
            list.forEach { put(JSONObject(it.toJson())) }
        }
    }
}
