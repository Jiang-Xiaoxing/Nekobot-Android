package com.nekobot.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.ai.ChatRequest
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.ai.LocalPipelineCallbacks
import com.nekobot.app.data.local.ai.PipelineContext
import com.nekobot.app.data.local.ai.SESSION_ALLOWED_TOOL_NAMES_CONTEXT_KEY
import com.nekobot.app.data.local.db.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android CursorWindow checks for the upstream repair combined with personal memory. */
@RunWith(AndroidJUnit4::class)
class UpstreamPersonalIntegrationAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val day = "2026-10-07"

    @Test fun newToolPermissionGateAllowsEnabledRecallAndKeepsSharedMemoryBlocked() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        val id = "permission-companion-${UUID.randomUUID()}"
        try {
            val character = LocalCharacterEntity(id, "Synthetic permission", createdAt = day, updatedAt = day)
            db.characterDao().upsert(character)
            val session = LocalSessionEntity(id, "Synthetic permission", characterId = id,
                sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true,
                createdAt = day, updatedAt = day)
            db.sessionDao().upsert(session)
            db.messageDao().upsert(LocalMessageEntity(id, id, "user", "我怕辣。", timestamp = day, createdAt = day))
            val scope = CompanionMemoryStore(db).ensureScope(session)
            db.companionMemoryDao().saveScope(scope.copy(historyAccess = true))
            val model = LocalAiModelEntity(id, "Offline permission", protocol = "openai_chat",
                apiKey = "", baseUrl = "http://127.0.0.1:1", model = "offline", createdAt = day)
            val callbacks = LocalPipelineCallbacks(db, LocalAiClient(), model, session, character,
                recallReader = LocalAgentRecallReader(db), isSessionToolEnabled = { true })
            // Production loads history before executing tools; this also pins the memory revision.
            callbacks.loadMessages(PipelineContext(ChatRequest.forLocal(session.id, "还记得我怕辣吗？")))
            val args = mapOf<String, Any>("query" to "怕辣")
            assertEquals(false, callbacks.executeTool("agent_history_search", args, emptyMap())["success"])
            val allowed = mapOf<String, Any>(SESSION_ALLOWED_TOOL_NAMES_CONTEXT_KEY to setOf("agent_history_search", "agent_memory_update"))
            val found = callbacks.executeTool("agent_history_search", args, allowed)
            assertEquals(true, found["success"])
            assertTrue(found["results"].toString().contains(id))
            val blocked = callbacks.executeTool("agent_memory_update", emptyMap(), allowed)
            assertEquals(false, blocked["success"])
            assertTrue(blocked["error"].toString().contains("独立记忆"))
        } finally { db.close() }
    }

    @Test fun openingPageRepairsOversizedUnicodeAndPreservesCompleteDump() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        val id = "oversize-page-${UUID.randomUUID()}"
        val dump = File(context.filesDir, "oversized_message_fields/${id}_content.txt")
        try {
            val session = LocalSessionEntity(id, "Synthetic oversized page", createdAt = day, updatedAt = day)
            db.sessionDao().upsert(session)
            val original = "正文🙂".repeat(450_000) + "完整末尾标记"
            db.messageDao().upsert(LocalMessageEntity(id, id, "assistant", original, timestamp = day, createdAt = day))
            val page = LocalRepository(db, appContext = context).listRecentMessages(id, 20)
            assertEquals(1, page.messages.size)
            assertFalse(page.hasMore)
            val restored = db.messageDao().getById(id)!!
            assertTrue(restored.content.startsWith(original.take(2_000)))
            assertTrue(restored.content.contains("完整内容转存"))
            assertEquals(original, dump.readText())
            assertEquals(0, MessageOversizeRepair.repairOversizedRows(db.messageDao(), context, id))
        } finally {
            db.close()
            dump.delete()
        }
    }

    @Test fun readRetryRepairsToolHistoryAndKeepsCompanionFactAndVerbatimSource() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        val id = "oversize-companion-${UUID.randomUUID()}"
        val dump = File(context.filesDir, "oversized_message_fields/${id}_tool_call_history.txt")
        try {
            db.characterDao().upsert(LocalCharacterEntity(id, "Synthetic companion", createdAt = day, updatedAt = day))
            val session = LocalSessionEntity(id, "Synthetic context", characterId = id,
                sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true,
                createdAt = day, updatedAt = day)
            db.sessionDao().upsert(session)
            val message = LocalMessageEntity(id, id, "user", "我怕辣。", timestamp = day, createdAt = day)
            db.messageDao().upsert(message)
            val store = CompanionMemoryStore(db)
            val scope = store.ensureScope(session)
            db.companionMemoryDao().saveMemory(CompanionMemoryEntity(id, scope.id, "diet", 1,
                "fact", "用户", "用户怕辣", "active", createdAt = day, updatedAt = day))
            db.companionMemoryDao().saveSources(listOf(CompanionSourceEntity(id, id,
                CompanionMemoryPolicy.fingerprint(message), message.content, day, "user")))
            val original = "旧工具输出🙂".repeat(300_000) + "工具历史完整末尾"
            db.messageDao().updateToolCallHistory(id, original)
            assertEquals(1, LocalRepository(db, appContext = context).listMessages(id).size)
            assertEquals(original, dump.readText())
            assertEquals(message.content, db.messageDao().getById(id)!!.content)
            assertEquals("用户怕辣", store.validatedMemories(scope).single().content)
            assertEquals("我怕辣。", db.companionMemoryDao().sources(id).single().quote)
        } finally {
            db.close()
            dump.delete()
        }
    }
}
