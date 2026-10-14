package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort

/** 内部请求标记，由各协议白名单过滤，不进入服务商的请求体。 */
internal const val CHARACTER_THINKING_EXTRA = "_character_thinking"

/** 角色视角只约束 system 与显示解析，不改写用户选择的推理档位或自定义参数。 */
internal fun putReasoningParams(
    extra: MutableMap<String, Any?>,
    effort: ReasoningEffort,
    characterThinking: Boolean = false,
    custom: () -> CustomReasoningParam
) {
    extra["reasoning_effort"] = effort.wireValue
    if (characterThinking) extra[CHARACTER_THINKING_EXTRA] = true
    if (effort == ReasoningEffort.CUSTOM) extra[CustomReasoningParam.EXTRA_KEY] = custom()
}
