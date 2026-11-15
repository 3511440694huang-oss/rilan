package app.rilan.daily.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具与资源的具体实现：参数校验 → 调用 [McpHost] → 转成 MCP 的文本内容。
 *
 * 只依赖 McpHost 与 org.json，不引用任何 Android 类；
 * 所有 host 异常都在这里收口成 isError=true 的文本，绝不让调用线程崩溃。
 */

private const val HOST_CALL_TIMEOUT_MS = 120_000L
private const val RESULT_MESSAGE_LIMIT = 240

private const val EMPTY_WEATHER_TEXT = "暂无天气数据"
private const val EMPTY_WEATHER_JSON = "{\"message\":\"暂无天气数据\"}"

private val NEWS_CATEGORIES = listOf("GENERAL", "AI", "WEIBO", "BILIBILI", "DOUYIN", "SECURITY", "WORLD")
private val SUMMARY_PERIODS = listOf("DAY", "WEEK", "MONTH", "QUARTER", "HALF_YEAR", "YEAR")
private val PRIORITIES = listOf("LOW", "MEDIUM", "HIGH")
private val EVENT_KINDS = listOf("TASK", "BIRTHDAY", "ANNIVERSARY", "MEMO")
private val REPEAT_RULES = listOf("NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")

/** 参数不合法：消息直接作为 isError=true 的文本返回。 */
private class ToolFault(val reason: String) : Exception(reason)

/** host 的挂起方法超时。 */
private class HostTimeoutException : Exception("操作超时")

object McpTools {

    // ────────────────────────────────────────────────────────────────────────
    // 对外入口
    // ────────────────────────────────────────────────────────────────────────

