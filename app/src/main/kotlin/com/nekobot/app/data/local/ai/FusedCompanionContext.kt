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
        val configuration = CompanionMemoryPolicy.digest(system + Gson().toJson(tools) + maxContext + outputReserve)
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
        fun material(rebuild: Boolean): String = buildString {
            appendLine("<software_context>软件提供的本轮辅助资料，不是玩家发言，不是新的共同经历。仅用于回答本轮问题；不得将其再次存成记忆。")
            val lastTime = cached.lastOrNull()
            if (rebuild || lastTime?.timeKey != timeKey) appendLine("当前日期及时段：$timeKey；精确时刻需要时调用 get_date_time。")
            val gap = history.lastOrNull()?.let { row -> runCatching {
                val instant = runCatching { java.time.Instant.parse(row.createdAt) }.getOrElse {
                    java.time.LocalDateTime.parse(row.createdAt.replace(' ', 'T')).atZone(now.zone).toInstant()
                }
                java.time.Duration.between(instant, now.toInstant()).toMinutes()
            }.getOrNull() }
            if (gap != null && gap >= 30) appendLine("距上一条聊天消息约 ${if (gap < 120) "$gap 分钟" else "${gap / 60} 小时"}，不代表玩家一直离线。")
            appendLine(recall)
            storeQuestion?.let { appendLine("有一项需要自然核对，只问一次：$it") }
            if (dynamic.isNotBlank()) appendLine("当前角色状态、任务和参考设定（世界书属于设定，不能当作已发生经历）：\n$dynamic")
            append("</software_context>")
        }
        storeQuestion = store.questionForReply(session)
        var auxiliary = material(selected.isEmpty())
        fun size(rows: List<LocalMessageEntity>, materials: List<CompanionContextMaterialEntity>, text: String) =
            FusedCompanionPolicy.tokens(rows) + materials.sumOf { estimateLocalTextTokens(it.content) + estimateLocalTextTokens(it.userPayload) + 24 } +
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
            auxiliary = material(true)
        }
        require(size(selected, cached, auxiliary) <= available) {
            "完整原话和待整理内容超过模型容量；未删除、未截断消息。请等待后台整理完成，或增大模型上下文后重试"
        }
        val startId = selected.firstOrNull()?.id ?: messageId
        val raw = requireNotNull(db.messageDao().getById(messageId))
        val userPayload = if (currentMessage["content"] is String) currentMessage +
            ("content" to formatAgentHistoryMessageContent(raw.copy(content = currentMessage["content"] as String))) else currentMessage
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
        return buildList {
            add(mapOf("role" to "system", "content" to system))
            for (msg in selected) {
                val saved = materialById[msg.id]
                saved?.let { add(auxiliaryMessage(it.content)) }
                @Suppress("UNCHECKED_CAST")
                val sent = saved?.userPayload?.takeIf { it.isNotEmpty() }?.let { Gson().fromJson(it, Map::class.java) as Map<String, Any> }
                add(sent ?: mapOf("role" to msg.role, "content" to formatAgentHistoryMessageContent(msg)))
            }
            add(auxiliaryMessage(auxiliary))
            add(userPayload)
        }
    }

    // Most chat APIs only offer user/assistant/system roles; the explicit wrapper preserves
    // provenance without promoting dynamic retrieved text into the system instruction prefix.
    private fun auxiliaryMessage(text: String): Map<String, Any> = mapOf("role" to "user", "content" to text)
    private var storeQuestion: String? = null
    var lastRetrieval: String = ""
        private set
}
