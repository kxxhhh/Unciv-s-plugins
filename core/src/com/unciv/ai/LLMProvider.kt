package com.unciv.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject

/**
 * LLM 网络错误分类，供上层决定重试/禁用/手动接管。
 */
sealed class LlmError(message: String) : Exception(message) {
    class Timeout(message: String = "请求超时") : LlmError(message)
    class RateLimited(message: String = "限流 (429)，请稍后重试") : LlmError(message)
    class ServerError(message: String = "服务端错误 (5xx)") : LlmError(message)
    class NetworkError(message: String = "网络错误") : LlmError(message)
    class InvalidResponse(message: String = "响应格式无效") : LlmError(message)
    class InvalidToolCall(message: String = "工具调用无效") : LlmError(message)
    class AuthError(message: String = "鉴权失败") : LlmError(message)
}

/** 一次 LLM 对话的结果 */
data class LlmTurnResult(
    val content: String?,
    val toolCalls: List<ParsedToolCall>,
    /** LLM 是否宣告本回合结束（如 end_turn 工具触发） */
    val finished: Boolean
)

/**
 * 与第三方大模型的交互层。游戏逻辑只依赖此接口，不直接依赖 HTTP 客户端。
 */
interface LLMProvider {
    /**
     * 执行一轮对话：发送 messages（含可选 tools），返回 assistant 回复。
     *
     * @param messages 消息历史，调用方维护
     * @param tools 可用的工具声明（可为空）
     * @param forceTool 若指定，强制 LLM 必须调用该工具（用于测试连接等）
     * @throws [LlmError] 网络/鉴权/解析错误，由调用方分类处理
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ChatTool>? = null,
        forceTool: String? = null
    ): LlmTurnResult
}

/**
 * 基于 Ktor Client 的 OpenAI-compatible 实现。
 * 复用 [com.unciv.logic.UncivKtor.client]，避免重复建连。
 */
class OpenAICompatibleProvider(
    private val client: HttpClient,
    private val config: AiSettings,
    private val json: Json = AiJson.json
) : LLMProvider {

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ChatTool>?,
        forceTool: String?
    ): LlmTurnResult {
        if (config.model.isBlank()) throw LlmError.InvalidResponse("模型名未配置")
        val request = ChatCompletionRequest(
            model = config.model,
            messages = messages,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            tools = tools,
            toolChoice = forceTool?.let { toolName ->
                json.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), """{"type":"function","function":{"name":"$toolName"}}""")
            }
        )

        val url = buildUrl()
        val response = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${config.apiKey}")
                setBody(json.encodeToString(ChatCompletionRequest.serializer(), request))
            }
        } catch (e: LlmError) {
            throw e
        } catch (e: io.ktor.utils.io.errors.IOException) {
            throw LlmError.NetworkError(safeMessage(e))
        } catch (e: Exception) {
            throw classifyException(e)
        }

        val body = try { response.bodyAsText() } catch (e: Exception) { throw LlmError.NetworkError("读取响应失败") }
        if (!response.status.isSuccess()) {
            throw classifyHttpStatus(response.status.value, body)
        }

        val parsed = try {
            json.decodeFromString(ChatCompletionResponse.serializer(), body)
        } catch (e: Exception) {
            throw LlmError.InvalidResponse("无法解析响应: ${safeMessage(e)}")
        }

        parsed.error?.let { error ->
            val message = error.message ?: ""
            throw when {
                error.code == "invalid_api_key" || message.contains("api key", ignoreCase = true) -> LlmError.AuthError("鉴权失败：${message.take(120)}")
                error.code == "rate_limit_exceeded" || message.contains("rate limit", ignoreCase = true) -> LlmError.RateLimited()
                response.status.value in 500..599 -> LlmError.ServerError()
                response.status.value == 429 -> LlmError.RateLimited()
                else -> LlmError.ServerError(message.take(120))
            }
        }

        val choice = parsed.choices.firstOrNull()
            ?: throw LlmError.InvalidResponse("响应缺少 choices")

        val content = choice.message.content
        val toolCalls = choice.message.toolCalls
            ?.mapNotNull { call -> parseToolCall(call) }
            ?: emptyList()

        val finished = toolCalls.any { it.name == "end_turn" }
        return LlmTurnResult(content, toolCalls, finished)
    }

    private fun buildUrl(): String {
        val base = config.baseUrl.trim().trimEnd('/')
        return "$base/chat/completions"
    }

    private fun parseToolCall(call: ToolCall): ParsedToolCall? {
        val args = try {
            json.decodeFromString(kotlinx.serialization.json.JsonElement.serializer(), call.function.arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return null
        }
        return ParsedToolCall(call.id, call.function.name, args)
    }

    private fun classifyHttpStatus(status: Int, body: String): LlmError = when {
        status == 401 || status == 403 -> LlmError.AuthError("鉴权失败 (HTTP $status)")
        status == 429 -> LlmError.RateLimited()
        status in 500..599 -> LlmError.ServerError("服务端错误 (HTTP $status)")
        else -> LlmError.NetworkError("HTTP $status: ${body.take(120)}")
    }

    private fun classifyException(e: Exception): LlmError {
        val msg = safeMessage(e)
        return when {
            msg.contains("timeout", ignoreCase = true) -> LlmError.Timeout()
            msg.contains("connect", ignoreCase = true) || msg.contains("refused", ignoreCase = true) -> LlmError.NetworkError(msg)
            msg.contains("resolve", ignoreCase = true) -> LlmError.NetworkError("无法解析域名：$msg")
            else -> LlmError.NetworkError(msg)
        }
    }

    private fun safeMessage(e: Throwable): String = e.message ?: e::class.simpleName ?: "未知错误"
}

/** AI 模块共用的 JSON 配置：容忍未知字段，兼容各派生响应 */
object AiJson {
    @JvmField
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
}
