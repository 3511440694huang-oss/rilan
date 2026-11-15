package app.rilan.daily.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class RilanMcpServerTest {

    private lateinit var server: RilanMcpServer
    private val token = "test-token-1234567890"
    private val port = 18765

    @Before
    fun setUp() {
        server = RilanMcpServer(FakeHost(), token, port)
        val result = server.start()
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun post(body: JSONObject, auth: String? = token): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        if (auth != null) conn.setRequestProperty("Authorization", "Bearer $auth")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        return code to text
    }

    private fun rpc(method: String, params: JSONObject = JSONObject(), id: Int = 1): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

    @Test
    fun initializeAndListTools() {
        val (code, text) = post(
            rpc("initialize", JSONObject().apply {
                put("protocolVersion", "2025-06-18")
                put("clientInfo", JSONObject().put("name", "test"))
            })
        )
        assertEquals(200, code)
        val result = JSONObject(text).getJSONObject("result")
        assertEquals("2025-06-18", result.getString("protocolVersion"))
        assertEquals("rilan", result.getJSONObject("serverInfo").getString("name"))

        val (listCode, listText) = post(rpc("tools/list", id = 2))
        assertEquals(200, listCode)
        val tools = JSONObject(listText).getJSONObject("result").getJSONArray("tools")
        assertTrue(tools.length() >= 19)
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        assertTrue("save_period_summary" in names)
        assertTrue("add_source" in names)
    }

    @Test
    fun toolCallReturnsHostData() {
        val (code, text) = post(
            rpc("tools/call", JSONObject().apply {
                put("name", "get_weather")
                put("arguments", JSONObject())
            })
        )
        assertEquals(200, code)
        val result = JSONObject(text).getJSONObject("result")
        assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("晴"))
    }

    @Test
    fun rejectsWithoutToken() {
        val (code, _) = post(rpc("tools/list"), auth = null)
        assertEquals(401, code)
    }

    @Test
    fun unsupportedProtocolFallsBackToLatest() {
        val (code, text) = post(
            rpc("initialize", JSONObject().apply {
                put("protocolVersion", "2099-01-01")
                put("clientInfo", JSONObject().put("name", "test"))
            })
        )
        assertEquals(200, code)
        assertEquals(
            McpJsonRpc.DEFAULT_PROTOCOL_VERSION,
            JSONObject(text).getJSONObject("result").getString("protocolVersion"),
        )
    }

    @Test
    fun deleteEndsSession() {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", "Bearer $token")
        assertEquals(204, conn.responseCode)
        conn.disconnect()
    }

    @Test
    fun notificationReturns202() {
        val (code, body) = post(
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }
        )
        assertEquals(202, code)
        assertTrue(body.isBlank())
    }

    private class FakeHost : McpHost {
        override fun serverVersion() = "test"
        override fun briefing(): JSONObject = JSONObject().put("date", "2026-11-06").put("sections", JSONArray())
        override fun weather(): JSONObject = JSONObject()
            .put("available", true)
            .put("city", "北京")
            .put("condition", "晴")
            .put("temperature", 15)
        override fun news(category: String?, limit: Int): JSONArray = JSONArray().put(JSONObject().put("title", "t"))
        override fun searchNews(query: String, days: Int): JSONArray = JSONArray()
        override fun listSchedules(): JSONArray = JSONArray()
        override suspend fun createSchedule(args: JSONObject): JSONObject = JSONObject().put("id", "1")
        override suspend fun updateSchedule(args: JSONObject): JSONObject = JSONObject().put("id", "1")
        override suspend fun deleteSchedule(id: String): Boolean = true
        override fun listSources(): JSONArray = JSONArray()
        override suspend fun addSource(pluginJson: String): JSONObject = JSONObject().put("ok", true)
        override suspend fun removeSource(id: String): Boolean = true
        override suspend fun setSourceEnabled(id: String, enabled: Boolean): Boolean = true
        override suspend fun refreshContent(): String = "刷新中"
        override suspend fun setCity(city: String): String = "已设置"
        override suspend fun setReminderTime(hour: Int, minute: Int): String = "已设置"
        override fun records(period: String): JSONArray = JSONArray()
        override suspend fun saveWeatherInsight(text: String): Boolean = true
        override suspend fun saveDailyBrief(text: String): Boolean = true
        override suspend fun savePeriodSummary(period: String, content: String): JSONObject = JSONObject()
    }
}
