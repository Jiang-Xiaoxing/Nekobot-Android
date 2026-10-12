package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort

/** 写入思考参数；自定义档额外携带用户的参数名/值，由 LocalAiClient 跳过自动换算后写入请求体。 */
internal fun putReasoningParams(extra: MutableMap<String, Any?>, effort: ReasoningEffort, custom: () -> CustomReasoningParam) {
    extra["reasoning_effort"] = effort.wireValue
    if (effort == ReasoningEffort.CUSTOM) extra[CustomReasoningParam.EXTRA_KEY] = custom()
}
