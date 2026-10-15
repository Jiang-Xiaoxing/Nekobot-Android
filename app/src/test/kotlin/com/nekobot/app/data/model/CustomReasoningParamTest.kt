package com.nekobot.app.data.model

import com.nekobot.app.data.local.ai.putReasoningParams
import org.junit.Assert.*
import org.junit.Test

class CustomReasoningParamTest {

    @Test fun customIsSeventhOptionAndRoundTrips() {
        assertEquals(7, ReasoningEffort.entries.size)
        assertEquals(ReasoningEffort.CUSTOM, ReasoningEffort.fromValue("custom"))
        assertEquals("自定义", ReasoningEffort.CUSTOM.displayName)
    }

    @Test fun valuesKeepJsonTypes() {
        assertEquals(8192L, CustomReasoningParam("a", "8192").typedValue())
        assertEquals(0.5, CustomReasoningParam("a", "0.5").typedValue())
        assertEquals(true, CustomReasoningParam("a", "true").typedValue())
        assertEquals(false, CustomReasoningParam("a", "false").typedValue())
        assertEquals("high", CustomReasoningParam("a", " high ").typedValue())
        assertEquals("123", CustomReasoningParam("a", "\"123\"").typedValue())
    }

    @Test fun flatAndDottedPathsAreWritten() {
        val base = mapOf<String, Any>("model" to "m", "messages" to emptyList<Any>(), "reasoning" to mapOf("summary" to "auto"))
        assertEquals("max", CustomReasoningParam("reasoning_effort", "max").applyTo(base)["reasoning_effort"])
        @Suppress("UNCHECKED_CAST")
        val nested = CustomReasoningParam("reasoning.effort", "high").applyTo(base)["reasoning"] as Map<String, Any>
        assertEquals(mapOf("summary" to "auto", "effort" to "high"), nested)
        @Suppress("UNCHECKED_CAST")
        val deep = CustomReasoningParam("thinking.budget_tokens", "1024").applyTo(base)["thinking"] as Map<String, Any>
        assertEquals(1024L, deep["budget_tokens"])
        assertEquals(mapOf("summary" to "auto"), base["reasoning"])
    }

    @Test fun coreFieldsCannotBeOverridden() {
        val base = mapOf<String, Any>("model" to "m", "stream" to true)
        listOf("model", "messages", "stream", "tools", "Model").forEach { key ->
            val p = CustomReasoningParam(key, "x")
            assertNotNull(p.validationError())
            assertEquals(base, p.applyTo(base))
        }
        assertNotNull(CustomReasoningParam("messages.0", "x").validationError())
        assertNotNull(CustomReasoningParam("a..b", "x").validationError())
        assertNotNull(CustomReasoningParam("", "x").validationError())
        assertNotNull(CustomReasoningParam("a", " ").validationError())
    }

    @Test fun putReasoningCarriesCustomOnlyForCustom() {
        val custom = CustomReasoningParam("enable_thinking", "true")
        val a = mutableMapOf<String, Any?>()
        putReasoningParams(a, ReasoningEffort.CUSTOM) { custom }
        assertEquals("custom", a["reasoning_effort"]); assertEquals(custom, a[CustomReasoningParam.EXTRA_KEY])
        val b = mutableMapOf<String, Any?>()
        putReasoningParams(b, ReasoningEffort.HIGH) { error("不应读取") }
        assertEquals("high", b["reasoning_effort"]); assertFalse(b.containsKey(CustomReasoningParam.EXTRA_KEY))
    }
}