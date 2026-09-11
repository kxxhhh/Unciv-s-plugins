package com.unciv.ai

import com.unciv.UncivGame
import com.unciv.logic.UncivKtor
import com.unciv.logic.civilization.Civilization
import com.unciv.utils.Concurrency

/**
 * LLM 文明一完整回合的编排逻辑。替代内置 [com.unciv.logic.automation.civilization.NextTurnAutomation]
 * 用于被标记为 LLM 控制的文明。
 *
 * 工作流：
 * 1. 读配置、生成 system prompt + 记忆 + 游戏状态
 * 2. 向 LLM 发送请求（含工具声明）
 * 3. 收到 tool_calls → 逐条校验执行 → 结果回填
 * 4. 循环至 end_turn 或达到最大迭代数
 * 5. 全程 try/catch，失败 → 报告 + 走内置四步保守回合
 */

object LLMTurnAutomation {

    /** 单回合最大工具调用迭代数，防死循环 */
    private const val MAX_ITERATIONS = 8

    /** 每个 LLM 文明持有一个内存（非持久化） */
    private val memories = mutableMapOf<String, AiMemory>()

    /**
     * 主入口。被 [com.unciv.logic.civilization.managers.TurnManager.automateTurn] 调用。
     * 执行后该文明的回合即完成。
     */
    fun runTurn(civInfo: Civilization) {
        if (civInfo.isDefeated()) return

        val game = civInfo.gameInfo
        val aiSettings = UncivGame.Current.settings.ai

        val provider = OpenAICompatibleProvider(UncivKtor.client, aiSettings)

        // 获取或创建该文明的记忆
        val memory = memories.getOrPut(civInfo.civName) { AiMemory() }

        try {
            // --- step 1: 构建消息历史 ---
            val contextLevel = aiSettings.getContextLevel()
            val gameState = GameStateSerializer.summarize(civInfo, contextLevel)
            val memoryText = memory.summarize()
            val systemPrompt = AiPersonality.generateSystemPrompt(
                aiSettings.getPersonalityPreset(),
                memoryText
            )

            val messages = mutableListOf<ChatMessage>(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", "以下是当前回合的游戏状态摘要：\n\n$gameState")
            )

            if (aiSettings.debugMode) {
                log("[LLM-AI] Turn ${game.turns} for ${civInfo.civName}")
                log("[LLM-AI] Game state tokens: ~${GameStateSerializer.estimateTokens(gameState)}")
            }

            // --- step 2~4: 工具循环 ---
            val tools = Tools.declarations()
            var iterations = 0

            while (iterations < MAX_ITERATIONS) {
                iterations++

                val result = try {
                    kotlinx.coroutines.runBlocking { provider.chat(messages, tools) }
                } catch (e: LlmError) {
                    // 网络错误 → 退避，让内置 AI 继续
                    if (aiSettings.debugMode) log("[LLM-AI] LLM error: ${e.message}")
                    return fallbackToBuiltIn(civInfo)
                }

                if (result.toolCalls.isEmpty()) {
                    // LLM 没有调用工具，可能想直接结束（如某些模型不支持 tool calling）
                    // 插入 end_turn 模拟
                    break
                }

                // 添加 assistant 消息（含工具调用），供 LLM 看历史
                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = result.content,
                    toolCalls = result.toolCalls.map { tc ->
                        val call = ToolCall(tc.id, "function", FunctionCall(tc.name, tc.arguments.toString()))
                        call
                    }
                )
                messages.add(assistantMsg)

                for (tc in result.toolCalls) {
                    val execResult = ActionExecutor.execute(civInfo, tc)

                    if (aiSettings.debugMode) {
                        log("[LLM-AI] Tool: ${tc.name}(${tc.arguments}) => $execResult")
                    }

                    // 工具结果回填
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            toolCallId = tc.id,
                            content = execResult
                        )
                    )

                    if (tc.name == "end_turn") {
                        // end_turn 工具被调用，结束本回合
                        if (aiSettings.debugMode) log("[LLM-AI] End turn requested by LLM")
                        break
                    }
                }

                // 如果某次迭代中包含 end_turn，不再继续循环
                if (result.toolCalls.any { it.name == "end_turn" }) break
            }

            // 战后总结：更新记忆
            memory.compress()
            val updatedState = GameStateSerializer.summarize(civInfo, AiContextLevel.Minimal)
            updateMemory(memory, civInfo, updatedState)

            if (aiSettings.debugMode) log("[LLM-AI] Turn ${game.turns} for ${civInfo.civName} DONE")

        } catch (e: Exception) {
            // 终极兜底：不让 AI 崩溃扩散到整个游戏
            if (aiSettings.debugMode) log("[LLM-AI] CRASH in turn for ${civInfo.civName}: ${e.message}")
            fallbackToBuiltIn(civInfo)
        }
    }

    /** 回退到内置 AI 的一回合决策（最小保守回合） */
    private fun fallbackToBuiltIn(civInfo: Civilization) {
        try {
            com.unciv.logic.automation.civilization.NextTurnAutomation.automateCivMoves(civInfo, tradeAndChangeState = true)
        } catch (_: Exception) { /* 尽最大努力不让回合卡死 */ }
    }

    /** 记忆更新：从最新状态中提取关键战略信息 */
    private fun updateMemory(memory: AiMemory, civ: Civilization, stateText: String) {
        // 简单提取：有战争 → 记录战争计划；有城邦 → 记录盟友等
        // 未来可让 LLM 自行总结，当前用规则提取
        if (civ.isAtWar()) {
            val enemies = civ.gameInfo.civilizations.filter { civ.isAtWarWith(it) }
            for (enemy in enemies) {
                memory.warPlans[enemy.civName] = "正在交战，回合=${civ.gameInfo.turns}"
            }
        } else {
            // 和平期间淡化战争计划
            memory.warPlans.clear()
        }
        memory.strategicGoal = when {
            civ.isAtWar() -> "以军事为优先，尽快击溃敌人"
            civ.isCityState -> "保护自身"
            civ.tech.techsToResearch.isNotEmpty() -> "研发 ${civ.tech.techsToResearch.first()}"
            else -> "持续发展"
        }
    }

    private fun log(message: String) {
        // 简单日志 → 后续接入 Debug 面板（Log 日志或直接输出到 UI Log 框）
        println("[UncivAI] $message")
    }
}