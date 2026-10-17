package com.nekobot.app.data.local.ai

import kotlinx.coroutines.CancellationException

/** 回查结束后模型仍要求调用工具时：去掉工具再请模型以角色身份回答一次，不用写死的台词冒充角色。 */
internal object CompanionRecallFinal {
    const val FINAL_NOTE = "先用已经想起来的事，按你平时的口吻接着聊；想不起来或拿不准的地方，就坦然说记不清了，别编。"
    const val NEUTRAL_LINE = "嗯……我想了一会儿，一下子没能想清楚。你再跟我说说是哪件事？"

    fun finalMessages(messages: List<Map<String, Any>>): List<Map<String, Any>> =
        messages + mapOf("role" to "system", "content" to FINAL_NOTE)

    suspend fun resolve(first: LocalAiResult, mustAnswer: Boolean, retry: suspend () -> LocalAiResult): LocalAiResult {
        if (!mustAnswer || first.toolCalls.isEmpty()) return first
        val second = try { retry() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        return if (second != null && second.error == null && second.toolCalls.isEmpty() && second.content.isNotBlank()) {
            second.copy(finishReason = "stop")
        } else {
            first.copy(content = NEUTRAL_LINE, toolCalls = emptyList(), finishReason = "stop")
        }
    }
}
