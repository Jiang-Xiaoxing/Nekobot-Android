package com.nekobot.app.ui.screens.settings

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class LogExportTest {
    @Test fun chronologicalExportIncludesAllLevelsTagsAndMultiLineMessages() {
        val logs = listOf(
            LogEntry("2026-10-15 12:02:01.123", "error", "[Relationship] 第一行\r\n第二行"),
            LogEntry("2026-10-15 12:01:00.000", "warning", "[LocalAiClient] 警告"),
            LogEntry("2026-10-14 23:59:59.999", "debug", "[DB] 保存完成"))
        assertEquals("2026-10-14 23:59:59.999 [DEBUG] [DB] 保存完成\n" +
            "2026-10-15 12:01:00.000 [WARN] [LocalAiClient] 警告\n" +
            "2026-10-15 12:02:01.123 [ERROR] [Relationship] 第一行\n    第二行\n", formatLogExport(logs))
        assertEquals("error", logs.first().level)
    }

    @Test fun sameMillisecondOrderAndDuplicateEntriesArePreserved() {
        val first = LogEntry("2026-10-15 12:00:00.000", "info", "先发生")
        val last = first.copy(message = "后发生")
        val text = formatLogExport(listOf(last, first, first))
        assertTrue(text.indexOf("先发生") < text.indexOf("后发生"))
        assertEquals(2, Regex("先发生").findAll(text).count())
    }

    @Test fun utf8ExportKeepsChineseAndLongMessagesWithoutTruncation() {
        val message = "中文日志🌟".repeat(2000)
        val output = ByteArrayOutputStream()
        val logs = listOf(LogEntry("2026-10-15", "info", message))
        writeLogExport(logs, output)
        assertEquals(formatLogExport(logs), output.toByteArray().toString(Charsets.UTF_8))
        assertTrue(output.toByteArray().toString(Charsets.UTF_8).contains(message))
    }

    @Test fun emptySnapshotAndLogExtensionAreExplicit() {
        assertEquals("", formatLogExport(emptyList()))
        assertTrue(Regex("Nekobot_\\d{8}_\\d{6}\\.log").matches(logExportFileName()))
    }
}
