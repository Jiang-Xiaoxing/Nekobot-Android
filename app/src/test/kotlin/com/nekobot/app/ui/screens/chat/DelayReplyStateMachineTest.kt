package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayReplyStateMachineTest {

    @Test
    fun `new messages append to one batch and restart the full delay`() {
        val state = DelayReplyStateMachine()
        val first = state.arm(message("A"), nowMs = 0L, delayMs = 10_000L)
        val second = state.arm(message("B"), nowMs = 4_000L, delayMs = 10_000L)

        assertTrue(state.pendingState.value)
        assertEquals(10_000L, first.delayMs)
        assertEquals(10_000L, second.delayMs)
        assertEquals(DelayReplyTimerResult.Stale, state.onTimerFired(first.generation, 10_000L))

        val ready = state.onTimerFired(second.generation, 14_000L) as DelayReplyTimerResult.Ready
        assertEquals(listOf("A", "B"), ready.messages.map { it.content })
        assertFalse(state.hasPending())
        assertFalse(state.pendingState.value)
    }

    @Test
    fun `draft text latches the pending batch indefinitely`() {
        val state = DelayReplyStateMachine()
        val timer = state.arm(message("A"), nowMs = 0L, delayMs = 10_000L)

        val latched = state.latchForDraftText(nowMs = 7_000L)

        assertNull(latched?.delayMs)
        assertTrue(state.isPaused())
        assertEquals(3_000L, state.remainingMs(100_000L))
        assertNull(state.currentPlan(100_000L)?.delayMs)
        assertEquals(DelayReplyTimerResult.Stale, state.onTimerFired(timer.generation, 10_000L))
        assertEquals(DelayReplyTimerResult.Stale, state.onTimerFired(latched!!.generation, 100_000L))
    }

    @Test
    fun `sending another message releases the latch and restarts the full delay`() {
        val state = DelayReplyStateMachine()
        state.arm(message("A"), nowMs = 0L, delayMs = 10_000L)
        val latched = state.latchForDraftText(nowMs = 1_000L)!!

        val restarted = state.arm(message("B"), nowMs = 50_000L, delayMs = 10_000L)

        assertEquals(10_000L, restarted.delayMs)
        assertFalse(state.isPaused())
        assertEquals(DelayReplyTimerResult.Stale, state.onTimerFired(latched.generation, 50_000L))
        val ready = state.onTimerFired(restarted.generation, 60_000L) as DelayReplyTimerResult.Ready
        assertEquals(listOf("A", "B"), ready.messages.map { it.content })
    }

    @Test
    fun `draft text without a pending batch does not affect the next batch`() {
        val state = DelayReplyStateMachine()

        assertNull(state.latchForDraftText(nowMs = 0L))
        val plan = state.arm(message("A"), nowMs = 100L, delayMs = 10_000L)

        assertEquals(10_000L, plan.delayMs)
        assertFalse(state.isPaused())
    }

    @Test
    fun `cancel clears the latch and the next batch counts normally`() {
        val state = DelayReplyStateMachine()
        val old = state.arm(message("A"), nowMs = 0L, delayMs = 10_000L)
        state.latchForDraftText(nowMs = 1_000L)

        assertEquals(listOf("A"), state.cancel().map { it.content })
        assertEquals(DelayReplyTimerResult.Stale, state.onTimerFired(old.generation, 10_000L))

        val next = state.arm(message("B"), nowMs = 20_000L, delayMs = 10_000L)
        assertEquals(10_000L, next.delayMs)
        assertFalse(state.isPaused())
    }

    @Test
    fun `pending delay counts as retained session work`() {
        val session = ChatSessionState("session")
        session.delayReply.arm(message("A"), nowMs = 0L, delayMs = 10_000L)

        assertFalse(session.hasActiveGeneration())
        assertTrue(session.hasActiveJobs())
        assertTrue(session.hasRetainedWork())

        session.delayReply.cancel()
        assertFalse(session.hasActiveGeneration())
        assertFalse(session.hasActiveJobs())
        assertFalse(session.hasRetainedWork())
    }

    @Test
    fun `pending batch can be rearmed after a session switch cancels its timer`() {
        val state = DelayReplyStateMachine()
        val initial = state.arm(message("A"), nowMs = 0L, delayMs = 10_000L)

        // 切换会话只取消外层 Job，不调用 onTimerFired，批次必须继续保留。
        val resumed = state.currentPlan(nowMs = 12_000L)!!

        assertTrue(state.hasPending())
        assertEquals(initial.generation, resumed.generation)
        assertEquals(1L, resumed.delayMs)
        val ready = state.onTimerFired(resumed.generation, nowMs = 12_001L)
            as DelayReplyTimerResult.Ready
        assertEquals(listOf("A"), ready.messages.map { it.content })
    }

    @Test
    fun `request keeps message order and combines all attachments`() {
        val first = message("A").copy(
            attachments = listOf(mapOf("name" to "a.png"))
        )
        val second = message("B").copy(
            attachments = listOf(mapOf("name" to "b.txt")),
            reasoningEffort = com.nekobot.app.data.model.ReasoningEffort.HIGH
        )

        val request = buildDelayReplyRequest(listOf(first, second))!!

        assertEquals(listOf("A"), request.precedingMessages)
        assertEquals("B", request.messageContent)
        assertEquals(listOf("a.png", "b.txt"), request.attachments.map { it["name"] })
        assertEquals(com.nekobot.app.data.model.ReasoningEffort.HIGH, request.reasoningEffort)
    }

    @Test
    fun `delay applies to eligible local character conversations only`() {
        fun eligible(
            local: Boolean = true,
            mode: String? = "character",
            inherit: Boolean? = false,
            allow: Boolean = true,
            command: Boolean = false,
            enabled: Boolean = true
        ) = shouldUseDelayReply(enabled, allow, local, mode, inherit, command)

        assertTrue(eligible())
        assertTrue(eligible(mode = "agent", inherit = true))
        assertFalse(eligible(local = false))
        assertFalse(eligible(mode = "agent", inherit = false))
        assertFalse(eligible(mode = "group"))
        assertFalse(eligible(allow = false))
        assertFalse(eligible(command = true))
        assertFalse(eligible(enabled = false))
    }

    @Test
    fun `delay seconds use default and stay in supported range`() {
        assertEquals(2, normalizeDelayReplySeconds(null))
        assertEquals(1, normalizeDelayReplySeconds(0))
        assertEquals(12, normalizeDelayReplySeconds(12))
        assertEquals(30, normalizeDelayReplySeconds(60))
    }

    private fun message(content: String) = DelayedReplyMessage(
        bubbleId = "pending-$content",
        content = content,
        timestamp = "2026-09-28T00:00:00Z"
    )
}
