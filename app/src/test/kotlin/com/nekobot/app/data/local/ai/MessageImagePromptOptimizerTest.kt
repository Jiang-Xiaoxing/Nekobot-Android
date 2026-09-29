package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息生图预处理（把拼接提示词改写为纯画面描述）的纯逻辑契约。
 *
 * 锁定三件事：改写请求把原始提示词完整带给模型、模型输出会被清洗成可用提示词、
 * 空/无效输出返回 null 以便调用方回退到原始拼接提示词。
 */
class MessageImagePromptOptimizerTest {

    @Test
    fun `改写请求包含原始提示词且 system 在前`() {
        val raw = "角色卡上下文：\n角色名：小喵\n本次消息的画面要求：\n在窗边喝咖啡"
        val messages = buildImagePromptRewriteMessages(raw)

        assertEquals(2, messages.size)
        assertEquals("system", messages.first()["role"])
        assertEquals("user", messages.last()["role"])
        val userContent = messages.last()["content"].toString()
        assertTrue(userContent.contains("在窗边喝咖啡"))
        assertTrue(userContent.contains("角色名：小喵"))
        val system = messages.first()["content"].toString()
        assertTrue(system.contains("不要"))
    }

    @Test
    fun `超长原始提示词会被截断后交给模型`() {
        val messages = buildImagePromptRewriteMessages("x".repeat(50_000))
        val userContent = messages.last()["content"].toString()
        assertTrue(userContent.length < 50_000)
    }

    @Test
    fun `清洗模型输出的代码块围栏与语言标记`() {
        val raw = "```markdown\n画面描述：少女站在窗边，晨光洒落。\n```"
        assertEquals("少女站在窗边，晨光洒落。", cleanRewrittenImagePrompt(raw))
    }

    @Test
    fun `清洗常见前缀与包裹引号`() {
        assertEquals("雨夜街道上撑伞的背影。", cleanRewrittenImagePrompt("提示词：「雨夜街道上撑伞的背影。」"))
        assertEquals("月下湖面倒映着灯火。", cleanRewrittenImagePrompt("Prompt: \"月下湖面倒映着灯火。\""))
    }

    @Test
    fun `空白输出返回 null 以触发回退`() {
        assertNull(cleanRewrittenImagePrompt(""))
        assertNull(cleanRewrittenImagePrompt("   \n  "))
        assertNull(cleanRewrittenImagePrompt("```\n```"))
    }

    @Test
    fun `超长改写结果按上限截断`() {
        val raw = "风".repeat(MAX_REWRITTEN_IMAGE_PROMPT_CHARS + 500)
        val cleaned = cleanRewrittenImagePrompt(raw)
        assertNotNull(cleaned)
        assertEquals(MAX_REWRITTEN_IMAGE_PROMPT_CHARS, cleaned!!.length)
    }
}
