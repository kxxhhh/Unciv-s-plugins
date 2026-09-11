package com.unciv.ai

/**
 * AI 记忆系统。分为三层，定期压缩，不作为存档一部分。
 *
 * - 短期记忆（Short-Term）：当前回合的状态——由 [GameStateSerializer] 生成
 * - 战略记忆（Strategic）：跨回合保留的关键信息（战略、重要敌人、资源、战争计划）
 * - 外交记忆（Diplomatic）：对每个已知文明的基本评价与历史摘要
 */

class AiMemory {

    /** 战略目标存留（如 "优先发展科技", "备战入侵罗马"） */
    var strategicGoal = ""

    /** 记录的目标文明名与当前计划 */
    val warPlans = mutableMapOf<String, String>()

    /** 重要资源位存留 */
    var noticedResources = ""

    /** 外交历史摘要（文明名 → 摘要） */
    val diplomaticHistory = mutableMapOf<String, String>()

    /** 将摘要长度限缩，防止无限制增长 */
    fun compress() {
        if (strategicGoal.length > 300) strategicGoal = strategicGoal.takeLast(200)
        val truncatedWarPlans = warPlans.filterValues { it.length < 200 }
        warPlans.clear()
        warPlans.putAll(truncatedWarPlans)
        if (noticedResources.length > 200) noticedResources = noticedResources.takeLast(150)
    }

    /** 将记忆汇总为可嵌入 LLM 系统提示的文本 */
    fun summarize(): String = buildString {
        if (strategicGoal.isNotBlank()) {
            appendLine("STRATEGIC GOAL: $strategicGoal")
        }
        if (warPlans.isNotEmpty()) {
            appendLine("WAR PLANS:")
            warPlans.forEach { (civName, plan) -> appendLine("  $civName: $plan") }
        }
        if (noticedResources.isNotBlank()) {
            appendLine("RESOURCES: $noticedResources")
        }
        if (diplomaticHistory.isNotEmpty()) {
            appendLine("DIPLOMATIC HISTORY:")
            diplomaticHistory.forEach { (civName, note) -> appendLine("  $civName: $note") }
        }
    }
}