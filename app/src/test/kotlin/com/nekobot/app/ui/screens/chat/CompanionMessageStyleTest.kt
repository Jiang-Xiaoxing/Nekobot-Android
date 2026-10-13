package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.nekobot.app.data.model.Message
import com.nekobot.app.ui.components.parseInline
import org.junit.Assert.*
import org.junit.Test

class CompanionMessageStyleTest {
    private val reply = Message(role = "assistant", content = "我陪你。")

    @Test fun firstReplyShowsAvatar() = assertTrue(companionShowsAvatar(reply, null, false))
    @Test fun consecutiveRepliesShareAvatar() = assertFalse(companionShowsAvatar(reply, reply, false))
    @Test fun userInterruptStartsNewGroup() = assertTrue(companionShowsAvatar(reply, Message(role = "human"), false))
    @Test fun outgoingHasNoAvatar() = assertFalse(companionShowsAvatar(Message(role = "user"), reply, true))
    @Test fun timeSeparatorStartsNewGroup() = assertTrue(companionShowsAvatar(reply, reply, true))
    @Test fun differentSenderStartsNewGroup() = assertTrue(companionShowsAvatar(reply.copy(sender = "小满"), reply.copy(sender = "晚星"), false))
    @Test fun senderWhitespaceDoesNotBreakGroup() = assertFalse(companionShowsAvatar(reply.copy(sender = " 小满 "), reply.copy(sender = "小满"), false))
    @Test fun senderNameFallbackIsStable() = assertFalse(companionShowsAvatar(reply.copy(sender = " ", name = "小满"), reply.copy(name = "小满"), false))
    @Test fun hiddenSystemMessageBreaksGroup() = assertTrue(companionShowsAvatar(reply, Message(role = "system"), false))
    @Test fun streamingReplyKeepsConsecutiveGroup() = assertFalse(companionShowsAvatar(reply.copy(id = ChatViewModel.STREAMING_ID), reply, false))
    @Test fun shortIntervalHasNoSeparator() = assertFalse(section("21:06", "21:02"))
    @Test fun fiveMinuteIntervalHasSeparator() = assertTrue(section("21:06", "21:01"))
    @Test fun crossedDayHasSeparator() = assertTrue(companionStartsTimeSection(false, false, "2026-10-13", "2026-10-12", "00:01", "23:59"))
    @Test fun unknownTimeHasSeparator() = assertTrue(section(null, "21:01"))
    @Test fun invalidTimeHasSeparator() = assertTrue(section("25:10", "21:01"))
    @Test fun backwardsTimeHasSeparator() = assertTrue(section("21:01", "21:06"))
    @Test fun streamingHasNoNewSeparator() = assertFalse(companionStartsTimeSection(true, true, null, null, null, null))
    @Test fun firstVisibleMessageHasSeparator() = assertTrue(companionStartsTimeSection(true, false, "2026-10-13", "2026-10-13", "21:06", "21:06"))
    @Test fun phoneWidthLeavesAvatarAndMargins() {
        assertEquals(264, companionBubbleMaxWidthDp(360, false))
        assertEquals(296, companionBubbleMaxWidthDp(360, true))
    }
    @Test fun tabletWidthIsCapped() = assertEquals(360, companionBubbleMaxWidthDp(900, false))
    @Test fun narrowWidthDoesNotForceMinimum() {
        assertEquals(84, companionBubbleMaxWidthDp(180, false))
        assertEquals(1, companionBubbleMaxWidthDp(40, false))
    }
    @Test fun uploadedPortraitHasPriority() = assertEquals("file:///portrait.jpg", companionPhotoPath("file:///portrait.jpg", "file:///avatar.jpg"))
    @Test fun legacyIconFallsBackToPhoto() = assertEquals("file:///avatar.jpg", companionPhotoPath("fas fa-cat", "file:///avatar.jpg"))
    @Test fun missingPhotoFallsBackToInitial() = assertNull(companionPhotoPath(" ", "fasfa-cat"))
    @Test fun avatarPhotoWorksWithoutPortrait() = assertEquals("content://photo/1", companionPhotoPath(null, "content://photo/1"))

    @Test fun lightAssistantRestoresPre53GreenPresetBase() {
        val style = companionAssistantBubbleStyle(false)
        assertEquals(Color(0xFF6BCB77), style.background)
        assertEquals(1f, style.background.alpha, 0f)
    }

    @Test fun darkAssistantUsesSameSavedGreenAsPre53Override() {
        val light = companionAssistantBubbleStyle(false)
        val dark = companionAssistantBubbleStyle(true)
        assertEquals(light.background, dark.background)
        assertEquals(1f, dark.background.alpha, 0f)
    }

