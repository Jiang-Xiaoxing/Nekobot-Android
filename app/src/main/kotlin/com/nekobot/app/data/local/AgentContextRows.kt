package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.MessageDao

/**
 * Read only the live Agent window when a persisted summary has a valid boundary.
 * Keep the boundary row itself so [agentContextWindow] can verify the ID; never
 * silently treat an incomplete window as complete if that source row is gone.
 */
internal suspend fun MessageDao.listAgentRowsWithBoundary(
    sessionId: String,
    strictBoundary: Boolean = false
): List<LocalMessageEntity> {
    val summary = latestAgentSummaryBySession(sessionId) ?: run {
        if (strictBoundary && countVisibleFinalBySession(sessionId) > 2_000) {
            throw IllegalStateException("已有大历史尚未建立安全摘要边界；已停止整段载入")
        }
        return listBySession(sessionId)
    }
    suspend fun invalidBoundary(): List<LocalMessageEntity> {
        if (strictBoundary) throw IllegalStateException("Agent 摘要边界无效；已停止读取旧历史以免整段回灌模型")
        return listBySession(sessionId)
    }
    val boundaryId = summary.agentContextSummaryBoundaryId()
        ?: return invalidBoundary()
    val boundary = getById(boundaryId)?.takeIf { it.sessionId == sessionId && !it.deleted }
        ?: return invalidBoundary()
    var cursor = cursorOf(boundary.id)
        ?: return invalidBoundary()
    val rows = ArrayList<LocalMessageEntity>()
    var firstPage = true
    while (true) {
        val page = listRowsAtOrAfterCursor(sessionId, cursor.createdAt, cursor.rowId, 512)
        if (page.isEmpty()) break
        val next = if (firstPage) page else page.drop(1)
        rows.addAll(next.map { it.message })
        firstPage = false
        if (page.size < 512) break
        val last = page.last()
        cursor = com.nekobot.app.data.local.db.LocalMessageCursor(last.message.createdAt, last.rowId)
        // A context this large requires explicit repair/compaction. Do not omit its tail.
        if (rows.size > 20_000) throw IllegalStateException("Agent 上下文窗口异常过大，请先修复摘要边界")
    }
    if (rows.none { it.id == boundaryId }) return invalidBoundary()
    if (rows.none { it.id == summary.id }) rows.add(summary)
    return rows
}
