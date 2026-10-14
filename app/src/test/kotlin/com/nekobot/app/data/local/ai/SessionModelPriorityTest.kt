package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalFailoverHealthEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionModelPriorityTest {
    private fun model(id: String, priority: Int) = LocalAiModelEntity(
        id = id, name = id, model = id, protocol = "openai_chat",
        apiKey = "", baseUrl = "https://example.com", priority = priority, createdAt = "2026-10-14"
    )

    private val models = listOf(model("a", 0), model("b", 5), model("c", 9))

    @Test
    fun promotesSelectedModelAndRenumbersWithoutChangingGlobalModels() {
        val queue = applySessionModelOrder(models, listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), queue.map { it.id })
        assertEquals(listOf(0, 1, 2), queue.map { it.priority })
        assertEquals(listOf(0, 5, 9), models.map { it.priority })
    }

    @Test
    fun repeatedSwitchKeepsRelativeOrderOfRemainingModels() {
        val first = applySessionModelOrder(models, listOf("c", "a", "b"))
        val nextOrder = listOf("b") + first.map { it.id }.filter { it != "b" }
        assertEquals(listOf("b", "c", "a"), applySessionModelOrder(models, nextOrder).map { it.id })
    }

    @Test
    fun removedModelsAreSkippedAndNewModelsAreAppended() {
        val queue = applySessionModelOrder(models, listOf("deleted", "c", "c", "a"))
        assertEquals(listOf("c", "a", "b"), queue.map { it.id })
        assertEquals(listOf(0, 1, 2), queue.map { it.priority })
    }

    @Test
    fun defaultAndFullyStaleOverridesPreserveGlobalOrder() {
        assertEquals(models, applySessionModelOrder(models, emptyList()))
        assertEquals(models, applySessionModelOrder(models, listOf("deleted")))
        assertEquals(emptyList<LocalAiModelEntity>(), applySessionModelOrder(emptyList(), listOf("c")))
    }

    @Test
    fun selectedP0AndRemainingOrderReachActualFailoverCoordinator() = runBlocking {
        val store = object : FailoverHealthStore {
            private val values = mutableMapOf<String, LocalFailoverHealthEntity>()
            override suspend fun get(modelId: String) = values[modelId]
            override suspend fun upsert(entity: LocalFailoverHealthEntity) { values[entity.modelId] = entity }
            override suspend fun listAll() = values.values.toList()
            override suspend fun delete(modelId: String) { values.remove(modelId) }
            override suspend fun clear() { values.clear() }
        }
        val usage = object : FailoverUsageReader {
            override suspend fun getUsage(modelId: String) = FailoverUsage()
        }
        val queue = applySessionModelOrder(models, listOf("c", "a", "b"))
        val result = FailoverCoordinator(store, usage).execute(queue, "chat") {
            if (it.id != "b") throw FailoverHttpException(503, "unavailable")
            it.id
        }
        assertEquals(listOf("c", "a", "b"), result.attempts)
        assertEquals("b", result.model.id)
    }
}
