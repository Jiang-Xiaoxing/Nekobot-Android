package com.nekobot.app.data.local.ai

import com.google.gson.JsonObject

/**
 * 本地 AI 协议适配层接口。
 *
 * 对应后端 `nbot/core/protocols/base.py:ModelProtocol`，仅保留本地模式必需的方法。
 * 实现需负责：
 * 1. resolveUrl：把 base_url + model 拼成最终请求 URL
 * 2. buildHeaders：构造鉴权 + Content-Type 头
 * 3. buildPayload：把统一的 messages 转成协议特定 payload
 * 4. parseStreamChunk：解析 SSE 单 chunk，返回文本增量
 * 5. parseNonStreamResponse：解析非流式响应，返回文本、工具调用与 usage
 */
data class LocalModelResponse(
    val content: String = "",
    val usage: Map<String, Int> = emptyMap(),
    val toolCalls: List<Map<String, Any>> = emptyList(),
    val finishReason: String = "",
    val thinkingContent: String = "",
    /**
     * 思考块签名（Anthropic 扩展思考）。
     *
     * Anthropic 要求把上一轮 assistant 的 thinking 块（含签名）原样带回，
     * 否则带工具调用的后续请求会被拒。其他协议不产生该字段。
     */
    val thinkingSignature: String = ""
)

/** 流式工具调用增量；由协议层解析，客户端负责按 [index] 聚合。 */
data class LocalToolCallDelta(
    val index: Int,
    val idChunk: String = "",
    val nameChunk: String = "",
    val argumentsChunk: String = "",
    val initialArgumentsJson: String = ""
)

/**
 * 单次模型调用的用量明细，含缓存命中/写入 token。
 *
 * 语义约定（缓存命中率的统一口径）：
 * - [promptTokens] 是本次计入计费的完整输入量。Anthropic 的 `input_tokens` 不含缓存读写，
 *   解析层已把 cache_read/cache_creation 累加回来，因此 [cachedPromptTokens] 始终是它的子集；
 * - [cachedPromptTokens] 命中缓存的输入量（OpenAI Chat `prompt_tokens_details.cached_tokens`、
 *   OpenAI Responses `input_tokens_details.cached_tokens`、
 *   DeepSeek `prompt_cache_hit_tokens`、Anthropic `cache_read_input_tokens`、
 *   Gemini `cachedContentTokenCount`）；
 * - [cacheWriteTokens] 写入缓存的输入量（Anthropic `cache_creation_input_tokens`），
 *   其余服务商为 0。
 */
data class LocalModelUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int = promptTokens + completionTokens,
    val cachedPromptTokens: Int = 0,
    val cacheWriteTokens: Int = 0
) {
    /** 合并成管线内部通用的 usage 字典（键名与协议解析结果一致）。 */
    fun toUsageMap(): Map<String, Int> = buildMap {
        put("prompt", promptTokens)
        put("completion", completionTokens)
        put("total", totalTokens)
        if (cachedPromptTokens > 0) put(KEY_CACHED_PROMPT, cachedPromptTokens)
        if (cacheWriteTokens > 0) put(KEY_CACHE_WRITE, cacheWriteTokens)
    }

    companion object {
        /** 缓存命中 token 在 usage 字典中的键名。 */
        const val KEY_CACHED_PROMPT = "cached_prompt"

        /** 缓存写入 token 在 usage 字典中的键名。 */
        const val KEY_CACHE_WRITE = "cache_write"
    }
}

/** usage 字典（protocol 解析产物）→ 带缓存明细的用量；缺失缓存字段按 0 处理。 */
internal fun usageMapToDetail(usage: Map<String, Int>): LocalModelUsage = LocalModelUsage(
    promptTokens = usage["prompt"] ?: 0,
    completionTokens = usage["completion"] ?: 0,
    totalTokens = usage["total"] ?: ((usage["prompt"] ?: 0) + (usage["completion"] ?: 0)),
    cachedPromptTokens = usage[LocalModelUsage.KEY_CACHED_PROMPT] ?: 0,
    cacheWriteTokens = usage[LocalModelUsage.KEY_CACHE_WRITE] ?: 0
)

