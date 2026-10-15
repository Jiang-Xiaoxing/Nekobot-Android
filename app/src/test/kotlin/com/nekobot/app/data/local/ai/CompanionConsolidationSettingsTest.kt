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

    @Test fun smallContextScalesChatSharesWithoutLoweringTriggerOrBatch() {
        val b = FusedCompanionPolicy.budgets(7_000)
        assertEquals(5090, b.recent); assertEquals(2000, b.trigger); assertEquals(3000, b.existing); assertEquals(4000, b.batch)
        assertEquals(636, b.pinned); assertEquals(1272, b.recall)
        assertTrue(b.recent + b.pinned + b.recall <= 7_000)
    }

    @Test fun defaultBudgetsFitTheBaselineContext() {
        val b = FusedCompanionPolicy.budgets(14_000)
        assertEquals(CompanionBudgets(8000, 1000, 2000, 2000, 4000, existing = 3000), b)
        assertTrue(b.recent + b.pinned + b.recall <= 14_000)
    }

    @Test fun oversizedUserSettingsStillKeepTheirTrigger() {
        val b = FusedCompanionPolicy.budgets(14_000, CompanionConsolidationSettings(true, 64000, 64000, 64000))
        assertTrue(b.recent + b.pinned + b.recall <= 14_000)
        assertEquals(64000, b.trigger)
        assertEquals(4000, b.batch)
        assertEquals(14000, b.existing)
    }

    @Test fun minimumContextKeepsTriggerAndBoundsEveryInputShare() {
        val b = FusedCompanionPolicy.budgets(512)
        assertEquals(2000, b.trigger)
        assertEquals(512, b.batch)
        assertEquals(512, b.existing)
        assertTrue(b.recent + b.pinned + b.recall <= 512)
    }

    @Test fun largeTriggerDoesNotShrinkChatOrTargetBatch() {
        val ordinary = FusedCompanionPolicy.budgets(7000)
        val later = FusedCompanionPolicy.budgets(7000, CompanionConsolidationSettings(trigger = 64000))
        assertEquals(ordinary.recent, later.recent)
        assertEquals(ordinary.batch, later.batch)
        assertEquals(64000, later.trigger)
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
