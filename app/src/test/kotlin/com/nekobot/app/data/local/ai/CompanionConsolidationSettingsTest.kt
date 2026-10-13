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
        assertEquals(2800, b.recent); assertEquals(700, b.trigger); assertEquals(1050, b.existing); assertEquals(1400, b.batch)
        assertEquals(350, b.pinned); assertEquals(700, b.recall)
        assertTrue(b.recent + b.pinned + b.recall + b.trigger + b.batch + b.existing <= 7_000)
    }

    @Test fun defaultBudgetsFitTheBaselineContext() {
        val b = FusedCompanionPolicy.budgets(14_000)
        assertEquals(CompanionBudgets(5600, 700, 1400, 1400, 2800, existing = 2100), b)
        assertEquals(14_000, b.recent + b.pinned + b.recall + b.trigger + b.batch + b.existing)
    }

    @Test fun oversizedUserSettingsShrinkTogether() {
        val b = FusedCompanionPolicy.budgets(14_000, CompanionConsolidationSettings(true, 8000, 2000, 3000))
        val sum = b.recent + b.pinned + b.recall + b.trigger + b.batch + b.existing
        assertTrue(sum <= 14_000)
        assertEquals(b.recent * 2_000, b.trigger * 8_000)
        assertEquals(b.recent * 4_000, b.batch * 8_000)
        assertEquals(b.recent * 3_000, b.existing * 8_000)
    }

    @Test fun invalidValuesRejected() {
        assertFalse(CompanionConsolidationSettings(recent = 500).valid())
        assertFalse(CompanionConsolidationSettings(trigger = 100).valid())
        assertFalse(CompanionConsolidationSettings(existing = 70000).valid())
    }

    @Test fun hybridAveragesVectorAndDoesNotPenalizeMissingVector() {
        assertEquals(0.75f, CompanionConsolidationScoring.hybrid(1f, .5f), 1e-6f)
        assertEquals(1f, CompanionConsolidationScoring.hybrid(1f, null), 1e-6f)
        assertEquals(1f, CompanionConsolidationScoring.hybrid(1f, 1f), 1e-6f)
        assertEquals(0.4f, CompanionConsolidationScoring.hybrid(.4f, null), 1e-6f)
        assertEquals(0.4f, CompanionConsolidationScoring.hybrid(.4f, .4f), 1e-6f)
        val weakerKeywordWithVector = CompanionConsolidationScoring.hybrid(.4f, .9f)
        val strongerKeywordWithoutVector = CompanionConsolidationScoring.hybrid(.8f, null)
        assertTrue(strongerKeywordWithoutVector > weakerKeywordWithVector)
        assertEquals(1.4f, CompanionConsolidationScoring.hybrid(.4f, null, dateBonus = 1f), 1e-6f)
        assertEquals(1.4f, CompanionConsolidationScoring.hybrid(.4f, .4f, dateBonus = 1f), 1e-6f)
    }

    @Test fun settingsFallBackToDefaultWithoutPrefs() {
        assertEquals(CompanionConsolidationSettings(), consolidationSettingsFor("p", "s"))
    }
}