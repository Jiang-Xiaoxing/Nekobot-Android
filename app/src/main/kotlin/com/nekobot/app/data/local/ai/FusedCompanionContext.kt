package com.nekobot.app.data.local.ai

import androidx.room.withTransaction
import com.google.gson.Gson
import com.nekobot.app.data.local.db.*
import java.time.ZonedDateTime

internal class FusedCompanionContext(private val db: NekobotDatabase) {
    private var summaryFailed = false
    suspend fun build(session: LocalSessionEntity, messageId: String, history: List<LocalMessageEntity>,
        system: String, tools: List<Map<String, Any>>, currentMessage: Map<String, Any>, dynamic: String,
        maxContext: Int, outputReserve: Int, now: ZonedDateTime = ZonedDateTime.now(), stateKey: String = "relationship-v2:off",
        thinkingPrompt: String? = null, summarize: (suspend (Int) -> Boolean)? = null, refs: FusedMemoryReferences = FusedMemoryReferences()): List<Map<String, Any>> {
        val store = CompanionMemoryStore(db)
        val dao = db.companionMemoryDao()
        val scope = store.ensureScope(session)
        val raw = requireNotNull(db.messageDao().getById(messageId))
        val consolidation = consolidationSettingsFor(db.dbName, session.id)
        val receipts = dao.receipts(scope.id).map { it.messageId }.toSet()
        val plan = FusedCompanionPolicy.windowPlan(history + raw, receipts, consolidation.recent, consolidation.trigger, messageId)
        suspend fun rebuild(): List<Map<String, Any>> = build(session, messageId, history, system, tools, currentMessage, dynamic,
            maxContext, outputReserve, now, stateKey, thinkingPrompt, summarize, refs)
        if (plan.needsSummary && !summaryFailed) {
            if (summarize?.invoke(consolidation.recent) == true) return rebuild()
            summaryFailed = true
            return rebuild()
        }
        // 到期后连同历史辅助资料一起重建，避免旧缓存继续带入已经过期的记忆。
        val expired = dao.memories(scope.id).filterNot { CompanionImportPolicy.usable(it, now.toInstant()) }.map { it.id }.sorted()
        // State changes invalidate auxiliary caches, including turning the feature off.
        val retrieval = com.nekobot.app.ServiceContainer.prefs.companionRetrievalSettings(db.dbName)
        val configuration = CompanionMemoryPolicy.digest(retrieval.toString() + stateKey + FusedCompanionPolicy.contextFormat + system + Gson().toJson(tools) + maxContext + outputReserve + now.zone.id + now.toLocalDate() + expired)
        val available = maxContext - outputReserve - estimateLocalTextTokens(system) - estimateLocalTextTokens(Gson().toJson(tools)) -
            estimateLocalTextTokens(thinkingPrompt.orEmpty()) - 256
        val budgets = FusedCompanionPolicy.budgets(available, consolidation)
        val recall = store.fusedRecallSelection(session, currentMessage["content"].toString(), budgets, now.toLocalDate(), messageId)
        val old = dao.contextMaterials(scope.id)
        var cached = old.filter { it.revision == scope.revision && it.configuration == configuration && CompanionReferences.ids(it.content) != null }
        val sequence = db.messageDao().cursorOf(messageId)?.rowId ?: error("本轮用户消息尚未保存")
        cached = cached.filter { it.sequence < sequence }
        val timeKey = FusedCompanionPolicy.timeKey(now)
        val examples = CompanionExamplesStore(db)
        val exampleReference = examples.reference(session)
        val turnReminder = CompanionPromptLanguage.clock(now, history.lastOrNull { it.role in setOf("user", "assistant") })
        val previousIds = CompanionReferences.retainedIds(history.map { it.id }.toSet(), cached).toSet()
        val retained = store.validatedMemories(scope, CompanionReferences.current(dao.memories(scope.id)).filter { it.id in previousIds })
        val valid = (recall.core + retained + recall.recalled).distinctBy { it.id }
        fun memories(rows: List<LocalMessageEntity>): List<CompanionMemoryEntity> {
            val selected = CompanionReferences.merge(recall.core, CompanionReferences.retainedIds(rows.map { it.id }.toSet(), cached), recall.recalled, valid)
            return (recall.core + recall.recalled + selected).distinctBy { it.id }
        }
        fun mapping(rows: List<LocalMessageEntity>) = FusedMemoryReferences(existing = memories(rows) + recall.uncertain)
        fun material(rows: List<LocalMessageEntity>): String {
            val numbers = mapping(rows)
            return CompanionReferences.render(recall.core, memories(rows), dynamic, now.toLocalDate()) { "[${numbers.register(it)}] " }
        }
        fun uncertainty(rows: List<LocalMessageEntity>): String {
            val numbers = mapping(rows)
            return FusedUncertainMemory.render(recall.uncertain, now.toLocalDate()) { "[${numbers.register(it)}] " }
        }
        var opening = listOf(system, exampleReference).filter(String::isNotBlank).joinToString("\n\n")
        fun size(rows: List<LocalMessageEntity>) = estimateLocalMessagesTokens(
            assembleMessages(opening, rows, cached, material(rows), turnReminder, currentMessage, raw, uncertain = uncertainty(rows)).map(CompanionRequestUsage::clean)) -
            estimateLocalTextTokens(system)
        var selected = plan.window.filterNot { it.id == messageId }
        if (selected.size < history.size || FusedCompanionPolicy.tokens(plan.window) >= consolidation.recent) {
            examples.windowFull(session.id, history.lastOrNull { it.role == "assistant" }?.id ?: messageId)
            opening = system
        }
        // 总容量不足先移出已有回执的整轮；核心和沿用记忆不挤占8000额度。
        for (turn in FusedCompanionPolicy.turns(selected)) {
            if (size(selected) <= available) break
            if (turn.all { it.id in receipts }) {
                val ids = turn.map { it.id }.toSet()
                selected = selected.filterNot { it.id in ids }
            }
        }
        if (size(selected) > available && !summaryFailed) {
            val target = (plan.unsummarizedTokens - (size(selected) - available) - 32).coerceAtLeast(0)
            if (summarize?.invoke(target) == true) return rebuild()
            summaryFailed = true
            return rebuild()
        }
        // 仅在总结失败且总容量仍不足时临时让出原话；没有回执，下次照常重试。
        var dropped = 0
        while (size(selected) > available && selected.isNotEmpty() && summaryFailed) {
            val turn = FusedCompanionPolicy.turns(selected).first()
            dropped += FusedCompanionPolicy.tokens(turn)
            selected = selected.drop(turn.size)
        }
        if (dropped > 0) fusedMemoryLog("warn", "unsummarized_dropped scope=${scope.id} droppedTokens=$dropped")
        require(size(selected) <= available) {
            FusedMemoryText.CONTEXT_TOO_LARGE.value()
        }
        val startId = selected.firstOrNull()?.id ?: messageId
        val userPayload = currentMessage
        cached = cached.filter { saved -> selected.any { it.id == saved.messageId } }
        val row = CompanionContextMaterialEntity(scope.id, messageId, sequence, scope.revision, configuration,
            startId, CompanionReferences.snapshot(recall.recalled.map { it.id }), timeKey, now.toInstant().toEpochMilli(), recall.mode, Gson().toJson(userPayload))
        db.withTransaction {
            require(dao.scope(scope.id)?.revision == scope.revision) { "构造请求期间记忆已更新，请重试" }
            if (old != cached) {
                dao.eraseContextMaterials(scope.id)
                cached.forEach { dao.saveContextMaterial(it) }
            }
            FusedUncertainMemory.recordInjected(dao, scope.id, messageId, recall.uncertain, now.toInstant().toString())
            dao.saveContextMaterial(row)
        }
        (memories(selected) + recall.uncertain).forEach { refs.register(it.id) }
        lastRetrieval = recall.mode
        return assembleMessages(opening, selected, cached, material(selected), turnReminder, userPayload, raw, memories(selected).map { it.id }, uncertainty(selected), recall.uncertain.map { it.id })
    }

