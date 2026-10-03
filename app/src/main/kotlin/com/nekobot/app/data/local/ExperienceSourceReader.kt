package com.nekobot.app.data.local

import androidx.room.withTransaction
import com.nekobot.app.data.local.db.LocalExperienceArchiveEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase

data class ExperienceSourcePage(
    val archive: LocalExperienceArchiveEntity,
    val sessionName: String,
    val messages: List<LocalMessageEntity>,
    val sourceCount: Int,
    val availableCount: Int,
    val hasMore: Boolean,
    val assistantName: String? = null
)

data class ChatHistoryWindow(
    val sessionId: String,
    val sessionName: String,
    val anchorMessageId: String,
    val messages: List<LocalMessageEntity>,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val assistantName: String? = null,
    val archiveId: String? = null,
    val sourceMessageIds: Set<String> = emptySet()
)

data class ChatHistoryPage(
    val messages: List<LocalMessageEntity>, val hasMore: Boolean,
    val sourceMessageIds: Set<String> = emptySet()
)

/** Read-only UI access. Never invokes a model or changes the live chat runtime. */
class ExperienceSourceReader(private val db: NekobotDatabase) {
    suspend fun sourcePage(
        sessionId: String, archiveId: String, afterMessageId: String? = null,
        pageSize: Int = PAGE_SIZE
    ): ExperienceSourcePage? = db.withTransaction {
        val archive = db.experienceArchiveDao().getById(archiveId)
            ?.takeIf { it.sessionId == sessionId } ?: return@withTransaction null
        val session = db.sessionDao().getById(sessionId) ?: return@withTransaction null
        val sources = db.experienceArchiveDao()
        val cursor = if (afterMessageId == null) null else {
            // Soft-deleted cursors still have a stable position. Foreign or missing
            // cursors cannot restart the list or disclose a different conversation.
            val message = db.messageDao().getById(afterMessageId)
                ?.takeIf { it.sessionId == sessionId } ?: return@withTransaction null
            if (!sources.containsSource(archiveId, message.id)) return@withTransaction null
            db.messageDao().cursorOf(message.id) ?: return@withTransaction null
        }
        val limit = pageSize.coerceIn(1, PAGE_SIZE)
        val rows = sources.listSourceRows(sessionId, archiveId, cursor?.createdAt, cursor?.rowId, limit + 1)
        ExperienceSourcePage(
            archive = archive,
            sessionName = session.name,
            messages = rows.take(limit).map { it.message },
            sourceCount = sources.sourceCount(archiveId),
            availableCount = sources.availableSourceCount(sessionId, archiveId),
            hasMore = rows.size > limit,
            assistantName = assistantName(session)
        )
    }

    suspend fun historyWindow(sessionId: String, messageId: String, archiveId: String? = null): ChatHistoryWindow? = db.withTransaction {
        val session = db.sessionDao().getById(sessionId) ?: return@withTransaction null
        if (archiveId != null && db.experienceArchiveDao().getById(archiveId)?.sessionId != sessionId) return@withTransaction null
        val messages = db.messageDao()
        val anchor = messages.visibleAnchorRow(sessionId, messageId) ?: return@withTransaction null
        if (archiveId != null && !db.experienceArchiveDao().containsSource(archiveId, messageId)) return@withTransaction null
        val previous = messages.listVisibleBeforeAnchor(sessionId, anchor.message.createdAt, anchor.rowId, PAGE_SIZE + 1)
        val following = messages.listVisibleAfterAnchor(sessionId, anchor.message.createdAt, anchor.rowId, PAGE_SIZE + 1)
        val visible = previous.take(PAGE_SIZE).asReversed().map { it.message } + anchor.message +
            following.take(PAGE_SIZE).map { it.message }
        ChatHistoryWindow(
            sessionId = sessionId, sessionName = session.name, anchorMessageId = messageId,
            messages = visible,
            hasOlder = previous.size > PAGE_SIZE, hasNewer = following.size > PAGE_SIZE,
            assistantName = assistantName(session), archiveId = archiveId,
            sourceMessageIds = sourceIds(sessionId, archiveId, visible)
        )
    }

    suspend fun historyPage(sessionId: String, cursorId: String, older: Boolean, archiveId: String? = null): ChatHistoryPage? = db.withTransaction {
        if (archiveId != null && db.experienceArchiveDao().getById(archiveId)?.sessionId != sessionId) return@withTransaction null
        val messages = db.messageDao()
        val cursor = messages.getById(cursorId)?.takeIf { it.sessionId == sessionId }
            ?: return@withTransaction null
        val position = messages.cursorOf(cursor.id) ?: return@withTransaction null
        val rows = if (older) {
            messages.listVisibleBeforeAnchor(sessionId, position.createdAt, position.rowId, PAGE_SIZE + 1)
        } else {
            messages.listVisibleAfterAnchor(sessionId, position.createdAt, position.rowId, PAGE_SIZE + 1)
        }
        val page = rows.take(PAGE_SIZE).let { if (older) it.asReversed() else it }
        val visible = page.map { it.message }
        ChatHistoryPage(visible, rows.size > PAGE_SIZE, sourceIds(sessionId, archiveId, visible))
    }

    private suspend fun assistantName(session: LocalSessionEntity): String? =
        session.characterName?.trim()?.takeIf { it.isNotEmpty() }
            ?: session.characterId?.let { db.characterDao().getById(it)?.name?.trim()?.takeIf(String::isNotEmpty) }
            ?: session.senderName?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun sourceIds(sessionId: String, archiveId: String?, messages: List<LocalMessageEntity>): Set<String> =
        if (archiveId == null || messages.isEmpty()) emptySet()
        else db.experienceArchiveDao().sourceIdsAmong(sessionId, archiveId, messages.map { it.id }).toSet()

    companion object { const val PAGE_SIZE = 60 }
}
