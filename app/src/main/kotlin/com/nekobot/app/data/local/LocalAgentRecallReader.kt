package com.nekobot.app.data.local

import com.nekobot.app.data.local.ai.AgentHistoryMessage
import com.nekobot.app.data.local.ai.AgentRecallCandidate
import com.nekobot.app.data.local.ai.AgentRecallReader
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.db.NekobotDatabase

/** All reads are scoped to the current database, inherited character and conversation. */
internal class LocalAgentRecallReader(private val db: NekobotDatabase) : AgentRecallReader {
    private val sessions = db.sessionDao()
    private val memories = db.memoryDao()
    private val experiences = db.experienceArchiveDao()
    private val messages = db.messageDao()

    override suspend fun isAvailable(sessionId: String): Boolean =
        sessions.getById(sessionId)?.let { session ->
            session.longConversationEnabled &&
                session.sessionMode.equals("agent", ignoreCase = true) &&
                session.inheritCharacter
        } == true

    override suspend fun searchRecall(
        sessionId: String,
        terms: List<String>,
        limit: Int
    ): List<AgentRecallCandidate> {
        val session = sessions.getById(sessionId)?.takeIf {
            it.longConversationEnabled && it.sessionMode.equals("agent", true) && it.inheritCharacter
        } ?: return emptyList()
        val bounded = limit.coerceIn(1, 48)
        val queries = terms.asSequence().map(String::trim).filter { it.length >= 2 }
            .distinct().take(12).toList()
        val candidates = linkedMapOf<String, AgentRecallCandidate>()
        for (query in queries) {
            session.characterId?.takeIf(String::isNotBlank)?.let { characterId ->
                memories.searchIncludingTags(characterId, "local-user", sessionId, query, bounded)
                    .forEach { memory ->
                        candidates["memory:${memory.id}"] = AgentRecallCandidate(
                            id = memory.id,
                            sourceType = "character_memory",
                            title = memory.title,
                            content = memory.content.ifBlank { memory.summary },
                            tags = MemoryTags.fromJson(memory.tagsJson),
                            sourceSessionId = memory.sourceSessionId,
                            startMessageId = memory.sourceStartMessageId,
                            endMessageId = memory.sourceEndMessageId,
                            startedAt = memory.createdAt,
                            endedAt = memory.updatedAt ?: memory.createdAt,
                            importance = memory.importance
                        )
                    }
            }
            experiences.searchReadyBySession(sessionId, query, bounded)
                .forEach { archive ->
                    candidates["episode:${archive.id}"] = AgentRecallCandidate(
                        id = archive.id,
                        sourceType = "episode",
                        title = MemoryTags.fromJson(archive.tagsJson).joinToString(" · ")
                            .ifBlank { "${archive.sourceStartedAt}—${archive.sourceEndedAt}" },
                        content = archive.summary,
                        tags = MemoryTags.fromJson(archive.tagsJson),
                        sourceSessionId = sessionId,
                        startMessageId = archive.startMessageId,
                        endMessageId = archive.endMessageId,
                        startedAt = archive.sourceStartedAt,
                        endedAt = archive.sourceEndedAt
                    )
                }
        }
        return candidates.values.take(48)
    }

    override suspend fun searchHistory(
        sessionId: String,
        terms: List<String>,
        limit: Int
    ): List<AgentHistoryMessage> {
        if (!isAvailable(sessionId)) return emptyList()
        val bounded = limit.coerceIn(1, 48)
        val found = linkedMapOf<String, AgentHistoryMessage>()
        for (query in terms.asSequence().map(String::trim).filter { it.length >= 2 }
            .distinct().take(12)) {
            messages.searchVisibleFinalBySession(sessionId, query, bounded).forEach { message ->
                found[message.id] = message.toRecallMessage()
            }
        }
        return found.values.take(48)
    }

    override suspend fun readHistory(
        sessionId: String,
        anchorMessageId: String,
        before: Int,
        after: Int
    ): List<AgentHistoryMessage> {
        if (!isAvailable(sessionId)) return emptyList()
        return messages.readVisibleWindow(
            sessionId = sessionId,
            anchorId = anchorMessageId,
            before = before.coerceIn(0, 4),
            after = after.coerceIn(0, 4)
        ).map { it.toRecallMessage() }
    }

    private fun com.nekobot.app.data.local.db.LocalMessageEntity.toRecallMessage() =
        AgentHistoryMessage(id = id, role = role, content = content, timestamp = timestamp)
}
