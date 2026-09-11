package com.unciv.ai

import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 LLM 返回的结构化工具调用映射到实际的游戏 API。
 * 所有执行操作都经过存在性与合法性校验，绝不盲目信任 LLM 输出。
 */

object ActionExecutor {

    /**
     * 执行一次工具调用。
     *
     * @return 执行结果的纯文本（供 LLM 作为 tool result 消息回填），失败时返回错误信息
     */
    fun execute(civ: Civilization, tool: ParsedToolCall): String {
        val args = tool.arguments

        return try {
            when (tool.name) {
                "move_unit" -> executeMoveUnit(civ, args)
                "attack" -> executeAttack(civ, args)
                "change_city_production" -> executeChangeProduction(civ, args)
                "research_technology" -> executeResearch(civ, args)
                "found_city" -> executeFoundCity(civ, args)
                "get_detail" -> executeGetDetail(civ, args)
                "declare_war" -> executeDeclareWar(civ, args)
                "make_peace" -> executeMakePeace(civ, args)
                "end_turn" -> "end_turn queued"
                else -> "unknown tool: ${tool.name}"
            }
        } catch (e: IllegalStateException) {
            "ERROR: ${e.message}"
        } catch (e: Exception) {
            "ERROR: 执行异常 ${e.message}"
        }
    }

    // ===== 单个工具实现 =====

    private fun executeMoveUnit(civ: Civilization, args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val unitName = argStr(args, "unit") ?: return "缺少 unit 参数"
        val x = argInt(args, "x") ?: return "缺少 x 坐标"
        val y = argInt(args, "y") ?: return "缺少 y 坐标"

        val unit = findOwnUnit(civ, unitName) ?: return "找不到己方单位: $unitName"
        if (unit.currentMovement <= 0f) return "$unitName 无剩余行动力"

        val tile = civ.gameInfo.tileMap.getOrNull(x, y) ?: return "坐标 ($x,$y) 不存在"
        if (!unit.movement.canReach(tile)) return "$unitName 无法在此回合到达 ($x,$y)"

        unit.movement.moveToTile(tile)
        return "$unitName 已移动到 (${tile.position.x},${tile.position.y})"
    }

    private fun executeAttack(civ: Civilization, args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val unitName = argStr(args, "unit") ?: return "缺少 unit 参数"
        val targetDesc = argStr(args, "target_enemy") ?: return "缺少 target_enemy 参数"

        val unit = findOwnUnit(civ, unitName) ?: return "找不到己方单位: $unitName"
        if (!unit.canAttack()) return "$unitName 本回合不能攻击"

        val attackable = TargetHelper.getAttackableEnemies(
            unit, unit.movement.getDistanceToTiles(),
            unit.getTile().getTilesInDistance(6).toList()
        )

        if (attackable.isEmpty()) return "附近无可攻击敌人"

        // 按描述匹配目标：优先名/坐标
        val target = attackable.firstOrNull {
            val enemyUnit = (it.combatant as? MapUnitCombatant)?.unit ?: return@firstOrNull false
            val pos = enemyUnit.getTile().position
            if (enemyUnit.name.equals(targetDesc, ignoreCase = true)) return@firstOrNull true
            val coordPattern = """\(?\s*(\d+)\s*,\s*(\d+)\s*\)?""".toRegex()
            val match = coordPattern.find(targetDesc)
            if (match != null) {
                val tx = match.groupValues[1].toIntOrNull() ?: return@firstOrNull false
                val ty = match.groupValues[2].toIntOrNull() ?: return@firstOrNull false
                return@firstOrNull pos.x == tx && pos.y == ty
            }
            enemyUnit.name.contains(targetDesc, ignoreCase = true)
        }

        if (target == null) return "找不到名为 '$targetDesc' 的敌人"

        Battle.moveAndAttack(MapUnitCombatant(unit), target)
        return "${unit.name} 已攻击 ${(target.combatant as? MapUnitCombatant)?.unit?.name ?: "目标"}"
    }

    private fun executeChangeProduction(civ: Civilization, args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val cityName = argStr(args, "city") ?: return "缺少 city 参数"
        val production = argStr(args, "production") ?: return "缺少 production 参数"
        val city = civ.cities.firstOrNull { it.name.equals(cityName, ignoreCase = true) } ?: return "找不到城市: $cityName"
        city.cityConstructions.setCurrentConstruction(production)
        return "城市 $cityName 当前建造: $production"
    }

