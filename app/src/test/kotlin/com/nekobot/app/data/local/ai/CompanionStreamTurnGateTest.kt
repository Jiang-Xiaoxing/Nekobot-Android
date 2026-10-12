package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionStreamTurnGateTest {

    @Test
    fun companionTurnEmitsStreamStartOnlyOnceAcrossToolRounds() {
        val metadata = mutableMapOf<String, Any>()
        assertTrue(CompanionStreamTurnGate.shouldEmitStart(true, metadata))
        assertFalse(CompanionStreamTurnGate.shouldEmitStart(true, metadata))
        assertFalse(CompanionStreamTurnGate.shouldEmitStart(true, metadata))
    }

    @Test
    fun nonCompanionSessionsKeepEmittingEveryRound() {
        val metadata = mutableMapOf<String, Any>()
        repeat(3) { assertTrue(CompanionStreamTurnGate.shouldEmitStart(false, metadata)) }
        assertFalse(metadata.containsKey(CompanionStreamTurnGate.METADATA_KEY))
    }

    @Test
    fun newTurnContextStartsFresh() {
        assertTrue(CompanionStreamTurnGate.shouldEmitStart(true, mutableMapOf()))
        assertTrue(CompanionStreamTurnGate.shouldEmitStart(true, mutableMapOf()))
    }

    @Test
    fun recallPillHoldsForMinimumDuration() {
        assertEquals(1200L, CompanionRecallActivity.remainingHoldMs(1000L, 1000L))
        assertEquals(900L, CompanionRecallActivity.remainingHoldMs(1000L, 1300L))
        assertEquals(0L, CompanionRecallActivity.remainingHoldMs(1000L, 5000L))
        assertEquals(1200L, CompanionRecallActivity.remainingHoldMs(5000L, 1000L))
    }
}
