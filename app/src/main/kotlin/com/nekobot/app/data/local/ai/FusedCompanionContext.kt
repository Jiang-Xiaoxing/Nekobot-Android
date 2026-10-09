package com.nekobot.app.data.local.ai

import androidx.room.withTransaction
import com.google.gson.Gson
import com.nekobot.app.data.local.db.*
import java.time.ZonedDateTime

/** Append-only auxiliary records are distinct from local_messages and never extraction inputs. */
internal class FusedCompanionContext(private val db: NekobotDatabase) {
    suspend fun build(session: LocalSessionEntity, messageId: String, history: List<LocalMessageEntity>,
        system: String, tools: List<Map<String, Any>>, currentMessage: Map<String, Any>, dynamic: String,
        maxContext: Int, outputReserve: Int, now: ZonedDateTime = ZonedDateTime.now()): List<Map<String, Any>> {
        val store = CompanionMemoryStore(db)
        val dao = db.companionMemoryDao()
        val scope = store.ensureScope(session)
        val configuration = CompanionMemoryPolicy.digest(FusedCompanionPolicy.contextFormat + system + Gson().toJson(tools) + maxContext + outputReserve + now.zone.id)
        val available = maxContext - outputReserve - estimateLocalTextTokens(system) - estimateLocalTextTokens(Gson().toJson(tools)) - 256
        val budgets = FusedCompanionPolicy.budgets(available)
        val (recall, mode) = store.fusedRecall(session, currentMessage["content"].toString(), budgets)
        val old = dao.contextMaterials(scope.id)
        var cached = old.takeIf { rows -> rows.all { it.revision == scope.revision && it.configuration == configuration } }.orEmpty()
        val sequence = db.messageDao().cursorOf(messageId)?.rowId ?: error("本轮用户消息尚未保存")
        cached = cached.filter { it.sequence < sequence }
        val first = cached.firstOrNull()?.startMessageId
        val from = history.indexOfFirst { it.id == first }
        var selected = if (first != null && from >= 0) history.drop(from) else emptyList()
        val timeKey = FusedCompanionPolicy.timeKey(now)
        val raw = requireNotNull(db.messageDao().getById(messageId))
        fun material(): String = buildString {
            appendLine("<software_context>软件提供的本轮辅助资料，不是玩家发言，不是新的共同经历。仅用于回答本轮问题；不得将其再次存成记忆。")
            // This is the request-start clock, not the message's send time or an event date.
            // Every new request gets a fresh sample; cached earlier requests stay immutable.
            appendLine("本轮请求开始时间：$timeKey。")
            appendLine("本轮玩家原话的发送日期：${agentUtteranceDay(raw) ?: "未知"}（不是所聊事件的发生日期）。")
            appendLine(recall)
            storeQuestion?.let { appendLine("有一项需要自然核对，只问一次：$it") }
            if (dynamic.isNotBlank()) appendLine("当前角色状态、任务和参考设定（世界书属于设定，不能当作已发生经历）：\n$dynamic")
            append("</software_context>")
        }
        storeQuestion = store.questionForReply(session)
        var auxiliary = material()
        fun size(rows: List<LocalMessageEntity>, materials: List<CompanionContextMaterialEntity>, text: String) =
            FusedCompanionPolicy.tokens(rows) + materials.sumOf { estimateLocalTextTokens(it.content) + estimateLocalTextTokens(it.userPayload) + 24 } +
                historyMetadata(rows, materials.map { it.messageId }.toSet()).values.sumOf { estimateLocalTextTokens(it) + 24 } +
                estimateLocalTextTokens(text) + estimateLocalTextTokens(Gson().toJson(currentMessage)) + 48
        val rebuild = selected.isEmpty() || size(selected, cached, auxiliary) > available
        if (rebuild) {
            cached = emptyList()
            selected = FusedCompanionPolicy.recent(history, budgets.recent)
            // Unprocessed messages cannot disappear merely because they fell out of the recent tail.
            val pendingIds = dao.pending(scope.id, Int.MAX_VALUE).map { it.id }.toSet()
            val oldestPending = history.indexOfFirst { it.id in pendingIds }
            val start = history.indexOfFirst { it.id == selected.firstOrNull()?.id }
            if (oldestPending >= 0 && (start < 0 || oldestPending < start)) selected = history.drop(oldestPending)
            auxiliary = material()
        }
        require(size(selected, cached, auxiliary) <= available) {
            "完整原话和待整理内容超过模型容量；未删除、未截断消息。请等待后台整理完成，或增大模型上下文后重试"
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
        val materialById = cached.associateBy { it.messageId }
        val datesById = historyMetadata(selected, materialById.keys)
        return buildList {
            add(mapOf("role" to "system", "content" to system))
            for (msg in selected) {
                val saved = materialById[msg.id]
                if (saved != null) add(auxiliaryMessage(saved.content))
                else datesById[msg.id]?.let { add(auxiliaryMessage(it)) }
                @Suppress("UNCHECKED_CAST")
                val sent = saved?.userPayload?.takeIf { it.isNotEmpty() }?.let { Gson().fromJson(it, Map::class.java) as Map<String, Any> }
                add(sent ?: mapOf("role" to msg.role, "content" to msg.content))
            }
            add(auxiliaryMessage(auxiliary))
            add(userPayload)
        }
    }

    // Most chat APIs only offer user/assistant/system roles; the explicit wrapper preserves
    // provenance without promoting dynamic retrieved text into the system instruction prefix.
    private fun auxiliaryMessage(text: String): Map<String, Any> = mapOf("role" to "user", "content" to text)
    // Dates remain available, but never masquerade as the assistant's own response format.
    private fun historyMetadata(rows: List<LocalMessageEntity>, cachedIds: Set<String>): Map<String, String> {
        var previousDay: java.time.LocalDate? = null
        var hasDate = false
        return buildMap {
            for (message in rows) {
                if (message.role !in listOf("user", "human", "assistant", "ai")) continue
                val day = agentUtteranceDay(message)
                // Cached request material already contains its user's send date. Between those
                // records, one marker per date boundary avoids overhead on many short messages.
                if (message.id !in cachedIds && (!hasDate || day != previousDay)) {
                    put(message.id, "<message_metadata>软件提供：以下原话的发送日期为 ${day ?: "未知"}，直到下一个发送日期标记；不是事件日期或发言内容，不要在回复中复述本标记。</message_metadata>")
                }
                previousDay = day
                hasDate = true
            }
        }
    }
    private var storeQuestion: String? = null
    var lastRetrieval: String = ""
        private set
}
