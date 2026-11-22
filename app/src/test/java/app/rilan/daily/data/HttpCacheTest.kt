package app.rilan.daily.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * 条件请求（ETag → 304 复用缓存）与普通 GET 的回归测试。
 *
 * 用一个极小的 ServerSocket HTTP 服务器，不依赖 Android 运行时，也不用
 * com.sun.net.httpserver（Android 单元测试的 bootclasspath 里没有它）。
 */
class HttpCacheTest {

    private lateinit var server: ServerSocket
    private lateinit var thread: Thread

    private val hits = AtomicInteger(0)
    private val conditionalHits = AtomicInteger(0)

    @Before
    fun setUp() {
        server = ServerSocket(0)
        thread = Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (t: Throwable) {
                    break
                }
                Thread { handle(socket) }.start()
            }
        }, "http-cache-test")
        thread.isDaemon = true
        thread.start()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        thread.interrupt()
    }

    private fun handle(socket: Socket) {
        socket.use {
            val input = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = input.readLine() ?: return
            var etagHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("If-None-Match:", ignoreCase = true)) {
                    etagHeader = line.substringAfter(':').trim()
                }
            }
            val path = requestLine.split(" ").getOrNull(1).orEmpty()
            val output = it.getOutputStream()
            when {
                path.startsWith("/rss") -> {
                    hits.incrementAndGet()
                    if (etagHeader == "v1") {
                        conditionalHits.incrementAndGet()
                        output.write(
                            "HTTP/1.1 304 Not Modified\r\nETag: v1\r\nConnection: close\r\n\r\n"
                                .toByteArray(StandardCharsets.UTF_8),
                        )
                    } else {
                        val body = "<rss>hello</rss>".toByteArray(StandardCharsets.UTF_8)
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/xml; charset=utf-8\r\n" +
                            "ETag: v1\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        output.write(head.toByteArray(StandardCharsets.UTF_8))
                        output.write(body)
                    }
                }

                else -> {
                    val body = "plain-body".toByteArray(StandardCharsets.UTF_8)
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    output.write(head.toByteArray(StandardCharsets.UTF_8))
                    output.write(body)
                }
            }
            output.flush()
        }
    }

    private fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

    @Test
    fun conditionalRequestReusesBodyOn304() = runBlocking {
        HttpCache.clear()
        val first = Http.getTextCached(url("/rss"), cacheKey = "test-rss")
        assertEquals("<rss>hello</rss>", first)
        assertEquals(1, hits.get())

        val second = Http.getTextCached(url("/rss"), cacheKey = "test-rss")
        assertEquals("<rss>hello</rss>", second)
        assertEquals(2, hits.get())
        assertEquals(1, conditionalHits.get())
    }

    @Test
    fun plainGetStillWorks() = runBlocking {
        assertEquals("plain-body", Http.getText(url("/plain")))
    }
}
