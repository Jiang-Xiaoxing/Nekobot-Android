package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LongConversationWindowTest {
    private fun msg(id: String, role: String, content: String = id) = LocalMessageEntity(
        id = id, sessionId = "one", role = role, content = content,
        timestamp = "same-time", createdAt = "same-time"
    )

    @Test fun keepsOnlyCompletePairsAtTheEnd() {
        val rows = listOf(msg("u1", "user"), msg("a1", "assistant"),
            msg("u2", "user"), msg("a2", "assistant"),
            msg("u3", "user"), msg("a3", "assistant"))
        val split = splitLongConversationHistory(rows, maxPairs = 2, maxRecentTokens = 1000)
        assertEquals(listOf("u1", "a1"), split.toSummarize.map { it.id })
        assertEquals(listOf("u2", "a2", "u3", "a3"), split.recent.map { it.id })
        assertEquals("a1", split.boundaryId)
        assertEquals("a3", split.recentEndId)
    }

    @Test fun aLargeLastPairFallsBackToSummaryWithoutSplittingIt() {
        val rows = listOf(msg("u", "user", "x".repeat(1000)), msg("a", "assistant"))
        val split = splitLongConversationHistory(rows, maxRecentTokens = 30)
        assertEquals(rows.map { it.id }, split.toSummarize.map { it.id })
        assertEquals(emptyList<String>(), split.recent.map { it.id })
        assertNull(split.recentEndId)
    }

    @Test fun doesNotTreatAnUnfinishedReplyAsACompletePair() {
        val rows = listOf(msg("u1", "user"), msg("a1", "assistant"), msg("u2", "user"))
        val split = splitLongConversationHistory(rows, maxRecentTokens = 1000)
        assertEquals(rows.map { it.id }, split.toSummarize.map { it.id })
        assertEquals(emptyList<String>(), split.recent.map { it.id })
    }

    @Test fun anchorFallsBackToOldestScannedRowWhenTailAlreadyFitsBudget() {
        val rows = listOf(msg("u1", "user"), msg("a1", "assistant"),
            msg("u2", "user"), msg("a2", "assistant"))
        val split = splitLongConversationHistory(rows, maxRecentTokens = 1000)
        assertEquals(emptyList<String>(), split.toSummarize.map { it.id })
        assertEquals(listOf("u1", "a1", "u2", "a2"), split.recent.map { it.id })
        assertEquals("u1", resolveUnindexedAnchorId(split, rows))
    }

    @Test fun anchorUsesLastSummarizedRowWhenTheTailIsSplit() {
        val rows = listOf(msg("u1", "user"), msg("a1", "assistant"),
            msg("u2", "user"), msg("a2", "assistant"))
        val split = splitLongConversationHistory(rows, maxPairs = 1, maxRecentTokens = 1000)
        assertEquals(listOf("u1", "a1"), split.toSummarize.map { it.id })
        assertEquals("a1", resolveUnindexedAnchorId(split, rows))
    }

    @Test fun anchorFallsBackToScannedRowsWhenNoEligibleTurnExists() {
        val rows = listOf(msg("s1", "system"), msg("u1", "user"))
        val split = splitLongConversationHistory(emptyList(), maxRecentTokens = 1000)
        assertEquals("s1", resolveUnindexedAnchorId(split, rows))
        assertNull(resolveUnindexedAnchorId(split, emptyList()))
    }
}