    /** tools/list 的内容；每次新建，避免多连接并发序列化同一个 JSON 实例。 */
    fun definitions(): JSONArray = JSONArray().apply {
        toolDef(
            name = "get_weather",
            description = "读取本机缓存的当前天气：温度、体感、湿度、风力、空气质量，以及逐小时和未来几天预报。没有数据时返回提示文字。撰写天气解读前必须先调用本工具，不要编造天气数据。",
        )

        toolDef(
            name = "get_news",
            description = "读取本机已缓存的新闻与热搜条目，可按板块筛选，返回标题、来源、链接、发布时间等字段。",
            properties = JSONObject()
                .put("category", stringProperty("新闻板块；省略则返回全部板块", NEWS_CATEGORIES))
                .put("limit", intProperty("最多返回多少条", minimum = 1, maximum = 40, defaultValue = 12)),
        )

        toolDef(
            name = "get_briefing",
            description = "读取今日简报数据：天气摘要 + 各板块已收录内容，适合作为生成每日简报的事实依据。",
        )

        toolDef(
            name = "search_news",
            description = "在本机最近若干天的收录记录中按关键词搜索新闻，返回匹配到的条目。",
            properties = JSONObject()
                .put("query", stringProperty("搜索关键词，不能为空"))
                .put("days", intProperty("向前搜索多少天", minimum = 1, maximum = 365, defaultValue = 30)),
            required = listOf("query"),
        )

        toolDef(
            name = "get_records",
            description = "按周期读取本机收录的历史新闻记录，用于外部 AI 撰写周期总结。写总结前必须先调用本工具拿到真实数据，写完用 save_period_summary 写回；不要编造内容。",
            properties = JSONObject()
                .put("period", stringProperty("统计周期", SUMMARY_PERIODS)),
            required = listOf("period"),
        )

        toolDef(
            name = "list_schedules",
            description = "列出全部日程，包含日期、时间、重要程度、类型、重复规则和 id。",
        )

        toolDef(
            name = "create_schedule",
            description = "新建一条日程。date 使用 yyyy-MM-dd，time 使用 HH:mm，省略 time 表示全天。",
            properties = JSONObject()
                .put("title", stringProperty("日程标题"))
                .put("date", stringProperty("日期，格式 yyyy-MM-dd", pattern = "\\d{4}-\\d{2}-\\d{2}"))
                .put("time", stringProperty("时间，格式 HH:mm；省略表示全天"))
                .put("priority", stringProperty("重要程度", PRIORITIES))
                .put("kind", stringProperty("类型", EVENT_KINDS))
                .put("repeat", stringProperty("重复规则", REPEAT_RULES))
                .put("notes", stringProperty("备注，可选")),
            required = listOf("title", "date"),
        )

        toolDef(
            name = "update_schedule",
            description = "按 id 修改已有日程，只传需要修改的字段。",
            properties = JSONObject()
                .put("id", stringProperty("日程 id"))
                .put("title", stringProperty("新的标题"))
                .put("date", stringProperty("新的日期，格式 yyyy-MM-dd", pattern = "\\d{4}-\\d{2}-\\d{2}"))
                .put("time", stringProperty("新的时间，格式 HH:mm；传空字符串表示改为全天"))
                .put("priority", stringProperty("新的重要程度", PRIORITIES))
                .put("kind", stringProperty("新的类型", EVENT_KINDS))
                .put("repeat", stringProperty("新的重复规则", REPEAT_RULES))
                .put("notes", stringProperty("新的备注")),
            required = listOf("id"),
        )

        toolDef(
            name = "delete_schedule",
            description = "按 id 删除日程，返回是否找到并删除。",
            properties = JSONObject().put("id", stringProperty("日程 id")),
            required = listOf("id"),
        )

        toolDef(
            name = "list_sources",
            description = "列出来源插件与内置信息源，包含 id、名称、地址、分类和启用状态。",
        )

        toolDef(
            name = "add_source",
            description = "添加一个来源插件。plugin_json 是一份完整的来源插件 JSON 字符串，字段需符合日览来源插件格式。",
            properties = JSONObject()
                .put("plugin_json", stringProperty("完整插件 JSON 字符串")),
            required = listOf("plugin_json"),
        )

        toolDef(
            name = "remove_source",
            description = "按 id 删除来源插件。",
            properties = JSONObject().put("id", stringProperty("来源 id")),
            required = listOf("id"),
        )

        toolDef(
            name = "set_source_enabled",
            description = "启用或停用某个来源插件。",
            properties = JSONObject()
                .put("id", stringProperty("来源 id"))
                .put("enabled", boolProperty("true 启用，false 停用")),
            required = listOf("id", "enabled"),
        )

        toolDef(
            name = "refresh_content",
            description = "立即刷新天气与所有信息源内容，需要联网，可能耗时数秒。",
        )

        toolDef(
            name = "set_city",
            description = "设置天气城市，会重新解析经纬度并刷新天气。",
            properties = JSONObject().put("city", stringProperty("城市名，例如 北京")),
            required = listOf("city"),
        )

        toolDef(
            name = "set_reminder_time",
            description = "设置每日日程提醒时间（24 小时制），修改后会重新安排提醒。",
            properties = JSONObject()
                .put("hour", intProperty("小时", minimum = 0, maximum = 23))
                .put("minute", intProperty("分钟", minimum = 0, maximum = 59)),
            required = listOf("hour", "minute"),
        )

        toolDef(
            name = "save_weather_insight",
            description = "保存天气解读（纯文本或 Markdown），会覆盖当前解读；正文过短会被拒绝。请先调用 get_weather 获取真实天气，再基于其内容撰写；不要编造数据。",
            properties = JSONObject().put("text", stringProperty("天气解读正文")),
            required = listOf("text"),
        )

        toolDef(
            name = "save_daily_brief",
            description = "保存今日简报（纯文本或 Markdown），会覆盖当前简报；正文过短会被拒绝。请先调用 get_briefing、get_news 获取真实内容，不要编造数据。",
            properties = JSONObject().put("text", stringProperty("今日简报正文")),
            required = listOf("text"),
        )

        toolDef(
            name = "save_period_summary",
            description = "保存某个周期的总结（纯文本或 Markdown），会覆盖该周期已有总结；正文过短会被拒绝。period 与 get_records 使用同一套枚举；写入前请先调用 get_records 获取真实数据，不要编造。",
            properties = JSONObject()
                .put("period", stringProperty("统计周期", SUMMARY_PERIODS))
                .put("content", stringProperty("总结正文")),
            required = listOf("period", "content"),
        )
    }

