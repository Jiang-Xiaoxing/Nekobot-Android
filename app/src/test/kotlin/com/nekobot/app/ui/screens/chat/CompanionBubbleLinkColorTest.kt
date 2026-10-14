package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import com.nekobot.app.ui.components.markdownLinkColor
import com.nekobot.app.ui.components.parseInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionBubbleLinkColorTest {
    @Test fun lightDefaultLinksAreIdenticalAndReadable() {
        val palette = companionChatPalette(false)
        assertEquals(0xFF172A20.toInt(), companionBubbleLinkColor(palette).toArgb())
        assertSharedReadableLinks(palette)
    }

    @Test fun darkDefaultLinksAreIdenticalAndReadable() {
        val palette = companionChatPalette(true)
        assertEquals(0xFFF0F6F2.toInt(), companionBubbleLinkColor(palette).toArgb())
        assertSharedReadableLinks(palette)
    }

    @Test fun customLinksAreIdenticalAndReadableInLightMode() = assertCustomLinks(false)
    @Test fun customLinksAreIdenticalAndReadableInDarkMode() = assertCustomLinks(true)

    @Test fun grayscaleCustomColorsPassContrastAcrossTheBrightnessBoundary() {
        listOf(false, true).forEach { dark ->
            (0..255).forEach { value ->
                val color = Color(0xFF000000.toInt() or (value shl 16) or (value shl 8) or value)
                assertSharedReadableLinks(companionChatPalette(dark, color))
            }
        }
    }

    @Test fun bothMarkdownLinksKeepTheSameColorUrlAndUnderline() {
        listOf(false, true).forEach { dark ->
            (listOf<Color?>(null) + customColors).forEach { custom ->
                val palette = companionChatPalette(dark, custom)
                val shared = companionBubbleLinkColor(palette)
                val assistant = companionAssistantBubbleStyle(palette)
                listOf(null, assistant.markdown).forEach { colors ->
                    val rendered = parseInline("[点这里](https://example.com)", palette.outgoingInk,
                        TextStyle.Default, linkColor = markdownLinkColor(colors, shared))
                    assertEquals("点这里", rendered.text)
                    assertEquals(shared, rendered.spanStyles.single().item.color)
                    assertEquals(TextDecoration.Underline, rendered.spanStyles.single().item.textDecoration)
                    assertEquals("https://example.com",
                        rendered.getStringAnnotations("URL", 0, rendered.length).single().item)
                }
            }
        }
    }

    @Test fun sharedBubbleLinkOverridePreservesDetailSurfaceLinks() {
        listOf(false, true).forEach { dark ->
            val palette = companionChatPalette(dark)
            val colors = companionAssistantBubbleStyle(palette).markdown
            val shared = companionBubbleLinkColor(palette)
            assertEquals(colors.surfaceLink, markdownLinkColor(colors, shared, onSurface = true))
            assertEquals(Color(0xFF58A6FF), markdownLinkColor(null, shared, onSurface = true))
        }
    }

    @Test fun markdownOutsideBubbleKeepsExistingLinkColors() {
        val colors = companionAssistantBubbleStyle(companionChatPalette(false)).markdown
        assertEquals(colors.link, markdownLinkColor(colors, null))
        assertEquals(Color(0xFF58A6FF), markdownLinkColor(null, null))
    }

    private fun assertCustomLinks(dark: Boolean) = customColors.forEach { color ->
        assertSharedReadableLinks(companionChatPalette(dark, color))
    }

    private fun assertSharedReadableLinks(palette: CompanionChatPalette) {
        // 与 ChatScreen 一致：独立链接直接取共用色，双方 Markdown 再走各自的局部配色。
        val shared = companionBubbleLinkColor(palette)
        val assistant = companionAssistantBubbleStyle(palette)
        val userMarkdown = markdownLinkColor(null, shared)
        val assistantMarkdown = markdownLinkColor(assistant.markdown, shared)
        assertEquals(shared, assistant.markdown.link)
        assertEquals(shared, userMarkdown)
        assertEquals(userMarkdown, assistantMarkdown)
        assertEquals(userMarkdown.toArgb(), assistantMarkdown.toArgb())
        assertReadable(userMarkdown, palette.outgoing)
        assertReadable(assistantMarkdown, assistant.background)
    }

    private fun assertReadable(link: Color, background: Color) {
        assertEquals(1f, link.alpha, 0f)
        val displayed = link.compositeOver(background)
        val ratio = (maxOf(displayed.luminance(), background.luminance()) + 0.05f) /
            (minOf(displayed.luminance(), background.luminance()) + 0.05f)
        assertTrue("链接 $link 在背景 $background 的对比度 $ratio 应不低于 4.5:1", ratio >= 4.5f)
    }

    private val customColors = listOf(
        Color.White, Color.Black, Color(0xFFEAF7D8), Color(0xFF102030),
        Color(0xFF757575), Color(0xFF767676), Color(0xFF777777),
        Color(0xFF8B6CFF), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFF0000FF)
    )
}
