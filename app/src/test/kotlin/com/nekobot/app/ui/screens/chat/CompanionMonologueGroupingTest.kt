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

    @Test fun monologueBetweenTwoBubblesFollowsOnlyItsPreviousSpeech() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), indices(group("正文<||>$thought<||>继续说话")))
        assertEquals(listOf(listOf(0), listOf(1)), indices(group("正文<||>$thought 后面还有正文")))
    }

    @Test fun eachShortSpeechKeepsItsOwnMonologueAndOriginalAnchor() {
        val text = "第一条<||>（内心：第一条的独白）<||>第二条<||>（内心：第二条的独白）<||>第三条"
        val groups = group(text)
        assertEquals(listOf(listOf(0,1),listOf(2,3),listOf(4)),indices(groups))
        assertEquals(listOf(0,2,4),groups.map { it.sourceIndex })
        assertEquals(text.split("<||>"),groups.flatMap { it.sourceIndices }.map(text.split("<||>")::get))
        assertEquals(indices(groups),indices(group(text,show=false)))
    }

    @Test fun streamingMiddleMonologueKeepsThePreviousBubbleThroughClosure() {
        for (prefix in listOf("（", "（内心：", "（内心：悄悄开心", "（内心：悄悄开心）")) {
            assertEquals(prefix,listOf(listOf(0,1)),indices(group("第一条<||>$prefix",streaming=true)))
        }
        assertEquals(listOf(listOf(0,1),listOf(2)),indices(group("第一条<||>（内心：悄悄开心）<||>第二条",streaming=true)))
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

    @Test fun ordinaryAndGroupModesUseSameMonologueFooterAsCompanion() {
        // 普通角色与单会话共用气泡独白页脚：同一段内容在两种模式下分组一致。
        for (text in listOf("正文<||>$thought", "甲说话<||>乙说话<||>$thought")) {
            for (show in listOf(true, false)) for (streaming in listOf(true, false)) {
                assertEquals("$text show=$show streaming=$streaming",
                    indices(group(text, show = show, streaming = streaming)),
                    indices(group(text, companion = false, show = show, streaming = streaming)))
                assertTrue(usesCompanionMonologueFooter(false, false, false))
            }
        }
    }

    @Test fun everySessionModeEnablesTheMonologueFooterAndGroupsTheSameWay() {
        val sessions = listOf(
            Session(id = "ordinary", type = "chat", companionMode = false, characterName = "林小满"),
            Session(id = "group", type = "group", groupId = "group-1", characterIds = listOf("a", "b")),
            Session(id = "agent", sessionMode = "agent", inheritCharacter = true),
            Session(id = "plot", plotMode = true),
            Session(id = "single-role", sessionMode = "agent", companionMode = true)
        )
        for (session in sessions) {
            assertTrue(session.id, usesCompanionMonologueFooter(session.companionMode == true, false, false))
            assertEquals(session.id, indices(group("正文<||>$thought")),
                indices(group("正文<||>$thought", companion = session.companionMode == true)))
        }
    }

    @Test fun pendingOrOnlyMonologueBubblesUseTheSameFooterRulesInAllModes() {
        val cases = listOf(thought, "（内心：未完", "正文<||>（内心：未完")
        for (text in cases) {
            assertEquals(text, indices(group(text, show = false, streaming = true)),
                indices(group(text, companion = false, show = false, streaming = true)))
        }
        // 收起的纯独白不留空泡；正文仍保留。
        assertEquals(emptyList<List<Int>>(), indices(group(thought, show = false, streaming = true)))
        assertEquals(emptyList<List<Int>>(), indices(group("（内心：未完", show = false, streaming = true)))
        assertEquals(listOf(listOf(0, 1)), indices(group("正文<||>（内心：未完", show = false, streaming = true)))
    }

    @Test fun userAndSafePlainTextPathsStayUnchanged() {
        val text = "正文<||>$thought"
        assertEquals(listOf(listOf(0), listOf(1)), indices(group(text, user = true, show = false)))
        assertEquals(listOf(listOf(0), listOf(1)), indices(group(text, safe = true, show = false)))
        assertFalse(usesCompanionMonologueFooter(true, true, false))
        assertFalse(usesCompanionMonologueFooter(true, false, true))
        assertTrue(usesCompanionMonologueFooter(true, false, false))
    }

    // 用户反馈：模型把（内心：…）写在正文前面时，独白块会显示在气泡顶部；
    // 单会话气泡应固定把独白收在正文之后（只调整显示顺序，原文不变）。
    @Test fun monologueWrittenBeforeBodyStillRendersAfterBodyBlocks() {
        val text = "（内心：先想到的）\n\n正文在这里"
        val blocks = companionBubbleMarkdownBlocks(text, true, false)
        assertEquals(1, blocks.count { it is MdBlock.InnerMonologue })
        assertTrue(blocks.last() is MdBlock.InnerMonologue)
        assertTrue(blocks.dropLast(1).none { it is MdBlock.InnerMonologue })
    }

    @Test fun monologueOrderAlreadyAtTailStaysUnchanged() {
        val text = "正文在这里\n\n（内心：最后才想到的）"
        val blocks = companionBubbleMarkdownBlocks(text, true, false)
        assertEquals(1, blocks.count { it is MdBlock.InnerMonologue })
        assertTrue(blocks.last() is MdBlock.InnerMonologue)
    }

    @Test fun pureMonologueBubbleKeepsSingleBlock() {
        assertEquals(listOf(MdBlock.InnerMonologue("想和你一起去")), companionBubbleMarkdownBlocks(thought, true, false))
    }
}
