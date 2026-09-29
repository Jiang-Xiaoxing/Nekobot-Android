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
    fun `multiple pauses preserve the actual remaining duration`() {
        val state = DelayReplyStateMachine()
        state.arm(message("A"), nowMs = 0L, delayMs = 10_000L)

        val firstPause = state.setInputActive(true, nowMs = 7_000L)
        assertNull(firstPause?.delayMs)
        assertEquals(3_000L, state.remainingMs(7_000L))

        val firstResume = state.setInputActive(false, nowMs = 20_000L)
        assertEquals(3_000L, firstResume?.delayMs)

        state.setInputActive(true, nowMs = 21_000L)
        assertEquals(2_000L, state.remainingMs(21_000L))

        val secondResume = state.setInputActive(false, nowMs = 30_000L)
        assertEquals(2_000L, secondResume?.delayMs)
        val ready = state.onTimerFired(secondResume!!.generation, 32_000L)
        assertTrue(ready is DelayReplyTimerResult.Ready)
    }

    @Test
    fun `arming while input is already active starts paused`() {
        val state = DelayReplyStateMachine()
        state.setInputActive(true, nowMs = 0L)

        val plan = state.arm(message("A"), nowMs = 100L, delayMs = 10_000L)

        assertNull(plan.delayMs)
        assertTrue(state.isPaused())
        assertEquals(10_000L, state.remainingMs(8_000L))
    }

    @Test
    fun `cancel invalidates old jobs and keeps current input state`() {
        val state = DelayReplyStateMachine()
        state.setInputActive(true, nowMs = 0L)
        val old = state.arm(message("A"), nowMs = 0L, delayMs = 10_000L)

        assertEquals(listOf("A"), state.cancel().map { it.content })
        assertEquals(DelayReplyTimerResult.Stale, state.onTimerFired(old.generation, 10_000L))

        val next = state.arm(message("B"), nowMs = 20_000L, delayMs = 10_000L)
        assertNull(next.delayMs)
        assertTrue(state.isPaused())
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
