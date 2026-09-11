package com.unciv.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * LLM 可调用的工具清单与 JSON Schema。
 * 每种工具由 ActionExecutor 实现执行；本文件只声明 LLM 可见的接口形状。
 */
object Tools {

    /** 返回给 LLM 的完整工具声明列表 */
    fun declarations(): List<ChatTool> = listOf(
        moveUnitTool,
        attackTool,
        changeProductionTool,
        researchTechTool,
        foundCityTool,
        getDetailTool,
        declareWarTool,
        makePeaceTool,
        endTurnTool
    )

    private val moveUnitTool = ChatTool("function", FunctionDeclaration(
        "move_unit",
        "命令某单位移动到一个目标格。目标格必须在 (x,y) 坐标；单位会尽量朝该格走一步（受本回行动力限制）。unit 必须在自己的文明中。",
        obj(
            "unit" to str("要移动的单位名（与状态摘要中的单位名一致）"),
            "x" to int("目标格 x 坐标"),
            "y" to int("目标格 y 坐标")
        )
    ))

    private val attackTool = ChatTool("function", FunctionDeclaration(
        "attack",
        "命令某军事单位攻击一个能被其攻击到的敌人。unit 必须是自己的军事单位，target_enemy 是攻击范围内敌人的描述（如单位名或所在格坐标）。",
        obj(
            "unit" to str("发起攻击的单位名"),
            "target_enemy" to str("目标敌人描述（单位名或 '(x,y)' 坐标）")
        )
    ))

    private val changeProductionTool = ChatTool("function", FunctionDeclaration(
        "change_city_production",
        "设置某城市当前的建造/生产对象。production 必须是该城市当前可建造的建筑、单位或奇观名。",
        obj(
            "city" to str("城市名"),
            "production" to str("要生产的建筑/单位/奇观名")
        )
    ))

    private val researchTechTool = ChatTool("function", FunctionDeclaration(
        "research_technology",
        "选择一个科技作为当前研究目标。tech 必须当前可研发。",
        obj("tech" to str("要研究的科技名"))
    ))

    private val foundCityTool = ChatTool("function", FunctionDeclaration(
        "found_city",
        "命令一个开拓者单位（settler）在指定格建城。",
        obj(
            "settler" to str("开拓者单位名"),
            "x" to int("建城格 x 坐标"),
            "y" to int("建城格 y 坐标")
        )
    ))

    private val getDetailTool = ChatTool("function", FunctionDeclaration(
        "get_detail",
        "请求更详细的信息。例如列出某城市的可建造项，或某单位可攻击的敌人列表。用于信息不足时补充上下文。",
        obj("what" to str("想了解的信息，如 'cities:首都', 'attackable:单位名'"))
    ))

    private val declareWarTool = ChatTool("function", FunctionDeclaration(
        "declare_war",
        "对另一个文明宣战。enemy_civ 必须是另一文明名。",
        obj("enemy_civ" to str("要宣战的文明名"))
    ))

    private val makePeaceTool = ChatTool("function", FunctionDeclaration(
        "make_peace",
        "与另一文明寻求和平（提出停战）。other_civ 必须是正在交战的文明名。",
        obj("other_civ" to str("寻求和平的文明名"))
    ))

    private val endTurnTool = ChatTool("function", FunctionDeclaration(
        "end_turn",
        "结束本文明的这一回合。当你已完成本回合所有合理行动，或单位都无更优行动时调用。",
        obj()
    ))

    // ---- schema 构建辅助 ----

    private fun obj(vararg fields: Pair<String, String>): JsonElement {
        val requiredPart = if (fields.isEmpty()) "" else ""","required":["${fields.map { it.first }.joinToString("\",\"")}"]"""
        return Json.parseToJsonElement(
            """{"type":"object","properties":{${fields.joinToString(",") { (k, schema) -> "\"$k\":$schema" }}}$requiredPart}"""
        )
    }

    private fun str(description: String): String = """{"type":"string","description":"$description"}"""
    private fun int(description: String): String = """{"type":"integer","description":"$description"}"""
}
