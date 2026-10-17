package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort

/** 辅助评估的输出预算必须放入 extra；chatOnce 不读取模型副本的 maxTokens。 */
internal object CompanionRelationshipRequest {
    const val EVALUATION_EXTRA = "_relationship_evaluation"
    const val CALL_TIMEOUT_EXTRA = "_relationship_call_timeout_ms"
    // 保留用户配置的预算，不因简化结构而降低已有上限。
    fun outputBudget(model: LocalAiModelEntity): Int = minOf(model.maxTokens?.takeIf { it > 0 } ?: 8192, 8192)

    fun isXiaomi(model: LocalAiModelEntity): Boolean {
        val host = runCatching { java.net.URI(model.baseUrl).host.orEmpty().lowercase() }.getOrDefault("")
        return model.provider?.lowercase() in setOf("xiaomi", "mimo") ||
            host == "api.xiaomimimo.com" || host == "api.xiaomimimo.ai" || host == "api.mimo.mi.com"
    }

    fun extra(model: LocalAiModelEntity, effort: ReasoningEffort,
        custom: () -> CustomReasoningParam = { CustomReasoningParam() }): Map<String, Any?> = buildMap {
        put("max_tokens", outputBudget(model))
        put(NO_COMPATIBILITY_RETRY_EXTRA, true)
        put(EVALUATION_EXTRA, true)
        put(CALL_TIMEOUT_EXTRA, CompanionRelationshipRecovery.TIMEOUT_SECONDS * 1000L)
        // 与聊天共用档位/自定义参数的协议映射，不为辅助评估另设关闭思考策略。
        putReasoningParams(this, effort, custom = custom)
        if (model.protocol == OpenAIChatProtocol.name && isXiaomi(model)) {
            put("_completion_token_limit", true)
        }
    }

    fun requireComplete(result: LocalAiResult) {
        require(result.finishReason !in setOf("length", "max_tokens", "max_output_tokens")) {
            "评估回复达到输出上限，本批尚未完成，已有状态和待评估对话保留。请检查评估模型的输出上限。"
        }
    }
}