/**
 * OpenAI 兼容（含 DeepSeek）usage 解析：命中量是 prompt 的子集。
 *
 * OpenAI 走 `prompt_tokens_details.cached_tokens`，DeepSeek 走 `prompt_cache_hit_tokens`，
 * 部分代理直接给 `cached_tokens`。
 */
internal fun openAiStyleUsage(
    promptTokens: Int,
    completionTokens: Int,
    totalTokens: Int?,
    cachedPromptTokens: Int
): LocalModelUsage {
    val prompt = promptTokens.coerceAtLeast(0)
    val cached = cachedPromptTokens.coerceIn(0, prompt)
    val total = totalTokens?.takeIf { it > 0 } ?: (prompt + completionTokens.coerceAtLeast(0))
    return LocalModelUsage(
        promptTokens = prompt,
        completionTokens = completionTokens.coerceAtLeast(0),
        totalTokens = total,
        cachedPromptTokens = cached
    )
}

/**
 * Anthropic usage 解析：`input_tokens` 不含缓存读写，需累加为完整输入量。
 */
internal fun anthropicStyleUsage(
    inputTokens: Int,
    outputTokens: Int,
    cacheReadTokens: Int,
    cacheWriteTokens: Int
): LocalModelUsage {
    val input = inputTokens.coerceAtLeast(0)
    val read = cacheReadTokens.coerceAtLeast(0)
    val write = cacheWriteTokens.coerceAtLeast(0)
    val prompt = input + read + write
    return LocalModelUsage(
        promptTokens = prompt,
        completionTokens = outputTokens.coerceAtLeast(0),
        totalTokens = prompt + outputTokens.coerceAtLeast(0),
        cachedPromptTokens = read.coerceIn(0, prompt),
        cacheWriteTokens = write.coerceIn(0, prompt - read.coerceAtMost(prompt))
    )
}

/**
 * 读取 JSON 数值字段；非数值（字符串数字也算）与缺失都返回 [fallback]。
 */
internal fun JsonObject.usageInt(name: String, fallback: Int = 0): Int {
    val element = get(name) ?: return fallback
    if (element.isJsonNull) return fallback
    return runCatching {
        if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isNumber -> primitive.asInt
                primitive.isString -> primitive.asString.toDoubleOrNull()?.toInt() ?: fallback
                else -> fallback
            }
        } else {
            fallback
        }
    }.getOrDefault(fallback)
}

