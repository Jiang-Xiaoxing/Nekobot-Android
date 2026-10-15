package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CacheReportingAvailabilityTest {
    @Test fun missingIsDifferentFromExplicitZero() {
        val missing=openAiStyleUsageFromJson(JsonParser.parseString("""{"prompt_tokens":42,"completion_tokens":5}""").asJsonObject)
        val zero=openAiStyleUsageFromJson(JsonParser.parseString("""{"prompt_tokens":42,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":0}}""").asJsonObject)
        assertFalse(missing.cacheReported); assertTrue(zero.cacheReported)
        assertFalse(missing.toUsageMap().containsKey(LocalModelUsage.KEY_CACHED_PROMPT))
        assertEquals(0,zero.toUsageMap()[LocalModelUsage.KEY_CACHED_PROMPT])
    }
}
