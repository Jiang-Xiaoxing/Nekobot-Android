package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缓存 token 解析口径：各服务商 usage 字段不同，但都必须还原出
 * 「完整输入量 + 命中量（+ 写入量）」这组统一数据，命中率与缓存价计费都依赖它。
 */
class LocalTokenUsageCacheTest {
    @Test
    fun `openai prompt tokens details cached tokens are recognized`() {
        val usage = JsonParser.parseString(
            """
            {"prompt_tokens":1000,"completion_tokens":200,"total_tokens":1200,
             "prompt_tokens_details":{"cached_tokens":768}}
            """.trimIndent()
        ).asJsonObject

        val detail = openAiStyleUsageFromJson(usage)

        assertEquals(1000, detail.promptTokens)
        assertEquals(200, detail.completionTokens)
        assertEquals(768, detail.cachedPromptTokens)
    }

    @Test
    fun `deepseek prompt cache hit tokens are recognized`() {
        val usage = JsonParser.parseString(
            """{"prompt_tokens":1000,"completion_tokens":50,"prompt_cache_hit_tokens":900}"""
        ).asJsonObject

        assertEquals(900, openAiStyleUsageFromJson(usage).cachedPromptTokens)
    }

    @Test
    fun `anthropic input excludes cache read and creation so they are added back`() {
        val usage = JsonParser.parseString(
            """
            {"input_tokens":100,"output_tokens":20,
             "cache_read_input_tokens":800,"cache_creation_input_tokens":50}
            """.trimIndent()
        ).asJsonObject

        val detail = anthropicStyleUsageFromJson(usage)

        // 完整输入 = 未命中 100 + 命中 800 + 写入 50
        assertEquals(950, detail.promptTokens)
        assertEquals(800, detail.cachedPromptTokens)
        assertEquals(50, detail.cacheWriteTokens)
        assertEquals(970, detail.totalTokens)
    }

    @Test
    fun `gemini cached content token count is a subset of prompt tokens`() {
        val usage = mapOf<String, Any>(
            "promptTokenCount" to 1200,
            "candidatesTokenCount" to 300,
            "totalTokenCount" to 1500,
            "cachedContentTokenCount" to 1000
        )

        val detail = geminiStyleUsageFromMap(usage)

        assertEquals(1200, detail.promptTokens)
        assertEquals(1000, detail.cachedPromptTokens)
    }

    @Test
    fun `resolved usage carries cache tokens and hit rate round trips`() {
        val resolved = resolveLocalTokenUsage(
            usage = mapOf(
                "prompt" to 2000,
                "completion" to 100,
                LocalModelUsage.KEY_CACHED_PROMPT to 1500,
                LocalModelUsage.KEY_CACHE_WRITE to 100
            ),
            messages = emptyList(),
            outputText = "回复"
        )

        assertEquals(2000, resolved.inputTokens)
        assertEquals(1500, resolved.cachedInputTokens)
        assertEquals(100, resolved.cacheWriteTokens)
        assertEquals(false, resolved.estimated)
        assertEquals(0.75, ModelPricingCatalog.cacheHitRate(2000, 1500) ?: -1.0, 0.000001)
    }

    @Test
    fun `estimated usage reports no cache tokens`() {
        val resolved = resolveLocalTokenUsage(
            usage = emptyMap<String, Int>(),
            messages = listOf(mapOf("role" to "user", "content" to "你好")),
            outputText = "你好"
        )

        assertEquals(true, resolved.estimated)
        assertEquals(0, resolved.cachedInputTokens)
        assertEquals(0, resolved.cacheWriteTokens)
    }

    @Test
    fun `usage map detail reads cached keys`() {
        val detail = usageMapToDetail(
            mapOf(
                "prompt" to 500,
                "completion" to 10,
                "total" to 510,
                LocalModelUsage.KEY_CACHED_PROMPT to 400
            )
        )

        assertEquals(500, detail.promptTokens)
        assertEquals(400, detail.cachedPromptTokens)
        assertEquals(0, detail.cacheWriteTokens)
    }
}
