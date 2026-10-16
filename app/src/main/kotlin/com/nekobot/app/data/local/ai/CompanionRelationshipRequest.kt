package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity

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

    fun extra(model: LocalAiModelEntity): Map<String, Any?> = buildMap {
        put("max_tokens", outputBudget(model))
        put(NO_COMPATIBILITY_RETRY_EXTRA, true)
        put(EVALUATION_EXTRA, true)
        put(CALL_TIMEOUT_EXTRA, CompanionRelationshipRecovery.TIMEOUT_SECONDS * 1000L)
        if (model.protocol == AnthropicMessagesProtocol.name) put("reasoning_effort", "none")
        if (model.protocol == OpenAIChatProtocol.name && isXiaomi(model)) {
            put("_completion_token_limit", true)
            put("_disable_thinking", true)
        }
        val provider = model.provider.orEmpty().lowercase()
        val host = runCatching { java.net.URI(model.baseUrl).host.orEmpty().lowercase() }.getOrDefault("")
        val name = model.model.lowercase().substringAfterLast('/')
        val hybridGlm = name == "glm-5" || listOf("glm-4.5", "glm-4.6", "glm-4.7", "glm-5.1", "glm-5.2")
            .any { name == it || name.startsWith("$it-") }
        if (model.protocol == OpenAIChatProtocol.name &&
            (provider in setOf("glm", "zhipu", "bigmodel", "zai", "z.ai") || host in setOf("open.bigmodel.cn", "api.z.ai")) &&
            hybridGlm) {
            put("_disable_thinking", true)
        }
        // 只适配百炼混合思考模型；QwQ/强制思考版和不明代理不猜参数。
        if (model.protocol == OpenAIChatProtocol.name &&
            (provider in setOf("dashscope", "qwen", "aliyun") || host.endsWith("dashscope.aliyuncs.com")) &&
            (name.startsWith("qwen3") || name.startsWith("qwen-plus") || name.startsWith("qwen-flash")) &&
            name != "qwen3-max" && listOf("thinking", "coder", "instruct", "omni").none { it in name }) put("enable_thinking", false)
        // DeepSeek 已有协议支持 thinking.type，NONE 会映射为 disabled。
        if (model.provider?.contains("deepseek", true) == true ||
            runCatching { java.net.URI(model.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)) {
            put("reasoning_effort", "none")
        }
    }

    fun requireComplete(result: LocalAiResult) {
        require(result.finishReason !in setOf("length", "max_tokens", "max_output_tokens")) {
            "评估回复达到输出上限，本批尚未完成，已有状态和待评估对话保留。请检查评估模型的输出上限。"
        }
    }
}
