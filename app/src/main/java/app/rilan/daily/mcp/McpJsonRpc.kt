package app.rilan.daily.mcp

import org.json.JSONObject

/**
 * JSON-RPC 2.0 信封 + MCP 协议常量。
 *
 * 这里只做纯 JSON 组装，不涉及 HTTP 与 Android API，方便在纯 JVM 环境里覆盖测试。
 */
object McpJsonRpc {

    const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
    const val SERVER_NAME = "rilan"

    /** 服务端实际支持的协议版本，从新到旧；协商时不能谎报。 */
    val SUPPORTED_PROTOCOL_VERSIONS = listOf(
        "2025-06-18",
        "2025-03-26",
        "2024-11-05",
        "2024-10-07",
    )

    fun negotiateProtocolVersion(requested: String): String =
        requested.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: DEFAULT_PROTOCOL_VERSION

    const val INSTRUCTIONS =
        "日览 App 的本地 MCP 服务。可读取天气/新闻/日程，操作日程与来源插件，保存 AI 解读与周期总结。所有数据来自本机。"

    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val RESOURCE_NOT_FOUND = -32002

    fun resultResponse(id: Any?, result: JSONObject): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("result", result)
    }

    fun errorResponse(id: Any?, code: Int, message: String): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
    }

    /** 请求体无法解析成 JSON 时的标准错误（id 为 null）。 */
    fun parseError(): JSONObject = errorResponse(null, PARSE_ERROR, "JSON 解析失败")

    /**
     * 只有带非 null id 的消息才需要 reply；
     * 没有 id（或 id 为 null）的是通知，MCP 里 `notifications/initialized` 等都属于这一类。
     */
    fun isRequest(message: JSONObject): Boolean =
        message.has("id") && !message.isNull("id")

    fun idOf(message: JSONObject): Any? = if (isRequest(message)) message.opt("id") else null
}
