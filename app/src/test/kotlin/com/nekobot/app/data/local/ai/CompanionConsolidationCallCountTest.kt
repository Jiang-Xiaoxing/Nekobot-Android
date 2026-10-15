package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用相同聊天逐轮运行预算、选批和真实 JSON 容量检查，统计模拟整理调用次数。 */
class CompanionConsolidationCallCountTest {
    @Test fun smallContextDoesNotIncreaseCallsDuringChat() = compareCalls(7000, false)
    @Test fun baselineContextDoesNotIncreaseCallsDuringChat() = compareCalls(14000, false)
    @Test fun largeContextDoesNotIncreaseCallsDuringChat() = compareCalls(28000, false)
    @Test fun forcedBacklogDoesNotIncreaseCalls() {
        for (capacity in listOf(3500, 7000, 14000, 28000)) compareCalls(capacity, true)
    }
    @Test fun customLimitsDoNotIncreaseCalls() {
        for (capacity in listOf(7000, 14000)) {
            compareCalls(capacity, false, CompanionConsolidationSettings(recent = 12000, trigger = 3000, existing = 5000))
        }
    }

    private fun compareCalls(capacity: Int, force: Boolean, settings: CompanionConsolidationSettings = CompanionConsolidationSettings()) {
        val before = calls(capacity, force, settings, old = true)
        val after = calls(capacity, force, settings, old = false)
        assertTrue("capacity=$capacity force=$force: before=$before after=$after", after <= before)
        if (capacity == 7000 && !force) assertTrue("小上下文应减少调用：$before -> $after", after < before)
        println("整理调用对照 capacity=$capacity force=$force: $before -> $after")
    }

    private fun calls(capacity: Int, force: Boolean, settings: CompanionConsolidationSettings, old: Boolean): Int {
        val history = mutableListOf<LocalMessageEntity>()
        val receipts = mutableSetOf<String>()
        val budget = if (old) oldBudgets(capacity, settings) else FusedCompanionPolicy.budgets(capacity, settings)
        val gson = Gson()
        val prompt = estimateLocalTextTokens(FusedCompanionPolicy.extractionPrompt)
        val existing = List(6) { mapOf("key" to "k$it", "content" to "旧记忆".repeat(70)) }
        fun cost(target: List<LocalMessageEntity>, reference: List<LocalMessageEntity>, facts: Int): Int {
            fun rows(values: List<LocalMessageEntity>) = values.map {
                mapOf("id" to it.id, "role" to it.role, "spoken_day" to "2026-10-13", "text" to it.content)
            }
            return prompt + estimateLocalTextTokens(gson.toJson(mapOf(
                "target" to rows(target), "reference" to rows(reference), "existing" to existing.take(facts))))
        }
        var count = 0
        fun process() {
            val reference = FusedCompanionPolicy.recent(history, budget.recent)
            val refIds = reference.map { it.id }.toSet()
            while (true) {
                val completeUnits = FusedCompanionPolicy.turns(history).filter { turn ->
                    turn.none { it.id in refIds } && turn.any { it.id !in receipts }
                }
                val pendingIds = history.filterNot { it.id in receipts }.map { it.id }.toSet()
                val units = if (old) completeUnits else FusedCompanionPolicy.pendingConsolidationTurns(completeUnits, pendingIds)
                if (units.isEmpty() || (!force && FusedCompanionPolicy.tokens(units.flatten()) < budget.trigger)) return
                val chosen = mutableListOf<List<LocalMessageEntity>>()
                for (unit in units) {
                    if (chosen.isNotEmpty() && FusedCompanionPolicy.tokens(chosen.flatten() + unit) > budget.batch) break
                    chosen += unit
                    if (FusedCompanionPolicy.tokens(chosen.flatten()) >= budget.batch) break
                }
                val pendingTurns = FusedCompanionPolicy.pendingConsolidationTurns(chosen, pendingIds)
                val target = pendingTurns.flatten()
                val facts = (0..existing.size).last { estimateLocalTextTokens(gson.toJson(existing.take(it))) <= budget.existing }
                val fits = { targets: Int, refs: Int, oldFacts: Int -> cost(target.take(targets), reference.takeLast(refs), oldFacts) <= capacity }
                val fit = if (old) oldFit(pendingTurns.map { it.size }, FusedCompanionPolicy.turns(reference).map { it.size }, facts, fits)
                    else FusedCompanionPolicy.fitConsolidationPayload(pendingTurns.map { it.size }, FusedCompanionPolicy.turns(reference).map { it.size }, facts, fits)
                check(fit.targetMessages > 0) { "合成消息应能单独装入容量" }
                assertTrue(cost(target.take(fit.targetMessages), reference.takeLast(fit.referenceMessages), fit.existingCount) <= capacity)
                receipts += target.take(fit.targetMessages).map { it.id }
                count++
                check(count <= history.size) { "每次调用必须处理至少一条未处理原话" }
            }
        }
        for (turn in 1..60) {
            for (role in listOf("user", "assistant")) history += LocalMessageEntity(
                id = "$turn-$role", sessionId = "s", role = role, content = "原话".repeat(150),
                timestamp = "2026-10-13T12:00:00Z", createdAt = "2026-10-13T12:00:00Z")
            if (!force) process()
        }
        if (force) process()
        return count
    }

    /** 修复前把不同请求及触发门槛一起缩放的基线。 */
    private fun oldBudgets(capacity: Int, settings: CompanionConsolidationSettings): CompanionBudgets {
        val scale = minOf(1.0, capacity.toDouble() / (settings.recent + 1000 + 2000 + settings.trigger + 4000 + settings.existing))
        fun scaled(value: Int) = maxOf(1, (value * scale).toInt())
        return CompanionBudgets(scaled(settings.recent), scaled(1000), scaled(2000), scaled(settings.trigger), scaled(4000), scaled(settings.existing))
    }

    /** 修复前先拆目标批次、再缩参考资料的基线；合成原话均能单独装入。 */
    private fun oldFit(targetTurns: List<Int>, referenceTurns: List<Int>, existing: Int,
        fits: (Int, Int, Int) -> Boolean): FusedCompanionPolicy.ConsolidationPayloadFit {
        val turns = targetTurns.toMutableList()
        var refs = referenceTurns.size
        var facts = existing
        while (!fits(turns.sum(), referenceTurns.takeLast(refs).sum(), facts)) {
            when {
                turns.size > 1 -> turns.removeAt(turns.lastIndex)
                turns[0] > 1 -> turns[0]--
                refs > 0 -> refs--
                facts > 0 -> facts--
                else -> error("合成消息超容量")
            }
        }
        return FusedCompanionPolicy.ConsolidationPayloadFit(turns.sum(), referenceTurns.takeLast(refs).sum(), facts)
    }
}
