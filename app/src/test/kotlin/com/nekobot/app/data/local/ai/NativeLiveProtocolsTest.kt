package com.nekobot.app.data.local.ai

import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NativeLiveProtocolsTest {
    @Test fun grokTurnDisablesVadAndUsesCompatibleEvents() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = JsonParser.parseString(text).asJsonObject
                when (event.get("type").asString) {
                    "session.update" -> {
                        assertTrue(event.getAsJsonObject("session").get("turn_detection").isJsonNull)
                        webSocket.send("""{"type":"session.updated"}""")
                    }
                    "input_audio_buffer.append" -> assertArrayEquals(byteArrayOf(1, 2, 3, 4), Base64.getDecoder().decode(event.get("audio").asString))
                    "input_audio_buffer.commit" -> webSocket.send("""{"type":"input_audio_buffer.committed"}""")
                    "response.create" -> {
                        webSocket.send("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"hello"}""")
                        webSocket.send("""{"type":"response.audio_transcript.done","transcript":"welcome"}""")
                        webSocket.send("""{"type":"response.audio.delta","delta":"AQI="}""")
                        webSocket.send("""{"type":"response.done","response":{"status":"completed"}}""")
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        try {
            val config = config("grok").copy(baseUrl = server.url("/v1").toString())
            val events = withTimeout(8_000) { RealtimeVoiceClient().streamTurn(config, "role", emptyList(), byteArrayOf(1, 2, 3, 4)).toList() }
            assertEquals("welcome", events.filterIsInstance<RealtimeVoiceEvent.Completed>().single().assistantTranscript)
            assertEquals("hello", events.filterIsInstance<RealtimeVoiceEvent.Completed>().single().userTranscript)
            assertEquals("grok-voice-latest", server.takeRequest().requestUrl!!.queryParameter("model"))
        } finally { server.shutdown() }
    }

    @Test fun stepfunRealtimeCompletesTurnUsingOriginalOpenAiEvents() = runBlocking {
        val server = MockWebServer()
        val received = mutableListOf<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                received += text
                val event = JsonParser.parseString(text).asJsonObject
                when (event.get("type").asString) {
                    "session.update" -> {
                        val session = event.getAsJsonObject("session")
                        assertEquals("realtime", session.get("type").asString)
                        assertEquals("audio", session.getAsJsonArray("output_modalities").single().asString)
                        assertFalse(session.has("modalities"))
                        val audio = session.getAsJsonObject("audio")
                        assertEquals(24000, audio.getAsJsonObject("input").getAsJsonObject("format").get("rate").asInt)
                        assertTrue(audio.getAsJsonObject("input").get("turn_detection").isJsonNull)
                        assertEquals(24000, audio.getAsJsonObject("output").getAsJsonObject("format").get("rate").asInt)
                        socket.send("""{"type":"session.updated"}""")
                    }
                    "input_audio_buffer.commit" ->
                        socket.send("""{"type":"input_audio_buffer.committed"}""")
                    "response.create" -> {
                        assertEquals("audio", event.getAsJsonObject("response").getAsJsonArray("output_modalities").single().asString)
                        socket.send("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"你好"}""")
                        socket.send("""{"type":"response.text.delta","delta":"你好"}""")
                        socket.send("""{"type":"response.audio_transcript.delta","delta":"你好"}""")
                        socket.send("""{"type":"response.audio_transcript.done","transcript":"你好"}""")
                        socket.send("""{"type":"response.audio.delta","delta":"AQI="}""")
                        socket.send("""{"type":"response.done","response":{"status":"completed"}}""")
                    }
                }
            }
            override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, reason) }
        }))
        server.start()
        try {
            val config = config("stepfun").copy(baseUrl = server.url("/v1").toString())
            val events = withTimeout(8_000) {
                RealtimeVoiceClient().streamTurn(
                    config,
                    "角色设定",
                    listOf(RealtimeContextMessage("user", "之前的对话"), RealtimeContextMessage("assistant", "历史回复")),
                    byteArrayOf(1, 2, 3, 4)
                ).toList()
            }
            val request = server.takeRequest()
            assertEquals("/v1/realtime", request.requestUrl?.encodedPath)
            assertEquals(REALTIME_STEPFUN_DEFAULT_MODEL, request.requestUrl?.queryParameter("model"))
            assertEquals("Bearer test", request.getHeader("Authorization"))
            val outgoing = received.map { JsonParser.parseString(it).asJsonObject }
            assertEquals(listOf("session.update", "conversation.item.create", "conversation.item.create",
                "input_audio_buffer.append", "input_audio_buffer.commit", "response.create"),
                outgoing.map { it.get("type").asString })
            assertEquals(outgoing.size, outgoing.map { it.get("event_id").asString }.distinct().size)
            val history = outgoing.filter { it.get("type").asString == "conversation.item.create" }
            assertEquals(buildRealtimeContextEvents(listOf(
                RealtimeContextMessage("user", "之前的对话"), RealtimeContextMessage("assistant", "历史回复")
            ), assistantContentType = "text"), history.map { it.deepCopy().apply { remove("event_id") } })
            assertEquals("text", history.last().getAsJsonObject("item")
                .getAsJsonArray("content")[0].asJsonObject.get("type").asString)
            assertArrayEquals(resampleRealtimeInput(byteArrayOf(1, 2, 3, 4)),
                Base64.getDecoder().decode(outgoing.first { it.has("audio") }.get("audio").asString))
            assertEquals("你好", events.filterIsInstance<RealtimeVoiceEvent.Completed>().single().userTranscript)
            assertEquals("你好", events.filterIsInstance<RealtimeVoiceEvent.Completed>().single().assistantTranscript)
            assertArrayEquals(byteArrayOf(1, 2), events.filterIsInstance<RealtimeVoiceEvent.AudioDelta>().single().pcm16)
        } finally { server.shutdown() }
    }

    @Test fun geminiLiveCompletesNativeWebSocketRoundTrip() = runBlocking {
        val server = MockWebServer()
        val received = mutableListOf<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                received += text
                val event = JsonParser.parseString(text).asJsonObject
                if (event.has("setup")) socket.send("""{"setupComplete":{}}""")
                if (event.getAsJsonObject("realtimeInput")?.has("activityEnd") == true) {
                    socket.send("""{"serverContent":{"inputTranscription":{"text":"你好"}}}""")
                    socket.send("""{"serverContent":{"outputTranscription":{"text":"欢迎"},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQI="}}]}}}""")
                    socket.send("""{"serverContent":{"turnComplete":true}}""")
                }
            }
            override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, reason) }
        }))
        server.start()
        try {
            val config = config("google").copy(baseUrl = server.url("/live").toString(), appendBaseUrlPath = false)
            val events = withTimeout(8_000) {
                RealtimeVoiceClient().streamTurn(config, "角色设定", listOf(RealtimeContextMessage("user", "历史")), byteArrayOf(1, 2, 3, 4)).toList()
            }
            val setup = JsonParser.parseString(received.first()).asJsonObject.getAsJsonObject("setup")
            assertTrue(setup.getAsJsonObject("systemInstruction").toString().contains("历史"))
            assertTrue(setup.getAsJsonObject("realtimeInputConfig").getAsJsonObject("automaticActivityDetection").get("disabled").asBoolean)
            val audio = received.map { JsonParser.parseString(it).asJsonObject.getAsJsonObject("realtimeInput") }.filterNotNull()
                .first { it.has("audio") }.getAsJsonObject("audio")
            assertEquals("audio/pcm;rate=16000", audio.get("mimeType").asString)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), Base64.getDecoder().decode(audio.get("data").asString))
            assertEquals("你好", events.filterIsInstance<RealtimeVoiceEvent.Completed>().single().userTranscript)
            assertEquals("欢迎", events.filterIsInstance<RealtimeVoiceEvent.Completed>().single().assistantTranscript)
            assertArrayEquals(byteArrayOf(1, 2), events.filterIsInstance<RealtimeVoiceEvent.AudioDelta>().single().pcm16)
        } finally { server.shutdown() }
    }

    @Test fun geminiProbeWaitsForSetupAndReportsErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(socket: WebSocket, text: String) {
                socket.send("""{"error":{"message":"model unavailable"}}""")
            }
        }))
        server.start()
        try {
            val result = RealtimeVoiceClient().testConnection(config("gemini").copy(baseUrl = server.url("/live").toString(), appendBaseUrlPath = false))
            assertTrue(result.isFailure)
            assertEquals("model unavailable", result.exceptionOrNull()?.message)
        } finally { server.shutdown() }
    }

    @Test fun grokSessionUsesItsVoiceAndSeparateInputOutputRates() {
        val config = config("grok")
        val session = buildRealtimeSessionUpdate(config, "角色").getAsJsonObject("session")
        assertEquals("eve", session.get("voice").asString)
        assertTrue(session.get("turn_detection").isJsonNull)
        assertFalse(session.has("type"))
        assertEquals(16000, session.getAsJsonObject("audio").getAsJsonObject("input").getAsJsonObject("format").get("rate").asInt)
        assertEquals(24000, session.getAsJsonObject("audio").getAsJsonObject("output").getAsJsonObject("format").get("rate").asInt)
        assertFalse(buildRealtimeResponseCreate(config).has("response"))
    }

    @Test fun liveUrlPreservesProxyPrefixAndReplacesKey() {
        val url = buildGeminiLiveWebSocketUrl(config("google").copy(baseUrl = "https://proxy.test/prefix/v1beta?region=cn&key=old"))
        assertEquals("wss://proxy.test/prefix/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?region=cn&key=test", url)
    }

    @Test fun resamplingKeepsDurationAndSignedSamples() {
        val output = resampleRealtimeInput(byteArrayOf(0, 0, -24, 3))
        assertArrayEquals(byteArrayOf(0, 0, -102, 2, -24, 3), output)
        assertEquals(48_000, resampleRealtimeInput(ByteArray(32_000)).size)
        assertEquals(0, resampleRealtimeInput(byteArrayOf()).size)
        assertEquals(0, resampleRealtimeInput(byteArrayOf(1, 2)).size % 2)
    }

    @Test fun stepfunLiveEntityDefaultsToStepAudioRealtime() {
        val config = config("stepfun")
        assertEquals(REALTIME_STEPFUN_DEFAULT_MODEL, config.model)
        assertEquals(REALTIME_STEPFUN_DEFAULT_BASE_URL, config.baseUrl)
        assertEquals(REALTIME_STEPFUN_DEFAULT_VOICE, config.voice)
        assertTrue(config.isStepfunRealtime)
    }

    private fun config(provider: String) = LocalAiModelEntity(
        id = "live", name = "Live", protocol = "openai_chat", provider = provider,
        apiKey = "test", baseUrl = "", model = "", purpose = "live", createdAt = "2026-10-14"
    ).toRealtimeModelConfig()
}
