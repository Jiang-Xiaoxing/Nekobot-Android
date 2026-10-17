package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerSegmentParsingTest {

    private val lookup: (String) -> String? = { name ->
        when (name) {
            "猫猫" -> "file:///stickers/cat.png"
            "生气" -> "file:///stickers/angry.png"
            else -> null
        }
    }

    @Test
    fun `未命中表情时保持原文`() {
        val segments = parseContentSegments("你好 [未知表情]", lookup)

        assertEquals(1, segments.size)
        assertEquals(SegmentType.TEXT, segments[0].type)
        assertEquals("你好 [未知表情]", segments[0].text)
    }

    @Test
    fun `命中表情时解析为表情段并保留前后文字`() {
        val segments = parseContentSegments("开心 [猫猫] 结束", lookup)

        assertEquals(3, segments.size)
        assertEquals(SegmentType.TEXT, segments[0].type)
        assertEquals("开心 ", segments[0].text)
        assertEquals(SegmentType.STICKER, segments[1].type)
        assertEquals("猫猫", segments[1].text)
        assertEquals("file:///stickers/cat.png", segments[1].url)
        assertEquals(" 结束", segments[2].text)
    }

    @Test
    fun `多个表情与文件引用可以共存`() {
        val segments = parseContentSegments("[猫猫][生气] [File: a.png]", lookup)

        assertEquals(3, segments.size)
        assertEquals(SegmentType.STICKER, segments[0].type)
        assertEquals(SegmentType.STICKER, segments[1].type)
        assertEquals(SegmentType.FILE, segments[2].type)
        assertEquals("a.png", segments[2].fileName)
    }

    @Test
    fun `没有查询表时行为与旧版一致`() {
        val segments = parseContentSegments("[猫猫]")

        assertEquals(1, segments.size)
        assertEquals(SegmentType.TEXT, segments[0].type)
        assertTrue(segments[0].text.contains("[猫猫]"))
    }

    @Test
    fun `表情段计入图片内容分组`() {
        val sticker = parseContentSegments("[猫猫]", lookup).first()

        assertTrue(sticker.isImageContent())
    }

    @Test
    fun `整条消息只有已导入表情时独立展示`() {
        assertTrue(isStandaloneStickerMessage("[猫猫]", lookup))
        assertTrue(isStandaloneStickerMessage(" \n\t[ 猫猫 ]\r\n ", lookup))
    }

    @Test
    fun `文字和表情混排不作为独立表情`() {
        listOf("你好[猫猫]", "[猫猫]你好", "你好[猫猫]再见").forEach { content ->
            assertFalse(content, isStandaloneStickerMessage(content, lookup))
        }
    }

    @Test
    fun `多个表情或分段消息不作为独立表情`() {
        listOf("[猫猫][生气]", "[猫猫] [猫猫]", "[猫猫]<||>[生气]", "[猫猫]<||>").forEach { content ->
            assertFalse(content, isStandaloneStickerMessage(content, lookup))
        }
    }

    @Test
    fun `未知表情或空表情库保持气泡`() {
        assertFalse(isStandaloneStickerMessage("[未知表情]", lookup))
        assertFalse(isStandaloneStickerMessage("[猫猫]") { null })
    }

    @Test
    fun `空内容和不完整标记保持气泡`() {
        listOf("", " \n ", "[]", "[ ]", "[猫猫", "猫猫]", "[[猫猫]]", "[猫\n猫]").forEach { content ->
            assertFalse(content, isStandaloneStickerMessage(content, lookup))
        }
    }

    @Test
    fun `文件引用即使命中表情名也不作为独立表情`() {
        listOf("[File: a.png]", "[file: a.png]", "[文件: a.png]").forEach { content ->
            assertFalse(content, isStandaloneStickerMessage(content) { "file:///stickers/file.png" })
        }
    }

    @Test
    fun `独立表情与现有解析器使用相同名称和本地图`() {
        val content = " \n[ 猫猫 ] \t"
        assertTrue(isStandaloneStickerMessage(content, lookup))
        assertEquals(
            listOf(ContentSegment(type = SegmentType.STICKER, text = "猫猫", url = "file:///stickers/cat.png")),
            parseContentSegments(content, lookup)
        )
    }
}
