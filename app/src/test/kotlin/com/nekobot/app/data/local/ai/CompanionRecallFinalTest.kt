package com.nekobot.app.data.local.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionRecallFinalTest {
    private val call = listOf(mapOf<String, Any>("id" to "c1", "name" to "agent_recall_search"))

    @Test fun exhaustedRecallWithToolCallsUsesTheNoToolAnswer() = runBlocking {
        var retried = 0
        val result = CompanionRecallFinal.resolve(LocalAiResult("", toolCalls = call), mustAnswer = true) {
            retried++; LocalAiResult("我想起来了，是你换部门的那件事。")
        }
        assertEquals(1, retried)
        assertEquals("我想起来了，是你换部门的那件事。", result.content)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test fun failedRetryFallsBackToANeutralInCharacterLine() = runBlocking {
        for (second in listOf(LocalAiResult("", toolCalls = call), LocalAiResult("  "), LocalAiResult("", error = "超时"))) {
            val result = CompanionRecallFinal.resolve(LocalAiResult("", toolCalls = call), true) { second }
            assertEquals(CompanionRecallFinal.NEUTRAL_LINE, result.content)
            assertTrue(result.toolCalls.isEmpty())
        }
        val thrown = CompanionRecallFinal.resolve(LocalAiResult("", toolCalls = call), true) { error("网络") }
        assertEquals(CompanionRecallFinal.NEUTRAL_LINE, thrown.content)
        for (word in listOf("回查", "资料", "额度", "工具")) assertFalse(word, CompanionRecallFinal.NEUTRAL_LINE.contains(word))
    }

    @Test fun normalRepliesAreUntouched() = runBlocking {
        val normal = LocalAiResult("好呀")
        assertSame(normal, CompanionRecallFinal.resolve(normal, true) { error("不应重试") })
        val tools = LocalAiResult("", toolCalls = call)
        assertSame(tools, CompanionRecallFinal.resolve(tools, false) { error("不应重试") })
    }

    @Test fun finalRequestAddsOnlyAnInternalSystemNote() {
        val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "去翻一翻记忆"))
        val final = CompanionRecallFinal.finalMessages(messages)
        assertEquals(messages, final.dropLast(1))
        assertEquals("system", final.last()["role"])
    }
}
