package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Session
import com.nekobot.app.ui.components.MdBlock
import com.nekobot.app.ui.components.companionBubbleMarkdownBlocks
import org.junit.Assert.*
import org.junit.Test

class CompanionMonologueGroupingTest {
    private val thought = "（内心：想和你一起去）"

    private fun group(
        text: String,
        companion: Boolean = true,
        user: Boolean = false,
        safe: Boolean = false,
        show: Boolean = true,
        streaming: Boolean = false,
        stickerLookup: ((String) -> String?)? = null
    ): List<ChatBubbleDisplayGroup> {
        val segments = text.split("<||>").map(String::trim).filter(String::isNotEmpty)
        return groupCompanionMonologueSegments(segments, segments.map { parseContentSegments(it, stickerLookup) },
            companion, user, safe, show, streaming)
    }

    private fun indices(groups: List<ChatBubbleDisplayGroup>) = groups.map { it.sourceIndices }

    @Test fun lastMonologueSharesOnlyThePreviousBodyBubble() {
        assertEquals(listOf(listOf(0), listOf(1, 2)), indices(group("第一句<||>最后一句<||>$thought")))
    }

    @Test fun consecutiveTrailingMonologuesKeepSourceOrderAndOriginalStrings() {
        val text = "第一句<||>最后一句<||>$thought<||>(心想:想了又想)"
        val groups = group(text)
        assertEquals(listOf(listOf(0), listOf(1, 2, 3)), indices(groups))
        val parts = text.split("<||>")
        assertEquals(parts, groups.flatMap { it.sourceIndices }.map(parts::get))
    }

    @Test fun mixedBodyStaysInItsOwnBubbleAndCanHostTrailingMonologue() {
        assertEquals(listOf(listOf(0), listOf(1)), indices(group("第一句<||>正文$thought")))
        assertEquals(listOf(listOf(0), listOf(1, 2)), indices(group("第一句<||>正文$thought<||>(心里:再想一句)")))
    }

