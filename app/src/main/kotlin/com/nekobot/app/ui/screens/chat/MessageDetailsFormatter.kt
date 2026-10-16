package com.nekobot.app.ui.screens.chat

import com.nekobot.app.R

import com.nekobot.app.data.model.Message
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class MessageDetails(
    val sentAt: String,
    val model: String,
    val inputTokens: String,
    val outputTokens: String,
    val totalTokens: String,
    val duration: String,
    val speed: String
)

/** 时区由调用方提供；不读时钟、不猜缺失日期、不修改消息。 */
internal fun formatMessageTimestamp(timestamp: String?, zone: ZoneId, unrecorded: String): String {
    val raw = timestamp?.trim()?.takeIf { it.isNotEmpty() } ?: return unrecorded
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    val numeric = raw.toLongOrNull()
    if (numeric != null) return runCatching {
        val instant = if (numeric >= 100_000_000_000L || numeric <= -100_000_000_000L)
            Instant.ofEpochMilli(numeric) else Instant.ofEpochSecond(numeric)
        formatter.format(instant.atZone(zone))
    }.getOrDefault(unrecorded)
    val normalized = raw.replace(' ', 'T')
    val instant = runCatching { Instant.parse(normalized) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(normalized).toInstant() }.getOrNull()
    if (instant != null) return formatter.format(instant.atZone(zone))
    return runCatching { formatter.format(LocalDateTime.parse(normalized)) }.getOrDefault(unrecorded)
}

internal fun formatMessageDetails(message: Message, zone: ZoneId, text: (Int) -> String): MessageDetails {
    val unrecorded = text(R.string.single_role_not_recorded)
    val input = message.inputTokens?.takeIf { it >= 0 }
    val output = message.outputTokens?.takeIf { it >= 0 }
    val total = if (input != null && output != null) input.toLong() + output.toLong()
        else message.tokens?.takeIf { it >= 0 }?.toLong()
    val duration = message.durationMs?.takeIf { it.isFinite() && it > 0 }
    val speed = if (output != null && duration != null) (output * 1000.0 / duration).takeIf { it.isFinite() } else null
    fun count(value: Long?): String = value?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } ?: unrecorded
    return MessageDetails(
        formatMessageTimestamp(message.timestamp, zone, unrecorded), message.model?.trim()?.takeIf { it.isNotEmpty() } ?: unrecorded,
        count(input?.toLong()), count(output?.toLong()), count(total),
        duration?.let { String.format(Locale.US, text(R.string.single_role_1f_s), it / 1000.0) } ?: unrecorded,
        speed?.let { String.format(Locale.US, text(R.string.single_role_1f_tokens_s), it) } ?: unrecorded
    )
}