/** 读取嵌套对象字段；不是对象时返回 null。 */
internal fun JsonObject.usageObject(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

/** 读取 Map 形式的数值字段（非流式响应由 Gson 反序列化成 Map）。 */
internal fun Map<*, *>.usageInt(key: String): Int = when (val value = this[key]) {
    is Number -> value.toInt()
    is String -> value.toDoubleOrNull()?.toInt() ?: 0
    else -> 0
}

/**
 * OpenAI 兼容 JSON usage → 用量明细。
 *
 * 同时兼容 Chat Completions（`prompt_tokens` + `prompt_tokens_details.cached_tokens`）与
 * Responses API（`input_tokens` + `input_tokens_details.cached_tokens`）两种形状，
 * 以及 DeepSeek `prompt_cache_hit_tokens`、Moonshot 顶层 `cached_tokens`；
 * 写入量取 Responses `input_tokens_details.cache_write_tokens` 或顶层 `cache_creation_input_tokens`。
 */
internal fun openAiStyleUsageFromJson(usage: JsonObject): LocalModelUsage {
    val prompt = usage.usageInt("prompt_tokens").takeIf { it > 0 }
        ?: usage.usageInt("input_tokens")
    val completion = usage.usageInt("completion_tokens").takeIf { it > 0 }
        ?: usage.usageInt("output_tokens")
    val total = usage.usageInt("total_tokens")
    val cached = usage.usageObject("prompt_tokens_details")?.usageInt("cached_tokens")
        ?.takeIf { it > 0 }
        ?: usage.usageObject("input_tokens_details")?.usageInt("cached_tokens")
            ?.takeIf { it > 0 }
        ?: usage.usageInt("cached_tokens").takeIf { it > 0 }
        ?: usage.usageInt("prompt_cache_hit_tokens")
    val cacheWrite = usage.usageObject("input_tokens_details")?.usageInt("cache_write_tokens")
        ?.takeIf { it > 0 }
        ?: usage.usageInt("cache_creation_input_tokens").takeIf { it > 0 }
        ?: 0
    return withCacheWrite(openAiStyleUsage(prompt, completion, total, cached), cacheWrite)
}

/** OpenAI 兼容 Map usage → 用量明细（字段口径同 [openAiStyleUsageFromJson]）。 */
internal fun openAiStyleUsageFromMap(usage: Map<*, *>): LocalModelUsage {
    val prompt = usage.usageInt("prompt_tokens").takeIf { it > 0 }
        ?: usage.usageInt("input_tokens")
    val completion = usage.usageInt("completion_tokens").takeIf { it > 0 }
        ?: usage.usageInt("output_tokens")
    val total = usage.usageInt("total_tokens")
    val details = usage["prompt_tokens_details"] as? Map<*, *>
    val inputDetails = usage["input_tokens_details"] as? Map<*, *>
    val cached = details?.usageInt("cached_tokens")?.takeIf { it > 0 }
        ?: inputDetails?.usageInt("cached_tokens")?.takeIf { it > 0 }
        ?: usage.usageInt("cached_tokens").takeIf { it > 0 }
        ?: usage.usageInt("prompt_cache_hit_tokens")
    val cacheWrite = inputDetails?.usageInt("cache_write_tokens")?.takeIf { it > 0 }
        ?: usage.usageInt("cache_creation_input_tokens").takeIf { it > 0 }
        ?: 0
    return withCacheWrite(openAiStyleUsage(prompt, completion, total, cached), cacheWrite)
}

/** 写入量是输入的子集（扣除命中部分）；0 保持原值不动。 */
private fun withCacheWrite(usage: LocalModelUsage, cacheWrite: Int): LocalModelUsage =
    if (cacheWrite > 0) {
        usage.copy(
            cacheWriteTokens = cacheWrite.coerceIn(0, usage.promptTokens - usage.cachedPromptTokens)
        )
    } else {
        usage
    }

/** Anthropic JSON usage → 用量明细（input_tokens 不含缓存读写）。 */
internal fun anthropicStyleUsageFromJson(usage: JsonObject): LocalModelUsage = anthropicStyleUsage(
    inputTokens = usage.usageInt("input_tokens"),
    outputTokens = usage.usageInt("output_tokens"),
    cacheReadTokens = usage.usageInt("cache_read_input_tokens"),
    cacheWriteTokens = usage.usageInt("cache_creation_input_tokens")
)

/** Anthropic Map usage → 用量明细。 */
internal fun anthropicStyleUsageFromMap(usage: Map<*, *>): LocalModelUsage = anthropicStyleUsage(
    inputTokens = usage.usageInt("input_tokens"),
    outputTokens = usage.usageInt("output_tokens"),
    cacheReadTokens = usage.usageInt("cache_read_input_tokens"),
    cacheWriteTokens = usage.usageInt("cache_creation_input_tokens")
)

/** Gemini usageMetadata → 用量明细（promptTokenCount 已含命中量，命中量是子集）。 */
internal fun geminiStyleUsageFromMap(usage: Map<*, *>): LocalModelUsage = openAiStyleUsage(
    promptTokens = usage.usageInt("promptTokenCount"),
    completionTokens = usage.usageInt("candidatesTokenCount"),
    totalTokens = usage.usageInt("totalTokenCount").takeIf { it > 0 },
    cachedPromptTokens = usage.usageInt("cachedContentTokenCount")
)

interface LocalProtocol {
    val name: String
    val requiresStreaming: Boolean
        get() = false

    fun resolveUrl(
        baseUrl: String,
        model: String,
        appendBaseUrlPath: Boolean,
        stream: Boolean = false,
        apiKey: String = ""
    ): String

    /**
     * [endpoint] 为 resolveUrl 产出的最终请求地址（含 query），协议实现可据此
     * 区分官方端点与第三方代理（例如 Gemini 官方端点不应发送 Authorization 头）。
     */
    fun buildHeaders(apiKey: String, stream: Boolean, endpoint: String = ""): Map<String, String>

    /**
     * @param messages 统一格式：[{role: "system"|"user"|"assistant", content: "..."}]
     * @param extra 可选参数：temperature / max_tokens / top_p / tools
     */
    fun buildPayload(
        model: String,
        messages: List<Map<String, Any>>,
        stream: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any>

    /**
     * 解析流式 SSE chunk（已去掉 `data: ` 前缀的 JSON 字符串）。
     * @return 文本增量，若无内容返回 null
     */
    fun parseStreamChunk(chunkJson: String): String?

    /** 解析模型推理/思考文本增量；协议不支持时返回 null。 */
    fun parseStreamThinkingChunk(chunkJson: String): String? = null

    /**
     * 解析思考块签名增量；协议不支持时返回 null。
     *
     * Anthropic 扩展思考在流式响应里用 signature_delta 单独下发签名，
     * 该签名必须随 thinking 块一起在下一次请求中回传。
     */
    fun parseStreamThinkingSignature(chunkJson: String): String? = null

    /** 解析流式工具调用的 id/name/arguments 增量。 */
    fun parseStreamToolCallDeltas(chunkJson: String): List<LocalToolCallDelta> = emptyList()

    /** 解析流式结束原因，如 stop/tool_calls/content_filter。 */
    fun parseStreamFinishReason(chunkJson: String): String? = null

    /**
     * 解析流式 SSE chunk 中的 usage 字段（OpenAI 在最后 chunk 携带，Anthropic 在 message_delta 携带）。
     * @return Triple(prompt, completion, total)，无 usage 返回 null
     */
    fun parseStreamUsage(chunkJson: String): Triple<Int, Int, Int>?

    /**
     * 解析流式 usage 的完整明细（含缓存命中/写入 token）。
     *
     * 默认实现只返回基础三项，支持缓存的服务商覆写本方法上报命中量。
     */
    fun parseStreamUsageDetail(chunkJson: String): LocalModelUsage? =
        parseStreamUsage(chunkJson)?.let { (prompt, completion, total) ->
            LocalModelUsage(prompt, completion, total)
        }

    fun parseStreamFinalResponse(chunkJson: String): LocalModelResponse? = null

    fun parseStreamError(chunkJson: String): String? = null

    /**
     * 解析非流式响应 JSON。
     * @return 统一模型响应，包含 content / toolCalls / finishReason / usage
     */
    fun parseNonStreamResponse(data: Map<String, Any>): LocalModelResponse
}

/** 协议注册表。 */
object LocalProtocols {
    private val registry: Map<String, LocalProtocol> = mapOf(
        OpenAIChatProtocol.name to OpenAIChatProtocol,
        OpenAIResponsesProtocol.name to OpenAIResponsesProtocol,
        AnthropicMessagesProtocol.name to AnthropicMessagesProtocol,
        GeminiNativeProtocol.name to GeminiNativeProtocol
    )

    fun get(name: String): LocalProtocol =
        registry[name] ?: OpenAIChatProtocol

    fun names(): List<String> = registry.keys.toList()
}
