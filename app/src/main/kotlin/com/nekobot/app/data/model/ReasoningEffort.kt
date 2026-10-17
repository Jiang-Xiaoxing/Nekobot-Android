package com.nekobot.app.data.model

/** 单次 AI 请求的思考强度。存储值同时作为通用协议值使用。 */
enum class ReasoningEffort(val wireValue: String, val displayName: String) {
    NONE("none", "None"),
    MINIMAL("minimal", "Minimal"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    MAX("max", "Max"),
    /** 用户自定义参数名与参数值，见 [CustomReasoningParam]。 */
    CUSTOM("custom", "自定义");

    companion object {
        fun fromValue(value: String?): ReasoningEffort =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: NONE
    }
}
