package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.oauth.OAuthRuntimeCredential
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ModelCapabilityProberTest {
    private fun model() = LocalAiModelEntity("model", "Model", OpenAIChatProtocol.name,
        apiKey = "test-key", baseUrl = "https://api.example.com/v1", model = "custom-model",
        supportsStream = false, supportsTools = false, supportsReasoning = false, supportsVision = false,
        createdAt = "2026-10-13T00:00:00Z")

    @Test fun codexProbesUseRuntimeCredentialsAndResponsesRequestShapes() = runBlocking {
        val bodies = mutableListOf<com.google.gson.JsonObject>()
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://chatgpt.com/backend-api/codex/responses", request.url.toString())
            assertEquals("Bearer runtime-token", request.header("Authorization"))
            assertEquals("account-id", request.header("ChatGPT-Account-ID"))
            val json = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
                .also { bodies += it }
            assertTrue(json.get("stream").asBoolean)
            assertFalse(json.get("store").asBoolean)
            assertFalse(json.has("max_output_tokens"))
            assertFalse(json.has("temperature"))
            assertFalse(json.has("top_p"))
            if (bodies.size < 4) assertFalse(json.has("reasoning"))
            val output = when (bodies.size) {
                2 -> {
                    assertEquals("required", json.get("tool_choice").asString)
                    val tool = json.getAsJsonArray("tools").single().asJsonObject
                    assertEquals("probe_echo", tool.get("name").asString)
                    assertFalse(tool.getAsJsonObject("parameters").get("additionalProperties").asBoolean)
                    """[{"type":"function_call","call_id":"call","name":"probe_echo","arguments":"{\"marker\":\"capability_ok\"}"}]"""
                }
                3 -> {
                    val content = json.getAsJsonArray("input").single().asJsonObject.getAsJsonArray("content")
                    assertEquals("input_text", content[0].asJsonObject.get("type").asString)
                    assertEquals("input_image", content[1].asJsonObject.get("type").asString)
                    assertEquals("data:image/png;base64,AA==", content[1].asJsonObject.get("image_url").asString)
                    """[{"type":"message","content":[{"type":"output_text","text":"1234"}]}]"""
                }
                4 -> {
                    assertEquals("low", json.getAsJsonObject("reasoning").get("effort").asString)
                    assertEquals("auto", json.getAsJsonObject("reasoning").get("summary").asString)
                    """[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"323"}]}]"""
                }
                else -> """[{"type":"message","content":[{"type":"output_text","text":"alpha beta gamma"}]}]"""
            }
            val reasoningTokens = if (bodies.size == 4) 12 else 0
            val response = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":$output," +
                "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"output_tokens_details\":{\"reasoning_tokens\":$reasoningTokens}}}}\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("text/event-stream".toMediaType())).build()
        }.build())
        client.setOAuthCredentialResolver { account ->
            assertEquals("oauth-account", account)
            OAuthRuntimeCredential("runtime-token", mapOf("ChatGPT-Account-ID" to "account-id"))
        }
        val codex = model().copy(protocol = OpenAIResponsesProtocol.name, provider = "openai-codex",
            baseUrl = "https://chatgpt.com/backend-api/codex", oauthAccountId = "oauth-account")
        val report = ModelCapabilityProber(client) { VisionChallenge("data:image/png;base64,AA==", "1234") }
            .run(codex, "probe")
        assertEquals(4, bodies.size)
        assertTrue(report.results.all { it.observation == CapabilityObservation.OBSERVED })
    }

    @Test fun testsRealRequestShapesAndDoesNotInferHiddenReasoning() = runBlocking {
        val bodies = mutableListOf<com.google.gson.JsonObject>()
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = JsonParser.parseString(body).asJsonObject.also { bodies += it }
            val tools = json.has("tools")
            val vision = json.getAsJsonArray("messages")[0].asJsonObject.get("content").isJsonArray
            val streaming = json.get("stream")?.asBoolean == true
            val response = when {
                tools -> """{"choices":[{"message":{"content":"","tool_calls":[{"id":"call","type":"function","function":{"name":"probe_echo","arguments":"{\"marker\":\"capability_ok\"}"}}]},"finish_reason":"tool_calls"}]}"""
                vision -> """{"choices":[{"message":{"content":"1234"}}]}"""
                streaming -> "data: {\"choices\":[{\"delta\":{\"content\":\"alpha beta gamma\"}}]}\n\ndata: [DONE]\n\n"
                else -> """{"choices":[{"message":{"content":"323"}}]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody((if (streaming) "text/event-stream" else "application/json").toMediaType())).build()
        }.build())
        val report = ModelCapabilityProber(client) { VisionChallenge("data:image/png;base64,AA==", "1234") }
            .run(model(), "probe")
        assertEquals(4, bodies.size)
        assertTrue(bodies[0].get("stream").asBoolean)
        assertTrue(bodies[1].has("tools"))
        assertTrue(bodies[2].getAsJsonArray("messages")[0].asJsonObject.get("content").isJsonArray)
        assertEquals("low", bodies[3].get("reasoning_effort").asString)
        assertEquals(CapabilityObservation.NOT_OBSERVED, report.results.last().observation)
        assertTrue(report.results.take(3).all { it.observation == CapabilityObservation.OBSERVED })
        assertFalse(model().supportsTools)
    }

    @Test fun bufferedResponsesDoNotProveStreamingAndErrorsRedactCredentials() = runBlocking {
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"hello"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val report = ModelCapabilityProber(client) { VisionChallenge("data:image/png;base64,AA==", "1234") }.run(model(), "probe")
        assertTrue(report.results.all { it.observation == CapabilityObservation.NOT_OBSERVED })
        val failing = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Denied")
                .body("test-key denied".toResponseBody("text/plain".toMediaType())).build()
        }.build())
        val errors = ModelCapabilityProber(failing) { VisionChallenge("data:image/png;base64,AA==", "1234") }.run(model(), "probe")
        assertTrue(errors.results.all { it.observation == CapabilityObservation.ERROR && !it.error.orEmpty().contains("test-key") })
    }

    @Test fun savedReportsAreIsolatedByProfileAndFingerprintCoversRequestConfiguration() {
        val directory = Files.createTempDirectory("model-report").toFile()
        try {
            val report = ModelCapabilityReport("model", capabilityFingerprint(model()), "2026-10-13T00:00:00Z",
                ModelCapability.entries.map { ModelCapabilityResult(it, CapabilityObservation.OBSERVED, 10) })
            ModelCapabilityReportStore(directory, "profile-a").write(report)
            assertEquals(report, ModelCapabilityReportStore(directory, "profile-a").read("model"))
            assertNull(ModelCapabilityReportStore(directory, "profile-b").read("model"))
            assertNotEquals(report.fingerprint, capabilityFingerprint(model().copy(baseUrl = "https://other.example/v1")))
            assertEquals(report.fingerprint, capabilityFingerprint(model().copy(supportsTools = true)))
        } finally { directory.deleteRecursively() }
    }
}
