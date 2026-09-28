package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNoticeBusTest {

    @Test
    fun publish_thenDrain_returnsAllNoticesInOrder() {
        val sessionId = "wake-test-session-1"
        AgentNoticeBus.clear(sessionId)
        AgentNoticeBus.publish(sessionId, "通知A")
        AgentNoticeBus.publish(sessionId, "通知B")

        assertTrue(AgentNoticeBus.hasPending(sessionId))
        assertEquals(listOf("通知A", "通知B"), AgentNoticeBus.drain(sessionId))
        assertFalse(AgentNoticeBus.hasPending(sessionId))
        assertEquals(emptyList<String>(), AgentNoticeBus.drain(sessionId))
        AgentNoticeBus.clear(sessionId)
    }

    @Test
    fun publish_triggersWakeHookWithSessionId() {
        val sessionId = "wake-test-session-2"
        AgentNoticeBus.clear(sessionId)
        var notified: String? = null
        AgentNoticeBus.onNoticePublished = { notified = it }
        try {
            AgentNoticeBus.publish(sessionId, "后台任务完成")
            assertEquals(sessionId, notified)

            // wakeIfIdle=false：只入队，不触发唤醒（用于失败回放等场景）。
            notified = null
            AgentNoticeBus.publish(sessionId, "放回队列", wakeIfIdle = false)
            assertNull(notified)
            assertTrue(AgentNoticeBus.hasPending(sessionId))
        } finally {
            AgentNoticeBus.onNoticePublished = null
            AgentNoticeBus.clear(sessionId)
        }
    }

    @Test
    fun publish_evictsOldestBeyondLimit() {
        val sessionId = "wake-test-session-3"
        AgentNoticeBus.clear(sessionId)
        for (i in 0 until AgentNoticeBus.MAX_NOTICES_PER_SESSION + 3) {
            AgentNoticeBus.publish(sessionId, "通知$i", wakeIfIdle = false)
        }
        val drained = AgentNoticeBus.drain(sessionId)
        assertEquals(AgentNoticeBus.MAX_NOTICES_PER_SESSION, drained.size)
        assertEquals("通知3", drained.first())
        AgentNoticeBus.clear(sessionId)
    }

    @Test
    fun publish_ignoresBlankSessionOrNotice() {
        var notified: String? = null
        AgentNoticeBus.onNoticePublished = { notified = it }
        try {
            AgentNoticeBus.publish("", "通知", wakeIfIdle = false)
            AgentNoticeBus.publish("some-session", "  ")
            assertNull(notified)
        } finally {
            AgentNoticeBus.onNoticePublished = null
        }
    }

    @Test
    fun buildAgentWakeUpMessage_joinsNoticesWithHeaderAndInstruction() {
        val message = buildAgentWakeUpMessage(
            listOf("[系统通知] 任务A已完成", "[系统通知] 任务B失败")
        )
        assertTrue(message.startsWith("[后台任务通知"))
        assertTrue(message.contains("[系统通知] 任务A已完成"))
        assertTrue(message.contains("[系统通知] 任务B失败"))
        assertTrue(message.contains("不要重复执行或重复委派"))
    }

    @Test
    fun buildAgentWakeUpMessage_blankNoticesYieldsEmpty() {
        assertEquals("", buildAgentWakeUpMessage(emptyList()))
        assertEquals("", buildAgentWakeUpMessage(listOf("  ", "")))
    }
}
