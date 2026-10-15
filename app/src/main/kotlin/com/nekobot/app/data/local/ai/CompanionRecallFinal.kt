package com.nekobot.app.data.local.ai

import kotlinx.coroutines.CancellationException

/** 回查结束后模型仍要求调用工具时：去掉工具再请模型以角色身份回答一次，不用写死的台词冒充角色。 */
internal object CompanionRecallFinal {
    const val FINAL_NOTE = "（软件内部说明，不是玩家发言）这一轮已经不能再查找记忆，这次也不能调用任何工具。" +
        "请以角色本人的身份和口吻，根据上下文里已经得到的内容直接回复玩家；想不起来或不确定的部分，按这个角色自己的方式自然表达。" +
        "除非符合角色设定，不要提到工具、额度、Token 或软件说明。"
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
