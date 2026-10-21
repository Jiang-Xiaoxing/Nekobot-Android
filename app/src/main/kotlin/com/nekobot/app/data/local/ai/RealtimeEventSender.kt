package com.nekobot.app.data.local.ai

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.util.UUID
import okhttp3.WebSocket

/** 只记录事件编号与类型，避免把密钥、对话正文或音频写入诊断信息。 */
internal class RealtimeEventSender(private val attachEventIds: Boolean) {
    private val gson = GsonBuilder().serializeNulls().create()
    private val eventTypes = linkedMapOf<String, String>()
    private val audioEventTypes = linkedMapOf<String, String>()

    @Synchronized
    fun send(socket: WebSocket, event: JsonObject): Boolean {
        val outgoing = event.deepCopy()
        if (attachEventIds) {
            val eventId = "event_${UUID.randomUUID()}"
            outgoing.addProperty("event_id", eventId)
            val type = outgoing.get("type").asString
            // 音频分块单独限量，防止长录音挤掉 session/history 的错误关联。
            val target = if (type == "input_audio_buffer.append") audioEventTypes else eventTypes
            target[eventId] = type
            while (target.size > 256) target.remove(target.keys.first())
        }
        return socket.send(gson.toJson(outgoing))
    }

    @Synchronized
    fun describeError(event: JsonObject, fallbackEventType: String? = null): String {
        val error = event.get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: event
        fun value(key: String): String? = error.get(key)
            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        val source = value("event_id")?.let { eventTypes[it] ?: audioEventTypes[it] } ?: fallbackEventType
        val details = listOfNotNull(source, value("code"), value("param"))
            .distinct().joinToString("; ")
        val message = value("message")
            ?: event.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "Realtime 服务返回错误"
        return if (details.isBlank()) message else "$message [$details]"
    }
}
