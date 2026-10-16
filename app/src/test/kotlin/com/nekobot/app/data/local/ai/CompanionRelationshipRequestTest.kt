package com.nekobot.app.data.local.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CompanionRelationshipRequestTest {
    private fun model(maxTokens: Int? = null, name: String = "glm-5.3") = LocalAiModelEntity(
        id = "eval", name = "评估模型", protocol = OpenAIChatProtocol.name, model = name,
        apiKey = "test-key", baseUrl = "https://example.invalid/v1", createdAt = "2026-10-15", maxTokens = maxTokens)
    private val messages = listOf(mapOf<String, Any>("role" to "system", "content" to "评估规则"),
        mapOf("role" to "user", "content" to "原始评估材料，不追加指令"))
    private class Http(private val code: Int = 200) {
        val bodies = mutableListOf<JsonObject>()
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            bodies += JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Mock")
                .body("""{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build())
    }

    @Test fun modelCopyAloneDoesNotSendAnOutputLimit() = runBlocking {
        val http = Http()
        http.client.chatOnce(model(2500), messages)
        assertFalse(http.bodies.single().has("max_tokens"))
    }

    @Test fun evaluationOutputLimitReachesRealHttpBodyInOneRequestWithoutChangingMessages() = runBlocking {
        val http = Http()
        val model = model()
        http.client.chatOnce(model, messages, extra = CompanionRelationshipRequest.extra(model))
        val body = http.bodies.single()
        assertEquals(8192, body["max_tokens"].asInt)
        assertFalse(body.has(NO_COMPATIBILITY_RETRY_EXTRA))
        assertEquals("原始评估材料，不追加指令", body["messages"].asJsonArray[1].asJsonObject["content"].asString)
        assertEquals(2, body["messages"].asJsonArray.size())
        assertNull(model.maxTokens)
    }

    @Test fun rejectedEvaluationParametersDoNotTriggerAnExtraHttpRequest() = runBlocking {
        val http = Http(400)
        try {
            http.client.chatOnce(model(), messages, extra = CompanionRelationshipRequest.extra(model()))
            fail("HTTP 400 should fail this evaluation")
        } catch (_: FailoverHttpException) {
            assertEquals(1, http.bodies.size)
        }
    }

    @Test fun userOutputLimitIsRespectedAndUnsetOrLargerLimitsAreBounded() {
        assertEquals(1024, CompanionRelationshipRequest.outputBudget(model(1024)))
        assertEquals(8192, CompanionRelationshipRequest.outputBudget(model(32000)))
        assertEquals(8192, CompanionRelationshipRequest.outputBudget(model(0)))
        assertEquals(8192, CompanionRelationshipRequest.outputBudget(model(-1)))
    }

    @Test fun truncatedOutputCannotBeParsedOrAdvanceTheBatch() {
        for (reason in listOf("length", "max_tokens", "max_output_tokens")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                CompanionRelationshipRequest.requireComplete(LocalAiResult("{}", finishReason = reason))
            }
            assertTrue(error.message!!.contains("待评估对话保留"))
        }
        CompanionRelationshipRequest.requireComplete(LocalAiResult("{}", finishReason = "stop"))
    }

    @Test fun xiaomiUsesCompletionLimitAndDisablesThinkingOnlyForEvaluation() = runBlocking {
        val http = Http()
        val model = model(5000, "mimo-v2.5").copy(provider = "xiaomi")
        http.client.chatOnce(model, messages, extra = CompanionRelationshipRequest.extra(model))
        val evaluation = http.bodies.single()
        assertEquals(5000, evaluation["max_completion_tokens"].asInt)
        assertFalse(evaluation.has("max_tokens"))
        assertEquals("disabled", evaluation["thinking"].asJsonObject["type"].asString)
        assertTrue(evaluation.keySet().none { it.startsWith("_") })
        http.client.chatOnce(model, messages, extra = mapOf("max_tokens" to 5000))
        val chat = http.bodies.last()
        assertTrue(chat.has("max_tokens")); assertFalse(chat.has("thinking"))
    }

    @Test fun customXiaomiEndpointIsRecognizedByExactHostWithoutChangingUnknownProviders() = runBlocking {
        val http = Http()
        val xiaomi = model(5000, "mimo-v2.6-flash").copy(baseUrl = "https://api.xiaomimimo.com/v1")
        http.client.chatOnce(xiaomi, messages, extra = CompanionRelationshipRequest.extra(xiaomi))
        assertEquals(5000, http.bodies.single()["max_completion_tokens"].asInt)
        assertFalse(CompanionRelationshipRequest.isXiaomi(xiaomi.copy(baseUrl = "https://api.xiaomimimo.com.evil.invalid/v1")))
        val other = model(5000, "custom-model").copy(provider = "custom")
        http.client.chatOnce(other, messages, extra = CompanionRelationshipRequest.extra(other))
        assertEquals(5000, http.bodies.last()["max_tokens"].asInt)
        assertFalse(http.bodies.last().has("thinking"))
    }

    @Test fun openAiReasoningModelsKeepTheirExistingCompletionBudgetField() = runBlocking {
        val http = Http()
        for (name in listOf("o3", "gpt-5.1")) {
            val model = model(4500, name)
            http.client.chatOnce(model, messages, extra = CompanionRelationshipRequest.extra(model))
            assertEquals(4500, http.bodies.last()["max_completion_tokens"].asInt)
            assertFalse(http.bodies.last().has("max_tokens"))
        }
    }

    @Test fun deepseekDisablesThinkingInActualPayload() = runBlocking {
        val http = Http()
        val model = model(5000, "deepseek-chat").copy(provider = "deepseek")
        http.client.chatOnce(model, messages, extra = CompanionRelationshipRequest.extra(model))
        assertEquals("disabled", http.bodies.single()["thinking"].asJsonObject["type"].asString)
        assertFalse(http.bodies.single().has("reasoning_effort"))
    }

    @Test fun glmHybridCanDisableThinkingButForcedThinkingModelIsLeftSupported() = runBlocking {
        val http = Http()
        val hybrid = model(5000, "glm-5.1").copy(provider = "zhipu")
        http.client.chatOnce(hybrid, messages, extra = CompanionRelationshipRequest.extra(hybrid))
        assertEquals("disabled", http.bodies.last()["thinking"].asJsonObject["type"].asString)
        val forced = hybrid.copy(model = "glm-5.3")
        http.client.chatOnce(forced, messages, extra = CompanionRelationshipRequest.extra(forced))
        assertFalse(http.bodies.last().has("thinking"))
        assertFalse(http.bodies.last().has("reasoning_effort"))
    }

    @Test fun dashscopeQwenHybridUsesItsOwnBooleanSwitch() = runBlocking {
        val http = Http()
        val model = model(5000, "qwen3.6-plus").copy(provider = "dashscope")
        http.client.chatOnce(model, messages, extra = CompanionRelationshipRequest.extra(model))
        assertFalse(http.bodies.single()["enable_thinking"].asBoolean)
        assertFalse(http.bodies.single().has("thinking"))
    }

    @Test fun anthropicEvaluationKeepsNativeOutputBudgetAndDisablesThinking() = runBlocking {
        val http = Http()
        val model = model(5000, "claude-sonnet-4-6").copy(protocol = AnthropicMessagesProtocol.name)
        http.client.chatOnce(model, messages, extra = CompanionRelationshipRequest.extra(model))
        assertEquals(5000, http.bodies.single()["max_tokens"].asInt)
        assertEquals("disabled", http.bodies.single()["thinking"].asJsonObject["type"].asString)
    }

    @Test fun legacyGlmAndNonHybridQwenDoNotReceiveUnsupportedThinkingSwitches() = runBlocking {
        val http = Http()
        for ((provider, name) in listOf("zhipu" to "glm-4-plus", "zhipu" to "glm-5.3-flash",
            "dashscope" to "qwen3-coder-plus", "dashscope" to "qwen3-235b-a22b-thinking-2507")) {
            val model = model(5000, name).copy(provider = provider)
            http.client.chatOnce(model, messages, extra = CompanionRelationshipRequest.extra(model))
            assertFalse(http.bodies.last().has("thinking"))
            assertFalse(http.bodies.last().has("enable_thinking"))
        }
    }
}
