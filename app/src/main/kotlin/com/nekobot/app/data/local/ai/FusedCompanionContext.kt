package com.nekobot.app.data.local.ai

import androidx.room.withTransaction
import com.google.gson.Gson
import com.nekobot.app.data.local.db.*
import java.time.ZonedDateTime

/** Append-only auxiliary records are distinct from local_messages and never extraction inputs. */
internal class FusedCompanionContext(private val db: NekobotDatabase) {
    suspend fun build(session: LocalSessionEntity, messageId: String, history: List<LocalMessageEntity>,
        system: String, tools: List<Map<String, Any>>, currentMessage: Map<String, Any>, dynamic: String,
        maxContext: Int, outputReserve: Int, now: ZonedDateTime = ZonedDateTime.now(), stateKey: String = "relationship-v2:off"): List<Map<String, Any>> {
        val store = CompanionMemoryStore(db)
        val dao = db.companionMemoryDao()
        val scope = store.ensureScope(session)
        // 到期后连同历史辅助资料一起重建，避免旧缓存继续带入已经过期的记忆。
        val expired = dao.memories(scope.id).filterNot { CompanionImportPolicy.usable(it, now.toInstant()) }.map { it.id }.sorted()
        // State changes invalidate auxiliary caches, including turning the feature off.
        val retrieval = com.nekobot.app.ServiceContainer.prefs.companionRetrievalSettings(db.dbName)
        val configuration = CompanionMemoryPolicy.digest(retrieval.toString() + stateKey + FusedCompanionPolicy.contextFormat + system + Gson().toJson(tools) + maxContext + outputReserve + now.zone.id + now.toLocalDate() + expired)
        val available = maxContext - outputReserve - estimateLocalTextTokens(system) - estimateLocalTextTokens(Gson().toJson(tools)) - 256
        val consolidation = consolidationSettingsFor(db.dbName, session.id)
        val budgets = FusedCompanionPolicy.budgets(available, consolidation)
        val (recall, mode) = store.fusedRecall(session, currentMessage["content"].toString(), budgets, now.toLocalDate())
        val old = dao.contextMaterials(scope.id)
        var cached = old.takeIf { rows -> rows.all { it.revision == scope.revision && it.configuration == configuration } }.orEmpty()
        val sequence = db.messageDao().cursorOf(messageId)?.rowId ?: error("本轮用户消息尚未保存")
        cached = cached.filter { it.sequence < sequence }
        val first = cached.firstOrNull()?.startMessageId
        val from = history.indexOfFirst { it.id == first }
        var selected = if (first != null && from >= 0) history.drop(from) else emptyList()
        val timeKey = FusedCompanionPolicy.timeKey(now)
        val raw = requireNotNull(db.messageDao().getById(messageId))
        val turnReminder = CompanionPromptLanguage.clock(now, history.lastOrNull { it.role in setOf("user", "assistant") }) +
            "\n" + CompanionPromptLanguage.styleReminder
        fun material(): String = buildString {
            appendLine("这些是帮助你想起往事的参考，不是对方刚说的话，也不是新发生的经历；别把这里的说明另记成往事。")
            appendLine(recall)
            appendLine()
            storeQuestion?.let { appendLine("有一项需要自然核对，只问一次：$it") }
            if (dynamic.isNotBlank()) appendLine("当前角色状态、任务和参考设定（世界书属于设定，不能当作已发生经历）：\n$dynamic")
        }
        storeQuestion = store.questionForReply(session)
        var auxiliary = material()
        fun size(rows: List<LocalMessageEntity>, materials: List<CompanionContextMaterialEntity>, text: String) =
            FusedCompanionPolicy.tokens(rows) + materials.sumOf { estimateLocalTextTokens(it.content) + estimateLocalTextTokens(it.userPayload) + 24 } +
                estimateLocalTextTokens(turnReminder) + estimateLocalTextTokens(text) + estimateLocalTextTokens(Gson().toJson(currentMessage)) + 72
        val rebuild = selected.isEmpty() || size(selected, cached, auxiliary) > available
        if (rebuild) {
            cached = emptyList()
            auxiliary = material()
            // 近期额度取设定与上下文份额的较小值。积压的未整理消息不扩大本轮窗口，留给后台整理。
            val recentCap = FusedCompanionPolicy.chatRecentBudget(available, consolidation)
            selected = FusedCompanionPolicy.selectChatHistory(history, recentCap) { rows ->
                size(rows, cached, auxiliary) <= available
            }
        }
        require(size(selected, cached, auxiliary) <= available) {
            "本轮原话、核心记忆及其他资料合计超过模型容量，尚未发送。请等待待整理内容处理完毕、精简核心记忆，或选择更大上下文的模型后重试；原资料保留。"
        }
        val startId = selected.firstOrNull()?.id ?: messageId
        val userPayload = currentMessage
        val row = CompanionContextMaterialEntity(scope.id, messageId, sequence, scope.revision, configuration,
            startId, auxiliary, timeKey, now.toInstant().toEpochMilli(), mode, Gson().toJson(userPayload))
        db.withTransaction {
            require(dao.scope(scope.id)?.revision == scope.revision) { "构造请求期间记忆已更新，请重试" }
            if (rebuild || old.size != cached.size) dao.eraseContextMaterials(scope.id)
            if (!rebuild && old.size != cached.size) cached.forEach { dao.saveContextMaterial(it) }
            dao.saveContextMaterial(row)
        }
        lastRetrieval = mode
        return assembleMessages(system, selected, cached, auxiliary, turnReminder, userPayload, raw)
    }

    companion object {
        /** 只读发送副本；旧轮缓存保持原样，易变提醒只紧邻当前轮。 */
        internal fun assembleMessages(system: String, selected: List<LocalMessageEntity>,
            cached: List<CompanionContextMaterialEntity>, auxiliary: String, turnReminder: String,
            userPayload: Map<String, Any>, raw: LocalMessageEntity): List<Map<String, Any>> = buildList {
            val materialById = cached.associateBy { it.messageId }
            add(mapOf("role" to "system", "content" to system))
            for (msg in selected) {
                val saved = materialById[msg.id]
                if (saved != null) add(auxiliaryMessage(saved.content))
                @Suppress("UNCHECKED_CAST")
                val sent = saved?.userPayload?.takeIf { it.isNotEmpty() }?.let { Gson().fromJson(it, Map::class.java) as Map<String, Any> }
                val outgoing = sent ?: mapOf("role" to msg.role, "content" to msg.content)
                // 本地身份标记在发给服务商前移除；正文不完全一致时宁可重读，也不能误判仍可见。
                add(outgoing + (CompanionRecallBudget.SOURCE_META to mapOf("id" to msg.id,
                    "revision" to CompanionMemoryPolicy.digest(msg.content))))
            }
            add(auxiliaryMessage(auxiliary))
            // 时间和临场提醒只出现在当前轮，不进入旧轮缓存，也不改变稳定配置指纹。
            add(auxiliaryMessage(turnReminder))
            add(userPayload + (CompanionRecallBudget.SOURCE_META to mapOf("id" to raw.id,
                "revision" to CompanionMemoryPolicy.digest(raw.content))))
        }

        // 动态参考使用普通消息角色，与稳定的顶部提示分开。
        private fun auxiliaryMessage(text: String): Map<String, Any> = CompanionRequestUsage.auxiliary(text)
    }
    private var storeQuestion: String? = null
    var lastRetrieval: String = ""
        private set
}
