package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.CompanionRelationshipEventEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import java.util.UUID

/** 容忍模型的解释/代码围栏，但不放宽来源、纠错归属和重复事件校验。 */
internal object CompanionRelationshipResponse {
    private val gson = Gson()
    data class Events(val accepted: List<CompanionRelationshipEventEntity>, val discardedKeys: Set<String>)

    fun json(content: String): JsonObject {
        for (start in content.indices.filter { content[it] == '{' }) {
            var depth = 0
            var quoted = false
            var escaped = false
            for (end in start until content.length) {
                val ch = content[end]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') quoted = false
                } else when (ch) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val obj = runCatching { JsonParser.parseString(content.substring(start, end + 1)).asJsonObject }.getOrNull()
                            if (obj?.get("format")?.let { it.isJsonPrimitive && it.asString == CompanionRelationshipNarrative.FORMAT } == true) return obj
                            break
                        }
                    }
                }
            }
        }
        throw IllegalArgumentException("评估模型没有返回有效的文字 JSON，旧状态保留")
    }

    fun events(json: JsonObject, evidence: Map<String, LocalMessageEntity>, old: List<CompanionRelationshipEventEntity>,
        sessionId: String, revision: Long, now: Long, correctionKey: String? = null): Events {
        val proposals = json["events"]
        require(proposals?.isJsonArray == true) { "评估结果缺少事件列表" }
        val accepted = mutableListOf<CompanionRelationshipEventEntity>()
        val discarded = mutableSetOf<String>()
        val keys = mutableSetOf<String>()
        val sourceSets = mutableSetOf<List<String>>()
        for (item in proposals.asJsonArray) {
            val event = runCatching {
                val obj = item.asJsonObject
                val key = CompanionRelationshipNarrative.text(obj, "key", 100)
                require(key !in keys && accepted.size < 6) { "事件标识重复或事件过多" }
                val prior = old.firstOrNull { it.eventKey == key }
                if (correctionKey != null) require(key == correctionKey) { "纠错不能改动其他事件" }
                require(prior == null || correctionKey == key) { "已处理的事件不重复记录" }
                val kind = CompanionRelationshipNarrative.text(obj, "kind", 20)
                require(kind in setOf("reliability", "comfort", "familiarity", "boundary", "other")) { "事件类型无效" }
                val impacts = CompanionRelationshipNarrative.impacts(obj)
                require(correctionKey != null || impacts.isNotEmpty()) { "没有关系影响时不必新增事件" }
                val reason = CompanionRelationshipNarrative.text(obj, "reason", 500)
                val summary = CompanionRelationshipNarrative.text(obj, "summary", 200)
                val sources = CompanionRelationshipNarrative.sources(obj["sources"], evidence)
                val ids = sources.map { it.id }.sorted()
                require(ids !in sourceSets) { "重复引用同一份材料新增事件" }
                require(prior != null || old.none { previous ->
                    JsonParser.parseString(previous.sourcesJson).asJsonArray.map { it.asJsonObject["id"].asString }.sorted() == ids
                }) { "这份原话已有关系记录" }
                val result = CompanionRelationshipEventEntity(UUID.randomUUID().toString(), sessionId, key, revision,
                    summary, reason, kind, "{}", gson.toJson(sources), now, prior != null, gson.toJson(impacts))
                keys.add(key)
                sourceSets.add(ids)
                result
            }.getOrNull()
            if (event != null) accepted.add(event)
            else runCatching { item.asJsonObject["key"].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim() }
                .getOrNull()?.let(discarded::add)
        }
        return Events(accepted, discarded - keys)
    }
}