    private fun executeResearch(civ: Civilization, args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val techName = argStr(args, "tech") ?: return "缺少 tech 参数"
        if (!civ.tech.canBeResearched(techName)) return "科技 $techName 不可研发或已研发"
        civ.tech.techsToResearch.clear()
        civ.tech.techsToResearch.add(techName)
        return "当前研究: $techName"
    }

    private fun executeFoundCity(civ: Civilization, args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val settlerName = argStr(args, "settler") ?: return "缺少 settler 参数"
        val x = argInt(args, "x") ?: return "缺少 x 坐标"
        val y = argInt(args, "y") ?: return "缺少 y 坐标"

        val settler = findOwnUnit(civ, settlerName) ?: return "找不到己方单位: $settlerName"
        if (!settler.hasUnique(UniqueType.FoundCity)) return "$settlerName 不是开拓者"

        val tile = civ.gameInfo.tileMap.getOrNull(x, y) ?: return "坐标 ($x,$y) 不存在"
        if (settler.getTile() != tile) {
            if (!settler.movement.canReach(tile)) return "$settlerName 无法到达 ($x,$y)"
            settler.movement.moveToTile(tile)
        }
        if (tile.isWater || tile.isImpassible()) return "($x,$y) 为水域或不可通行"
        if (!tile.canBeSettled(civ)) return "($x,$y) 不可定居"

        civ.addCity(tile.position, settler)
        return "已在 (${tile.position.x},${tile.position.y}) 建城"
    }

    private fun executeGetDetail(civ: Civilization, args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val what = argStr(args, "what") ?: "all"
        return when {
            what.startsWith("cities:", ignoreCase = true) -> {
                val cityName = what.removePrefix("cities:").removePrefix("CITIES:").trim()
                val city = if (cityName.isEmpty()) civ.cities.firstOrNull() else civ.cities.firstOrNull { it.name.equals(cityName, ignoreCase = true) }
                if (city == null) "城市 '$cityName' 不存在"
                else "已建建筑: ${city.cityConstructions.builtBuildings.joinToString(", ").take(200)}"
            }
            what.startsWith("attackable:", ignoreCase = true) -> {
                val unitName = what.removePrefix("attackable:").removePrefix("ATTACKABLE:").trim()
                val unit = findOwnUnit(civ, unitName) ?: return "找不到己方单位: $unitName"
                val attackable = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles(), unit.getTile().getTilesInDistance(6).toList())
                attackable.joinToString("\n") { t -> "  ${(t.combatant as? MapUnitCombatant)?.unit?.name ?: "city"} at ${t.tileToAttack.position}" }
            }
            else -> "可选详情: cities:城市名 / attackable:单位名"
        }
    }

    private fun executeDeclareWar(civ: Civilization, args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val enemyName = argStr(args, "enemy_civ") ?: return "缺少 enemy_civ 参数"
        val enemy = civ.gameInfo.civilizations.firstOrNull { it.civName.equals(enemyName, ignoreCase = true) && !it.isDefeated() }
            ?: return "找不到文明: $enemyName"
        if (enemy == civ) return "不能对自己宣战"
        if (civ.isAtWarWith(enemy)) return "已在与 $enemyName 交战中"
        val dm = civ.getDiplomacyManager(enemy) ?: return "无法获取外交管理器"
        dm.declareWar()
        return "已向 $enemyName 宣战"
    }

    private fun executeMakePeace(civ: Civilization, args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val otherName = argStr(args, "other_civ") ?: return "缺少 other_civ 参数"
        val other = civ.gameInfo.civilizations.firstOrNull { it.civName.equals(otherName, ignoreCase = true) && !it.isDefeated() }
            ?: return "找不到文明: $otherName"
        if (!civ.isAtWarWith(other)) return "并未与 $otherName 交战"
        val dm = civ.getDiplomacyManager(other) ?: return "无法获取外交管理器"
        com.unciv.logic.automation.civilization.DiplomacyAutomation.offerPeaceTreaty(civ)
        return "已向 $otherName 提出停战"
    }

    // ===== 辅助方法 =====

    private fun findOwnUnit(civ: Civilization, name: String): MapUnit? =
        civ.units.getCivUnits().firstOrNull {
            it.name.equals(name, ignoreCase = true) || it.name.contains(name, ignoreCase = true)
        }

    private fun argStr(args: Map<String, kotlinx.serialization.json.JsonElement>, key: String): String? =
        (args[key] as? JsonPrimitive)?.content

    private fun argInt(args: Map<String, kotlinx.serialization.json.JsonElement>, key: String): Int? =
        argStr(args, key)?.toIntOrNull()
}