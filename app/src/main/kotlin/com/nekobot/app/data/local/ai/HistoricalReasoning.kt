package com.nekobot.app.data.local.ai

/** 只清理历史模型消息及其隐藏轨迹；工具名、参数、结果与数据库原记录保持不变。 */
internal fun withoutHistoricalReasoning(message: Map<String, Any>): Map<String, Any> =
    message.filterKeys { it !in historicalReasoningKeys }.toMutableMap().apply {
        if (this["role"] != "tool") {
            (this["content"] as? List<*>)?.let { blocks ->
                this["content"] = blocks.filterNot { block ->
                    (block as? Map<*, *>)?.get("type") in historicalReasoningBlockTypes
                }
            }
        }
        val history = this["tool_call_history"] as? List<*> ?: return@apply
        this["tool_call_history"] = history.map { entry ->
            if (entry is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                withoutHistoricalReasoning(entry as Map<String, Any>)
            } else entry
        }
    }

private val historicalReasoningKeys = setOf(
    "reasoning_content", "thinking_content", "reasoningContent", "thinkingContent",
    "reasoning_signature", "thinking_signature", "reasoningSignature", "thinkingSignature",
    "thinking", "redacted_thinking", "signature", "thinking_blocks", "reasoning_details"
)

/** Anthropic 扩展思考的内容块：thinking（含 signature）与 redacted_thinking（含 data）。 */
private val historicalReasoningBlockTypes = setOf("thinking", "redacted_thinking")
