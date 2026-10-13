package com.nekobot.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.nekobot.app.data.model.Message
import com.nekobot.app.data.model.MessagePage

/** 新后端直接分页；旧后端的数组响应由仓库保留为兼容缓存。 */
internal fun parseRemoteMessagePage(payload: JsonElement, gson: Gson): MessagePage {
    val value = payload.asJsonObject
    val messages = value.getAsJsonArray("messages") ?: error("Invalid message page")
    return MessagePage(
        messages = messages.map { gson.fromJson(it, Message::class.java) },
        hasMore = value.get("has_more")?.asBoolean ?: false
    )
}
