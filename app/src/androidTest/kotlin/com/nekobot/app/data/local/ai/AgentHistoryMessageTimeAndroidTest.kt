package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.db.LocalAgentToolMessageEntity
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and production loader; in-memory synthetic data, no generation requests. */
@RunWith(AndroidJUnit4::class)
class AgentHistoryMessageTimeAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val model = LocalAiModelEntity(id = "offline-time", name = "Offline", protocol = "openai_chat",
        apiKey = "", baseUrl = "http://127.0.0.1:1", model = "offline", createdAt = "now")
    private fun database() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()

    private suspend fun seed(db: NekobotDatabase, inherited: Boolean): LocalSessionEntity {
        val characterId = if (inherited) "character" else null
        if (characterId != null) db.characterDao().upsert(LocalCharacterEntity(
            id = characterId, name = "Test", createdAt = "now", updatedAt = "now"))
        val session = LocalSessionEntity(id = "s", name = "Time test", sessionMode = "agent",
            characterId = characterId, inheritCharacter = inherited, createdAt = "now", updatedAt = "now")
        db.sessionDao().upsert(session)
        db.messageDao().upsertAll(listOf(
            row("u0", "user", "Earlier question", "2026-10-01 23:59:58.001"),
            row("a0", "assistant", "Earlier answer", "2026-10-01 23:59:59.002"),
            row("u1", "user", "Question to regenerate", "2026-10-02 12:36:34.242"),
            row("a1", "assistant", "Answer to replace", "2026-10-02 12:36:35.100"),
            row("u2", "user", "Later question", "2026-10-02 12:36:39.171"),
            row("a2", "assistant", "Later answer", "2026-10-02 12:36:40.001")
        ))
        return session
    }

    private fun row(id: String, role: String, content: String, date: String) = LocalMessageEntity(
        id = id, sessionId = "s", role = role, content = content, timestamp = date, createdAt = date)

    private suspend fun callbacks(db: NekobotDatabase, session: LocalSessionEntity, parent: String? = null,
        target: String? = null) = LocalPipelineCallbacks(db, LocalAiClient(), model, session,
        session.characterId?.let { db.characterDao().getById(it) }, parentMessageId = parent,
        variantTargetMessageId = target)

    @Test
    fun boundAndUnboundLoadersAttachStoredTimesAndAppendCurrentRequestOnce() = runBlocking {
        for (inherited in listOf(true, false)) {
            val db = database()
            try {
                val session = seed(db, inherited)
                db.messageDao().upsert(row("current", "user", "Current request", "2026-10-07 12:00:00"))
                val before = db.messageDao().listBySession(session.id)
                val loader = callbacks(db, session, parent = "current")
                val request = PipelineContext(ChatRequest.forLocal(session.id, "Current request"))
                val loaded = loader.loadMessages(request)
                before.filter { it.id != "current" }.zip(loaded).forEach { (original, message) ->
                    assertEquals(original.role, message["role"])
                    assertEquals("【历史消息日期：${original.createdAt.take(10)}】\n${original.content}", message["content"])
                    assertEquals(original.content, (message["content"] as String).substringAfter("\n"))
                }
                assertEquals(before.size, loaded.size)
                assertEquals("Current request", loaded.last()["content"])
                assertEquals(1, loaded.count { it["content"] == "Current request" })
                assertEquals(loaded, loader.loadMessages(request))
                assertEquals(before, db.messageDao().listBySession(session.id))
            } finally { db.close() }
        }
    }

    @Test
    fun regenerationKeepsEarlierDatesAndExcludesReplacedAndLaterReplies() = runBlocking {
        for (inherited in listOf(true, false)) {
            val db = database()
            try {
                val session = seed(db, inherited)
                val before = db.messageDao().listBySession(session.id)
                val loader = callbacks(db, session, parent = "u1", target = "a1")
                val loaded = loader.loadMessages(PipelineContext(ChatRequest.forLocal(session.id, "Question to regenerate")))
                assertEquals(3, loaded.size)
                assertEquals("【历史消息日期：2026-10-01】\nEarlier question", loaded[0]["content"])
                assertEquals("【历史消息日期：2026-10-01】\nEarlier answer", loaded[1]["content"])
                assertEquals("Question to regenerate", loaded.last()["content"])
                assertFalse(loaded.any { (it["content"] as String).contains("Later") ||
                    (it["content"] as String).contains("Answer to replace") })
                assertEquals(before, db.messageDao().listBySession(session.id))
            } finally { db.close() }
        }
    }

    @Test
    fun durableContinuationKeepsToolCallPairsAndDoesNotApplyUtteranceTimeToTools() = runBlocking {
        val db = database()
        try {
            val session = seed(db, inherited = true)
            val trace = listOf(
                mapOf<String, Any>("role" to "assistant", "content" to "", "tool_calls" to listOf(
                    mapOf("id" to "read", "type" to "function", "function" to mapOf("name" to "read", "arguments" to "{}")))),
                mapOf<String, Any>("role" to "tool", "tool_call_id" to "read", "content" to "Tool output")
            )
            for (message in trace) db.agentToolMessageDao().insert(LocalAgentToolMessageEntity(
                sessionId = session.id, runId = "run", role = message["role"] as String,
                payload = encodeAgentToolMessageRow(message), createdAt = "2026-10-07 12:00:00"))
            val before = db.messageDao().listBySession(session.id)
            val loaded = callbacks(db, session).loadMessages(PipelineContext(ChatRequest.forLocal(session.id, "继续")))
            val prepared = prepareChatContext(loaded, "继续", maxTotalChars = 200000)
            assertEquals(trace, prepared.toolCallHistory)
            assertEquals(5, prepared.messages.size)
            assertEquals("【历史消息日期：2026-10-02】\nLater question", prepared.messages.last()["content"])
            assertEquals(before, db.messageDao().listBySession(session.id))
            assertEquals(2, db.agentToolMessageDao().countBySession(session.id))
        } finally { db.close() }
    }
}
