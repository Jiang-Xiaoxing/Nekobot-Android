package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 只核对路由，不调用网络；选择失效时不能把角色资料发给其他服务商。 */
class CompanionEmbeddingBindingTest {
    @Test fun explicitModelSurvivesGlobalSwitchAndMissingModelDoesNotFallback() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java).build()
        try {
            val chosen = LocalAiModelEntity("chosen", "角色专用", protocol = "openai_chat", apiKey = "", baseUrl = "https://example.invalid",
                model = "chosen-vector", purpose = "embedding", createdAt = "old")
            val global = chosen.copy(id = "global", name = "全局", model = "different-vector", active = true)
            db.aiModelDao().upsert(chosen)
            db.aiModelDao().upsert(global)
            val scope = CompanionScopeEntity("scope", db.dbName, "s", "c", 0, updatedAt = "old", embeddingModelId = chosen.id)
            assertEquals(embeddingCacheIdentity(chosen), CompanionHybridSearch.configuredBackend(db, scope)?.modelKey)
            assertNull(CompanionHybridSearch.configuredBackend(db, scope.copy(embeddingModelId = "missing")))
            assertNull(CompanionHybridSearch.configuredBackend(db, scope.copy(embeddingModelId = "")))
            db.aiModelDao().upsert(chosen.copy(enabled = false))
            assertNull(CompanionHybridSearch.configuredBackend(db, scope))
        } finally { db.close() }
    }
}
