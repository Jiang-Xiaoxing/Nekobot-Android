package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTagsTest {
    @Test
    fun normalizesCaseWhitespaceAndDuplicatesWithoutMergingDifferentTopics() {
        assertEquals(
            listOf("中秋", "moon cake", "月饼"),
            MemoryTags.normalize(listOf(" 中秋 ", "Moon   Cake", "moon cake", "月饼"))
        )
    }

    @Test
    fun oldMemoryWithoutTagsReturnsEmptyList() {
        assertEquals(emptyList<String>(), MemoryTags.fromJson(null))
        assertEquals(emptyList<String>(), MemoryTags.fromJson("invalid"))
    }

    @Test
    fun manualTagsAreNotSilentlyLimitedToGeneratedTagCount() {
        val manual = listOf("甲", "乙", "丙", "丁", "戊", "己")
        assertEquals(manual, MemoryTags.fromJson(MemoryTags.toJson(manual)))
        assertEquals(5, MemoryTags.normalizeGenerated(manual).size)
    }
}
