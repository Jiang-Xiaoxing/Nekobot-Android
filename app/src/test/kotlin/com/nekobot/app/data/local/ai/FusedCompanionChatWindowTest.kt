package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusedCompanionChatWindowTest {
    @Test fun recentBudgetTakesTheSmallerOfSettingAndContextShare() {
        assertEquals(2_000, FusedCompanionPolicy.chatRecentBudget(28_000, CompanionConsolidationSettings(recent = 2_000)))
        assertEquals(5_600, FusedCompanionPolicy.chatRecentBudget(14_000))
        assertTrue(FusedCompanionPolicy.chatRecentBudget(14_000) < 8_000)
    }

    @Test fun historyKeepsOnlyTheRecentTail() {
        val rows = (1..20).map { message(it, if (it % 2 == 1) "user" else "assistant", "话$it") }
        val budget = FusedCompanionPolicy.tokens(rows.takeLast(2))
        val selected = FusedCompanionPolicy.selectChatHistory(rows, budget) { true }
        assertEquals(listOf("19", "20"), selected.map { it.id })
    }

    @Test fun oldestTurnsDropWhenTheRequestStillDoesNotFit() {
        val rows = (1..6).map { message(it, if (it % 2 == 1) "user" else "assistant", "话") }
        val selected = FusedCompanionPolicy.selectChatHistory(rows, 100_000) { batch -> batch.size <= 2 }
        assertEquals(listOf("5", "6"), selected.map { it.id })
    }

    @Test fun oneOversizedTurnStaysOutOfTheWindow() {
        val rows = listOf(message(1, "user", "长".repeat(1_000)), message(2, "assistant", "答"))
        val selected = FusedCompanionPolicy.selectChatHistory(rows, 100) { true }
        assertTrue(selected.isEmpty())
    }

    private fun message(id: Int, role: String, text: String) = LocalMessageEntity(
        id = "$id", sessionId = "s", role = role, content = text,
        timestamp = "2026-10-13T12:00:00Z", createdAt = "2026-10-13T12:00:00Z")
}
