package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class LocalAiClientEmbeddingTest {
    private fun model(dimensions: Int = 4096, name: String = "Qwen/Qwen3-Embedding-8B") = LocalAiModelEntity(
        "embedding-fixture", "Embedding", protocol = "openai_chat", apiKey = "fixture-key",
        baseUrl = "https://api.example.com/v1", model = name, purpose = "embedding",
        dimensions = dimensions, createdAt = "now"
    )

    private fun client(
        dimension: Int, captured: (String, JsonObject) -> Unit, status: Int = 200
    ): LocalAiClient = LocalAiClient(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        captured(request.url.encodedPath, JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject)
        val body = if (status == 200) Gson().toJson(mapOf("data" to listOf(
            mapOf("index" to 0, "embedding" to List(dimension) { if (it == 0) 1f else 0f })
        ), "usage" to mapOf("prompt_tokens" to 2))) else """{"error":{"message":"unsupported dimensions"}}"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
            .message(if (status == 200) "OK" else "Bad Request")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build())

    @Test fun connectivityTestUsesEmbeddingsAndReports4096InsteadOfChat() = runBlocking {
        var calls = 0
        val result = client(4096, { path, body ->
            calls++
            assertEquals("/v1/embeddings", path)
            assertEquals(4096, body["dimensions"].asInt)
            assertEquals("Qwen/Qwen3-Embedding-8B", body["model"].asString)
            assertEquals("你好", body["input"].asJsonArray.single().asString)
            assertFalse(body.has("messages")); assertFalse(body.has("temperature")); assertFalse(body.has("stream"))
        }).testModel(model())
        assertEquals(1, calls); assertNull(result.error); assertTrue(result.content.contains("4096"))
    }

    @Test fun customDimensionIsSentAndDefaultLeavesItOut() = runBlocking {
        client(1280, { _, body -> assertEquals(1280, body["dimensions"].asInt) })
            .createEmbeddings(model(1280), listOf("偏好"))
        val result = client(4096, { _, body -> assertFalse(body.has("dimensions")) })
            .createEmbeddings(model(0), listOf("问题"))
        assertEquals(4096, result.vectors.single().size)
    }

    @Test fun fixedBgeModelKeepsNativeDimensionsEvenWithLegacySetting() = runBlocking {
        val result = client(1024, { _, body -> assertFalse(body.has("dimensions")) })
            .testModel(model(1536, "BAAI/bge-m3"))
        assertNull(result.error); assertTrue(result.content.contains("1024"))
        assertNull(requestedEmbeddingDimensions(model(4096, "Pro/BAAI/bge-m3")))
    }

    @Test fun failedEmbeddingTestReportsHttpErrorOnceWithoutChatFallback() = runBlocking {
        var calls = 0
        val result = client(0, { path, _ -> calls++; assertEquals("/v1/embeddings", path) }, 400).testModel(model())
        assertEquals(1, calls); assertEquals(400, result.statusCode); assertNotNull(result.error)
    }

    @Test fun ignoredDimensionOverrideCannotPretendToHaveTakenEffect() = runBlocking {
        val result = client(1024, { _, body -> assertEquals(4096, body["dimensions"].asInt) }).testModel(model())
        assertTrue(result.error.orEmpty().contains("维度与请求不一致"))
    }

    @Test fun invalidCustomValuesAreRejectedAndDimensionChangeSeparatesCaches() {
        for (value in listOf("", "0", "-1", "1.0", "abc", "65537", "9999999999999999")) {
            assertFalse(value, validEmbeddingDimensionsInput(value))
        }
        for (value in listOf("1", " 1280 ", "4096", "65536")) assertTrue(validEmbeddingDimensionsInput(value))
        assertNotEquals(embeddingCacheIdentity(model()), embeddingCacheIdentity(model(1280)))
        assertNotEquals(embeddingCacheIdentity(model()), embeddingCacheIdentity(model(0)))
        assertEquals(embeddingCacheIdentity(model(1024, "BAAI/bge-m3")), embeddingCacheIdentity(model(1536, "BAAI/bge-m3")))
    }
}
