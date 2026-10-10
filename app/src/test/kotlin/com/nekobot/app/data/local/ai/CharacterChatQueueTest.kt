package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import org.junit.Assert.*
import org.junit.Test

class CharacterChatQueueTest {
    private fun model(id: String) = LocalAiModelEntity(id, id, "openai_chat", apiKey = "",
        baseUrl = "https://example.invalid", model = id, createdAt = "2026-10-10")

    @Test fun unsetQueueInheritsWithoutChoosingAnyModels() {
        assertNull(CharacterChatQueue.resolve(null, listOf(model("global"))))
        assertNull(CharacterChatQueue.encode(null))
    }

    @Test fun eachCharacterOrderIsIndependentOfGlobalPriority() {
        val models = listOf(model("a").copy(priority = 0), model("b").copy(priority = 5), model("c").copy(priority = 2))
        val a = CharacterChatQueue.encode(listOf("b", "a"))
        val b = CharacterChatQueue.encode(listOf("c", "b"))
        assertEquals(listOf("b", "a"), CharacterChatQueue.resolve(a, models)!!.map { it.id })
        assertEquals(listOf("c", "b"), CharacterChatQueue.resolve(b, models.reversed())!!.map { it.id })
        assertEquals(listOf(0, 5, 2), models.map { it.priority })
    }

    @Test fun unavailableReferencesAreSkippedWithoutExpandingTheQueue() {
        val models = listOf(model("disabled").copy(enabled = false), model("vector").copy(purpose = "embedding"),
            model("backup"), model("global"))
        assertEquals(listOf("backup"), CharacterChatQueue.resolve(
            "[\"deleted\",\"disabled\",\"vector\",\"backup\"]", models)!!.map { it.id })
    }

    @Test fun invalidExplicitQueueNeverMeansInherit() {
        for (raw in listOf("", "null", "[]", "{}", "[1]", "[\"\"]", "[\"a\",\"a\"]")) {
            assertThrows(IllegalStateException::class.java) { CharacterChatQueue.ids(raw) }
        }
    }

    @Test fun allUnavailableReferencesFailEvenWhenGlobalModelWorks() {
        assertThrows(IllegalArgumentException::class.java) {
            CharacterChatQueue.resolve("[\"gone\"]", listOf(model("global")))
        }
    }

    @Test fun backupUsesItsOwnSamplingAndOutputParameters() {
        val tool = listOf(mapOf("type" to "function"))
        val extra = mapOf("temperature" to 1f, "max_tokens" to 5000, "top_p" to 0.9f,
            "tools" to tool, "reasoning_effort" to "low")
        val backup = model("backup").copy(temperature = 0.3f, maxTokens = 1000)
        val resolved = CharacterChatQueue.extraFor(backup, extra)
        assertEquals(0.3f, resolved["temperature"])
        assertEquals(1000, resolved["max_tokens"])
        assertFalse(resolved.containsKey("top_p"))
        assertEquals(tool, resolved["tools"])
        assertEquals("low", resolved["reasoning_effort"])
    }

    @Test fun providerFallbackShowsOnlyHostAndNeverUrlCredentialsOrQuery() {
        val configured = model("a").copy(baseUrl = "https://user:sample@example.invalid/v1?token=sample")
        assertEquals("example.invalid", CharacterChatQueue.providerLabel(configured))
        assertEquals("服务商A", CharacterChatQueue.providerLabel(configured.copy(provider = "服务商A")))
    }
}
