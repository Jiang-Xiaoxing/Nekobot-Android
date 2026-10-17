package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.CompanionRelationshipEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import org.junit.Assert.*
import org.junit.Test

class CompanionHistoryScopeTest {
    private val initial = CompanionRelationshipPolicy.initial
    private val old = CompanionRelationshipEntity("s", "c", cursorId = "a450", legacy = true,
        baselineJson = Gson().toJson(initial), historyScopeVersion = 0)
    private fun review(state: CompanionRelationshipEntity = old, events: Boolean = false, snapshots: Boolean = false) =
        CompanionRelationshipPolicy.needsHistoryReview(state, events, snapshots, initial)

    @Test fun ambiguousInitialLegacyRequiresChoiceRatherThanSilentlyIgnoringOldHistory() {
        assertTrue(review())
        // 识别不会更改旧游标，也不把疑似缺陷自动当作已获补评授权。
        assertEquals("a450", old.cursorId)
    }

    @Test fun explicitDecisionIsNeverReopened() {
        assertFalse(review(old.copy(historyScopeVersion = 1)))
        assertFalse(review(old.copy(legacy = false, cursorId = null)))
    }

    @Test fun actualAssessmentsOrDifferentAccumulationAreNotRewound() {
        assertFalse(review(events = true))
        assertFalse(review(snapshots = true))
        assertFalse(review(old.copy(updatedAt = 100)))
        assertFalse(review(old.copy(baselineJson = Gson().toJson(initial + ("trust" to 65)))))
    }

    @Test fun fullHistoryAndNewTurnsHaveDistinctCounts() {
        val rows = (1..453).flatMap { n -> listOf(
            LocalMessageEntity(id = "u$n", sessionId = "s", role = "user", content = "问题$n", timestamp = n.toString(), createdAt = n.toString()),
            LocalMessageEntity(id = "a$n", sessionId = "s", role = "assistant", content = "回复$n", timestamp = n.toString(), createdAt = n.toString())
        ) }
        val turns = CompanionRelationshipPolicy.turns(rows)
        assertEquals(3, CompanionRelationshipPolicy.pending(turns, "a450").size)
        assertEquals(453, CompanionRelationshipPolicy.pending(turns, null).size)
    }
}
