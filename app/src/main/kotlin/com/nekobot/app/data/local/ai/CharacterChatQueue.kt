package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity

/** null 才表示沿用默认。显式配置损坏、为空或全部失效都不能扩大到全局队列。 */
internal object CharacterChatQueue {
    fun ids(raw: String?): List<String>? {
        if (raw == null) return null
        return try {
            val array = JsonParser.parseString(raw).asJsonArray
            val ids = array.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
            require(ids.size in 1..100 && ids.all { it.isNotBlank() } && ids.distinct().size == ids.size)
            ids
        } catch (e: Exception) {
            throw IllegalStateException("角色的模型队列配置无效，请在角色设置中重新保存队列。")
        }
    }

    fun encode(ids: List<String>?): String? = ids?.let {
        val raw = Gson().toJson(it)
        CharacterChatQueue.ids(raw)
        raw
    }

    fun resolve(raw: String?, available: List<LocalAiModelEntity>): List<LocalAiModelEntity>? {
        val chosen = ids(raw) ?: return null
        val byId = available.associateBy { it.id }
        return chosen.mapNotNull { id -> byId[id]?.takeIf { it.enabled && it.purpose == "chat" } }
            .also { require(it.isNotEmpty()) { "这个角色的模型队列全部不可用，请检查模型是否已删除、停用或更改用途。" } }
    }

    fun extraFor(model: LocalAiModelEntity, shared: Map<String, Any?>): Map<String, Any?> = buildMap {
        putAll(shared - setOf("temperature", "max_tokens", "top_p"))
        model.temperature?.let { put("temperature", it) }
        model.maxTokens?.let { put("max_tokens", it) }
        model.topP?.let { put("top_p", it) }
    }

    fun providerLabel(model: LocalAiModelEntity): String = model.provider?.takeIf(String::isNotBlank)
        ?: runCatching { java.net.URI(model.baseUrl).host }.getOrNull()?.takeIf(String::isNotBlank)
        ?: "自定义服务商"
}

internal data class CharacterQueueModel(val id: String, val name: String, val provider: String,
    val model: String, val enabled: Boolean, val chat: Boolean)
internal data class CharacterQueueSettings(val name: String, val selected: List<String>?,
    val models: List<CharacterQueueModel>, val defaults: List<String>, val error: String? = null)