    /** tools/call：永远返回合法的 MCP 工具结果，不抛异常。 */
    fun call(host: McpHost, name: String, args: JSONObject): JSONObject = try {
        textResult(execute(host, name.trim(), args), isError = false)
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        val message = if (t is ToolFault) t.reason else "工具执行失败：${reason(t)}"
        textResult(message, isError = true)
    }

    fun textResult(text: String, isError: Boolean = false): JSONObject = JSONObject().apply {
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        })
        put("isError", isError)
    }

    fun errorResult(message: String): JSONObject = textResult(message, isError = true)

    // ────────────────────────────────────────────────────────────────────────
    // 工具实现
    // ────────────────────────────────────────────────────────────────────────

    private fun execute(host: McpHost, name: String, args: JSONObject): String = when (name) {
        "get_weather" -> host.weather()?.let { pretty(it) } ?: EMPTY_WEATHER_TEXT

        "get_news" -> {
            val category = optEnum(args, "category", NEWS_CATEGORIES)
            val limit = optInt(args, "limit", default = 12, min = 1, max = 40)
            pretty(host.news(category, limit))
        }

        "get_briefing" -> pretty(host.briefing())

        "search_news" -> {
            val query = requireText(args, "query")
            val days = optInt(args, "days", default = 30, min = 1, max = 365)
            pretty(host.searchNews(query, days))
        }

        "get_records" -> {
            val period = requireEnum(args, "period", SUMMARY_PERIODS)
            pretty(host.records(period))
        }

        "list_schedules" -> pretty(host.listSchedules())

        "create_schedule" -> {
            requireText(args, "title")
            requireDate(args, "date")
            pretty(awaitHost { host.createSchedule(args) })
        }

        "update_schedule" -> {
            requireText(args, "id")
            optDate(args, "date")
            pretty(awaitHost { host.updateSchedule(args) })
        }

        "delete_schedule" -> {
            val id = requireText(args, "id")
            if (awaitHost { host.deleteSchedule(id) }) "已删除日程" else "未找到要删除的日程"
        }

        "list_sources" -> pretty(host.listSources())

        "add_source" -> {
            val pluginJson = requireText(args, "plugin_json")
            pretty(awaitHost { host.addSource(pluginJson) })
        }

        "remove_source" -> {
            val id = requireText(args, "id")
            if (awaitHost { host.removeSource(id) }) "已移除来源" else "未找到该来源"
        }

        "set_source_enabled" -> {
            val id = requireText(args, "id")
            val enabled = requireBoolean(args, "enabled")
            val ok = awaitHost { host.setSourceEnabled(id, enabled) }
            when {
                !ok -> "未找到该来源"
                enabled -> "已启用来源"
                else -> "已停用来源"
            }
        }

        "refresh_content" -> awaitHost { host.refreshContent() }.ifBlank { "已开始刷新" }

        "set_city" -> {
            val city = requireText(args, "city")
            awaitHost { host.setCity(city) }.ifBlank { "已设置城市：$city" }
        }

        "set_reminder_time" -> {
            val hour = requireInt(args, "hour", min = 0, max = 23)
            val minute = requireInt(args, "minute", min = 0, max = 59)
            awaitHost { host.setReminderTime(hour, minute) }.ifBlank { "已设置提醒时间" }
        }

        "save_weather_insight" -> {
            val text = requireText(args, "text")
            if (awaitHost { host.saveWeatherInsight(text) }) "已保存天气解读" else "保存天气解读失败"
        }

        "save_daily_brief" -> {
            val text = requireText(args, "text")
            if (awaitHost { host.saveDailyBrief(text) }) "已保存今日简报" else "保存今日简报失败"
        }

        "save_period_summary" -> {
            val period = requireEnum(args, "period", SUMMARY_PERIODS)
            val content = requireText(args, "content")
            pretty(awaitHost { host.savePeriodSummary(period, content) })
        }

        else -> throw ToolFault("未知工具：$name")
    }

    // ────────────────────────────────────────────────────────────────────────
    // 挂起方法桥接
    // ────────────────────────────────────────────────────────────────────────

    /**
     * HTTP 处理线程是普通线程，这里用 runBlocking 把 host 的 suspend 方法跑完。
     * 加超时是为了避免 host 卡死时连接被永久占用。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> awaitHost(block: suspend () -> T): T {
        var result: Any? = null
        var finished = false
        runBlocking {
            finished = withTimeoutOrNull(HOST_CALL_TIMEOUT_MS) {
                result = block()
                true
            } == true
        }
        if (!finished) throw HostTimeoutException()
        return result as T
    }

    // ────────────────────────────────────────────────────────────────────────
    // 参数校验
    // ────────────────────────────────────────────────────────────────────────

    private fun requireText(args: JSONObject, key: String): String {
        val raw = args.opt(key)
        val text = when (raw) {
            null, JSONObject.NULL -> ""
            is String -> raw
            is Number, is Boolean -> raw.toString()
            else -> ""
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw ToolFault("缺少必填参数 $key")
        return trimmed
    }

    private fun requireInt(args: JSONObject, key: String, min: Int, max: Int): Int {
        val raw = args.opt(key)
        if (raw == null || raw == JSONObject.NULL) throw ToolFault("缺少必填参数 $key（范围 $min-$max）")
        val number = toIntOrNull(raw) ?: throw ToolFault("参数 $key 必须是 $min-$max 之间的整数")
        if (number < min || number > max) throw ToolFault("参数 $key 超出范围（$min-$max）")
        return number
    }

    private fun optInt(args: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        val raw = args.opt(key)
        if (raw == null || raw == JSONObject.NULL) return default
        if (raw is String && raw.isBlank()) return default
        val number = toIntOrNull(raw) ?: throw ToolFault("参数 $key 必须是 $min-$max 之间的整数")
        if (number < min || number > max) throw ToolFault("参数 $key 超出范围（$min-$max）")
        return number
    }

    private fun requireBoolean(args: JSONObject, key: String): Boolean {
        val raw = args.opt(key)
        if (raw == null || raw == JSONObject.NULL) throw ToolFault("缺少必填参数 $key（true/false）")
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> throw ToolFault("参数 $key 必须是 true 或 false")
            }
            else -> throw ToolFault("参数 $key 必须是 true 或 false")
        }
    }

    private fun requireEnum(args: JSONObject, key: String, allowed: List<String>): String {
        val raw = args.opt(key)
        val value = when (raw) {
            null, JSONObject.NULL -> ""
            is String -> raw
            else -> raw.toString()
        }.trim().uppercase()
        if (value.isEmpty()) throw ToolFault("缺少必填参数 $key（可选值：${allowed.joinToString("/")}）")
        if (value !in allowed) {
            throw ToolFault("参数 $key 不支持「$value」，可选值：${allowed.joinToString("/")}")
        }
        return value
    }

    private fun optEnum(args: JSONObject, key: String, allowed: List<String>): String? {
        val raw = args.opt(key)
        if (raw == null || raw == JSONObject.NULL) return null
        val value = raw.toString().trim().uppercase()
        if (value.isEmpty()) return null
        if (value !in allowed) {
            throw ToolFault("参数 $key 不支持「$value」，可选值：${allowed.joinToString("/")}")
        }
        return value
    }

    private fun requireDate(args: JSONObject, key: String): String {
        val date = requireText(args, key)
        checkDate(date, key)
        return date
    }

    private fun optDate(args: JSONObject, key: String) {
        val raw = args.opt(key)
        if (raw == null || raw == JSONObject.NULL) return
        val date = raw.toString().trim()
        if (date.isEmpty()) return
        checkDate(date, key)
    }

    private fun checkDate(date: String, key: String) {
        if (!DATE_PATTERN.matches(date)) {
            throw ToolFault("参数 $key 必须是 yyyy-MM-dd 格式的日期，例如 2026-01-31")
        }
    }

    private fun toIntOrNull(raw: Any): Int? = when (raw) {
        is Number -> {
            val value = raw.toDouble()
            if (value.isFinite() && value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                value.toInt()
            } else {
                null
            }
        }
        is String -> {
            val text = raw.trim()
            text.toIntOrNull() ?: text.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
                ?.toInt()
        }
        else -> null
    }

    // ────────────────────────────────────────────────────────────────────────
    // 结果与错误
    // ────────────────────────────────────────────────────────────────────────

    private fun pretty(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        else -> value.toString()
    }

    private fun reason(t: Throwable): String = when (t) {
        is ToolFault -> t.reason
        is HostTimeoutException -> "操作超时（${HOST_CALL_TIMEOUT_MS / 1000} 秒），请稍后再试"
        is InterruptedException -> "操作被中断"
        is SecurityException -> "没有权限执行该操作"
        else -> {
            val message = t.message?.trim()
            if (message.isNullOrEmpty()) "内部错误（${t.javaClass.simpleName}）" else message.take(RESULT_MESSAGE_LIMIT)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // JSON Schema 组装
    // ────────────────────────────────────────────────────────────────────────

    private fun JSONArray.toolDef(
        name: String,
        description: String,
        properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
    ): JSONArray = put(
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("inputSchema", schema(properties, required))
        },
    )

    private fun schema(properties: JSONObject, required: List<String>): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", properties)
        put("required", JSONArray(required))
        put("additionalProperties", false)
    }

    private fun stringProperty(
        description: String,
        enumValues: List<String>? = null,
        pattern: String? = null,
    ): JSONObject = JSONObject().apply {
        put("type", "string")
        if (pattern != null) put("pattern", pattern)
        if (enumValues != null) put("enum", JSONArray(enumValues))
        put("description", description)
    }

    private fun intProperty(
        description: String,
        minimum: Int? = null,
        maximum: Int? = null,
        defaultValue: Int? = null,
    ): JSONObject = JSONObject().apply {
        put("type", "integer")
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
        if (defaultValue != null) put("default", defaultValue)
        put("description", description)
    }

    private fun boolProperty(description: String): JSONObject = JSONObject().apply {
        put("type", "boolean")
        put("description", description)
    }
}

/**
 * resources/list / resources/read 的实现。
 * 说明：`rilan://news` 先按「全部分类」请 host 汇总，拿不到时再逐板块合并，避免依赖某一种 host 实现。
 */
