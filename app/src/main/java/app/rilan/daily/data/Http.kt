package app.rilan.daily.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

class HttpException(val statusCode: Int, message: String) : Exception(message)

/** GET 的原始结果：状态码、正文和响应头（小写键），304 也走这条路径。 */
private data class HttpRaw(
    val status: Int,
    val bytes: ByteArray,
    val headers: Map<String, String>,
)

/**
 * 极简 HTTP 客户端：不引第三方库，减少包体和构建风险。
 * 所有请求都带移动端 UA 与中文 Accept-Language。
 *
 * 连接复用：成功读完响应后**不调用** `disconnect()`，让 HttpURLConnection
 * 把 TLS 连接还给连接池，下一轮十几个来源刷新时省掉重新握手。
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 14_000,
    ): String = withContext(Dispatchers.IO) {
        val raw = requestRaw(url, headers, connectTimeoutMs, readTimeoutMs)
        decode(raw.bytes, headers["Accept-Charset"])
    }

    /**
     * 带条件请求的文本 GET：第一次全量下载，之后带 `If-None-Match` /
     * `If-Modified-Since`，服务器回 304 时直接复用本机缓存正文。
     *
     * 信息流是十几个 RSS 源，能命中 304 的源不用再传 60–160 KB。
     */
    suspend fun getTextCached(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cacheKey: String,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 14_000,
    ): String = withContext(Dispatchers.IO) {
        val cached = HttpCache.get(cacheKey)
        val requestHeaders = if (cached == null) headers else headers.toMutableMap().apply {
            cached.etag?.let { put("If-None-Match", it) }
            cached.lastModified?.let { put("If-Modified-Since", it) }
        }
        val raw = requestRaw(url, requestHeaders, connectTimeoutMs, readTimeoutMs)
        if (raw.status == 304) {
            return@withContext cached?.body
                ?: throw HttpException(304, "服务端返回 304，但本机没有缓存正文")
        }
        val text = decode(raw.bytes, raw.headers["content-type"] ?: headers["Accept-Charset"])
        HttpCache.put(cacheKey, text, raw.headers["etag"], raw.headers["last-modified"])
        text
    }

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
    ): ByteArray = withContext(Dispatchers.IO) {
        requestRaw(url, headers, connectTimeoutMs, readTimeoutMs).bytes
    }

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 90_000,
    ): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            doOutput = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { input ->
                val decoded = if (conn.contentEncoding?.contains("gzip", true) == true) GZIPInputStream(input) else input
                decode(decoded.readBytes(), conn.contentType)
            } ?: ""
            if (code !in 200..299) {
                throw HttpException(code, errorMessage(code, text))
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun requestRaw(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpRaw {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, application/xml, text/xml, text/html, */*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            setRequestProperty("Accept-Encoding", "gzip")
            // 默认的 keep-alive 会把同一个域名的 TLS 连接复用起来，
            // 十几个来源连续刷新时能省掉大半的连接建立时间。
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        var keepAlive = false
        try {
            val code = conn.responseCode
            val responseHeaders = conn.headerFields.orEmpty()
                .mapNotNull { (name, values) ->
                    name?.lowercase()?.let { it to values.firstOrNull().orEmpty() }
                }
                .toMap()

            if (code == 304) {
                keepAlive = true
                return HttpRaw(code, ByteArray(0), responseHeaders)
            }
            if (code !in 200..299) {
                val text = runCatching {
                    conn.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8)
                }.getOrNull()
                throw HttpException(code, errorMessage(code, text.orEmpty()))
            }
            val input = if (conn.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(conn.inputStream)
            } else conn.inputStream
            val bytes = input.use { it.readBytes() }
            keepAlive = true
            return HttpRaw(code, bytes, responseHeaders)
        } finally {
            // 成功读完就把连接还给连接池；出错时主动断开，避免半截连接占着池子。
            if (!keepAlive) runCatching { conn.disconnect() }
        }
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val charset = when {
            contentType?.contains("charset=gbk", true) == true -> Charset.forName("GBK")
            contentType?.contains("charset=gb2312", true) == true -> Charset.forName("GBK")
            else -> Charsets.UTF_8
        }
        return bytes.toString(charset)
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(120)
        return when (code) {
            401 -> "认证失败（401）"
            402 -> "账户余额不足（402）"
            403 -> "访问被拒绝（403）"
            404 -> "接口不存在（404）"
            429 -> "请求太频繁，请稍后再试（429）"
            in 500..599 -> "服务端暂时不可用（$code）"
            else -> "网络请求失败（$code）${if (detail.isNotBlank()) "：$detail" else ""}"
        }
    }
}

/** 条件请求命中的缓存正文。 */
data class HttpCacheEntry(
    val body: String,
    val etag: String?,
    val lastModified: String?,
    val storedAt: Long,
)

/** 条件请求用的轻量正文缓存；按 URL/插件 id 分开，最多留 48 份。 */
object HttpCache {

    private const val MAX_ENTRIES = 48
    private val entries = ConcurrentHashMap<String, HttpCacheEntry>()

    fun get(key: String): HttpCacheEntry? = entries[key]

    fun put(key: String, body: String, etag: String?, lastModified: String?) {
        if (entries.size >= MAX_ENTRIES && !entries.containsKey(key)) entries.clear()
        entries[key] = HttpCacheEntry(body, etag, lastModified, System.currentTimeMillis())
    }

    fun clear() = entries.clear()
}

fun ByteArray.toUtf8(): String = toString(Charsets.UTF_8)

fun ByteArrayOutputStream.text(): String = toString(Charsets.UTF_8.name())
