package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoricalReasoningTest {
    private val trace = listOf(
        mapOf<String, Any>("role" to "assistant", "content" to "可见的中间回复", "reasoning_content" to "OLD_REASONING",
            "thinking_content" to "OLD_THINKING", "reasoning_signature" to "OLD_SIGNATURE",
            "tool_calls" to listOf(mapOf("id" to "call", "type" to "function", "function" to mapOf("name" to "agent_history_search", "arguments" to "{\"query\":\"海边\"}")))),
        mapOf<String, Any>("role" to "tool", "tool_call_id" to "call", "content" to "工具原文")
    )
    private val saved = mapOf<String, Any>("role" to "assistant", "content" to "可见的最终回复", "reasoningContent" to "OLD_TOP",
        "thinking_signature" to "OLD_SIGNATURE", "tool_call_history" to trace)

    @Test
    fun ordinaryPipelineHistoryDoesNotResendTopLevelOrHiddenReasoning() = runBlocking {
        val ctx = PipelineContext(ChatRequest.forLocal("s", "你好", metadata = mapOf("session_mode" to "character")))
        AIPipeline().prepareContext(ctx, fixture())
        val text = ctx.messages.toString()
        assertFalse(text.contains("OLD_"))
        assertTrue(text.contains("可见的中间回复"))
        assertTrue(text.contains("可见的最终回复"))
        assertTrue(text.contains("工具原文"))
        assertTrue(text.contains("agent_history_search"))
        assertTrue(text.contains("海边"))
        assertEquals("OLD_REASONING", trace.first()["reasoning_content"])
        assertEquals("OLD_TOP", saved["reasoningContent"])
    }

    @Test
    fun agentRecoveryHistoryStillPreservesProtocolReasoning() = runBlocking {
        val ctx = PipelineContext(ChatRequest.forLocal("s", "你好", metadata = mapOf("session_mode" to "agent")))
        AIPipeline().prepareContext(ctx, fixture())
        assertTrue(ctx.messages.any { it["reasoning_content"] == "OLD_REASONING" })
        assertTrue(ctx.messages.any { it["reasoning_signature"] == "OLD_SIGNATURE" })
    }

    @Test
    fun directChatBuilderStripsHiddenReasoningWithoutChangingStoredRow() {
        val row = LocalMessageEntity(id = "a", sessionId = "s", role = "assistant", content = "公开回复",
            reasoningContent = "TOP_REASONING", toolCallHistory = encodeToolCallHistory(trace), timestamp = "now", createdAt = "now")
        val session = LocalSessionEntity(id = "s", name = "测试", sessionMode = "character", createdAt = "now", updatedAt = "now")
        val prepared = prepareChatContext(LocalPromptBuilder.build(session, null, listOf(row), "你好"), "你好")
        assertFalse(prepared.messages.toString().contains("OLD_"))
        assertFalse(prepared.messages.toString().contains("TOP_REASONING"))
        assertTrue(prepared.messages.toString().contains("工具原文"))
        assertEquals("TOP_REASONING", row.reasoningContent)
        assertEquals("OLD_REASONING", decodeToolCallHistory(row.toolCallHistory).first()["reasoning_content"])
    }

    @Test
    fun reasoningRemovalNeverTraversesToolArgumentsOrResults() {
        val arguments = mapOf("reasoning_content" to "参数数据", "thinking_content" to "参数数据")
        val result = withoutHistoricalReasoning(mapOf("role" to "tool", "content" to "thinking_content 是文件字段", "arguments" to arguments))
        assertEquals(arguments, result["arguments"])
        assertEquals("thinking_content 是文件字段", result["content"])
    }

    @Test
    fun anthropicThinkingAndRedactedBlocksAreRemovedFromHistoryAndTrace() {
        val blocks = listOf(
            mapOf("type" to "thinking", "thinking" to "OLD_BLOCK_THINKING", "signature" to "OLD_BLOCK_SIG"),
            mapOf("type" to "redacted_thinking", "data" to "OLD_REDACTED"),
            mapOf("type" to "text", "text" to "可见正文")
        )
        val inner = mapOf<String, Any>("role" to "assistant", "content" to blocks, "signature" to "OLD_SIG2", "thinking" to "OLD_T2")
        val original = mapOf<String, Any>("role" to "assistant", "content" to blocks,
            "signature" to "OLD_SIG", "redacted_thinking" to "OLD_R", "tool_call_history" to listOf(inner))
        val cleaned = withoutHistoricalReasoning(original)
        assertFalse(cleaned.toString().contains("OLD_"))
        assertEquals(listOf(mapOf("type" to "text", "text" to "可见正文")), cleaned["content"])
        assertEquals(listOf(mapOf("type" to "text", "text" to "可见正文")), (cleaned["tool_call_history"] as List<*>).filterIsInstance<Map<*, *>>().single()["content"])
        assertEquals(3, (original["content"] as List<*>).size)
        assertEquals("OLD_SIG", original["signature"])
    }

    private fun fixture() = object : PipelineCallbacks() {
        override fun loadMessages(ctx: PipelineContext) = listOf(saved)
    }
}
