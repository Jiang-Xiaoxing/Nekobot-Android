package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val historyDateTimePattern = Regex(
    "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2}(?::\\d{2})?)?"
)
private val historyEpochPattern = Regex("(?:\\d{10}|\\d{13})")
private val historyDatePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

/**
 * Render a persisted Agent utterance for model input only. The creation date is the
 * history cursor's date. Naive dates retain their calendar day; absolute instants use
 * the device's calendar day, matching TimeContext. Only YYYY-MM-DD goes on the wire.
 * Fall back to the timestamp only after validation; never substitute the current time.
 * Always render from the original database row, so rebuilding context adds one prefix.
 * Summaries and tool traces have their own semantics and are not dated as utterances.
 */
internal fun formatAgentHistoryMessageContent(
    message: LocalMessageEntity,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    if (message.role.lowercase() !in listOf("user", "human", "assistant", "ai")) return message.content
    val date = validatedHistoryDate(message.createdAt, zoneId)
        ?: validatedHistoryDate(message.timestamp, zoneId)
        ?: validatedHistoryEpoch(message.timestamp, zoneId)
        ?: return message.content
    return "【历史消息日期：$date】\n${message.content}"
}

/** Only parse a bounded date value; malformed fields cannot become prompt instructions. */
private fun validatedHistoryDate(value: String, zoneId: ZoneId): LocalDate? {
    if (value.length > 64) return null
    val text = value.trim()
    if (historyDatePattern.matches(text)) {
        return runCatching { LocalDate.parse(text) }.getOrNull()?.takeIf { it.year in 1..9999 }
    }
    val match = historyDateTimePattern.matchEntire(text) ?: return null
    val isoText = text.replace(' ', 'T')
    return runCatching {
        if (match.groupValues[2].isNotEmpty()) {
            OffsetDateTime.parse(isoText, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .atZoneSameInstant(zoneId).toLocalDate()
        } else {
            LocalDateTime.parse(isoText, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate()
        }
    }.getOrNull()?.takeIf { it.year in 1..9999 }
}

/** Unix timestamps are absolute instants; use the device zone before taking the day. */
private fun validatedHistoryEpoch(value: String, zoneId: ZoneId): LocalDate? {
    if (value.length > 64) return null
    val text = value.trim()
    if (!historyEpochPattern.matches(text)) return null
    val number = text.toLongOrNull() ?: return null
    return runCatching {
        (if (text.length == 13) Instant.ofEpochMilli(number) else Instant.ofEpochSecond(number))
            .atZone(zoneId).toLocalDate()
    }.getOrNull()
}

/** Shared by extraction and recall; an unknown utterance day must remain unknown. */
internal fun agentUtteranceDay(message: LocalMessageEntity, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate? =
    validatedHistoryDate(message.createdAt, zoneId) ?: validatedHistoryDate(message.timestamp, zoneId)
        ?: validatedHistoryEpoch(message.timestamp, zoneId)
