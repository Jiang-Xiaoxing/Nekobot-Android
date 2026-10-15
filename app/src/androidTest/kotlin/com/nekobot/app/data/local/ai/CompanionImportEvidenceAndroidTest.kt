package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CompanionImportEvidenceAndroidTest {
    @Test fun unverifiedTextIsIndependentLabeledAndExpiryRetiresCachedContext() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java).build()
        try {
            val session = LocalSessionEntity("s", "角色", characterId = "c", sessionMode = "agent",
                inheritCharacter = true, longConversationEnabled = true, companionMode = true, createdAt = "now", updatedAt = "now")
            db.sessionDao().upsert(session)
            val store = CompanionMemoryStore(db)
            val scope = store.ensureScope(session)
            val memory = CompanionMemoryEntity("m", scope.id, "diet", 1, "fact", "玩家", "独立保存的饮食偏好", "active",
                alwaysProvide = true, provenance = CompanionImportPolicy.IMPORTED_UNVERIFIED, createdAt = "old", updatedAt = "old")
            db.companionMemoryDao().saveMemory(memory)
            assertEquals(listOf(memory), store.validatedMemories(scope))
            assertTrue(db.companionMemoryDao().sources("m").isEmpty())
            val first = LocalMessageEntity("u1", "s", "user", "你好", timestamp = "2026-10-09T00:00:00Z", createdAt = "2026-10-09T00:00:00Z")
            db.messageDao().upsert(first)
            val context = FusedCompanionContext(db)
            val initial = context.build(session, "u1", emptyList(), "test", emptyList(), mapOf("content" to "你好"), "", 24000, 1000)
            assertTrue(initial.toString().contains("原话来源未验证"))
            // 有效期经过无需原话编辑或 revision 变化；旧辅助缓存也不能继续携带旧正文。
            db.companionMemoryDao().saveMemory(memory.copy(expiresAt = Instant.now().minusSeconds(1).toString()))
            val second = first.copy(id = "u2", createdAt = "2026-10-09T01:00:00Z")
            db.messageDao().upsert(second)
            val rebuilt = context.build(session, "u2", listOf(first), "test", emptyList(), mapOf("content" to "继续"), "", 24000, 1000)
            assertFalse(rebuilt.toString().contains(memory.content))
            assertTrue(store.validatedMemories(scope).isEmpty())
            assertNotNull(db.companionMemoryDao().versions(scope.id, "diet").single().expiresAt)
        } finally { db.close() }
    }
}
