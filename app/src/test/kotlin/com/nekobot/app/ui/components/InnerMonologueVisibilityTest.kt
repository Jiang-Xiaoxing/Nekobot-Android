package com.nekobot.app.ui.components

import com.google.gson.Gson
import com.nekobot.app.data.local.db.LocalSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerMonologueVisibilityTest {

    private val text = "早上好呀（内心：今天也想见你）\n要一起吃早饭吗？"

    @Test
    fun showByDefaultKeepsInnerBlocks() {
        val blocks = visibleMarkdownBlocks(text, chatMode = true, showInnerMonologue = true)
        assertTrue(blocks.any { it is MdBlock.InnerMonologue && it.content == "今天也想见你" })
    }

    @Test
    fun hiddenSkipsInnerBlocksButKeepsOtherText() {
        val blocks = visibleMarkdownBlocks(text, chatMode = true, showInnerMonologue = false)
        assertFalse(blocks.any { it is MdBlock.InnerMonologue })
        val joined = blocks.filterIsInstance<MdBlock.Paragraph>().joinToString("|") { it.content }
        assertTrue(joined.contains("早上好呀"))
        assertTrue(joined.contains("要一起吃早饭吗？"))
        assertFalse(joined.contains("内心"))
    }

    @Test
    fun hiddenOnlyInnerLineLeavesNoEmptyBlock() {
        val blocks = visibleMarkdownBlocks("（心想：好紧张）\n你好", chatMode = true, showInnerMonologue = false)
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph("你好")), blocks)
    }

    @Test
    fun streamingUnclosedInnerTailIsHiddenWhenOff() {
        val streaming = "嗯嗯（内心：其实我"
        val hidden = visibleMarkdownBlocks(streaming, chatMode = true, showInnerMonologue = false)
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph("嗯嗯")), hidden)
        // 开启时保持旧行为：未闭合文本照常显示
        val shown = visibleMarkdownBlocks(streaming, chatMode = true, showInnerMonologue = true)
        assertTrue(shown.filterIsInstance<MdBlock.Paragraph>().any { it.content.contains("其实我") })
    }

    @Test
    fun codeSpanIsNotTreatedAsInner() {
        assertEquals("看 `（内心：x", stripUnclosedInnerTail("看 `（内心：x"))
        val blocks = visibleMarkdownBlocks("代码 `（内心：x）` 示例", chatMode = true, showInnerMonologue = false)
        assertTrue(blocks.filterIsInstance<MdBlock.Paragraph>().any { it.content.contains("（内心：x）") })
    }

    @Test
    fun nonChatModeUnaffected() {
        assertEquals(parseBlocks(text, false), visibleMarkdownBlocks(text, chatMode = false, showInnerMonologue = false))
    }

    @Test
    fun oldBackupWithoutFieldDefaultsToShow() {
        val gson = Gson()
        val json = """{"id":"s1","name":"n","created_at":"t","updated_at":"t","session_mode":"character"}"""
        val entity = gson.fromJson(json, LocalSessionEntity::class.java)
        assertFalse(entity.hideInnerMonologue)
        val hidden = LocalSessionEntity(id = "s2", name = "n", createdAt = "t", updatedAt = "t", hideInnerMonologue = true)
        val roundTrip = gson.fromJson(gson.toJson(hidden), LocalSessionEntity::class.java)
        assertTrue(roundTrip.hideInnerMonologue)
    }
}