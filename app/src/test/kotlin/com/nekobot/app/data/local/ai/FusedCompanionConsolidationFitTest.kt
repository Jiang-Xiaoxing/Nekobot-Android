package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FusedCompanionConsolidationFitTest {
    @Test fun batchThatFitsStaysWhole() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(2, 2),
            referenceTurnSizes = listOf(2),
            existingCount = 3,
            fits = { _, _, _ -> true },
            messageFitsAlone = { true })
        assertEquals(4, fit.targetMessages)
        assertEquals(2, fit.referenceMessages)
        assertEquals(3, fit.existingCount)
        assertEquals(0, fit.skipMessages)
        assertFalse(fit.needsLargerModel)
    }

    @Test fun overflowShrinksTheBatchBeforeReference() {
        val seen = mutableListOf<Triple<Int, Int, Int>>()
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(1, 1),
            referenceTurnSizes = listOf(1),
            existingCount = 0,
            fits = { target, reference, existing ->
                seen += Triple(target, reference, existing)
                target <= 1 && reference <= 1
            },
            messageFitsAlone = { true })
        assertEquals(1, fit.targetMessages)
        assertEquals(1, fit.referenceMessages)
        assertEquals(Triple(2, 1, 0), seen.first())
        assertTrue(seen.any { it.first == 1 && it.second == 1 })
    }

    @Test fun singleMessageThatCannotFitIsSkipped() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(1, 1),
            referenceTurnSizes = emptyList(),
            existingCount = 0,
            fits = { _, _, _ -> false },
            messageFitsAlone = { index -> index != 0 })
        assertEquals(1, fit.skipMessages)
        assertEquals(0, fit.targetMessages)
        assertFalse(fit.needsLargerModel)
    }

    @Test fun severalMessagesThatCannotFitAreSkippedTogether() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(2),
            referenceTurnSizes = emptyList(),
            existingCount = 0,
            fits = { _, _, _ -> false },
            messageFitsAlone = { false })
        assertEquals(2, fit.skipMessages)
        assertEquals(0, fit.targetMessages)
    }

    @Test fun messageThatFitsAloneIsKeptAfterOtherPartsShrink() {
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(1),
            referenceTurnSizes = listOf(4),
            existingCount = 6,
            fits = { target, reference, existing -> target == 1 && reference == 0 && existing == 0 },
            messageFitsAlone = { true })
        assertEquals(1, fit.targetMessages)
        assertEquals(0, fit.referenceMessages)
        assertEquals(0, fit.existingCount)
        assertEquals(0, fit.skipMessages)
    }

    @Test fun tokenSizedPayloadShrinksUntilTheLimit() {
        val prompt = 2_500
        val limit = 14_000
        val fit = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(1, 1, 1, 1),
            referenceTurnSizes = listOf(1, 1),
            existingCount = 3,
            fits = { target, reference, existing ->
                prompt + target * 3_000 + reference * 2_000 + existing * 500 <= limit
            },
            messageFitsAlone = { index -> prompt + 3_000 <= limit || index > 0 })
        assertEquals(0, fit.skipMessages)
        assertTrue(prompt + fit.targetMessages * 3_000 + fit.referenceMessages * 2_000 + fit.existingCount * 500 <= limit)
        assertTrue(fit.targetMessages >= 1)
    }

    @Test fun skippedMessageIsNotRetriedWithTheRestOfItsTurn() {
        val huge = row(1, "user", "长")
        val reply = row(2, "assistant", "答")
        val first = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = listOf(2),
            referenceTurnSizes = emptyList(),
            existingCount = 0,
            fits = { _, _, _ -> false },
            messageFitsAlone = { index -> index != 0 })
        assertEquals(1, first.skipMessages)
        val pending = FusedCompanionPolicy.pendingConsolidationTurns(listOf(listOf(huge, reply)), setOf(reply.id))
        assertEquals(listOf(listOf(reply)), pending)
        val second = FusedCompanionPolicy.fitConsolidationPayload(
            targetTurnSizes = pending.map { it.size },
            referenceTurnSizes = emptyList(),
            existingCount = 0,
            fits = { target, _, _ -> target == 1 },
            messageFitsAlone = { true })
        assertEquals(1, second.targetMessages)
        assertEquals(0, second.skipMessages)
        assertFalse(second.needsLargerModel)
    }

    private fun row(id: Int, role: String, text: String) = LocalMessageEntity(
        id = "$id", sessionId = "s", role = role, content = text,
        timestamp = "2026-10-13T12:00:00Z", createdAt = "2026-10-13T12:00:00Z")
}
