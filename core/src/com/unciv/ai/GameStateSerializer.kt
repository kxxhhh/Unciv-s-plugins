package com.unciv.ai

import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.Civilization

/**
 * 面向 LLM 的紧凑游戏状态摘要。
 *
 * 设计目标：不做整个 GameInfo 的序列化（过大且含 @Transient 对象），而是
 * 提取"决策所需"的可读信息，并按 [AiContextLevel] 控制详细程度以省 token。
 */

object GameStateSerializer {

    /**
     * 生成本文明（[viewCiv]）视角的状态文本。
     * 全局信息 + 本文明详细 + 邻近地图，按 contextLevel 裁剪。
     */
    fun summarize(viewCiv: Civilization, level: AiContextLevel): String {
        val game = viewCiv.gameInfo
        val sb = StringBuilder(4096)

        sb.appendLine("Turn: ${game.turns}")
        sb.append("Civ: ${viewCiv.civName} | ")
        sb.append("Gold: ${viewCiv.gold} | ")
        sb.append("Happiness: ${viewCiv.getHappiness()} | ")
        val science = viewCiv.stats.statsForNextTurn?.science
        val food = viewCiv.stats.statsForNextTurn?.food
        val prod = viewCiv.stats.statsForNextTurn?.production
        sb.append("PerTurn(Science=$science Food=$food Production=$prod)")
        sb.appendLine()
        if (viewCiv.tech.currentTechnology() != null)
            sb.appendLine("Researching: ${viewCiv.tech.currentTechnology()}")
        sb.appendLine("Researchable techs: ${researchableTechs(viewCiv).joinToString(", ")}")

        // 文明概览
        sb.appendLine("=== Civilizations ===")
        for (other in game.civilizations) {
            if (other.civName == viewCiv.civName) continue
            if (other.isDefeated()) continue
            val status = diplomStatusLabel(viewCiv, other)
            sb.appendLine("- ${other.civName} ($status) cities=${other.cities.size}")
        }

        // 城市
        sb.appendLine("=== Cities (${viewCiv.cities.size}) ===")
        for (city in viewCiv.cities) {
            sb.append("  ${city.name} | pop=${city.population.population} | ")
            sb.append("current=${city.cityConstructions.currentConstructionName()} | ")
            sb.append("pos=(${city.getCenterTile().position.x},${city.getCenterTile().position.y})")
            sb.appendLine()
        }

        // 单位
        sb.appendLine("=== Units (${viewCiv.units.getCivUnitsSize()}) ===")
        for (unit in viewCiv.units.getCivUnits()) {
            val tile = unit.getTile()
            sb.append("  ${unit.name} | hp=${unit.health} | movesLeft=${unit.currentMovement} | ")
            sb.append("pos=(${tile.position.x},${tile.position.y})")
            if (level != AiContextLevel.Minimal) {
                val targets = nearbyAttackableTargets(viewCiv, unit)
                if (targets.isNotEmpty())
                    sb.append(" | attackable: ${targets.joinToString(", ") { it.name }}")
            }
            sb.appendLine()
        }

        if (level != AiContextLevel.Minimal) {
            appendVisibleMap(viewCiv, sb, level)
        }

        return sb.toString()
    }

    private fun appendVisibleMap(viewCiv: Civilization, sb: StringBuilder, level: AiContextLevel) {
        sb.appendLine("=== Visible Map ===")
        val citiesByPos = viewCiv.gameInfo.civilizations
            .flatMap { it.cities }
            .associateBy { it.getCenterTile().position }
        val unitsByPos = viewCiv.gameInfo.civilizations
            .flatMap { it.units.getCivUnits() }
            .groupBy { it.getTile().position }
        var shown = 0
        for (tile in viewCiv.gameInfo.tileMap.values) {
            if (!tile.isVisible(viewCiv)) continue
            val city = citiesByPos[tile.position]
            val units = unitsByPos[tile.position] ?: emptyList()
            if (city == null && units.isEmpty()) continue
            sb.append(
                "  (${tile.position.x},${tile.position.y}) " +
                    (city?.let { "city:${it.name}(${it.civ.civName})" } ?: "") +
                    (units.takeIf { it.isNotEmpty() }?.let { " units:${it.joinToString { u -> u.name } }" } ?: "")
            ).appendLine()
            if (++shown >= 60) { // 地图信息很容易爆炸，兜底截断
                sb.appendLine("  ...")
                break
            }
        }
    }

    private fun nearbyAttackableTargets(viewCiv: Civilization, unit: com.unciv.logic.map.mapunit.MapUnit): List<com.unciv.logic.map.mapunit.MapUnit> {
        val attackable = TargetHelper.getAttackableEnemies(
            unit,
            unit.movement.getDistanceToTiles(),
            unit.getTile().getTilesInDistance(if (unit.baseUnit.isRanged()) 6 else 2).toList()
        )
        return attackable.mapNotNull { (it.combatant as? MapUnitCombatant)?.unit }.filter { it.civ != viewCiv }
    }

    private fun researchableTechs(civ: Civilization): List<String> =
        civ.gameInfo.ruleset.technologies.values.asSequence()
            .filter { civ.tech.canBeResearched(it.name) }
            .map { it.name }
            .take(12)
            .toList()

    private fun diplomStatusLabel(viewCiv: Civilization, other: Civilization): String {
        val dm = viewCiv.getDiplomacyManager(other) ?: return "unknown"
        return dm.diplomaticStatus.name
    }

    /** 估算文本的 token 数（粗略：约 4 字符/词 -> 用字符/4 近似） */
    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
}