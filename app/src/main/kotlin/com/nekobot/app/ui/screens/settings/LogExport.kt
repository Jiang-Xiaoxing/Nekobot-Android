package com.nekobot.app.ui.screens.settings

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun logExportFileName(now: Date = Date()): String =
    "Nekobot_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(now)}.log"

/** 输入与页面一致为新到旧；输出从早到晚，同一时刻仍保留原始追加顺序及重复记录。 */
internal fun formatLogExport(logs: List<LogEntry>): String = logs.asReversed().sortedBy { it.time }
    .joinToString(separator = "\n", postfix = if (logs.isEmpty()) "" else "\n") { entry ->
        val time = entry.time.replace(Regex("[\r\n\t]"), " ")
        val level = (if (entry.level.equals("warning", true)) "WARN" else entry.level.uppercase(Locale.ROOT))
            .replace(Regex("[\r\n\t]"), " ")
        val message = entry.message.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n    ")
        "$time [$level] $message"
    }

internal fun writeLogExport(logs: List<LogEntry>, output: OutputStream) {
    output.writer(Charsets.UTF_8).use { it.write(formatLogExport(logs)) }
}
