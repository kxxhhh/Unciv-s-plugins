package com.unciv.ai

/** AI 文明的控制级别 */
enum class AiControlMode {
    /** AI 只给出建议，由人类确认执行 */
    Manual,
    /** 普通操作自动执行，重大决策（开战、投降等）询问 */
    SemiAutonomous,
    /** AI 完全自动操作文明 */
    FullyAutonomous
}

/** 决定每次发送给 LLM 的游戏状态上下文量（影响 token 成本） */
enum class AiContextLevel {
    Minimal, Balanced, Detailed
}

/** AI 人格预设 */
enum class AiPersonalityPreset(val key: String) {
    Balanced("balanced"),
    Aggressive("aggressive"),
    Defensive("defensive"),
    Economic("economic"),
    Scientific("scientific"),
    Cultural("cultural"),
    Diplomatic("diplomatic"),
    Expansionist("expansionist");

    companion object {
        fun fromKey(key: String): AiPersonalityPreset =
            entries.firstOrNull { it.key == key } ?: Balanced
    }
}

/**
 * 与第三方 LLM API 相关的全部配置。
 * 存储在 GameSettings.json 中（明文，见 README 安全说明）。
 */
class AiSettings {
    /** 是否启用 LLM 控制的 AI 文明 */
    var enabled = false

    /** OpenAI 兼容 API 的 Base URL，如 https://example.com/v1 */
    var baseUrl = "https://api.openai.com/v1"

    /** API Key，运行时读取，不硬编码进源码 */
    var apiKey = ""

    /** 使用的模型名，如 gpt-5.6-luna */
    var model = "gpt-5.6-luna"

    var temperature = 0.7f
    var maxTokens = 2048

    /** 单次 HTTP 请求超时（秒） */
    var timeoutSeconds = 90

    var contextLevel = AiContextLevel.Balanced.name

    var personality = AiPersonalityPreset.Balanced.key

    var controlMode = AiControlMode.SemiAutonomous.name

    /** 由 LLM 控制的文明名列表（与具体对局无关，跨局生效） */
    var llmControlledCivs = ArrayList<String>()

    /** 启用 Debug 面板，显示回合/请求/响应/工具调用等日志（默认不含 API Key） */
    var debugMode = false

    // ---- 派生辅助 ----

    fun getContextLevel(): AiContextLevel =
        AiContextLevel.valueOf(AiContextLevel.entries.firstOrNull { it.name == contextLevel }?.name ?: AiContextLevel.Balanced.name)

    fun getPersonalityPreset(): AiPersonalityPreset = AiPersonalityPreset.fromKey(personality)

    fun getControlMode(): AiControlMode =
        AiControlMode.valueOf(AiControlMode.entries.firstOrNull { it.name == controlMode }?.name ?: AiControlMode.SemiAutonomous.name)

    /**
     * 判断某个文明名是否应受 LLM 控制。
     * 规范化（去空白、忽略大小写）后比较。
     */
    fun isCivLlmControlled(civName: String): Boolean {
        val normalized = civName.trim()
        return llmControlledCivs.any { it.trim().equals(normalized, ignoreCase = true) }
    }
}