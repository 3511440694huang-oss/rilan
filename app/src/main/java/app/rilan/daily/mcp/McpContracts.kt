package app.rilan.daily.mcp

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 日览向 MCP 暴露的能力边界。
 * MCP 协议层只做 JSON-RPC / 工具参数校验，真正的读写由 AppRepository 实现。
 */
interface McpHost {
    fun serverVersion(): String

    /** 天气 + 今日各板块摘要，给外部 AI 做每日简报。 */
    fun briefing(): JSONObject

    fun weather(): JSONObject?

    fun news(category: String?, limit: Int): JSONArray

    fun searchNews(query: String, days: Int): JSONArray

    fun listSchedules(): JSONArray

    suspend fun createSchedule(args: JSONObject): JSONObject

    suspend fun updateSchedule(args: JSONObject): JSONObject

    suspend fun deleteSchedule(id: String): Boolean

    fun listSources(): JSONArray

    suspend fun addSource(pluginJson: String): JSONObject

    suspend fun removeSource(id: String): Boolean

    suspend fun setSourceEnabled(id: String, enabled: Boolean): Boolean

    suspend fun refreshContent(): String

    suspend fun setCity(city: String): String

    suspend fun setReminderTime(hour: Int, minute: Int): String

    /** period: DAY/WEEK/MONTH/QUARTER/HALF_YEAR/YEAR；返回历史记录数组。 */
    fun records(period: String): JSONArray

    suspend fun saveWeatherInsight(text: String): Boolean

    suspend fun saveDailyBrief(text: String): Boolean

    suspend fun savePeriodSummary(period: String, content: String): JSONObject
}

data class McpServerState(
    val running: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 8765,
    val token: String = "",
    val requestCount: Int = 0,
    val startedAt: Long = 0L,
    val lastRequestAt: Long = 0L,
    val lastError: String? = null,
) {
    val endpoint: String get() = "http://$host:$port/mcp"
}

/** 设置页「检测连接」的结果；走本机回环，验证服务、令牌和工具列表。 */
data class McpTestState(
    val loading: Boolean = false,
    val ok: Boolean = false,
    val message: String? = null,
    val toolCount: Int = 0,
)

interface McpServer {
    val state: StateFlow<McpServerState>
    fun start(): Result<Unit>
    fun stop()
}
