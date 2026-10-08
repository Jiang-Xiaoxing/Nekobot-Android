package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmbeddingCacheDimensionAndroidTest {
    @Test fun persistedModelDimensionChangeReindexesFactsAndArchivesTogether() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        try {
            val session = LocalSessionEntity("embedding-s", "Synthetic", characterId = "embedding-c",
                sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true,
                createdAt = "now", updatedAt = "now")
            db.characterDao().upsert(LocalCharacterEntity("embedding-c", "Synthetic", createdAt = "now", updatedAt = "now"))
            db.sessionDao().upsert(session)
            val scope = CompanionMemoryStore(db).ensureScope(session)
            val model = LocalAiModelEntity("embedding-model", "Synthetic", protocol = "openai_chat",
                apiKey = "fixture-key", baseUrl = "https://api.example.com/v1", model = "Qwen/Qwen3-Embedding-8B",
                purpose = "embedding", active = true, dimensions = 4096, createdAt = "now")
            db.aiModelDao().upsert(model)
            val calls = mutableListOf<Pair<Int, List<String>>>()
            val search = CompanionHybridSearch(db) {
                val active = db.aiModelDao().getActiveByPurpose("embedding")!!
                CompanionEmbeddingBackend(embeddingCacheIdentity(active)) { inputs ->
                    calls += active.dimensions to inputs
                    inputs.map { FloatArray(active.dimensions) { if (it == 0) 1f else 0f } }
                }
            }
            val items = listOf(CompanionSearchItem("fact", "f", "用户怕辣"), CompanionSearchItem("archive", "a", "旧经历"))
            assertEquals(2, search.scores(scope, "口味", items, true).size)
            assertEquals(2, calls.size)
            val oldKey = embeddingCacheIdentity(model)
            db.aiModelDao().upsert(model.copy(dimensions = 1280)); calls.clear()
            assertEquals(2, search.scores(scope, "口味", items, true).size)
            assertEquals(listOf(1280, 1280), calls.map { it.first })
            assertTrue(db.companionMemoryDao().vectors(scope.id, oldKey).isEmpty())
            calls.clear(); assertEquals(2, search.scores(scope, "口味", items, true).size)
            assertEquals(1, calls.size)
        } finally { db.close() }
    }
}
