package com.nekobot.app.ui.screens.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSummaryVectorSwitchTest {
    @Test fun shownOffIsSavedOffWhenVectorIsNotReady() {
        assertFalse(effectiveSummaryVector(requested = true, vectorReady = false))
    }

    @Test fun readySwitchSavesTheRequestedValue() {
        assertTrue(effectiveSummaryVector(requested = true, vectorReady = true))
        assertFalse(effectiveSummaryVector(requested = false, vectorReady = true))
        assertFalse(effectiveSummaryVector(requested = false, vectorReady = false))
    }
}
