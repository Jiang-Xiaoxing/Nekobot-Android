package com.nekobot.app.data.local.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StepfunRealtimeRegressionTest {
    private fun config(server: MockWebServer) = RealtimeModelConfig(
        id = "stepfun", name = "StepAudio", apiKey = "test-key",
        baseUrl = server.url("/v1").toString(), model = REALTIME_STEPFUN_DEFAULT_MODEL,
        voice = "marin", provider = "stepfun"
    )

    private abstract class Listener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    @Test
    fun stepfunRealtimeToolsKeepFunctionEnvelopeButToolCallingStaysDisabled() {
        val schema = mapOf("type" to "object", "required" to listOf("city"),
            "properties" to mapOf("city" to mapOf("type" to "string")))
        val tools = listOf(mapOf("type" to "function", "function" to mapOf(
            "name" to "weather", "description" to "天气", "parameters" to schema
        )))
        // 服务端按 session.tools[i].function.name 校验，必须保留 function 外层。
        val config = RealtimeModelConfig(
            "stepfun", "StepAudio", "test", REALTIME_STEPFUN_DEFAULT_BASE_URL,
            REALTIME_STEPFUN_DEFAULT_MODEL, voice = "marin", provider = "stepfun"
        )
        val session = buildRealtimeSessionUpdate(config, "role", tools = tools).getAsJsonObject("session")
        val registered = session.getAsJsonArray("tools").single().asJsonObject
        assertEquals("function", registered.get("type").asString)
        assertEquals("weather", registered.getAsJsonObject("function").get("name").asString)
        // 与可用的 OpenAI 路径对齐：Realtime 会话不声明 Agent 工具，避免服务端校验失败。
        assertFalse(config.supportsRealtimeFunctionCalling)
    }

    @Test
    fun invalidEventReportsTheCorrelatedEventNotTheLastSentEvent() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : Listener() {
            private var historyEventId = ""
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = JsonParser.parseString(text).asJsonObject
                if (event.get("type").asString == "conversation.item.create") {
                    historyEventId = event.get("event_id").asString
                }
                if (event.get("type").asString == "input_audio_buffer.commit") {
                    val error = JsonParser.parseString("""{"type":"error","error":{"message":"invalid event format","code":"invalid_param","param":"item.content"}}""").asJsonObject
                    error.getAsJsonObject("error").addProperty("event_id", historyEventId)
                    webSocket.send(error.toString())
                }
            }
        }))
        server.start()
        try {
            val events = withTimeout(8_000) {
                RealtimeVoiceClient().streamTurn(config(server), "private instructions",
                    listOf(RealtimeContextMessage("assistant", "private history")), ByteArray(640_000)).toList()
            }
            val message = events.filterIsInstance<RealtimeVoiceEvent.Failure>().single().message
            assertEquals("invalid event format [conversation.item.create; invalid_param; item.content]", message)
            assertFalse(message.contains("private"))
            assertFalse(message.contains("test-key"))
            assertTrue(events.filterIsInstance<RealtimeVoiceEvent.Completed>().isEmpty())
        } finally { server.shutdown() }
    }

    @Test
    fun multibyteServerErrorDoesNotBreakWebSocketCloseOrProbeCompletion() = runBlocking {
        val server = MockWebServer()
        val message = "无效的事件格式".repeat(20)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : Listener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val error = JsonObject().apply {
                    addProperty("type", "error")
                    addProperty("message", message)
                }
                webSocket.send(error.toString())
            }
        }))
        server.start()
        try {
            val result = withTimeout(8_000) { RealtimeVoiceClient().testConnection(config(server)) }
            assertEquals("$message [session.update]", result.exceptionOrNull()?.message)
        } finally { server.shutdown() }
    }

    @Test
    fun connectionProbeUsesTheSameOpenAiSessionContractAndReportsSessionErrors() = runBlocking {
        val server = MockWebServer()
        val sessions = CopyOnWriteArrayList<JsonObject>()
        for (success in listOf(true, false)) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : Listener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = JsonParser.parseString(text).asJsonObject
                    sessions += event
                    if (success) {
                        webSocket.send("""{"type":"session.updated"}""")
                    } else {
                        webSocket.send("""{"type":"error","message":"invalid event format"}""")
                    }
                }
            }))
        }
        server.start()
        try {
            val client = RealtimeVoiceClient()
            assertTrue(client.testConnection(config(server)).isSuccess)
            val failed = client.testConnection(config(server))
            assertEquals("invalid event format [session.update]", failed.exceptionOrNull()?.message)
            sessions.forEach { event ->
                assertEquals("realtime", event.getAsJsonObject("session").get("type").asString)
                assertTrue(event.has("event_id"))
                assertTrue(event.getAsJsonObject("session").getAsJsonObject("audio")
                    .getAsJsonObject("input").get("turn_detection").isJsonNull)
            }
        } finally { server.shutdown() }
    }
}
