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
    fun recallPillStaysUntilFirstBodyText() {
        // 回忆开始：显示
        assertTrue(CompanionRecallActivity.pillVisible(latched = false, active = true, contentBlank = true))
        // 回忆结束但正文未到：继续显示，不留空档
        assertTrue(CompanionRecallActivity.pillVisible(latched = true, active = false, contentBlank = true))
        // 正文出现：隐藏（界面淡出）
        assertFalse(CompanionRecallActivity.pillVisible(latched = true, active = false, contentBlank = false))
        // 本轮未回忆：不显示
        assertFalse(CompanionRecallActivity.pillVisible(latched = false, active = false, contentBlank = true))
    }
}