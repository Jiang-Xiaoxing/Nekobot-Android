package com.nekobot.app.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDaoScopeTest {
    @Test
    fun tagSearchKeepsSessionEventsPrivateAndGlobalPersonaVisible() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NekobotDatabase::class.java
        ).build()
        try {
            val dao = db.memoryDao()
            dao.upsertAll(listOf(
                memory("persona", "character_persona", "s2"),
                memory("same-event", "important_event", "s1"),
                memory("other-event", "important_event", "s2"),
                memory("other-life", "life_sim", "s2")
            ))
            val found = dao.searchIncludingTags("character", "player", "s1", "月饼", 20)
                .map { it.id }.toSet()
            assertEquals(setOf("persona", "same-event"), found)
        } finally {
            db.close()
        }
    }

    @Test
    fun generatedReplacementAndTrimKeepManualTagsAndOriginalSource() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NekobotDatabase::class.java
        ).build()
        try {
            val dao = db.memoryDao()
            val path = "characters/character/users/player/character_persona.md"
            dao.upsert(
                memory("manual", "character_persona", "s1").copy(
                    memoryPath = path,
                    tagsEdited = true,
                    tagsJson = "[\"用户标签\"]",
                    sourceSessionId = "s1",
                    sourceStartMessageId = "u1",
                    sourceEndMessageId = "a1"
                )
            )
            dao.upsert(memory("obsolete", "character_persona", "s1").copy(memoryPath = path))
            dao.deleteGeneratedByPath(path)
            assertEquals(listOf("manual"), dao.listByPath(path).map { it.id })

            dao.upsert(memory("new-generated", "character_persona", "s2").copy(memoryPath = path))
            dao.trimGeneratedByPath(path, keep = 0)
            val kept = dao.listByPath(path)
            assertEquals(listOf("manual"), kept.map { it.id })
            assertTrue(kept.single().tagsEdited)
            assertEquals("[\"用户标签\"]", kept.single().tagsJson)
            assertEquals("s1", kept.single().sourceSessionId)
            assertEquals("u1", kept.single().sourceStartMessageId)
            assertEquals("a1", kept.single().sourceEndMessageId)
        } finally {
            db.close()
        }
    }

    @Test
    fun onlyExistingAgentSummaryCanBeReanchored() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NekobotDatabase::class.java
        ).build()
        try {
            db.sessionDao().upsert(LocalSessionEntity(
                id = "s1", name = "对话", createdAt = "now", updatedAt = "now"
            ))
            val dao = db.messageDao()
            dao.upsert(LocalMessageEntity(
                id = "summary", sessionId = "s1", role = "system", content = "【历史对话摘要】旧",
                timestamp = "now", createdAt = "now", source = "agent_context_summary"
            ))
            dao.upsert(LocalMessageEntity(
                id = "user", sessionId = "s1", role = "user", content = "原话",
                timestamp = "now", createdAt = "now"
            ))
            assertEquals(1, dao.updateSummaryBoundary("summary", "【历史对话摘要】新", "agent_context_summary:rebased"))
            assertEquals(0, dao.updateSummaryBoundary("user", "篡改", "agent_context_summary"))
            assertEquals("【历史对话摘要】新", dao.getById("summary")?.content)
            assertEquals("原话", dao.getById("user")?.content)
            assertFalse(dao.getById("user")?.role == "system")
        } finally {
            db.close()
        }
    }

    private fun memory(id: String, category: String, conversationId: String) = LocalCharacterMemoryEntity(
        id = id,
        characterId = "character",
        targetId = "player",
        type = "long",
        category = category,
        title = "月饼",
        summary = "月饼",
        content = "月饼",
        importance = 5,
        createdAt = "now",
        conversationId = conversationId,
        tagsJson = "[\"月饼\"]"
    )
}
