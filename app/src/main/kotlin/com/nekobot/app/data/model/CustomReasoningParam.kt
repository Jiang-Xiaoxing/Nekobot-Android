package com.nekobot.app.data.model

import com.google.gson.JsonParser

/**
 * 用户自定义的思考参数：选择“自定义”思考强度时，发送请求用这组参数替换自动换算的思考参数。
 * [path] 支持点号路径（如 reasoning.effort）；[value] 按 JSON 类型解析：数字、true/false、对象/数组保持原类型，其余按字符串。
 */
data class CustomReasoningParam(val path: String = "", val value: String = "") {
    val segments: List<String> get() = path.trim().split('.').map(String::trim)

    /** 不合法时返回原因；合法返回 null。 */
    fun validationError(): String? = when {
        path.isBlank() -> "请填写参数名"
        segments.any(String::isEmpty) -> "参数名格式不正确"
        segments.first().lowercase() in FORBIDDEN_ROOTS -> "不能覆盖 ${segments.first()}"
        value.isBlank() -> "请填写参数值"
        else -> null
    }

    val isValid: Boolean get() = validationError() == null

    fun typedValue(): Any {
        val raw = value.trim()
        return when {
            raw == "true" -> true
            raw == "false" -> false
            raw.toLongOrNull() != null -> raw.toLong()
            raw.toDoubleOrNull() != null && raw.any { it.isDigit() } -> raw.toDouble()
            raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') -> raw.substring(1, raw.length - 1)
            raw.startsWith("{") || raw.startsWith("[") ->
                runCatching { com.google.gson.Gson().fromJson(JsonParser.parseString(raw), Any::class.java) }
                    .getOrNull() ?: raw
            else -> raw
        }
    }

    /** 把参数写入请求体（深拷贝路径上的对象，不修改原 payload）。不合法时原样返回。 */
    fun applyTo(payload: Map<String, Any>): Map<String, Any> {
        if (!isValid) return payload
        @Suppress("UNCHECKED_CAST")
        fun put(map: Map<String, Any>, keys: List<String>, v: Any): Map<String, Any> =
            LinkedHashMap(map).apply {
                val key = keys.first()
                if (keys.size == 1) put(key, v)
                else put(key, put((this[key] as? Map<String, Any>) ?: emptyMap(), keys.drop(1), v))
            }
        return put(payload, segments, typedValue())
    }

    companion object {
        /** 请求核心字段，自定义参数不得覆盖。 */
        val FORBIDDEN_ROOTS = setOf("model", "messages", "stream", "stream_options", "tools", "tool_choice",
            "input", "contents", "system", "system_instruction", "systeminstruction")
        /** 在请求 extra 中携带自定义参数的键。 */
        const val EXTRA_KEY = "reasoning_custom"
    }
}
