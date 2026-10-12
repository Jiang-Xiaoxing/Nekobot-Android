package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class CompanionConsolidationSettingsTest {

    @Test fun defaultsMatchApprovedDesign() {
        val d = CompanionConsolidationSettings()
        assertTrue(d.vectorEnabled)
        assertEquals(8000, d.recent); assertEquals(2000, d.trigger); assertEquals(3000, d.existing)
        assertTrue(d.valid())
    }

    @Test fun largeContextUsesUserValuesAndKeepsBatchCap() {
        val b = FusedCompanionPolicy.budgets(28_000, CompanionConsolidationSettings(true, 12000, 3000, 5000))
        assertEquals(12000, b.recent); assertEquals(3000, b.trigger); assertEquals(5000, b.existing)
        assertEquals(4000, b.batch); assertEquals(2000, b.recall)
    }

    @Test fun smallContextScalesProportionally() {
        val b = FusedCompanionPolicy.budgets(7_000)
        assertEquals(4000, b.recent); assertEquals(1000, b.trigger); assertEquals(1500, b.existing); assertEquals(2000, b.batch)
    }

    @Test fun defaultBudgetsUnchangedExceptExisting() {
        val b = FusedCompanionPolicy.budgets(14_000)
        assertEquals(CompanionBudgets(8000, 1000, 2000, 2000, 4000, existing = 3000), b)
    }

    @Test fun invalidValuesRejected() {
        assertFalse(CompanionConsolidationSettings(recent = 500).valid())
        assertFalse(CompanionConsolidationSettings(trigger = 100).valid())
        assertFalse(CompanionConsolidationSettings(existing = 70000).valid())
    }

    @Test fun hybridAddsVectorAndFallsBackToKeyword() {
        assertEquals(1.5f, CompanionConsolidationScoring.hybrid(1f, .5f), 1e-6f)
        assertEquals(1f, CompanionConsolidationScoring.hybrid(1f, null), 1e-6f)
    }

    @Test fun settingsFallBackToDefaultWithoutPrefs() {
        assertEquals(CompanionConsolidationSettings(), consolidationSettingsFor("p", "s"))
    }
}