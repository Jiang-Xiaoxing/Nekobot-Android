package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import org.junit.Assert.*
import org.junit.Test

class ChatMessageMotionTest {
    private val opened = 1_800_000_000_000L
    private fun user(id: String? = null, content: String = "你好") =
        Message(id = id, role = "user", content = content, timestamp = (opened + 10).toString())

    @Test fun historyAndReopenedChatDoNotAnimate() {
        assertFalse(MessageMotionRegistry(opened).claim("history", 0, (opened - 1).toString(), true))
        assertFalse(MessageMotionRegistry(opened + 100).claim("reply", 0, (opened + 10).toString(), true))
    }

    @Test fun streamedReplyAndSavedReplyShareTheSameEntrance() {
        val streaming = listOf(user(), Message(id = "_streaming_", role = "assistant", content = "你"))
        val saved = listOf(user("stored-user"), Message(id = "stored-reply", role = "assistant", content = "你好呀"))
        val streamedKey = messageMotionKeys(streaming).last()
        val savedKey = messageMotionKeys(saved).last()
        assertEquals(streamedKey, savedKey)
        val registry = MessageMotionRegistry(opened)
        assertTrue(registry.claim(streamedKey, 0, (opened + 10).toString(), true))
        assertFalse(registry.claim(savedKey, 0, (opened + 20).toString(), true))
    }

    @Test fun laterSegmentAnimatesOnceAndScrollingBackDoesNotReplayIt() {
        val registry = MessageMotionRegistry(opened)
        val time = (opened + 10).toString()
        assertTrue(registry.claim("reply", 0, time, true))
        assertFalse(registry.claim("reply", 0, time, true))
        assertTrue(registry.claim("reply", 1, time, true))
        assertFalse(registry.claim("reply", 1, time, true))
    }

    @Test fun enablingEffectDoesNotReplayAlreadyVisibleBubbles() {
        val registry = MessageMotionRegistry(opened)
        assertFalse(registry.claim("reply", 0, (opened + 10).toString(), false))
        assertFalse(registry.claim("reply", 0, (opened + 10).toString(), true))
    }

    @Test fun editingUserMessageDoesNotChangeReplyIdentity() {
        val reply = Message(role = "assistant", timestamp = (opened + 20).toString())
        assertEquals(messageMotionKeys(listOf(user(), reply)), messageMotionKeys(listOf(user("saved", "改一下"), reply)))
    }

    @Test fun severalRepliesAndTurnsHaveDifferentEntrances() {
        val first = user()
        val reply = Message(role = "assistant")
        val keys = messageMotionKeys(listOf(first, reply, reply, first.copy(timestamp = (opened + 40).toString()), reply))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun timestampsWithoutReliableTimeDoNotInventFreshMessages() {
        assertNull(messageMotionTime("日期未知"))
        assertNull(messageMotionTime("2026-10-11"))
        assertEquals(1_800_000_000_000L, messageMotionTime("1800000000"))
        assertEquals(java.time.Instant.parse("2026-10-11T00:00:00Z").toEpochMilli(), messageMotionTime("2026-10-11T00:00:00Z"))
    }
}
