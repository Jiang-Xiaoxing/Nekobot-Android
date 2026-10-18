package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.CompanionMemoryEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 仅供单会话角色的发送格式；不改写旧记忆或历史正文。 */
internal object CompanionPromptLanguage {
    const val diaryRule = "记忆正文用角色第一人称、日记口吻写成一句话，以‘我’自称，写清对方和实际参与者；忠于原话，不把独自经历写成共同经历。日期单独依据原话填写，未知就留空，不在正文里编日期。"
    const val recallRules = "想不起来的事，可以先翻一翻记忆再回答；翻过还是没有，就老实说记不清了，不要编。和记忆对不上时，自然地问一句确认；已经确认过的事就接受，不反复追问。"
    const val styleReminder = "像平时和对方聊天一样，用口语，简短自然；想分成几条短消息时，用<||>隔开。别用总结、汇报或记账的语气，也别列清单；不确定或忘了就直说，不要复述设定和规则。"

    fun memory(memory: CompanionMemoryEntity, today: LocalDate = LocalDate.now()): String {
        val text = memory(memory.content, memory.eventDate, memory.annualMonthDay, today)
        // 纯文本发送副本也必须保留未核验声明；存储正文和已核验记忆不加说明。
        return if (!memory.manual && memory.provenance == CompanionImportPolicy.IMPORTED_UNVERIFIED) {
            "（${CompanionImportPolicy.evidence(memory)}）$text"
        } else text
    }

    fun memory(content: String, eventDate: String? = null, annualMonthDay: String? = null,
        today: LocalDate = LocalDate.now()): String {
        val date = eventDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val annual = annualMonthDay?.let { runCatching { MonthDay.parse("--$it") }.getOrNull() }
        val prefix = annual?.let { "${it.monthValue}月${it.dayOfMonth}日：" } ?: date?.let { when (it) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            today.minusDays(2) -> "前天"
            else -> (if (it.year == today.year) "" else "${it.year}年") + "${it.monthValue}月${it.dayOfMonth}日"
        } + "：" }
            .orEmpty()
        return prefix + content.replace(Regex("[\\r\\n]+"), " ").trim()
    }

    fun memories(rows: List<CompanionMemoryEntity>, today: LocalDate = LocalDate.now()): String = rows.joinToString("\n") { memory(it, today) }

    fun stickers(names: List<String>): String {
        // 不改名称这个匹配键；无法在正文中无歧义表达的名字不列出。
        val usable = names.filter { it.isNotBlank() && it.length <= 60 && it.none { c -> c in "[]\r\n" } }
            .distinct().take(60)
        if (usable.isEmpty()) return ""
        return "你可以发表情，在正文里写[表情名]就行，现在能用的有：" +
            usable.joinToString("、") { "[$it]" } + "。偶尔用，别每句都发。"
    }

    fun clock(now: ZonedDateTime, previous: LocalMessageEntity?): String {
        val period = when (now.hour) { in 0..5 -> "凌晨"; in 6..11 -> "上午"; in 12..17 -> "下午"; else -> "晚上" }
        val stamp = now.format(DateTimeFormatter.ofPattern("uuuu年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)) +
            " $period${now.format(DateTimeFormatter.ofPattern("h:mm", Locale.ROOT))}"
        val elapsed = previous?.let { sentAt(it, now) }?.takeIf { !it.isAfter(now.toInstant()) }
            ?.let { "，距离你们上一条消息过去了${interval(Duration.between(it, now.toInstant()))}" }.orEmpty()
        return "现在是$stamp$elapsed。这只是让你把握语气的参考，不用主动提起。"
    }

    fun interval(duration: Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        return when {
            minutes < 1 -> "不到1分钟"
            minutes < 60 -> "${minutes}分钟"
            minutes < 1440 -> "${minutes / 60}小时" + (minutes % 60).takeIf { it > 0 }?.let { "${it}分钟" }.orEmpty()
            else -> "${minutes / 1440}天" + (minutes % 1440 / 60).takeIf { it > 0 }?.let { "${it}小时" }.orEmpty()
        }
    }

    private fun sentAt(row: LocalMessageEntity, now: ZonedDateTime): Instant? =
        listOf(row.createdAt, row.timestamp).firstNotNullOfOrNull { value ->
            if (value.length > 64) null else runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(value.replace(' ', 'T')).atZone(now.zone).toInstant() }.getOrNull()
                ?: value.takeIf { it.matches(Regex("\\d{10}|\\d{13}")) }?.toLongOrNull()?.let {
                    runCatching { if (value.length == 13) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }.getOrNull()
                }
        }

    /** 回查去重和计费仍用内部结构，只有发送副本转成人话。原话读取保留分页所需结构。 */
    fun message(message: Map<String, Any>): Map<String, Any> {
        val cleaned = CompanionRequestUsage.clean(message)
        if (message["role"] != "tool" || message["name"] !in setOf("agent_memory_read", "agent_recall_search")) return cleaned
        val result = runCatching { Gson().fromJson(message["content"] as? String, Map::class.java) }.getOrNull() ?: return cleaned
        val rows = (result["memories"] ?: result["results"]) as? List<*>
        val body = rows.orEmpty().mapNotNull {
            val row = it as? Map<*, *> ?: return@mapNotNull null
            val content = row["content"] as? String ?: return@mapNotNull null
            // 兼容已保存的工具结果：它们的正文尚未带来源说明，不能在转纯文本时丢掉 evidence。
            val notice = "（${CompanionImportPolicy.UNVERIFIED_EVIDENCE}）"
            if (row["evidence"] == CompanionImportPolicy.UNVERIFIED_EVIDENCE && !content.startsWith(notice)) {
                notice + content
            } else content
        }.joinToString("\n")
        val note = when {
            result["success"] == false -> "这次没能翻到记忆，记不清的地方就直说。"
            body.isNotBlank() -> body
            result["visible_duplicates"] != null -> "相关内容前面已经翻到了，可以接着那些回忆说。"
            result["recall_stopped"] == true -> "先用已经想起来的事回答，记不清的地方就直说。"
            else -> "这次没有找到相关记忆；不代表这件事没有发生过。"
        }
        return cleaned + ("content" to note)
    }
}
