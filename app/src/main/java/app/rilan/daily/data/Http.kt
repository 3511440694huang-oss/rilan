package app.rilan.daily.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

class HttpException(val statusCode: Int, message: String) : Exception(message)

/**
 * 极简 HTTP 客户端：不引第三方库，减少包体和构建风险。
 * 所有请求都带移动端 UA 与中文 Accept-Language。
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 18_000,
    ): String = withContext(Dispatchers.IO) {
        val bytes = requestBytes(url, headers, connectTimeoutMs, readTimeoutMs)
        decode(bytes, headers["Accept-Charset"])
    }

    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
    ): ByteArray = withContext(Dispatchers.IO) {
        requestBytes(url, headers, connectTimeoutMs, readTimeoutMs)
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

    private fun requestBytes(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, application/xml, text/xml, text/html, */*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Connection", "close")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
                throw HttpException(code, errorMessage(code, text.orEmpty()))
            }
            val input = if (conn.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(conn.inputStream)
            } else conn.inputStream
            return input.use { it.readBytes() }
        } finally {
            conn.disconnect()
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

fun ByteArray.toUtf8(): String = toString(Charsets.UTF_8)

fun ByteArrayOutputStream.text(): String = toString(Charsets.UTF_8.name())
