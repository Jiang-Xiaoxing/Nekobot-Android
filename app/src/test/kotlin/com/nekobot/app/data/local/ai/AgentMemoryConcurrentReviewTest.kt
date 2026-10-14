package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AgentMemoryConcurrentReviewTest {
    @Test fun staleAutomaticReviewDoesNotReplaceAManualEditInTheSameSection() = runBlocking {
        var memory = "# 用户偏好\n- 用户喜欢红色主题"
        val manual = "# 用户偏好\n- 用户喜欢绿色主题"
        var requests = 0
        var writes = 0
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            memory = manual
            val body = Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf(
                "content" to "replace|preference|用户喜欢蓝色主题")))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val writer = AgentMemoryWriter(client, readMemory = { memory }, writeMemory = { memory = it; writes++ },
            aiModelProvider = { LocalAiModelEntity("memory", "Memory", OpenAIChatProtocol.name,
                apiKey = "", baseUrl = "https://api.example.com/v1", model = "custom", createdAt = "now") })
        val changed = writer.extractAndAppend("session", "我一直喜欢红色主题，这是我的偏好。".repeat(50), "明白，我会记住。")
        assertEquals(1, requests)
        assertEquals(0, changed)
        assertEquals(0, writes)
        assertEquals(manual, memory)
    }
}