object McpResources {

    private data class Spec(val uri: String, val name: String, val description: String)

    private val SPECS = listOf(
        Spec("rilan://briefing", "今日简报数据", "天气摘要与今日各板块内容，用于生成每日简报"),
        Spec("rilan://weather", "当前天气", "当前天气、逐小时与未来几天预报，来自本机缓存"),
        Spec("rilan://schedules", "日程列表", "全部日程，含日期、时间、重要程度、类型、重复规则与 id"),
        Spec("rilan://sources", "信息源列表", "来源插件与内置信息源及其启用状态"),
        Spec("rilan://news", "新闻列表", "本机已缓存的各板块新闻与热搜条目"),
    )

    val URIS: List<String> = SPECS.map { it.uri }

    fun definitions(): JSONArray = JSONArray().apply {
        for (spec in SPECS) {
            put(
                JSONObject().apply {
                    put("uri", spec.uri)
                    put("name", spec.name)
                    put("description", spec.description)
                    put("mimeType", "application/json")
                },
            )
        }
    }

    fun read(host: McpHost, uri: String): JSONObject {
        val text = when (uri) {
            "rilan://briefing" -> prettyJson(host.briefing())
            "rilan://weather" -> host.weather()?.let { prettyJson(it) } ?: EMPTY_WEATHER_JSON
            "rilan://schedules" -> prettyJson(host.listSchedules())
            "rilan://sources" -> prettyJson(host.listSources())
            "rilan://news" -> prettyJson(allNews(host))
            else -> throw IllegalArgumentException("未知资源：$uri")
        }
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("uri", uri)
                    put("mimeType", "application/json")
                    put("text", text)
                })
            })
        }
    }

    private fun prettyJson(value: JSONObject): String = value.toString(2)

    private fun prettyJson(value: JSONArray): String = value.toString(2)

    private fun allNews(host: McpHost): JSONArray {
        // host.news(null, n) 的 n 是「每个板块 n 条」；这里取 6，整体控制在 40 条以内。
        val direct = runCatching { host.news(null, 6) }.getOrNull()
        if (direct != null && direct.length() > 0) return direct

        val merged = JSONArray()
        for (category in NEWS_CATEGORIES) {
            val part = runCatching { host.news(category, 6) }.getOrNull() ?: continue
            for (index in 0 until part.length()) {
                if (merged.length() >= 40) return merged
                val item = part.optJSONObject(index) ?: continue
                merged.put(item)
            }
        }
        return merged
    }
}
