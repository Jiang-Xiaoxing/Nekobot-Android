package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Gemini Live 原生双向音频：16kHz 输入、24kHz 输出，显式控制每轮语音边界。 */
internal class GeminiLiveClient(private val client: OkHttpClient) {
    fun streamTurn(
        config: RealtimeModelConfig,
        instructions: String,
        context: List<RealtimeContextMessage>,
        audio: ByteArray,
        probe: Boolean = false
    ): Flow<RealtimeVoiceEvent> = callbackFlow {
        require(config.apiKey.isNotBlank() && config.apiKey != "********") { "Gemini Live 缺少 API Key" }
        require(probe || audio.isNotEmpty()) { "没有可发送的语音数据" }
        val gson = Gson()
        val ended = AtomicBoolean(false)
        val uploaded = AtomicBoolean(false)
        val user = StringBuilder()
        val assistant = StringBuilder()
        var completionJob: kotlinx.coroutines.Job? = null
        val request = Request.Builder().url(buildGeminiLiveWebSocketUrl(config)).build()
        val socket = client.withModelProxy(config.proxyUrl).newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(gson.toJson(buildGeminiLiveSetup(config, instructions, context)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended.get()) return
                try {
                    val event = JsonParser.parseString(text).asJsonObject
                    if (event.has("error")) error(event.getAsJsonObject("error").get("message")?.asString ?: "Gemini Live 服务错误")
                    if (event.has("setupComplete") && uploaded.compareAndSet(false, true)) {
                        trySend(RealtimeVoiceEvent.Connected)
                        if (probe) {
                            ended.set(true)
                            webSocket.close(1000, "probe complete")
                            close()
                            return
                        }
                        launch {
                            fun send(value: Any) {
                                check(webSocket.send(gson.toJson(mapOf("realtimeInput" to value)))) { "Gemini Live 音频发送失败" }
                            }
                            try {
                                send(mapOf("activityStart" to emptyMap<String, Any>()))
                                var offset = 0
                                while (offset < audio.size && !ended.get()) {
                                    val end = minOf(offset + 3_200, audio.size)
                                    send(mapOf("audio" to mapOf(
                                        "mimeType" to "audio/pcm;rate=16000",
                                        "data" to Base64.getEncoder().encodeToString(audio.copyOfRange(offset, end))
                                    )))
                                    offset = end
                                }
                                send(mapOf("activityEnd" to emptyMap<String, Any>()))
                            } catch (error: Exception) {
                                if (ended.compareAndSet(false, true)) {
                                    trySend(RealtimeVoiceEvent.Failure(error.message ?: "Gemini Live 音频发送失败"))
                                    webSocket.cancel()
                                    close()
                                }
                            }
                        }
                    }
                    val content = event.getAsJsonObject("serverContent") ?: return
                    content.getAsJsonObject("inputTranscription")?.get("text")?.asString?.let {
                        user.append(it)
                        trySend(RealtimeVoiceEvent.UserTranscript(user.toString(), false))
                    }
                    content.getAsJsonObject("outputTranscription")?.get("text")?.asString?.let {
                        assistant.append(it)
                        trySend(RealtimeVoiceEvent.AssistantTranscript(assistant.toString(), false))
                    }
                    content.getAsJsonObject("modelTurn")?.getAsJsonArray("parts")?.forEach { part ->
                        val obj = part.asJsonObject
                        val inline = obj.getAsJsonObject("inlineData")
                        if (inline != null && inline.get("mimeType")?.asString.orEmpty().startsWith("audio/pcm")) {
                            trySend(RealtimeVoiceEvent.AudioDelta(Base64.getDecoder().decode(inline.get("data").asString)))
                        }
                    }
                    if (content.get("turnComplete")?.asBoolean == true && completionJob == null) {
                        // 转写独立于音频事件返回，等待最后一段字幕后保存消息。
                        completionJob = launch {
                            delay(500)
                            if (ended.compareAndSet(false, true)) {
                                trySend(RealtimeVoiceEvent.Completed(user.toString().trim(), assistant.toString().trim()))
                                webSocket.close(1000, "turn complete")
                                close()
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (ended.compareAndSet(false, true)) {
                        trySend(RealtimeVoiceEvent.Failure(error.message ?: "Gemini Live 响应无效"))
                        webSocket.cancel()
                        close()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ended.compareAndSet(false, true)) {
                    trySend(RealtimeVoiceEvent.Failure(t.message ?: "Gemini Live 连接失败"))
                    close()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ended.compareAndSet(false, true)) {
                    trySend(RealtimeVoiceEvent.Failure(reason.ifBlank { "Gemini Live 连接已关闭" }))
                    close()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (ended.compareAndSet(false, true)) {
                    trySend(RealtimeVoiceEvent.Failure(reason.ifBlank { "Gemini Live 服务关闭了会话" }))
                    close()
                }
            }
        })
        val timeoutJob = launch {
            delay(120_000)
            if (ended.compareAndSet(false, true)) {
                trySend(RealtimeVoiceEvent.Failure("Gemini Live 响应超时"))
                socket.cancel()
                close()
            }
        }
        awaitClose {
            ended.set(true)
            completionJob?.cancel()
            timeoutJob.cancel()
            socket.cancel()
        }
    }

    suspend fun testConnection(config: RealtimeModelConfig): Result<Unit> = runCatching {
        withTimeout(15_000) {
            val event = streamTurn(config, "", emptyList(), byteArrayOf(), probe = true).first()
            if (event is RealtimeVoiceEvent.Failure) error(event.message)
            check(event is RealtimeVoiceEvent.Connected) { "Gemini Live 会话初始化失败" }
        }
    }
}

internal fun buildGeminiLiveWebSocketUrl(config: RealtimeModelConfig): String {
    val base = config.baseUrl.trim().ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
        .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://").toHttpUrl()
    val url = base.newBuilder().apply {
        if (config.appendBaseUrlPath && !base.encodedPath.contains("BidiGenerateContent")) {
            // REST 基址转为原生 Live 路径；保留中转服务的自定义前缀。
            val prefix = base.encodedPath.trimEnd('/').removeSuffix("/v1beta").removeSuffix("/v1")
            encodedPath("$prefix/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
        }
        setQueryParameter("key", config.apiKey)
    }.build().toString()
    return if (url.startsWith("https://")) "wss://" + url.removePrefix("https://") else "ws://" + url.removePrefix("http://")
}

internal fun buildGeminiLiveSetup(
    config: RealtimeModelConfig, instructions: String, context: List<RealtimeContextMessage>
): JsonObject {
    val history = trimRealtimeContext(context, 24_000).joinToString("\n") { "${it.role}: ${it.content}" }
    val instruction = listOf(instructions.trim().take(12_000), history).filter { it.isNotBlank() }.joinToString("\n\n")
    return Gson().toJsonTree(mapOf("setup" to mapOf(
        "model" to "models/${config.model.removePrefix("models/")}",
        "generationConfig" to mapOf(
            "responseModalities" to listOf("AUDIO"),
            "maxOutputTokens" to config.maxOutputTokens,
            "speechConfig" to mapOf("voiceConfig" to mapOf("prebuiltVoiceConfig" to mapOf("voiceName" to config.voice.ifBlank { "Kore" })))
        ),
        "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to instruction.ifBlank { "自然地进行简洁的语音对话。" }))),
        "inputAudioTranscription" to emptyMap<String, Any>(),
        "outputAudioTranscription" to emptyMap<String, Any>(),
        "realtimeInputConfig" to mapOf("automaticActivityDetection" to mapOf("disabled" to true))
    ))).asJsonObject
}
