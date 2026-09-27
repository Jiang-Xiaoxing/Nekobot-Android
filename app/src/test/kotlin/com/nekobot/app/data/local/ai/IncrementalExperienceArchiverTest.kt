package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalExperienceArchiverTest {
    private fun message(id: String, role: String, content: String = id, date: String = "2026-09-27T10:00:00Z") =
        ExperienceSourceMessage(id, role, content, date)

    @Test
    fun keepsCompleteTurnsAndLeavesUnfinishedTailForNextRun() {
        val split = ExperienceSegmenter.split(
            listOf(message("u1", "user"), message("a1", "assistant"), message("u2", "user"))
        )
        assertEquals(listOf("u1", "a1"), split.segments.single().messages.map { it.id })
        assertEquals(listOf("u2"), split.unprocessed.map { it.id })
    }

    @Test
    fun duplicateTimestampsUseInputOrderAndSegmentCapPreservesRemainder() {
        val input = listOf(
            message("u1", "user"), message("a1", "assistant"),
            message("u2", "user"), message("a2", "assistant"),
            message("u3", "user"), message("a3", "assistant")
        )
        val split = ExperienceSegmenter.split(input, maxTurnsPerSegment = 1, maxSegments = 2)
        assertEquals(listOf("u1", "a1"), split.segments[0].messages.map { it.id })
        assertEquals(listOf("u2", "a2"), split.segments[1].messages.map { it.id })
        assertEquals(listOf("u3", "a3"), split.unprocessed.map { it.id })
    }

    @Test
    fun longTurnRemainsWholeAndFingerprintChangesWhenOriginalTextChanges() {
        val original = ExperienceSegmenter.split(
            listOf(message("u1", "user", "甲".repeat(1000)), message("a1", "assistant", "乙")),
            maxCharsPerSegment = 10
        ).segments.single()
        assertEquals(2, original.messages.size)
        val edited = original.copy(messages = original.messages.map {
            if (it.id == "u1") it.copy(content = "甲".repeat(999) + "丙") else it
        })
        assertNotEquals(ExperienceSegmenter.fingerprint(original), ExperienceSegmenter.fingerprint(edited))
    }

    @Test
    fun standaloneAssistantOpeningIsArchivedWithoutDroppingOriginal() {
        val split = ExperienceSegmenter.split(
            listOf(message("greeting", "assistant"), message("u1", "user"), message("a1", "assistant"))
        )
        assertEquals(listOf("greeting"), split.segments[0].messages.map { it.id })
        assertEquals(listOf("u1", "a1"), split.segments[1].messages.map { it.id })
        assertEquals(1, split.segments[0].turnCount)
        assertTrue(split.unprocessed.isEmpty())
    }

    @Test
    fun parsesSummaryAndTagsFromOneModelOutput() {
        val result = ExperienceSummaryParser.parse("""```json
            {"summary":"中秋一起挑了月饼，礼物细节需核对。","tags":[" 中秋 ","月饼","月饼"]}
            ```""".trimIndent())
        assertEquals("中秋一起挑了月饼，礼物细节需核对。", result.summary)
        assertEquals(listOf("中秋", "月饼"), result.tags)
        assertTrue(ExperienceSummaryParser.parse("""{"summary":"聊了近况","tags":[]}""").tags.isEmpty())
    }
}
