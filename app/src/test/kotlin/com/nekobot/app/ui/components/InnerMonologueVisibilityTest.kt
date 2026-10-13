package com.nekobot.app.ui.components

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
    fun streamingUnclosedInnerTailIsVisibleWhenOff() {
        val streaming = "嗯嗯（内心：其实我"
        val hidden = visibleMarkdownBlocks(streaming, chatMode = true, showInnerMonologue = false)
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph(streaming)), hidden)
        // 开启时保持旧行为：未闭合文本照常显示
        val shown = visibleMarkdownBlocks(streaming, chatMode = true, showInnerMonologue = true)
        assertTrue(shown.filterIsInstance<MdBlock.Paragraph>().any { it.content.contains("其实我") })
    }

    @Test
    fun codeSpanIsNotTreatedAsInner() {
        assertEquals("看 `（内心：x", visibleText("看 `（内心：x"))
        val blocks = visibleMarkdownBlocks("代码 `（内心：x）` 示例", chatMode = true, showInnerMonologue = false)
        assertTrue(blocks.filterIsInstance<MdBlock.Paragraph>().any { it.content.contains("（内心：x）") })
    }

    @Test
    fun sameLineInnerAfterBodyIsHiddenForBothBracketWidths() {
        val samples = listOf(
            "好的（内心：今天想你）",
            "好的(内心:今天想你)",
            "好的（内心:今天想你)",
            "好的(内心：今天想你）",
            "好的（心里：嗯）",
            "好的(心想:嗯)",
            "好的（内白：嗯）"
        )
        for (sample in samples) {
            val alone = visibleText(sample)
            assertFalse(sample, alone.contains("内心") || alone.contains("心里") || alone.contains("心想") || alone.contains("内白"))
            assertTrue(sample, alone.contains("好的"))
            val followed = visibleText("上一句\n$sample\n下一句")
            assertFalse(sample, followed.contains("内心") || followed.contains("心里") || followed.contains("心想") || followed.contains("内白"))
            assertTrue(sample, followed.contains("好的"))
            assertTrue(sample, followed.contains("上一句"))
            assertTrue(sample, followed.contains("下一句"))
        }
    }

    @Test
    fun shownModeStillRendersFollowingLineInnerAsText() {
        val blocks = visibleMarkdownBlocks("上一句\n好的（内心：今天想你）", chatMode = true, showInnerMonologue = true)
        val joined = blocks.filterIsInstance<MdBlock.Paragraph>().joinToString("\n") { it.content }
        assertTrue(joined.contains("（内心：今天想你）"))
    }

    @Test
    fun streamingUnclosedInnerKeepsFollowingBodyAcrossLines() {
        val text = "嗯嗯（内心：其实我\n还想说"
        val hidden = visibleMarkdownBlocks(text, chatMode = true, showInnerMonologue = false)
        assertEquals(parseBlocks(text, true), hidden)
        val halfText = "好的(内心:还没说完\n下一行"
        val half = visibleMarkdownBlocks(halfText, chatMode = true, showInnerMonologue = false)
        assertEquals(parseBlocks(halfText, true), half)
        val shown = visibleMarkdownBlocks("嗯嗯（内心：其实我\n还想说", chatMode = true, showInnerMonologue = true)
        assertTrue(shown.filterIsInstance<MdBlock.Paragraph>().any { it.content.contains("还想说") })
    }

    @Test
    fun codeFenceAndInlineCodeAreNotStripped() {
        val fenced = "正文\n```\n（内心：这是代码\n下一行\n```"
        assertEquals(parseBlocks(fenced, true), visibleMarkdownBlocks(fenced, true, false))
        val fencedBlocks = visibleMarkdownBlocks(fenced, chatMode = true, showInnerMonologue = false)
        assertTrue(fencedBlocks.filterIsInstance<MdBlock.CodeBlock>().single().code.contains("（内心：这是代码"))

        val openFence = "```\n（内心：secret"
        assertEquals(parseBlocks(openFence, true), visibleMarkdownBlocks(openFence, true, false))

        val afterFence = "```\ncode\n```\n然后（内心：还没完\n第二行"
        assertEquals(parseBlocks(afterFence, true), visibleMarkdownBlocks(afterFence, true, false))

        val mixed = "好的（内心：隐藏）\n```\n（内心：保留）\n```\n还有(内心:也藏)"
        val mixedText = visibleText(mixed)
        assertTrue(mixedText.contains("好的"))
        assertTrue(mixedText.contains("还有"))
        assertFalse(mixedText.contains("隐藏"))
        assertFalse(mixedText.contains("也藏"))
        val mixedBlocks = visibleMarkdownBlocks(mixed, chatMode = true, showInnerMonologue = false)
        assertTrue(mixedBlocks.filterIsInstance<MdBlock.CodeBlock>().single().code.contains("（内心：保留）"))
    }

    @Test fun unmatchedOuterBracketKeepsNestedBracketAndFollowingBody() {
        val sample = "正文（内心：想到（昨晚）\n今天照常出门。"
        assertEquals(parseBlocks(sample, true), visibleMarkdownBlocks(sample, true, false))
    }

    @Test fun closedInnerBeforeUnclosedInnerDoesNotHideTheRest() {
        assertEquals("正文后文（内心：未完\n下一段", visibleText("正文（内心：隐藏）后文（内心：未完\n下一段"))
    }

    @Test fun nestedFullWidthParenthesesAreHiddenCompletely() {
        assertEquals("前文后文", visibleText("前文（内心：想起（昨天（一起吃饭））很开心）后文"))
    }

    @Test fun nestedMixedWidthParenthesesAreHiddenCompletely() {
        assertEquals("前文后文", visibleText("前文(内心:想起（昨天(一起吃饭)）很开心)后文"))
    }

    @Test fun nestedInnerIsOneFoldableBlockWhenShown() {
        assertEquals(listOf(MdBlock.Paragraph("前"), MdBlock.InnerMonologue("想起（昨天）很开心"), MdBlock.Paragraph("后")),
            visibleMarkdownBlocks("前（内心：想起（昨天）很开心）后", true, true))
    }

    @Test fun parenthesesInCodeDoNotCloseOrExtendInner() {
        assertEquals("前`）(`后", visibleText("前（内心：想起（昨天）`）(`很开心）后"))
    }

    @Test fun nestedInnerAcrossLinesHidesOnlyThroughMatchingOuterClose() {
        assertEquals("前后", visibleText("前（内心：想起（昨天\n一起吃饭）很开心）后"))
    }

    @Test fun orphanBacktickBeforeInnerDoesNotExposeIt() {
        assertEquals("正文`后文", visibleText("正文`（内心：秘密）后文"))
    }

    @Test fun orphanBacktickInsideInnerDoesNotPreventClosure() {
        assertEquals("正文后文", visibleText("正文（内心：秘密`还在想）后文"))
    }

    @Test fun orphanBacktickBeforeInnerStillAllowsFolding() {
        val blocks = visibleMarkdownBlocks("正文`（内心：秘密）后文", true, true)
        assertTrue(blocks.any { it == MdBlock.InnerMonologue("秘密") })
    }

    private fun visibleText(text: String): String =
        visibleMarkdownBlocks(text, chatMode = true, showInnerMonologue = false).joinToString("\n") { block ->
            when (block) {
                is MdBlock.Paragraph -> block.content
                is MdBlock.InnerMonologue -> "INNER:${block.content}"
                is MdBlock.CodeBlock -> block.code
                else -> ""
            }
        }

    @Test
    fun nonChatModeUnaffected() {
        assertEquals(parseBlocks(text, false), visibleMarkdownBlocks(text, chatMode = false, showInnerMonologue = false))
    }
}
