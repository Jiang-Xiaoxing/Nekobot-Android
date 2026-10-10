package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonParser

/** 仅保存分类计数与请求时间，不另存聊天正文、附件或凭据。 */
internal data class CompanionRequestSnapshot(
    val breakdown: ContextUsageBreakdown,
    val sentAt: Long,
    val maxContext: Int,
    val reportedInput: Int? = null
)

internal object CompanionRequestUsage {
    const val META = "_companion_accounting"
    private val gson = Gson()

    fun clean(message: Map<String, Any>): Map<String, Any> = message - META - CompanionRecallBudget.SOURCE_META

    // 只给软件生成的辅助消息标记；用户仿写同样正文也仍属于用户消息。
    fun auxiliary(text: String): Map<String, Any> {
        fun section(header: String): Map<String, Int> {
            val line = text.substringAfter(header, "").lineSequence().firstOrNull().orEmpty()
            val count = runCatching { JsonParser.parseString(line).asJsonArray.size() }.getOrDefault(0)
            return mapOf("tokens" to if (count == 0) 0 else estimateLocalTextTokens(line), "count" to count)
        }
        return mapOf("role" to "user", "content" to text, META to mapOf(
            "core" to section("每次提供的核心记忆：\n"),
            "recall" to section("本轮相关记忆：\n")
        ))
    }

    fun snapshot(messages: List<Map<String, Any>>, tools: List<Map<String, Any>>, maxContext: Int,
        usage: Map<String, Any>, sentAt: Long): CompanionRequestSnapshot {
        val sums = mutableMapOf<ContextUsagePart, Pair<Int, Int>>()
        fun add(part: ContextUsagePart, tokens: Int, count: Int = 1) {
            val old = sums[part] ?: (0 to 0)
            sums[part] = old.first + tokens.coerceAtLeast(0) to old.second + count
        }
        if (tools.isNotEmpty()) add(ContextUsagePart.TOOL_DEFINITIONS, estimateToolDefinitionsTokens(tools), tools.size)
        for (message in messages) {
            val body = message["content"]
            val text = when (body) {
                is String -> body
                is List<*> -> body.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
                else -> ""
            }
            val tokens = estimateLocalTextTokens(text) + CONTEXT_USAGE_AGENT_OVERHEAD_TOKENS
            val mark = message[META] as? Map<*, *>
            if (mark != null) {
                var left = tokens
                for ((key, part) in listOf("core" to ContextUsagePart.CORE_MEMORY, "recall" to ContextUsagePart.RECALLED_MEMORY)) {
                    val segment = mark[key] as? Map<*, *> ?: continue
                    val size = ((segment["tokens"] as? Number)?.toInt() ?: 0).coerceIn(0, left)
                    val count = (segment["count"] as? Number)?.toInt() ?: 0
                    if (size > 0) add(part, size, count)
                    left -= size
                }
                add(ContextUsagePart.AUXILIARY_CONTEXT, left)
            } else {
                val part = when (message["role"]) {
                    "system" -> ContextUsagePart.SYSTEM_PROMPT
                    "user" -> ContextUsagePart.USER_MESSAGES
                    "assistant" -> if (message["tool_calls"] != null) ContextUsagePart.TOOL_TRAJECTORY else ContextUsagePart.ASSISTANT_MESSAGES
                    "tool", "function" -> ContextUsagePart.TOOL_TRAJECTORY
                    else -> ContextUsagePart.OTHER_MESSAGES
                }
                add(part, tokens + (message["tool_calls"]?.let { estimateLocalTextTokens(gson.toJson(it)) } ?: 0))
            }
        }
        val reported = (usage["prompt"] as? Number)?.toInt()
            ?: (usage["prompt_tokens"] as? Number)?.toInt()
            ?: (usage["input_tokens"] as? Number)?.toInt()
        return CompanionRequestSnapshot(ContextUsageBreakdown(sums.map { (part, values) ->
            ContextUsagePartTokens(part, values.first, values.second)
        }), sentAt, maxContext, reported)
    }
}
