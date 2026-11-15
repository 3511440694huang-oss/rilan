package app.rilan.daily.mcp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 「日览」的 MCP 服务端（MCP Streamable HTTP，只监听 127.0.0.1）。
 *
 * - 传输：`java.net.ServerSocket` + 每连接一线程的缓存线程池（上限 16），支持 keep-alive 与批量 JSON-RPC。
 * - 鉴权：除 OPTIONS 外都要求 `Authorization: Bearer <token>`，常量时间比较；token 为空一律拒绝。
 * - 工具：见 [McpTools]；资源：见 [McpResources]。所有 host 异常都收口成 JSON-RPC error 或工具 isError。
 * - 不依赖任何 Android API，也不打日志（避免 token 与请求体泄漏）。
 */
class RilanMcpServer(
    private val host: McpHost,
    private val token: String,
    private val port: Int = 8765,
) : McpServer {

    private val _state = MutableStateFlow(
        McpServerState(host = BIND_HOST, port = port, token = token),
    )
    override val state: StateFlow<McpServerState> = _state.asStateFlow()

    private val threadSeq = AtomicInteger(0)
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var workers: ThreadPoolExecutor = createWorkerPool()

    // ────────────────────────────────────────────────────────────────────────
    // 生命周期
    // ────────────────────────────────────────────────────────────────────────

    @Synchronized
    override fun start(): Result<Unit> {
        if (running) return Result.success(Unit)
        if (port !in 1..65535) return fail("端口 $port 无效（应在 1-65535 之间）")

        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(BIND_HOST, port), BACKLOG)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            closeQuietly(socket)
            return fail(startFailureMessage(t))
        }

        workers = createWorkerPool()
        serverSocket = socket
        running = true
        _state.update {
            it.copy(
                running = true,
                host = BIND_HOST,
                port = port,
                token = token,
                startedAt = System.currentTimeMillis(),
                requestCount = 0,
                lastRequestAt = 0L,
                lastError = null,
            )
        }

        val thread = Thread({ acceptLoop(socket) }, "rilan-mcp-accept")
        thread.isDaemon = true
        acceptThread = thread
        return try {
            thread.start()
            Result.success(Unit)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            running = false
            serverSocket = null
            acceptThread = null
            closeQuietly(socket)
            workers.shutdownNow()
            fail("启动 MCP 服务失败：${reason(t)}")
        }
    }

    @Synchronized
    override fun stop() {
        running = false
        val server = serverSocket
        serverSocket = null
        closeQuietly(server)
        val thread = acceptThread
        acceptThread = null
        thread?.interrupt()
        for (client in clients) closeQuietly(client)
        clients.clear()
        workers.shutdownNow()
        sessionId = null
        _state.update { it.copy(running = false) }
    }

    private fun fail(message: String): Result<Unit> {
        _state.update {
            it.copy(running = false, host = BIND_HOST, port = port, token = token, lastError = message)
        }
        return Result.failure(IllegalStateException(message))
    }

    private fun startFailureMessage(t: Throwable): String = when (t) {
        is BindException -> "端口 $port 被占用"
        is SocketException -> "启动 MCP 服务失败：${t.message ?: "网络接口不可用"}"
        is IOException -> "启动 MCP 服务失败：${t.message ?: "I/O 错误"}"
        else -> "启动 MCP 服务失败：${reason(t)}"
    }

    private fun createWorkerPool(): ThreadPoolExecutor {
        val factory = ThreadFactory { runnable ->
            val thread = Thread(runnable, "rilan-mcp-worker-${threadSeq.incrementAndGet()}")
            thread.isDaemon = true
            thread
        }
        return ThreadPoolExecutor(0, MAX_WORKERS, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), factory)
    }

    // ────────────────────────────────────────────────────────────────────────
    // 接入连接
    // ────────────────────────────────────────────────────────────────────────

    private fun acceptLoop(server: ServerSocket) {
        while (running && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                if (!running || server.isClosed) break
                if (t is SocketException) break
                sleepQuietly(80L)
                continue
            }
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = IDLE_TIMEOUT_MS
                clients.add(socket)
                if (!running) {
                    clients.remove(socket)
                    closeQuietly(socket)
                    break
                }
                workers.execute { handleConnection(socket) }
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                clients.remove(socket)
                closeQuietly(socket)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        var keepAlive = true
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            while (keepAlive && running && !socket.isClosed) {
                socket.soTimeout = IDLE_TIMEOUT_MS
                val requestLine = readLine(input) ?: break
                if (requestLine.isBlank()) continue
                val parts = REQUEST_LINE_SPLIT.split(requestLine.trim())
                if (parts.size < 2) {
                    writeJson(output, 400, McpJsonRpc.parseError().toString(), keepAlive = false)
                    break
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = readHeaders(input)
                keepAlive = wantsKeepAlive(parts.getOrNull(2), headers)
                keepAlive = when (method) {
                    "OPTIONS" -> {
                        writeEmpty(output, 204, PREFLIGHT_HEADERS, keepAlive)
                        keepAlive
                    }

                    "GET" -> when {
                        path != MCP_PATH -> {
                            writeJson(output, 404, NOT_FOUND_BODY, keepAlive = false)
                            false
                        }

                        !isAuthorized(headers) -> {
                            writeUnauthorized(output)
                            false
                        }

                        else -> {
                            serveSse(socket, input, output)
                            false
                        }
                    }

                    "POST" -> if (path != MCP_PATH) {
                        writeJson(output, 404, NOT_FOUND_BODY, keepAlive = false)
                        false
                    } else {
                        handlePost(output, input, headers, keepAlive)
                    }

                    "DELETE" -> {
                        if (path != MCP_PATH) {
                            writeJson(output, 404, NOT_FOUND_BODY, keepAlive = false)
                            false
                        } else if (!isAuthorized(headers)) {
                            writeUnauthorized(output)
                            false
                        } else {
                            sessionId = null
                            writeEmpty(output, 204, emptyList(), keepAlive)
                            keepAlive
                        }
                    }

                    else -> {
                        writeJson(
                            output,
                            405,
                            METHOD_NOT_ALLOWED_BODY,
                            keepAlive = false,
                            extra = listOf("Allow" to ALLOW_METHODS),
                        )
                        false
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            // 连接级异常（客户端断开、超时、协议错误）：结束该连接即可
        } finally {
            clients.remove(socket)
            closeQuietly(socket)
        }
    }

    private fun handlePost(
        output: OutputStream,
        input: InputStream,
        headers: Map<String, String>,
        keepAlive: Boolean,
    ): Boolean {
        if (!isAuthorized(headers)) {
            writeUnauthorized(output)
            return false
        }
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            output.write(CONTINUE_RESPONSE.toByteArray(Charsets.UTF_8))
            output.flush()
        }
        val body = try {
            readRequestBody(input, headers)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            if (t is RequestTooLargeException) {
                writeJson(output, 413, PAYLOAD_TOO_LARGE_BODY, keepAlive = false)
            } else {
                writeJson(output, 400, McpJsonRpc.parseError().toString(), keepAlive = false)
            }
            return false
        }

        val raw = try {
            String(body, Charsets.UTF_8)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            ""
        }
        val reply = dispatchPayload(raw)
        return when (reply.status) {
            202 -> {
                writeEmpty(output, 202, emptyList(), keepAlive)
                keepAlive
            }

            200 -> {
                writeJson(output, 200, reply.body ?: "", keepAlive)
                keepAlive
            }

            else -> {
                writeJson(output, reply.status, reply.body ?: "", keepAlive = false)
                false
            }
        }
    }

    /** GET /mcp：SSE 长连接，只发注释心跳，不主动推消息；客户端断开即结束。 */
    private fun serveSse(socket: Socket, input: InputStream, output: OutputStream) {
        val head = StringBuilder(160)
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: keep-alive\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("X-Accel-Buffering: no\r\n")
        sessionId?.let { head.append("Mcp-Session-Id: ").append(it).append("\r\n") }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        output.write(SSE_COMMENT.toByteArray(Charsets.UTF_8))
        output.flush()

        // 用短超时读来感知客户端断开（FIN/RST）；读超时是正常的空闲状态。
        socket.soTimeout = SSE_POLL_MS
        var lastBeatAt = System.currentTimeMillis()
        while (running && !socket.isClosed) {
            val now = System.currentTimeMillis()
            if (now - lastBeatAt >= SSE_INTERVAL_MS) {
                output.write(SSE_COMMENT.toByteArray(Charsets.UTF_8))
                output.flush()
                lastBeatAt = now
            }
            try {
                val value = input.read()
                if (value < 0) break
            } catch (t: SocketTimeoutException) {
                // 空闲，继续等下一次心跳
            } catch (t: IOException) {
                break
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // JSON-RPC 分发
    // ────────────────────────────────────────────────────────────────────────

    private fun dispatchPayload(raw: String): HttpReply {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return HttpReply(400, McpJsonRpc.parseError().toString())
        return try {
            if (trimmed.startsWith("[")) dispatchBatch(trimmed) else dispatchSingle(trimmed)
        } catch (t: JSONException) {
            HttpReply(400, McpJsonRpc.parseError().toString())
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            HttpReply(400, McpJsonRpc.parseError().toString())
        }
    }

    private fun dispatchSingle(raw: String): HttpReply {
        val message = JSONObject(raw)
        countRequest()
        val response = safeProcess(message)
        return if (response == null) HttpReply(202, null) else HttpReply(200, response.toString())
    }

    private fun dispatchBatch(raw: String): HttpReply {
        val batch = JSONArray(raw)
        if (batch.length() == 0) {
            return HttpReply(
                200,
                McpJsonRpc.errorResponse(
                    null,
                    McpJsonRpc.INVALID_REQUEST,
                    "无效的请求：批量数组为空",
                ).toString(),
            )
        }
        val responses = JSONArray()
        for (index in 0 until batch.length()) {
            countRequest()
            val element = batch.optJSONObject(index)
            if (element == null) {
                responses.put(
                    McpJsonRpc.errorResponse(null, McpJsonRpc.INVALID_REQUEST, "无效的请求：不是 JSON 对象"),
                )
                continue
            }
            val response = safeProcess(element) ?: continue
            responses.put(response)
        }
        return if (responses.length() == 0) HttpReply(202, null) else HttpReply(200, responses.toString())
    }

    private fun countRequest() {
        _state.update {
            it.copy(
                requestCount = it.requestCount + 1,
                lastRequestAt = System.currentTimeMillis(),
            )
        }
    }

    private fun safeProcess(message: JSONObject): JSONObject? = try {
        processMessage(message)
    } catch (fault: RpcFault) {
        replyError(message, fault.code, fault.reason)
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        replyError(message, McpJsonRpc.INTERNAL_ERROR, "服务器内部错误：${reason(t)}")
    }

    private fun replyError(message: JSONObject, code: Int, text: String): JSONObject? =
        if (McpJsonRpc.isRequest(message)) McpJsonRpc.errorResponse(message.opt("id"), code, text) else null

    private fun processMessage(message: JSONObject): JSONObject? {
        if (!McpJsonRpc.isRequest(message)) {
            // 通知（notifications/initialized 等）不需要回复
            return null
        }
        val id = message.opt("id")
        val method = message.opt("method") as? String
        if (method.isNullOrBlank()) {
            return McpJsonRpc.errorResponse(id, McpJsonRpc.INVALID_REQUEST, "无效的请求：缺少 method")
        }
        val params = message.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize" -> McpJsonRpc.resultResponse(id, initializeResult(params))
                "ping" -> McpJsonRpc.resultResponse(id, JSONObject())
                "tools/list" -> McpJsonRpc.resultResponse(id, JSONObject().put("tools", McpTools.definitions()))
                "tools/call" -> McpJsonRpc.resultResponse(id, callTool(params))
                "resources/list" ->
                    McpJsonRpc.resultResponse(id, JSONObject().put("resources", McpResources.definitions()))
                "resources/templates/list" ->
                    McpJsonRpc.resultResponse(id, JSONObject().put("resourceTemplates", JSONArray()))
                "resources/read" -> McpJsonRpc.resultResponse(id, readResource(params))
                "prompts/list" -> McpJsonRpc.resultResponse(id, JSONObject().put("prompts", JSONArray()))
                else -> McpJsonRpc.errorResponse(id, McpJsonRpc.METHOD_NOT_FOUND, "不支持的方法：$method")
            }
        } catch (fault: RpcFault) {
            McpJsonRpc.errorResponse(id, fault.code, fault.reason)
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            McpJsonRpc.errorResponse(id, McpJsonRpc.INTERNAL_ERROR, "服务器内部错误：${reason(t)}")
        }
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val requested = optText(params, "protocolVersion")
        val version = McpJsonRpc.negotiateProtocolVersion(
            requested.ifEmpty { McpJsonRpc.DEFAULT_PROTOCOL_VERSION }
        )
        ensureSessionId()
        return JSONObject().apply {
            put("protocolVersion", version)
            put("capabilities", JSONObject().apply {
                put("tools", JSONObject().apply { put("listChanged", false) })
                put("resources", JSONObject().apply {
                    put("subscribe", false)
                    put("listChanged", false)
                })
            })
            put("serverInfo", JSONObject().apply {
                put("name", McpJsonRpc.SERVER_NAME)
                put("version", hostVersion())
            })
            put("instructions", McpJsonRpc.INSTRUCTIONS)
        }
    }

    @Synchronized
    private fun ensureSessionId(): String {
        val current = sessionId
        if (current != null) return current
        val created = UUID.randomUUID().toString()
        sessionId = created
        return created
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = optText(params, "name")
        if (name.isEmpty()) return McpTools.errorResult("缺少工具名 name")
        val args = when (val rawArgs = params.opt("arguments")) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> rawArgs
            else -> return McpTools.errorResult("arguments 必须是 JSON 对象")
        }
        return McpTools.call(host, name, args)
    }

    private fun readResource(params: JSONObject): JSONObject {
        val uri = optText(params, "uri")
        if (uri.isEmpty()) throw RpcFault(McpJsonRpc.INVALID_PARAMS, "缺少资源 uri")
        if (uri !in McpResources.URIS) throw RpcFault(McpJsonRpc.RESOURCE_NOT_FOUND, "资源不存在：$uri")
        return McpResources.read(host, uri)
    }

    /** Android 的 JSONObject.optString 会把 JSON null 变成字符串 "null"，这里统一成空串。 */
    private fun optText(params: JSONObject, key: String): String {
        val raw = params.opt(key)
        return when (raw) {
            null, JSONObject.NULL -> ""
            is String -> if (raw.trim() == "null") "" else raw.trim()
            else -> raw.toString().trim()
        }
    }

    private fun hostVersion(): String = try {
        host.serverVersion().trim().ifEmpty { "1.0.0" }
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        "1.0.0"
    }

    private fun reason(t: Throwable): String {
        val message = t.message?.trim()
        return if (message.isNullOrEmpty()) t.javaClass.simpleName else message.take(200)
    }

    // ────────────────────────────────────────────────────────────────────────
    // HTTP 请求读取
    // ────────────────────────────────────────────────────────────────────────

    /** 按字节读一行（ISO-8859-1 语义），超长部分丢弃但继续消费到行尾。 */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder(64)
        var sawByte = false
        while (true) {
            val value = input.read()
            if (value == -1) return if (sawByte) builder.toString() else null
            sawByte = true
            if (value == NEWLINE) {
                val length = builder.length
                if (length > 0 && builder[length - 1] == '\r') builder.setLength(length - 1)
                return builder.toString()
            }
            if (builder.length < MAX_LINE_CHARS) builder.append(Char(value))
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            total += line.length + 2
            if (total > MAX_HEADER_CHARS) throw IOException("请求头过大")
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            if (name.isEmpty()) continue
            val value = line.substring(separator + 1).trim()
            val existing = headers[name]
            headers[name] = if (existing.isNullOrEmpty()) value else "$existing, $value"
        }
        return headers
    }

    private fun readRequestBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val transferEncoding = headers["transfer-encoding"].orEmpty()
        if (transferEncoding.contains("chunked", ignoreCase = true)) {
            return readChunkedBody(input)
        }
        val rawLength = headers["content-length"]
        if (rawLength.isNullOrBlank()) return ByteArray(0)
        val length = rawLength.trim().toLongOrNull() ?: throw IOException("Content-Length 无效")
        if (length < 0L) throw IOException("Content-Length 无效")
        if (length > MAX_BODY_BYTES.toLong()) throw RequestTooLargeException()
        if (length == 0L) return ByteArray(0)
        return readExactly(input, length.toInt())
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw IOException("请求体不完整")
            offset += read
        }
        return buffer
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(1024)
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("分块请求体读取失败")
            if (sizeLine.isBlank()) continue
            val token = sizeLine.substringBefore(';').trim()
            val size = token.toIntOrNull(16) ?: throw IOException("分块长度无效")
            if (size < 0) throw IOException("分块长度无效")
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                return output.toByteArray()
            }
            if (size > MAX_BODY_BYTES || output.size() > MAX_BODY_BYTES - size) {
                throw RequestTooLargeException()
            }
            output.write(readExactly(input, size))
            readLine(input)
        }
    }

    private fun wantsKeepAlive(version: String?, headers: Map<String, String>): Boolean {
        val connection = headers["connection"]?.lowercase().orEmpty()
        if (connection.contains("close")) return false
        val http10 = version?.equals("HTTP/1.0", ignoreCase = true) == true
        return if (http10) connection.contains("keep-alive") else true
    }

    // ────────────────────────────────────────────────────────────────────────
    // 鉴权
    // ────────────────────────────────────────────────────────────────────────

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        if (token.isEmpty()) return false
        val header = headers["authorization"] ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val provided = header.substring("Bearer ".length).trim()
        if (provided.isEmpty()) return false
        return constantTimeEquals(token, provided)
    }

    /** 常量时间比较，避免按前缀逐字符提前返回。 */
    private fun constantTimeEquals(expected: String, provided: String): Boolean {
        val left = expected.toByteArray(Charsets.UTF_8)
        val right = provided.toByteArray(Charsets.UTF_8)
        var diff = left.size xor right.size
        val size = if (left.size > right.size) left.size else right.size
        for (index in 0 until size) {
            val a = if (index < left.size) left[index].toInt() else 0
            val b = if (index < right.size) right[index].toInt() else 0
            diff = diff or (a xor b)
        }
        return diff == 0
    }

    // ────────────────────────────────────────────────────────────────────────
    // HTTP 响应写出
    // ────────────────────────────────────────────────────────────────────────

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        body: ByteArray?,
        contentType: String?,
        extraHeaders: List<Pair<String, String>>,
        keepAlive: Boolean,
    ) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        if (contentType != null && body != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n")
        }
        if (status != 204) {
            head.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        }
        head.append("Cache-Control: no-store\r\n")
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n")
        val currentSession = sessionId
        if (currentSession != null) {
            head.append("Mcp-Session-Id: ").append(currentSession).append("\r\n")
        }
        for ((name, value) in extraHeaders) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.UTF_8))
        if (body != null && body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun writeJson(
        output: OutputStream,
        status: Int,
        json: String,
        keepAlive: Boolean,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        writeResponse(output, status, json.toByteArray(Charsets.UTF_8), JSON_CONTENT_TYPE, extra, keepAlive)
    }

    private fun writeEmpty(
        output: OutputStream,
        status: Int,
        extra: List<Pair<String, String>>,
        keepAlive: Boolean,
    ) {
        writeResponse(output, status, ByteArray(0), null, extra, keepAlive)
    }

    private fun writeUnauthorized(output: OutputStream) {
        writeJson(
            output,
            401,
            UNAUTHORIZED_BODY,
            keepAlive = false,
            extra = listOf("WWW-Authenticate" to "Bearer"),
        )
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun closeQuietly(closeable: Closeable?) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 小类型
    // ────────────────────────────────────────────────────────────────────────

    private data class HttpReply(val status: Int, val body: String?)

    private class RpcFault(val code: Int, val reason: String) : Exception(reason)

    private class RequestTooLargeException : IOException("请求体过大")

    private companion object {
        const val BIND_HOST = "127.0.0.1"
        const val MCP_PATH = "/mcp"
        const val BACKLOG = 16
        const val MAX_WORKERS = 16
        const val MAX_BODY_BYTES = 1024 * 1024
        const val IDLE_TIMEOUT_MS = 60_000
        const val SSE_INTERVAL_MS = 15_000L
        const val SSE_POLL_MS = 250
        const val SSE_COMMENT = ": ok\n\n"
        const val MAX_LINE_CHARS = 8 * 1024
        const val MAX_HEADER_CHARS = 32 * 1024
        const val NEWLINE = 10
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val ALLOW_METHODS = "GET, POST, DELETE, OPTIONS"
        const val UNAUTHORIZED_BODY = "{\"error\":\"unauthorized\"}"
        const val NOT_FOUND_BODY = "{\"error\":\"not found\"}"
        const val METHOD_NOT_ALLOWED_BODY = "{\"error\":\"method not allowed\"}"
        const val PAYLOAD_TOO_LARGE_BODY = "{\"error\":\"request body too large\"}"
        const val CONTINUE_RESPONSE = "HTTP/1.1 100 Continue\r\n\r\n"

        val REQUEST_LINE_SPLIT = Regex("\\s+")

        val PREFLIGHT_HEADERS: List<Pair<String, String>> = listOf(
            "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
            "Access-Control-Allow-Headers" to
                "Authorization, Content-Type, Accept, Mcp-Session-Id, MCP-Protocol-Version, Last-Event-ID",
            "Access-Control-Max-Age" to "86400",
            "Allow" to ALLOW_METHODS,
        )
    }
}
