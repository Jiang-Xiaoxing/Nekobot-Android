package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptStackStripSectionTest {
    private val key = "output.thinking_style"

    @Test
    fun untitledTextAfterStrippedSectionIsKept() {
        for (heading in listOf("## $key", "[$key]")) {
            val prompt = "角色卡\n\n$heading\n旧思考\n第二行\n\n这段无标题文字\n\n## next\n保留"
            assertEquals("角色卡\n\n这段无标题文字\n\n## next\n保留", PromptStack.stripSection(prompt, key))
        }
    }

    @Test
    fun sectionAtStartOrEndIsRemovedAndOthersKept() {
        assertEquals("## a\n甲", PromptStack.stripSection("## a\n甲\n\n## $key\n旧思考", key))
        assertEquals("[b]\n乙", PromptStack.stripSection("[$key]\n旧思考\n\n[b]\n乙", key))
        assertEquals("## other\n不动", PromptStack.stripSection("## other\n不动", key))
    }
}