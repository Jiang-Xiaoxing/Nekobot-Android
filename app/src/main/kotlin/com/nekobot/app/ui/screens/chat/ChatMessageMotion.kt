package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A streamed reply and its saved replacement occupy the same reply slot. */
internal fun messageMotionKeys(messages: List<Message>): List<String> {
    var user = "initial"
    var reply = 0
    val repeatedTimes = mutableMapOf<String, Int>()
    return messages.mapIndexed { index, message ->
        if (message.isUser) {
            val stamp = message.timestamp ?: message.id ?: "position:$index"
            val occurrence = repeatedTimes.getOrDefault(stamp, 0)
            repeatedTimes[stamp] = occurrence + 1
            user = "user:$stamp:$occurrence"
            reply = 0
            user
        } else {
            "reply:$user:${reply++}"
        }
    }
}

/** Lives only while a chat is open. History and off/on toggles never replay entrances. */
internal class MessageMotionRegistry(private val openedAtMillis: Long) {
    private val shown = mutableSetOf<String>()

    fun claim(key: String, segment: Int, timestamp: String?, enabled: Boolean): Boolean {
        if (!shown.add("$key:$segment")) return false
        if (!enabled) return false
        val time = messageMotionTime(timestamp) ?: return false
        return time >= openedAtMillis
    }
}

internal fun messageMotionTime(value: String?): Long? {
    val text = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    text.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
}
