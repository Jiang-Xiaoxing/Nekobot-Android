package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity

/** 辅助评估的输出预算必须放入 extra；chatOnce 不读取模型副本的 maxTokens。 */
internal object CompanionRelationshipRequest {
    // 预算包含模型思考与 JSON 正文；预留六维关系及最多六件事件的完整输出空间。
    fun outputBudget(model: LocalAiModelEntity): Int = minOf(model.maxTokens?.takeIf { it > 0 } ?: 8192, 8192)

    fun extra(model: LocalAiModelEntity): Map<String, Any?> = mapOf(
        "max_tokens" to outputBudget(model), NO_COMPATIBILITY_RETRY_EXTRA to true)

    fun requireComplete(result: LocalAiResult) {
        require(result.finishReason !in setOf("length", "max_tokens", "max_output_tokens")) {
            "评估回复达到输出上限，本批尚未完成，已有状态和待评估对话保留。请检查评估模型的输出上限。"
        }
    }
}
