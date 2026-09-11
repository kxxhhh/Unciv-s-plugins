package com.unciv.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI-compatible Chat Completions 协议的最小模型。
 * 仅声明我们实际用到、且各大 OpenAI 兼容服务都支持的字段，
 * 以保证与第三方中转站/自建 API 的兼容性。
 */

@Serializable
data class ChatMessage(
    val role: String,

    @SerialName("content")
    val content: String? = null,

    /** LLM 返回的工具调用（assistant 消息中） */
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,

    /** 供 LLM 回填工具执行结果的工具消息须带的调用 id */
    @SerialName("tool_call_id")
    val toolCallId: String? = null,

    val name: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String // JSON 字符串，由调用方解析
)

@Serializable
data class FunctionDeclaration(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null // JSON Schema
)

@Serializable
data class ChatTool(
    val type: String = "function",
    val function: FunctionDeclaration
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val tools: List<ChatTool>? = null,
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,
    val stream: Boolean = false
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val error: ApiError? = null
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

/** 结构化工具调用的解析结果 */
data class ParsedToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)