    @Test fun lightAssistantBodyAndAsidePassContrastOnGreen() = assertBubbleContrast(false)
    @Test fun darkAssistantBodyAndAsidePassContrastOnGreen() = assertBubbleContrast(true)
    @Test fun lightAssistantLinksPassContrastOnGreen() = assertLinkContrast(false)
    @Test fun darkAssistantLinksPassContrastOnGreen() = assertLinkContrast(true)
    @Test fun lightMonologueAndCodeTextAndControlsPassContrast() = assertSurfaceContrast(false)
    @Test fun darkMonologueAndCodeTextAndControlsPassContrast() = assertSurfaceContrast(true)

    @Test fun monologueLinksRemainReadableOnBothDetailSurfaces() {
        listOf(false, true).forEach { dark ->
            val colors = companionAssistantBubbleStyle(dark).markdown
            assertReadable(colors.surfaceLink, colors.surface)
        }
    }

    @Test fun assistantMarkdownLinkUsesBubbleInkAndKeepsClickTargetAndUnderline() {
        val colors = companionAssistantBubbleStyle(false).markdown
        val rendered = parseInline("[点这里](https://example.com)", Color.Black, TextStyle.Default,
            linkColor = colors.link)
        assertEquals("点这里", rendered.text)
        assertEquals(colors.link, rendered.spanStyles.single().item.color)
        assertEquals(TextDecoration.Underline, rendered.spanStyles.single().item.textDecoration)
        assertEquals("https://example.com", rendered.getStringAnnotations("URL", 0, rendered.length).single().item)
    }

    @Test fun assistantAsideKeepsOriginalTextWithOpaqueReadableInk() {
        val style = companionAssistantBubbleStyle(true)
        val rendered = parseInline("（轻声说）", style.content, TextStyle.Default, styleParentheses = true,
            parenthesesColor = style.markdown.parentheses)
        assertEquals("（轻声说）", rendered.text)
        val ink = rendered.spanStyles.single().item.color
        assertEquals(1f, ink.alpha, 0f)
        assertReadable(ink, style.background)
    }

    @Test fun monologueInlineLinksUseSurfaceColorAndKeepOriginalContent() {
        listOf(false, true).forEach { dark ->
            val colors = companionAssistantBubbleStyle(dark).markdown
            val rendered = parseInline("心想：[去看看](https://example.com)", colors.onSurface,
                TextStyle.Default, linkColor = colors.surfaceLink)
            assertEquals("心想：去看看", rendered.text)
            assertReadable(rendered.spanStyles.single().item.color, colors.surface)
        }
    }

    @Test fun markdownWithoutAssistantOverrideKeepsExistingColors() {
        val base = Color(0xFF202927)
        val rendered = parseInline("（轻声说）[链接](https://example.com)", base, TextStyle.Default,
            styleParentheses = true)
        assertEquals(base.copy(alpha = 0.62f), rendered.spanStyles.first().item.color)
        assertEquals(Color(0xFF58A6FF), rendered.spanStyles.last().item.color)
    }

    private fun assertBubbleContrast(dark: Boolean) {
        val style = companionAssistantBubbleStyle(dark)
        assertReadable(style.content, style.background)
        assertReadable(style.markdown.parentheses, style.background)
        // 行内代码仍沿用原来的灰色半透明底色。
        assertReadable(style.content, Color.Gray.copy(alpha = 0.15f).compositeOver(style.background))
    }

    private fun assertLinkContrast(dark: Boolean) {
        val style = companionAssistantBubbleStyle(dark)
        assertReadable(style.markdown.link, style.background)
    }

    private fun assertSurfaceContrast(dark: Boolean) {
        val colors = companionAssistantBubbleStyle(dark).markdown
        assertEquals(1f, colors.surface.alpha, 0f)
        assertEquals(1f, colors.onSurface.alpha, 0f)
        assertReadable(colors.onSurface, colors.surface)
    }

    private fun assertReadable(ink: Color, background: Color) {
        val displayed = ink.compositeOver(background)
        val ratio = (maxOf(displayed.luminance(), background.luminance()) + 0.05f) /
            (minOf(displayed.luminance(), background.luminance()) + 0.05f)
        assertTrue("文字对比度 $ratio 应不低于 4.5:1", ratio >= 4.5f)
    }

    private fun section(time: String?, previous: String?) =
        companionStartsTimeSection(false, false, "2026-10-13", "2026-10-13", time, previous)
}
