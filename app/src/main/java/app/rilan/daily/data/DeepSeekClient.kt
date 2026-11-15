package app.rilan.daily.data

import app.rilan.daily.core.DEEPSEEK_MODEL_IDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * DeepSeek 官方 Chat Completions 客户端。
 *
 * 只做一件事：把两段消息发出去，拿回一段中文正文。
 * - 使用非思考模式，日报总结不需要暴露思维链，也更快、更省 token。
 * - API Key 只在请求头里出现，不写日志、不拼进错误信息。
 * - 所有失败都收口成带中文说明的 IOException，交给仓库展示。
 *
 * 官方文档：https://api-docs.deepseek.com/zh-cn/quick_start/your_first_api_call
 */
class DeepSeekClient {

    suspend fun complete(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int = 1_400,
    ): String = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) throw IOException("还没有填写 DeepSeek API Key")
        val safeModel = model.takeIf { it in DEEPSEEK_MODEL_IDS } ?: "deepseek-flash"

        val payload = JSONObject().apply {
            put("model", safeModel)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", system)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", user)
                })
            })
            put("thinking", JSONObject().put("type", "disabled"))
            put("temperature", 0.55)
            put("max_tokens", maxTokens.coerceIn(64, 8_192))
            put("stream", false)
        }

        val body = try {
            Http.postJson(
                url = ENDPOINT,
                body = payload.toString(),
                headers = mapOf(
                    "Authorization" to "Bearer $key",
                    "Accept" to "application/json",
                ),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 120_000,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            throw IOException(httpMessage(e.statusCode, e.message), e)
        } catch (e: IOException) {
            throw IOException("DeepSeek 网络连接失败：${e.message ?: "请检查网络"}", e)
        }

        parseContent(body)
    }

    /** 设置页的轻量连通性测试：只让模型回一个短句。 */
    suspend fun test(apiKey: String, model: String): String = complete(
        apiKey = apiKey,
        model = model,
        system = "你是连接测试助手，只回复用户要求的内容，不要解释。",
        user = "只回复三个字：连接成功",
        maxTokens = 16,
    )

    internal fun parseContent(raw: String): String {
        if (raw.isBlank()) throw IOException("DeepSeek 返回了空内容")
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw IOException("DeepSeek 返回内容无法解析", e)
        }

        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message").trim()
            throw IOException(message.ifBlank { "DeepSeek 请求失败" })
        }

        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw IOException("DeepSeek 没有返回结果")
        }
        val choice = choices.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val content = when (val value = message?.opt("content")) {
            null, JSONObject.NULL -> ""
            is String -> value
            else -> value.toString()
        }.trim()

        if (content.isNotEmpty()) return content

        val reason = choice?.optString("finish_reason").orEmpty()
        throw IOException(
            when (reason) {
                "length" -> "DeepSeek 输出被长度限制截断了，请重试"
                "content_filter" -> "这次内容被 DeepSeek 过滤了，请换个说法重试"
                "insufficient_system_resource" -> "DeepSeek 服务繁忙，请稍后重试"
                else -> "DeepSeek 返回了空内容，请重试"
            }
        )
    }

    private fun httpMessage(status: Int, fallback: String?): String = when (status) {
        400 -> "DeepSeek 请求格式不对：${fallback ?: "请检查模型设置"}"
        401 -> "DeepSeek API Key 无效，请到设置里检查"
        402 -> "DeepSeek 账户余额不足，请先充值"
        403 -> "DeepSeek 拒绝访问，请检查 API Key 权限"
        429 -> "DeepSeek 请求太频繁，请稍后再试"
        in 500..599 -> "DeepSeek 服务暂时不可用（$status），请稍后再试"
        else -> "DeepSeek 请求失败（$status）${fallback?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}"
    }

    private companion object {
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    }
}
