package app.rilan.daily.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DeepSeekClientTest {

    private val client = DeepSeekClient()

    @Test
    fun parsesAssistantContent() {
        val body = """
            {
              "id":"x",
              "choices":[{"index":0,"message":{"role":"assistant","content":"今天适合把重要的事放在上午。"},"finish_reason":"stop"}]
            }
        """.trimIndent()
        assertEquals("今天适合把重要的事放在上午。", client.parseContent(body))
    }

    @Test
    fun rejectsEmptyContentWithReason() {
        val body = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"length"}]}
        """.trimIndent()
        val error = assertThrows(IOException::class.java) { client.parseContent(body) }
        assertEquals("DeepSeek 输出被长度限制截断了，请重试", error.message)
    }

    @Test
    fun surfacesApiErrorMessage() {
        val body = """{"error":{"message":"Authentication Fails","type":"authentication_error"}}"""
        val error = assertThrows(IOException::class.java) { client.parseContent(body) }
        assertEquals("Authentication Fails", error.message)
    }
}
