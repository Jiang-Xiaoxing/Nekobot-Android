package com.nekobot.app.data.local.ai

/**
 * 思考提示词注入：放到请求最末尾——最后一条用户消息末尾一次 + 请求最后一条 system 一次。
 *
 * 实测（硅基 GLM-5.3，当前应用真实 81 条消息 / 11 工具 / 流式上下文，2026-10-20）：
 * 默认英文原生 reasoning；只在请求最底部放规则约 4/6–5/6 稳定，仍有英文长思考；
 * 同一段文本双处注入后 16/16 次思考全部为简体中文，工具调用正常。
 *
 * 只改发送副本，不回写数据库；重复调用不会叠加。普通角色与单会话共用。
 */
internal fun withCharacterThinkingRule(
    messages: List<Map<String, Any>>,
    prompt: String?,
    enabled: Boolean
): List<Map<String, Any>> {
    val rule = prompt?.trim().orEmpty()
    if (!enabled || rule.isEmpty() || messages.isEmpty()) return messages
    // 已注入过（工具循环重复调用）就不再叠加。
    if (messages.any { it["role"] == "system" && it["content"] == rule }) return messages
    val lastUserIndex = messages.indexOfLast { it["role"] == "user" }
    val patched = messages.mapIndexed { index, message ->
        if (index == lastUserIndex) {
            (message["content"] as? String)?.let { content ->
                message + ("content" to content + "\n\n" + rule)
            } ?: message
        } else {
            message
        }
    }
    return patched + mapOf("role" to "system", "content" to rule)
}
