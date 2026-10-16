package com.nekobot.app.data.local.ai

import com.nekobot.app.ui.screens.memory.recallQuotaExplanation
import kotlinx.coroutines.runBlocking
import com.nekobot.app.testString
import org.junit.Assert.*
import org.junit.Test

class CompanionRecallPerCallTest {
    private var saved: String? = null
    private fun memories(n: Int) = mapOf<String, Any>("success" to true,
        "memories" to List(n) { i -> mapOf("id" to "f$i", "content" to "很长的一条记忆正文".repeat(40)) })

    @Test fun defaultPerCallIs1500() {
        assertEquals(1500, CompanionRecallSettings().perCall)
        assertEquals(1500, CompanionRecallSettings().effectivePerCall)
    }

    @Test fun perCallNeverExceedsTotalAndLegacyZeroUsesDefault() {
        assertEquals(800, CompanionRecallSettings(3, 800, 0).effectivePerCall)
        assertEquals(1500, CompanionRecallSettings(3, 4000, 0).effectivePerCall)
        assertTrue(CompanionRecallSettings(3, 4000, 0).valid())
        assertEquals(1000, CompanionRecallSettings(3, 1000, 2000).effectivePerCall)
        assertFalse(CompanionRecallSettings(3, 1000, 64).valid())
        assertTrue(CompanionRecallSettings(3, 1000, 1000).valid())
    }

    @Test fun budgetUsesConfiguredPerCall() = runBlocking {
        val g = CompanionRecallBudget({ CompanionRecallSettings(3, 4000, 600) }, { saved }, { saved = it })
        g.prepare("main", emptyList(), buildAgentRecallToolDefinitions(), 64000, 4000)
        val result = g.read("main") { memories(40) }
        assertEquals("truncated", result["status"])
        assertTrue((result["tokens_left"] as Int) >= 4000 - 600)
    }

    @Test fun popoverFollowsPerCallSetting() {
        assertEquals("单次 600 ≈ 约 3 条普通记忆", recallQuotaExplanation(3, 4000, 600, text = ::testString)[2])
        assertEquals("单次 1500 ≈ 七八条普通记忆", recallQuotaExplanation(3, 4000, text = ::testString)[2])
    }
}