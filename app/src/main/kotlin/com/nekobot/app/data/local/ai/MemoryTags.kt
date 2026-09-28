package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

/** 角色记忆与经历档案共用的轻量标签规范化。 */
object MemoryTags {
    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type
    private val whitespace = Regex("\\s+")

    fun normalize(raw: Iterable<*>): List<String> = raw.mapNotNull { item ->
        (item as? String)
            ?.trim()
            ?.replace(whitespace, " ")
            ?.lowercase(Locale.ROOT)
            ?.take(40)
            ?.takeIf(String::isNotBlank)
    }.distinct()

    /** 自动提取只留少量主题词；用户手工维护的标签不在这里裁掉。 */
    fun normalizeGenerated(raw: Iterable<*>): List<String> = normalize(raw).take(5)

    fun toJson(raw: Iterable<*>): String = gson.toJson(normalize(raw))

    fun fromJson(raw: String?): List<String> = if (raw.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { normalize(gson.fromJson<List<String>>(raw, listType).orEmpty()) }
            .getOrDefault(emptyList())
    }
}
