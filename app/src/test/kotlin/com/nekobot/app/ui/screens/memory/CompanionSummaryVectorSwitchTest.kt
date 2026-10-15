package com.nekobot.app.ui.screens.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSummaryVectorSwitchTest {
    @Test fun unavailableVectorKeepsCheckedAndSavedPreference() {
        val state = summaryVectorSwitchState(requested = true, vectorReady = false)
        assertTrue(state.checked)
        assertFalse(state.enabled)
        assertTrue(state.settings(8000, 2000, 3000).vectorEnabled)
    }

    @Test fun readySwitchSavesTheRequestedValue() {
        assertTrue(summaryVectorSwitchState(requested = true, vectorReady = true).settings(8000, 2000, 3000).vectorEnabled)
        assertFalse(summaryVectorSwitchState(requested = false, vectorReady = true).settings(8000, 2000, 3000).vectorEnabled)
        assertFalse(summaryVectorSwitchState(requested = false, vectorReady = false).settings(8000, 2000, 3000).vectorEnabled)
    }

    @Test fun editingLimitsWhileUnavailableDoesNotTurnSemanticPreferenceOff() {
        val settings = summaryVectorSwitchState(true, false).settings(12000, 4000, 5000)
        assertTrue(settings.vectorEnabled)
        assertEquals(12000, settings.recent)
        assertEquals(4000, settings.trigger)
        assertEquals(5000, settings.existing)
    }

    @Test fun readinessReturningKeepsPreferenceInsteadOfResettingIt() {
        val saved = summaryVectorSwitchState(true, false).settings(8000, 2000, 3000)
        val restored = summaryVectorSwitchState(saved.vectorEnabled, true)
        assertTrue(restored.checked)
        assertTrue(restored.enabled)
    }

    @Test fun unavailableVectorKeepsAnExplicitOffPreference() {
        val state = summaryVectorSwitchState(false, false)
        assertFalse(state.checked)
        assertFalse(state.enabled)
        assertFalse(state.settings(8000, 2000, 3000).vectorEnabled)
    }

    @Test fun readySwitchCanSaveUserTurningItOff() {
        val state = summaryVectorSwitchState(false, true)
        assertFalse(state.checked)
        assertTrue(state.enabled)
        assertFalse(state.settings(8000, 2000, 3000).vectorEnabled)
    }
}