    companion object {
        internal fun assembleMessages(system: String, selected: List<LocalMessageEntity>,
            cached: List<CompanionContextMaterialEntity>, auxiliary: String, turnReminder: String,
            userPayload: Map<String, Any>, raw: LocalMessageEntity, memoryIds: List<String> = emptyList(),
            uncertain: String = "", uncertainIds: List<String> = emptyList()): List<Map<String, Any>> = buildList {
            val materialById = cached.associateBy { it.messageId }
            add(mapOf("role" to "system", "content" to system))
            for (msg in selected) {
                val saved = materialById[msg.id]
                @Suppress("UNCHECKED_CAST")
                val sent = saved?.userPayload?.takeIf { it.isNotEmpty() }?.let {
                    runCatching { Gson().fromJson(it, Map::class.java) as Map<String, Any> }.getOrNull()
                }?.takeIf { it["role"] == msg.role }
                val outgoing = sent ?: mapOf("role" to msg.role, "content" to msg.content)
                // 本地身份标记在发给服务商前移除；正文不完全一致时宁可重读，也不能误判仍可见。
                add(outgoing + (CompanionRecallBudget.SOURCE_META to mapOf("id" to msg.id,
                    "revision" to CompanionMemoryPolicy.digest(msg.content))))
            }
            if (auxiliary.isNotBlank()) add(CompanionRequestUsage.auxiliary(auxiliary) + (CompanionReferences.META to memoryIds))
            if (uncertain.isNotBlank()) add(CompanionRequestUsage.auxiliary(uncertain) + (CompanionReferences.META to uncertainIds))
            add(userPayload + (CompanionRecallBudget.SOURCE_META to mapOf("id" to raw.id,
                "revision" to CompanionMemoryPolicy.digest(raw.content))))
            if (turnReminder.isNotBlank()) add(mapOf("role" to "system", "content" to turnReminder, CHARACTER_TURN_CONTEXT to turnReminder))
        }
    }
    var lastRetrieval: String = ""
        private set
}