    @Test fun onlyTrailingPureSegmentsMerge() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), indices(group("正文<||>$thought<||>继续说话")))
        assertEquals(listOf(listOf(0), listOf(1)), indices(group("正文<||>$thought 后面还有正文")))
    }

    @Test fun monologueAloneOrWithoutBodyKeepsStandaloneBubbles() {
        assertEquals(listOf(listOf(0)), indices(group(thought)))
        assertEquals(listOf(listOf(0), listOf(1)), indices(group("$thought<||>(心想:另一句)")))
        assertEquals(emptyList<ChatBubbleDisplayGroup>(), group("  <||>  "))
    }

    @Test fun callsCannotAttachToAnotherMessage() {
        assertEquals(listOf(listOf(0)), indices(group("上一条消息正文")))
        assertEquals(listOf(listOf(0)), indices(group(thought)))
    }

    @Test fun everyMediaKindBlocksAttachmentAndKeepsOrder() {
        for (kind in SegmentType.entries.filter { it != SegmentType.TEXT }) {
            val parts = listOf("正文", "媒体", thought)
            val parsed = listOf(listOf(ContentSegment(SegmentType.TEXT, text = parts[0])),
                listOf(ContentSegment(kind)), listOf(ContentSegment(SegmentType.TEXT, text = parts[2])))
            assertEquals(kind.name, listOf(listOf(0), listOf(1), listOf(2)), indices(
                groupCompanionMonologueSegments(parts, parsed, true, false, false, true, false)))
        }
    }

    @Test fun actualImageFileAndStickerParsingCannotBeSkipped() {
        for (media in listOf("![照片](https://example.com/photo.png)", "[文件: 笔记.txt]", "[微笑]")) {
            assertEquals(media, listOf(listOf(0), listOf(1), listOf(2)),
                indices(group("正文<||>$media<||>$thought", stickerLookup = { if (it == "微笑") "file:///smile.png" else null })))
        }
    }

    @Test fun monologueContainingMediaIsNeverAbsorbed() {
        assertEquals(listOf(listOf(0), listOf(1)),
            indices(group("正文<||>（内心：看 https://example.com/photo.png ）")))
    }

    @Test fun codeOrdinaryAsideAndMalformedSyntaxAreNotMonologues() {
        val samples = listOf("`$thought`", "```text\n$thought\n```", "（轻轻点头）", "（内心：没说完", "# $thought", "- $thought")
        for (sample in samples) {
            assertEquals(sample, listOf(listOf(0), listOf(1)), indices(group("正文<||>$sample")))
        }
    }

    @Test fun supportedLabelsAndNestedParenthesesReuseMarkdownSemantics() {
        for (sample in listOf(thought, "(心里:想到（昨天(一起吃饭)）)", "（心想:好开心）", "(内白：再想想)")) {
            assertEquals(sample, listOf(listOf(0, 1)), indices(group("正文<||>$sample")))
        }
    }

    @Test fun multilineSyntaxWithoutExistingFoldSemanticsStaysReadable() {
        val sample = "（内心：第一行\n第二行）"
        assertEquals(listOf(listOf(0), listOf(1)), indices(group("正文<||>$sample")))
        assertEquals(listOf(MdBlock.Paragraph(sample)), companionBubbleMarkdownBlocks(sample, true, false))
    }

    @Test fun hidingMonologuesRemovesEmptyBubblesButRetainsSourceIndices() {
        assertEquals(emptyList<ChatBubbleDisplayGroup>(), group(thought, show = false))
        assertEquals(listOf(listOf(0, 1)), indices(group("正文<||>$thought", show = false)))
        assertEquals(listOf(listOf(1)), indices(group("$thought<||>正文", show = false)))
        assertEquals(listOf(listOf(0)), indices(group("https://example.com/photo.png<||>$thought", show = false)))
    }

    @Test fun streamingTailNeverAddsAnEmptyBubbleBeforeClosure() {
        val pending = "（内心：还在想"
        assertEquals(listOf(listOf(0, 1)), indices(group("正文<||>$pending", streaming = true)))
        assertTrue(companionBubbleMarkdownBlocks(pending, true, true).isEmpty())
        assertEquals(listOf(listOf(0, 1)), indices(group("正文<||>$pending）", streaming = true)))
        assertEquals(listOf(MdBlock.InnerMonologue("还在想")), companionBubbleMarkdownBlocks("$pending）", true, true))
        assertEquals(group("正文<||>$pending）", streaming = true), group("正文<||>$pending）"))
    }

    @Test fun streamingLabelPrefixesDoNotFlashInASeparateBubble() {
        for (prefix in listOf("（", "（内", "（内心", "（内心：", "(", "(心", "(心里", "(心里:")) {
            assertEquals(prefix, listOf(listOf(0, 1)), indices(group("正文<||>$prefix", streaming = true)))
            assertTrue(prefix, companionBubbleMarkdownBlocks(prefix, true, true).isEmpty())
            // 如果最终停在半截语法，必须在结束后恢复原文字。
            assertEquals(prefix, listOf(MdBlock.Paragraph(prefix)), companionBubbleMarkdownBlocks(prefix, true, false))
        }
        assertEquals(listOf(listOf(0), listOf(1)), indices(group("正文<||>（内侧的口袋）", streaming = true)))
    }

    @Test fun completedMalformedTailReappearsVerbatim() {
        val pending = "（内心：还在想\n下一行"
        assertEquals(listOf(listOf(0), listOf(1)), indices(group("正文<||>$pending")))
        assertEquals(listOf(MdBlock.Paragraph(pending)), companionBubbleMarkdownBlocks(pending, false, false))
    }

    @Test fun streamingWithoutBodyOrAfterMediaHoldsPendingThenShowsSeparateMonologue() {
        assertTrue(group("（内心：未完", streaming = true).isEmpty())
        assertEquals(listOf(listOf(0)), indices(group("（内心：完整）", streaming = true)))
        val image = "https://example.com/photo.png"
        assertEquals(listOf(listOf(0)), indices(group("$image<||>（内心：未完", streaming = true)))
        assertEquals(listOf(listOf(0), listOf(1)), indices(group("$image<||>$thought", streaming = true)))
    }

    @Test fun bodyIdentitySurvivesStreamingGrowthClosureAndHistory() {
        val states = listOf("第一句<||>最后一句", "第一句<||>最后一句<||>（内心：未完", "第一句<||>最后一句<||>$thought")
        val groups = states.map { group(it, streaming = true) } + listOf(group(states.last()))
        assertTrue(groups.all { it.map(ChatBubbleDisplayGroup::sourceIndex) == listOf(0, 1) })
        val anchor = messageMenuAnchorKey("message-1", "fallback", 1)
        assertTrue(groups.all { messageMenuAnchorKey("message-1", "fallback", it.last().sourceIndex) == anchor })
    }

    @Test fun ordinaryAndGroupModesKeepExactOldSegmentsForEveryDisplayState() {
        // 普通会话与群聊均不提供 companionMode；两者共用原 MarkdownText 路径。
        val modeMessages = mapOf("普通" to "正文<||>$thought", "群聊" to "甲说话<||>乙说话<||>$thought")
        for ((mode, text) in modeMessages) {
            val expected = text.split("<||>").indices.map { listOf(it) }
            for (show in listOf(true, false)) for (streaming in listOf(true, false)) {
                assertEquals("$mode show=$show streaming=$streaming", expected,
                    indices(group(text, companion = false, show = show, streaming = streaming)))
                assertFalse(usesCompanionMonologueFooter(false, false, false))
            }
        }
    }

    @Test fun actualSessionModesEnableOnlyTheSingleSessionRole() {
        val sessions = listOf(
            Session(id = "ordinary", type = "chat", companionMode = false, characterName = "林小满"),
            Session(id = "group", type = "group", groupId = "group-1", characterIds = listOf("a", "b")),
            Session(id = "agent", sessionMode = "agent", inheritCharacter = true),
            Session(id = "plot", plotMode = true),
            Session(id = "single-role", sessionMode = "agent", companionMode = true)
        )
        for (session in sessions) {
            val companion = session.companionMode == true
            assertEquals(session.id, session.id == "single-role", usesCompanionMonologueFooter(companion, false, false))
            val expected = if (session.id == "single-role") listOf(listOf(0, 1)) else listOf(listOf(0), listOf(1))
            assertEquals(session.id, expected, indices(group("正文<||>$thought", companion = companion)))
        }
    }

    @Test fun ordinaryAndGroupPendingOrOnlyMonologueBubblesAreUnchanged() {
        for (text in listOf(thought, "（内心：未完", "正文<||>（内心：未完")) {
            val expected = text.split("<||>").indices.map { listOf(it) }
            assertEquals(expected, indices(group(text, companion = false, show = false, streaming = true)))
        }
    }

    @Test fun userAndSafePlainTextPathsStayUnchanged() {
        val text = "正文<||>$thought"
        assertEquals(listOf(listOf(0), listOf(1)), indices(group(text, user = true, show = false)))
        assertEquals(listOf(listOf(0), listOf(1)), indices(group(text, safe = true, show = false)))
        assertFalse(usesCompanionMonologueFooter(true, true, false))
        assertFalse(usesCompanionMonologueFooter(true, false, true))
        assertTrue(usesCompanionMonologueFooter(true, false, false))
    }
}
