package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPricingCatalogTest {
    @Test
    fun `openrouter response converts per-token prices to per-million prices`() {
        val snapshot = ModelPricingCatalog.parseOpenRouterResponse(
            json = """
                {
                  "data": [
                    {
                      "id": "openai/gpt-test",
                      "canonical_slug": "openai/gpt-test",
                      "name": "OpenAI: GPT Test",
                      "context_length": 128000,
                      "pricing": {
                        "prompt": "0.0000025",
                        "completion": "0.00001"
                      },
                      "supported_parameters": ["tools", "reasoning"]
                    }
                  ]
                }
            """.trimIndent(),
            updatedAt = "2026-07-30T00:00:00Z"
        )

        val entry = snapshot.entries.single()
        assertEquals(2.5, entry.inputPricePerMillion ?: -1.0, 0.000001)
        assertEquals(10.0, entry.outputPricePerMillion ?: -1.0, 0.000001)
        assertEquals(128_000, entry.contextLength)
        assertTrue(entry.supportsTools)
        assertTrue(entry.supportsReasoning)
    }

    @Test
    fun `matcher accepts provider prefix short id and dated provider aliases`() {
        val entry = ModelPricingEntry(
            id = "openai/gpt-4.1",
            name = "OpenAI: GPT-4.1",
            provider = "openai",
            inputPricePerMillion = 2.0,
            outputPricePerMillion = 8.0,
            aliases = listOf("gpt-4.1")
        )
        val snapshot = ModelPricingSnapshot(
            entries = listOf(entry),
            updatedAt = "2026-07-30T00:00:00Z",
            source = ModelPricingCatalog.SOURCE_OPENROUTER
        )

        assertEquals(entry, ModelPricingCatalog.find("gpt-4.1", catalog = snapshot))
        assertEquals(entry, ModelPricingCatalog.find("openai/gpt-4.1", catalog = snapshot))
        assertEquals(
            entry,
            ModelPricingCatalog.find("openai/gpt-4.1-2025-04-14", catalog = snapshot)
        )
    }

    @Test
    fun `manual price wins while missing side is filled from catalog`() {
        val prices = ModelPricingCatalog.resolvePrices(
            modelName = "gpt-4o",
            provider = "openai",
            inputPrice = 99.0,
            outputPrice = null
        )

        assertEquals(99.0, prices.first ?: -1.0, 0.000001)
        assertEquals(10.0, prices.second ?: -1.0, 0.000001)
        assertNotNull(ModelPricingCatalog.find("openai/gpt-4o"))
    }

    @Test
    fun `openrouter response parses cache read and write prices`() {
        val snapshot = ModelPricingCatalog.parseOpenRouterResponse(
            json = """
                {
                  "data": [
                    {
                      "id": "anthropic/claude-test",
                      "canonical_slug": "anthropic/claude-test",
                      "name": "Anthropic: Claude Test",
                      "context_length": 200000,
                      "pricing": {
                        "prompt": "0.000003",
                        "completion": "0.000015",
                        "input_cache_read": "0.0000003",
                        "input_cache_write": "0.00000375"
                      },
                      "supported_parameters": ["tools"]
                    }
                  ]
                }
            """.trimIndent(),
            updatedAt = "2026-07-30T00:00:00Z"
        )

        val entry = snapshot.entries.single()
        assertEquals(0.3, entry.cacheReadPricePerMillion ?: -1.0, 0.000001)
        assertEquals(3.75, entry.cacheWritePricePerMillion ?: -1.0, 0.000001)
    }

    @Test
    fun `cached input is billed at cache price and uncached at input price`() {
        val prices = ModelPrices(
            inputPerMillion = 3.0,
            outputPerMillion = 15.0,
            cacheReadPerMillion = 0.3,
            cacheWritePerMillion = 3.75
        )

        // 1M 输入中 800k 命中缓存：800k * 0.3 + 200k * 3.0 + 100k 输出 * 15.0
        val cost = ModelPricingCatalog.estimateCostUsd(
            inputTokens = 1_000_000,
            outputTokens = 100_000,
            cachedInputTokens = 800_000,
            prices = prices
        )

        assertEquals(0.24 + 0.6 + 1.5, cost, 0.000001)
    }

    @Test
    fun `cache write tokens are billed at cache write price`() {
        val prices = ModelPrices(
            inputPerMillion = 3.0,
            outputPerMillion = 15.0,
            cacheReadPerMillion = 0.3,
            cacheWritePerMillion = 3.75
        )

        // 100k 命中缓存 + 50k 写入缓存 + 850k 未命中
        val cost = ModelPricingCatalog.estimateCostUsd(
            inputTokens = 1_000_000,
            outputTokens = 0,
            cachedInputTokens = 100_000,
            cacheWriteTokens = 50_000,
            prices = prices
        )

        assertEquals(0.03 + 0.1875 + 2.55, cost, 0.000001)
    }

    @Test
    fun `model prices fall back to catalog cache price and ratio`() {
        val entry = ModelPricingEntry(
            id = "anthropic/claude-opus-test",
            name = "Claude Opus Test",
            provider = "anthropic",
            inputPricePerMillion = 5.0,
            outputPricePerMillion = 25.0,
            cacheReadPricePerMillion = 0.5
        )
        val snapshot = ModelPricingSnapshot(
            entries = listOf(entry),
            updatedAt = "2026-07-30T00:00:00Z",
            source = ModelPricingCatalog.SOURCE_OPENROUTER
        )

        val prices = ModelPricingCatalog.resolveModelPrices(
            modelName = "claude-opus-test",
            catalog = snapshot
        )

        assertEquals(0.5, prices.cacheReadPerMillion ?: -1.0, 0.000001)
        // 目录没有缓存写入价时按 Anthropic 兜底比例（输入价 1.25 倍）折算
        assertEquals(6.25, prices.cacheWritePerMillion ?: -1.0, 0.000001)
    }

    @Test
    fun `cache hit rate is null without input tokens`() {
        assertEquals(null, ModelPricingCatalog.cacheHitRate(inputTokens = 0, cachedInputTokens = 0))
        assertEquals(0.5, ModelPricingCatalog.cacheHitRate(1_000, 500) ?: -1.0, 0.000001)
        // 命中量超过输入量时按 100% 截断
        assertEquals(1.0, ModelPricingCatalog.cacheHitRate(1_000, 5_000) ?: -1.0, 0.000001)
    }
}
