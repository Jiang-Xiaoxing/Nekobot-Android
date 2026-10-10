package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionCoreMemoryTest {
    private fun memory(id: String = "m") = CompanionMemoryEntity(
        id, "scope", id, 1, "core", "用户", "叫我小舟", "active",
        createdAt = "2026-10-10T00:00:00Z", updatedAt = "2026-10-10T00:00:00Z")

    @Test fun explicitExclusionSurvivesModelCoreClassification() {
        assertTrue(CompanionCoreMemory.contains(memory()))
        assertFalse(CompanionCoreMemory.contains(memory().copy(coreExcluded = true)))
        assertTrue(CompanionCoreMemory.contains(memory().copy(kind = "fact", alwaysProvide = true)))
    }

    @Test fun expiredInvalidHistoricalAndCancelledEntriesAreNotCore() {
        listOf(memory().copy(valid = false), memory().copy(current = false),
            memory().copy(status = "cancelled"), memory().copy(status = "conflict"),
            memory().copy(expiresAt = "2020-01-01T00:00:00Z")).forEach {
            assertFalse(CompanionCoreMemory.contains(it))
        }
    }

    @Test fun legacyOversizeCoreRemainsCompleteInsteadOfSilentlyTruncated() {
        val saved = (1..30).map { memory("m$it").copy(content = "重要关系资料".repeat(30)) }
        val rows = CompanionCoreMemory.rows(saved)
        assertTrue(CompanionCoreMemory.tokens(rows) > CompanionCoreMemory.maintenanceTokens)
        assertEquals(saved.size, JsonParser.parseString(CompanionCoreMemory.render(rows)).asJsonArray.size())
    }
}
