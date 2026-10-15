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
}
