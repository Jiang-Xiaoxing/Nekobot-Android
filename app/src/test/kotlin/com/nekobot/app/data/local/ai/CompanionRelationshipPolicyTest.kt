package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionRelationshipPolicyTest {
    private fun row(id: String, role: String, content: String = id, source: String? = null) = LocalMessageEntity(
        id, "s", role, content, timestamp = "0", createdAt = "2026-10-10T00:00:00Z", source = source)
    @Test fun reassuranceDoesNotEraseAccumulatedDistrust() {
        val losses = List(20) { mapOf("trust" to -8) }
        val before = CompanionRelationshipPolicy.totals(CompanionRelationshipPolicy.initial, losses)
        val after = CompanionRelationshipPolicy.totals(CompanionRelationshipPolicy.initial, losses + mapOf("security" to 4, "affection" to 3))
        assertEquals(-110L, after["trust"])
        assertEquals(before["trust"], after["trust"])
        assertEquals(54L, after["security"])
    }
    @Test fun correctingOneEventRetainsOtherAccumulation() {
        val original = List(20) { mapOf("trust" to -8) }
        val corrected = original.dropLast(1) + mapOf("trust" to 0)
        assertEquals(-102L, CompanionRelationshipPolicy.totals(CompanionRelationshipPolicy.initial, corrected)["trust"])
    }
    @Test fun comfortCannotIncreaseReliabilityTrust() {
        assertThrows(IllegalArgumentException::class.java) { CompanionRelationshipPolicy.validateDelta("comfort", mapOf("trust" to 3)) }
        CompanionRelationshipPolicy.validateDelta("comfort", mapOf("affection" to 3, "trust" to 0))
        CompanionRelationshipPolicy.validateDelta("reliability", mapOf("trust" to 3))
        assertThrows(IllegalArgumentException::class.java) { CompanionRelationshipPolicy.validateDelta("reliability", mapOf("trust" to 6)) }
    }
    @Test fun turnCursorDoesNotDoubleCountMultipleAssistantBubblesOrLoseEarlierPendingTurns() {
        val rows = listOf(row("u1", "user"), row("a1", "assistant"), row("a2", "assistant"),
            row("u2", "user"), row("u3", "user"), row("a3", "assistant"), row("u4", "user"))
        val turns = CompanionRelationshipPolicy.turns(rows)
        assertEquals(2, turns.size)
        assertEquals(listOf("u2", "u3", "a3"), CompanionRelationshipPolicy.pending(turns, "a1").single().rows.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { CompanionRelationshipPolicy.pending(turns, "missing") }
    }
    @Test fun incompleteAndProactiveRepliesDoNotAdvanceTheTurnCounter() {
        val turns = CompanionRelationshipPolicy.turns(listOf(row("u", "user"), row("failed", "assistant", source = "companion_incomplete"), row("wake", "assistant", source = "proactive_chat")))
        assertTrue(turns.isEmpty())
    }
    @Test fun sourceTimeEditsInvalidateEvidence() {
        val original = row("u", "user")
        assertNotEquals(CompanionRelationshipPolicy.hash(original), CompanionRelationshipPolicy.hash(original.copy(createdAt = "2026-10-11T00:00:00Z")))
    }
    @Test fun localCommandsAreNotConversationTurns() {
        val command = row("command", "user", "/goal test")
        val result = row("result", "assistant", "单会话角色不提供目标任务").copy(model = "local-command")
        assertTrue(CompanionRelationshipPolicy.turns(listOf(command, result)).isEmpty())
    }
}
