package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
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
