package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort

/** 内部请求标记，由各协议白名单过滤，不进入服务商的请求体。 */
internal const val FAKE_CHARACTER_THINKING_EXTRA = "_fake_character_thinking"

/** 伪思考只覆盖本次请求；关闭时继续使用原档位和自定义参数。 */
internal fun putReasoningParams(
    extra: MutableMap<String, Any?>,
    effort: ReasoningEffort,
    fakeCharacterThinking: Boolean = false,
    custom: () -> CustomReasoningParam
) {
    extra["reasoning_effort"] = if (fakeCharacterThinking) ReasoningEffort.NONE.wireValue else effort.wireValue
    if (fakeCharacterThinking) {
        extra[FAKE_CHARACTER_THINKING_EXTRA] = true
        extra.remove(CustomReasoningParam.EXTRA_KEY)
    } else if (effort == ReasoningEffort.CUSTOM) extra[CustomReasoningParam.EXTRA_KEY] = custom()
}
