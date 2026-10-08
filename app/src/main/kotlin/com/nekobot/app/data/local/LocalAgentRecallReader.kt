package com.nekobot.app.data.local

import com.nekobot.app.data.local.ai.AgentHistoryMessage
import com.nekobot.app.data.local.ai.AgentRecallCandidate
import com.nekobot.app.data.local.ai.AgentRecallReader
import com.nekobot.app.data.local.ai.MemoryTags
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import com.nekobot.app.data.local.ai.usesCompanionMemory
import com.nekobot.app.data.local.db.NekobotDatabase

/** All reads are scoped to the current database, inherited character and conversation. */
internal class LocalAgentRecallReader(private val db: NekobotDatabase) : AgentRecallReader {
    private val sessions = db.sessionDao()
    private val experiences = db.experienceArchiveDao()
    private val messages = db.messageDao()

    override suspend fun isAvailable(sessionId: String): Boolean =
        sessions.getById(sessionId)?.let { session ->
            session.usesCompanionMemory()
        } == true

    override suspend fun searchRecall(
        sessionId: String,
        terms: List<String>,
        limit: Int
    ): List<AgentRecallCandidate> {
        val session = sessions.getById(sessionId)?.takeIf {
            it.usesCompanionMemory()
        } ?: return emptyList()
        val bounded = limit.coerceIn(1, 48)
        val queries = terms.asSequence().map(String::trim).filter { it.length >= 2 }
            .distinct().take(12).toList()
        val candidates = linkedMapOf<String, AgentRecallCandidate>()
        val store = CompanionMemoryStore(db)
        val scope = store.ensureScope(session)
        val forgotten = db.companionMemoryDao().forgotten(scope.id).toSet()
        val facts = store.validatedMemories(scope)
        for (query in queries) {
            if (session.usesCompanionMemory()) {
                facts.filter { CompanionMemoryPolicy.score(query, it.content + it.factKey + it.tagsJson + it.eventDate) > 0.04f }
                    .forEach { memory ->
                        val sources = db.companionMemoryDao().sources(memory.id)
                        candidates["memory:${memory.id}"] = AgentRecallCandidate(
                            id = memory.id,
                            sourceType = "companion_memory",
                            title = "${memory.factKey} · v${memory.version} · ${if (memory.current) "当前" else "历史"}",
                            content = "${memory.content} [状态:${memory.status}; ${CompanionMemoryPolicy.temporalHint(memory.eventDate, java.time.LocalDate.now())}]",
                            tags = MemoryTags.fromJson(memory.tagsJson),
                            sourceSessionId = sessionId,
                            startMessageId = sources.firstOrNull()?.messageId,
                            endMessageId = sources.lastOrNull()?.messageId,
                            startedAt = sources.firstOrNull()?.spokenDate,
                            endedAt = sources.lastOrNull()?.spokenDate
                        )
                    }
            }
            experiences.searchReadyBySession(sessionId, query, bounded)
                .filter { it.characterId == session.characterId && experiences.sourceMessageIds(it.id).none { id -> id in forgotten } }
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
        val session = sessions.getById(sessionId) ?: return emptyList()
        for (day in terms.flatMap(CompanionMemoryPolicy::dateTerms).distinct()) {
            CompanionMemoryStore(db).permittedHistory(session, db.companionMemoryDao().historyOnDay(sessionId, day))
                .take(bounded).forEach { found[it.id] = it.toRecallMessage() }
        }
        for (query in terms.asSequence().map(String::trim).filter { it.length >= 2 }
            .distinct().take(12)) {
            CompanionMemoryStore(db).permittedHistory(session, messages.searchVisibleFinalBySession(sessionId, query, bounded)).forEach { message ->
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
        val session = sessions.getById(sessionId) ?: return emptyList()
        return CompanionMemoryStore(db).permittedHistory(session, messages.readVisibleWindow(
            sessionId = sessionId,
            anchorId = anchorMessageId,
            before = before.coerceIn(0, 4),
            after = after.coerceIn(0, 4)
        )).map { it.toRecallMessage() }
    }

    private fun com.nekobot.app.data.local.db.LocalMessageEntity.toRecallMessage() =
        AgentHistoryMessage(id = id, role = role, content = content,
            timestamp = com.nekobot.app.data.local.ai.agentUtteranceDay(this)?.toString().orEmpty())
}
