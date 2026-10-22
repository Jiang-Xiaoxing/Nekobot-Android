package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.AiModel
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class RealtimeModelConfig(
    val id: String,
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val appendBaseUrlPath: Boolean = true,
    val proxyUrl: String = "",
    val voice: String = "marin",
    val transcriptionModel: String = "gpt-4o-mini-transcribe",
    val language: String = "zh",
    val maxOutputTokens: Int = 4096,
    /** 模型供应商标识，用于在 Realtime 协议层做差异化处理（如 qwen / dashscope 走阿里云 DashScope Realtime）。 */
    val provider: String? = null
) {
    private val realtimeProtocol: RealtimeProtocol
        get() = RealtimeProtocol.resolve(provider, model)

    /** 是否走阿里云 DashScope Qwen Realtime 协议。 */
    val isQwenRealtime: Boolean
        get() = realtimeProtocol == RealtimeProtocol.QWEN

    /** 是否走火山引擎 Ark SeedRealtime 协议。 */
    val isSeedRealtime: Boolean
        get() = realtimeProtocol == RealtimeProtocol.SEED

    /** 是否走智谱 GLM-Realtime 协议。 */
    val isGlmRealtime: Boolean
        get() = realtimeProtocol == RealtimeProtocol.GLM

    /** 是否走阶跃星辰 StepFun Realtime 协议。 */
    val isStepfunRealtime: Boolean
        get() = realtimeProtocol == RealtimeProtocol.STEPFUN

    /** 当前协议是否支持应用内 Agent 函数工具。 */
    val supportsRealtimeFunctionCalling: Boolean
        get() = isQwenRealtime

    val isGeminiLive: Boolean
        get() = realtimeProtocol == RealtimeProtocol.GEMINI

    val isGrokRealtime: Boolean
        get() = realtimeProtocol == RealtimeProtocol.GROK

    /** 国内 Realtime 服务采用兼容旧版 OpenAI Realtime 的扁平会话字段。 */
    internal val usesFlatSessionUpdate: Boolean
        get() = realtimeProtocol in setOf(RealtimeProtocol.QWEN, RealtimeProtocol.SEED, RealtimeProtocol.GLM)
}

private enum class RealtimeProtocol {
    OPENAI,
    QWEN,
    SEED,
    GLM,
    STEPFUN,
    GEMINI,
    GROK;

    companion object {
        private val STEPFUN_PROVIDERS = setOf("stepfun", "stepfun-ai", "step")

        fun resolve(provider: String?, model: String): RealtimeProtocol {
            val normalizedProvider = provider.orEmpty().trim().lowercase()
            val normalizedModel = model.trim().lowercase()
            val isStepfunModel = normalizedModel.startsWith("stepaudio-") ||
                normalizedModel.startsWith("step-audio-") ||
                normalizedModel.startsWith("step-1o-audio")
            return when {
                normalizedProvider == "openai" && isStepfunModel -> OPENAI
                normalizedProvider in STEPFUN_PROVIDERS || isStepfunModel -> STEPFUN
                MultimodalProviderSupport.normalize(normalizedProvider) == "gemini" ||
                    normalizedModel.startsWith("gemini") -> GEMINI
                MultimodalProviderSupport.normalize(normalizedProvider) == "grok" ||
                    normalizedModel.startsWith("grok") -> GROK
                normalizedProvider in setOf("qwen", "dashscope", "tongyi") ||
                    normalizedModel.contains("qwen") && normalizedModel.contains("realtime") -> QWEN
                normalizedProvider in setOf("doubao", "seed", "volcengine", "bytedance", "ark", "volces") ||
                    normalizedModel.contains("seed") && normalizedModel.contains("realtime") -> SEED
                normalizedProvider in setOf("glm", "zhipu", "bigmodel") ||
                    normalizedModel.contains("glm") && normalizedModel.contains("realtime") -> GLM
                else -> OPENAI
            }
        }
    }
}

data class RealtimeContextMessage(
    val role: String,
    val content: String
)

/** Qwen Realtime 返回的函数调用。 */
data class RealtimeFunctionCall(
    val callId: String,
    val name: String,
    val arguments: String
)

sealed interface RealtimeVoiceEvent {
    data object Connected : RealtimeVoiceEvent
    data class UserTranscript(val text: String, val isFinal: Boolean) : RealtimeVoiceEvent
    data class AssistantTranscript(val text: String, val isFinal: Boolean) : RealtimeVoiceEvent
    data class AudioDelta(val pcm16: ByteArray) : RealtimeVoiceEvent
    data class Completed(
        val userTranscript: String,
        val assistantTranscript: String
    ) : RealtimeVoiceEvent
    data class Failure(val message: String) : RealtimeVoiceEvent
}

fun LocalAiModelEntity.toRealtimeModelConfig(): RealtimeModelConfig {
    val initialProtocol = RealtimeProtocol.resolve(provider, model)
    val resolvedModel = model.ifBlank { initialProtocol.defaultModel }
    val protocol = RealtimeProtocol.resolve(provider, resolvedModel)
    return RealtimeModelConfig(
        id = id,
        name = name,
        apiKey = apiKey,
        baseUrl = baseUrl.ifBlank {
            if (protocol == RealtimeProtocol.STEPFUN) REALTIME_STEPFUN_DEFAULT_BASE_URL else baseUrl
        },
        model = resolvedModel,
        appendBaseUrlPath = appendBaseUrlPath,
        proxyUrl = proxyUrl,
        voice = ttsVoice.takeUnless { it.isBlank() || it == "default" }
            ?: protocol.defaultVoice,
        transcriptionModel = sttModel.ifBlank {
            protocol.defaultTranscriptionModel
        },
        language = language,
        maxOutputTokens = (maxTokens ?: 4096).coerceIn(1, 4096),
        provider = provider
    )
}

fun AiModel.toRealtimeModelConfig(): RealtimeModelConfig {
    val initialProtocol = RealtimeProtocol.resolve(provider, model.orEmpty())
    val resolvedModel = model.orEmpty().ifBlank {
        initialProtocol.defaultModel
    }
    val protocol = RealtimeProtocol.resolve(provider, resolvedModel)
    return RealtimeModelConfig(
        id = id.orEmpty(),
        name = displayName,
        apiKey = apiKey.orEmpty(),
        baseUrl = baseUrl.orEmpty().ifBlank {
            if (protocol == RealtimeProtocol.STEPFUN) REALTIME_STEPFUN_DEFAULT_BASE_URL else ""
        },
        model = resolvedModel,
        appendBaseUrlPath = appendBaseUrlPath ?: true,
        voice = ttsVoice.takeUnless { it.isNullOrBlank() || it == "default" }
            ?: protocol.defaultVoice,
        transcriptionModel = sttModel.orEmpty().ifBlank {
            protocol.defaultTranscriptionModel
        },
        language = sttLanguage ?: language ?: "zh",
        maxOutputTokens = (maxTokens ?: 4096).coerceIn(1, 4096),
        provider = provider
    )
}

class RealtimeVoiceClient(
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    fun streamTurn(
        config: RealtimeModelConfig,
        instructions: String,
        context: List<RealtimeContextMessage>,
        pcm16: ByteArray,
        tools: List<Map<String, Any>> = emptyList(),
        onToolCall: (suspend (RealtimeFunctionCall) -> Map<String, Any>)? = null
    ): Flow<RealtimeVoiceEvent> = if (config.isGeminiLive) {
        require(tools.isEmpty()) { "Gemini Live 当前不支持本地 Agent 工具调用" }
        GeminiLiveClient(baseClient).streamTurn(config, instructions, context, pcm16)
    } else callbackFlow {
        require(config.apiKey.isNotBlank() && config.apiKey != "********") {
            "Realtime 模型缺少可用的 API Key"
        }
        require(pcm16.isNotEmpty()) { "没有可发送的语音数据" }
        require(tools.isEmpty() || config.supportsRealtimeFunctionCalling) {
            "当前 Realtime 模型不支持本地 Agent 工具调用"
        }
        require(tools.isEmpty() || onToolCall != null) {
            "启用 Realtime 工具调用时必须提供工具执行器"
        }
        // Qwen Realtime 手动 commit 模式下，音频过短会触发 "buffer too small, or have no audio"。
        // 至少需要约 0.5 秒音频（24000Hz × 0.5s × 2 bytes = 24000 bytes）。
        val minAudioBytes = if (config.isQwenRealtime) REALTIME_QWEN_MIN_AUDIO_BYTES else 1
        require(pcm16.size >= minAudioBytes) {
            "语音太短，请至少说 0.5 秒"
        }

        val client = baseClient.withModelProxy(config.proxyUrl)
        val eventSender = RealtimeEventSender(attachEventIds = config.isStepfunRealtime)
        // 聊天录音统一为 16kHz；除 Qwen/Grok 外，Realtime PCM16 输入按 24kHz 上传。
        val inputAudio = if (config.isQwenRealtime || config.isGrokRealtime) pcm16 else resampleRealtimeInput(pcm16)
        val request = Request.Builder()
            .url(buildRealtimeWebSocketUrl(
                config.baseUrl.ifBlank {
                    when {
                        config.isGrokRealtime -> "https://api.x.ai/v1"
                        config.isStepfunRealtime -> REALTIME_STEPFUN_DEFAULT_BASE_URL
                        else -> ""
                    }
                },
                config.appendBaseUrlPath,
                config.model,
                config.isQwenRealtime,
                config.isStepfunRealtime
            ))
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        val userTranscript = StringBuilder()
        val assistantTranscript = StringBuilder()
        val stepfunAudioTranscript = StringBuilder()
        val stepfunTextTranscript = StringBuilder()
        val terminal = AtomicBoolean(false)
        var responseDone = false
        var responseActive = false
        var responseCreateSent = false
        var audioUploadStarted = false
        var inputTranscriptDone = false
        var completionEmitted = false
        var closeRequested = false
        var completionJob: Job? = null
        var closeTimeoutJob: Job? = null
        var responseCreateJob: Job? = null
        val pendingFunctionCalls = linkedMapOf<String, RealtimeFunctionCall>()
        lateinit var socket: WebSocket

        fun emitCompletedAndClose() {
            if (terminal.get() || closeRequested) return
            if (!completionEmitted) {
                completionEmitted = true
                trySend(
                    RealtimeVoiceEvent.Completed(
                        userTranscript = userTranscript.toString().trim(),
                        assistantTranscript = if (config.isStepfunRealtime) {
                            stepfunAudioTranscript.toString().ifBlank { stepfunTextTranscript.toString() }.trim()
                        } else {
                            assistantTranscript.toString().trim()
                        }
                    )
                )
            }
            // response.done 已表示 Omni Realtime 本轮生成完成。仅请求 WebSocket 正常关闭，
            // 必须等待 onClosed 才关闭 callbackFlow，避免 awaitClose 立即 cancel() 中断握手。
            closeRequested = true
            if (!socket.close(1000, "turn completed")) {
                terminal.set(true)
                close()
                return
            }
            closeTimeoutJob = launch {
                delay(5_000)
                if (terminal.compareAndSet(false, true)) {
                    socket.cancel()
                    close()
                }
            }
        }

        fun finishOpenAiAfter(delayMs: Long) {
            completionJob?.cancel()
            completionJob = launch {
                delay(delayMs)
                emitCompletedAndClose()
            }
        }

        fun scheduleQwenClose() {
            if (!responseDone || pendingFunctionCalls.isNotEmpty() || terminal.get()) return
            completionJob?.cancel()
            completionJob = launch {
                // input_audio_transcription.completed 通常紧随 response.done；给它一个
                // 明确的窗口，避免在最终识别结果到达前提交不完整字幕。
                delay(if (inputTranscriptDone) 80L else 5_000L)
                emitCompletedAndClose()
            }
        }

        fun failTurn(message: String) {
            if (terminal.compareAndSet(false, true)) {
                trySend(RealtimeVoiceEvent.Failure(message))
                socket.close(1011, "realtime error")
                close()
            }
        }

        fun createResponseIfIdle(webSocket: WebSocket) {
            if (terminal.get() || responseCreateSent || responseActive) return
            responseCreateSent = true
            if (!eventSender.send(webSocket, buildRealtimeResponseCreate(config)) && terminal.compareAndSet(false, true)) {
                trySend(RealtimeVoiceEvent.Failure("Realtime 响应创建失败"))
                webSocket.cancel()
                close()
            }
        }

        fun scheduleQwenResponseCreate(webSocket: WebSocket) {
            responseCreateJob?.cancel()
            responseCreateJob = launch {
                // qwen-audio-3.0-realtime-plus 可能在提交音频后立即自动创建响应。
                // 留出一个短窗口接收 response.created，避免再发送 response.create。
                delay(REALTIME_QWEN_AUTO_RESPONSE_GRACE_MS)
                createResponseIfIdle(webSocket)
            }
        }

        fun uploadAudio(webSocket: WebSocket) {
            if (audioUploadStarted || terminal.get()) return
            audioUploadStarted = true
            launch {
                var offset = 0
                while (offset < inputAudio.size) {
                    val end = minOf(offset + REALTIME_INPUT_CHUNK_BYTES, inputAudio.size)
                    val bytes = inputAudio.copyOfRange(offset, end)
                    if (!eventSender.send(
                        webSocket,
                        JsonObject().apply {
                            addProperty("type", "input_audio_buffer.append")
                            addProperty("audio", Base64.getEncoder().encodeToString(bytes))
                        }
                    )) {
                        if (terminal.compareAndSet(false, true)) {
                            trySend(RealtimeVoiceEvent.Failure("Realtime 音频发送失败"))
                            webSocket.cancel()
                            close()
                        }
                        return@launch
                    }
                    offset = end
                }
                // 必须等待 input_audio_buffer.committed 后再 response.create。
                if (!eventSender.send(webSocket, JsonObject().apply {
                    addProperty("type", "input_audio_buffer.commit")
                })) {
                    if (terminal.compareAndSet(false, true)) {
                        trySend(RealtimeVoiceEvent.Failure("Realtime 音频提交失败"))
                        webSocket.cancel()
                        close()
                    }
                }
            }
        }

        fun executePendingRealtimeToolCalls(webSocket: WebSocket) {
            val executor = onToolCall ?: return failTurn("Realtime 工具执行器不可用")
            val calls = pendingFunctionCalls.values.toList()
            if (calls.isEmpty()) return
            pendingFunctionCalls.clear()
            completionJob?.cancel()
            responseDone = false
            responseActive = true

            launch {
                for (call in calls) {
                    val output = runCatching {
                        withContext(Dispatchers.IO) {
                            executor(call)
                        }
                    }.getOrElse { error ->
                        mapOf(
                            "success" to false,
                            "error" to (error.message ?: "工具执行失败")
                        )
                    }
                    if (!eventSender.send(webSocket, buildRealtimeFunctionCallOutput(call.callId, output))) {
                        failTurn("Realtime 工具结果发送失败")
                        return@launch
                    }
                }
                if (!eventSender.send(webSocket, buildRealtimeResponseCreate(config))) {
                    failTurn("Realtime 工具调用后响应创建失败")
                }
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Qwen Realtime 的 conversation.item.create 仅支持 function_call_output 类型，
                // 不支持 message 类型，因此上下文需拼接到 instructions 中传递。
                val sessionUpdate = buildRealtimeSessionUpdate(config, instructions, context, tools)
                eventSender.send(webSocket, sessionUpdate)
                if (!config.isQwenRealtime) {
                    // StepFun 的助手历史只接受 text 类型，OpenAI 使用 output_text。
                    val assistantContentType = if (config.isStepfunRealtime) "text" else "output_text"
                    buildRealtimeContextEvents(context, assistantContentType).forEach { event ->
                        eventSender.send(webSocket, event)
                    }
                }
                // Qwen 必须先确认 session.update 已禁用服务端 VAD，才可提交音频。
                // 否则新模型可能按默认 VAD 自动创建响应，再收到 response.create 时会报并发错误。
                if (!config.isQwenRealtime && !config.isGrokRealtime) uploadAudio(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
                when (event.string("type")) {
                    "session.created" -> trySend(RealtimeVoiceEvent.Connected)

                    "session.updated" -> {
                        trySend(RealtimeVoiceEvent.Connected)
                        if (config.isQwenRealtime || config.isGrokRealtime) uploadAudio(webSocket)
                    }

                    "conversation.item.input_audio_transcription.delta" -> {
                        // Qwen 返回 text（已确认前缀）+ stash（暂存后缀），不能把每次
                        // 回调的 delta 直接累加，否则会重复或丢失识别文本。
                        val confirmed = event.string("text")
                        val stash = event.string("stash")
                        val transcript = if (confirmed != null || stash != null) {
                            confirmed.orEmpty() + stash.orEmpty()
                        } else {
                            userTranscript.toString() + event.string("delta").orEmpty()
                        }
                        if (transcript.isNotEmpty()) {
                            userTranscript.clear()
                            userTranscript.append(transcript)
                            trySend(RealtimeVoiceEvent.UserTranscript(userTranscript.toString(), false))
                        }
                    }

                    "conversation.item.input_audio_transcription.completed" -> {
                        val transcript = event.string("transcript").orEmpty().trim()
                        if (transcript.isNotEmpty()) {
                            userTranscript.clear()
                            userTranscript.append(transcript)
                            trySend(RealtimeVoiceEvent.UserTranscript(transcript, true))
                        }
                        inputTranscriptDone = true
                        if (config.isQwenRealtime) scheduleQwenClose()
                        else if (responseDone) finishOpenAiAfter(80)
                    }

                    "input_audio_buffer.committed" -> {
                        if (config.isQwenRealtime) scheduleQwenResponseCreate(webSocket)
                        else createResponseIfIdle(webSocket)
                    }

                    "response.created" -> {
                        responseActive = true
                        responseCreateJob?.cancel()
                    }

                    "response.output_audio.delta", "response.audio.delta" -> {
                        event.string("delta")
                            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { trySend(RealtimeVoiceEvent.AudioDelta(it)) }
                    }

                    "response.output_audio_transcript.delta", "response.audio_transcript.delta",
                    "response.output_text.delta", "response.text.delta" -> {
                        val delta = event.string("delta").orEmpty()
                        if (delta.isNotEmpty()) {
                            if (config.isStepfunRealtime) {
                                val isAudioTranscript = event.string("type")?.contains("audio_transcript") == true
                                val target = if (isAudioTranscript) stepfunAudioTranscript else stepfunTextTranscript
                                target.append(delta)
                                val visibleTranscript = stepfunAudioTranscript.toString()
                                    .ifBlank { stepfunTextTranscript.toString() }
                                trySend(RealtimeVoiceEvent.AssistantTranscript(visibleTranscript, false))
                            } else {
                                assistantTranscript.append(delta)
                                trySend(RealtimeVoiceEvent.AssistantTranscript(assistantTranscript.toString(), false))
                            }
                        }
                    }

                    "response.output_audio_transcript.done", "response.audio_transcript.done",
                    "response.output_text.done", "response.text.done" -> {
                        val transcript = event.string("transcript") ?: event.string("text")
                        val visibleTranscript = if (config.isStepfunRealtime) {
                            if (!transcript.isNullOrBlank()) {
                                val isAudioTranscript = event.string("type")?.contains("audio_transcript") == true
                                val target = if (isAudioTranscript) stepfunAudioTranscript else stepfunTextTranscript
                                target.clear()
                                target.append(transcript)
                            }
                            stepfunAudioTranscript.toString().ifBlank { stepfunTextTranscript.toString() }
                        } else {
                            if (!transcript.isNullOrBlank()) {
                                assistantTranscript.clear()
                                assistantTranscript.append(transcript)
                            }
                            assistantTranscript.toString()
                        }
                        if (visibleTranscript.isNotBlank()) {
                            trySend(RealtimeVoiceEvent.AssistantTranscript(visibleTranscript, true))
                        }
                    }

                    "response.function_call_arguments.done" -> {
                        if (!config.supportsRealtimeFunctionCalling) return
                        val callId = event.string("call_id").orEmpty()
                        val name = event.string("name").orEmpty()
                        val arguments = event.string("arguments").orEmpty()
                        if (callId.isBlank() || name.isBlank()) {
                            failTurn("Realtime 返回了无效的工具调用")
                            return
                        }
                        completionJob?.cancel()
                        pendingFunctionCalls[callId] = RealtimeFunctionCall(
                            callId = callId,
                            name = name,
                            arguments = arguments
                        )
                    }

                    "response.done" -> {
                        val responseObject = event.objectOrNull("response")
                        val status = responseObject?.string("status")
                        if (status == "failed" || status == "cancelled") {
                            val message = responseObject.objectOrNull("status_details")
                                ?.objectOrNull("error")
                                ?.string("message")
                                ?: "Realtime 响应失败"
                            if (terminal.compareAndSet(false, true)) {
                                trySend(RealtimeVoiceEvent.Failure(message))
                                webSocket.close(1011, "realtime error")
                                close()
                            }
                            return
                        }
                        responseActive = false
                        if (config.supportsRealtimeFunctionCalling && pendingFunctionCalls.isNotEmpty()) {
                            executePendingRealtimeToolCalls(webSocket)
                            return
                        }
                        responseDone = true
                        if (config.isQwenRealtime) scheduleQwenClose()
                        else finishOpenAiAfter(if (userTranscript.isBlank()) 1_500L else 80L)
                    }

                    "error" -> {
                        val message = eventSender.describeError(event)
                        if (config.isQwenRealtime && isQwenResponseAlreadyInProgress(message)) {
                            // 服务端已经自动开始了这一轮响应时，重复 response.create 只会返回该错误；
                            // 保留当前 WebSocket，继续等待已有响应的 response.done。
                            responseCreateJob?.cancel()
                            responseActive = true
                            return
                        }
                        if (terminal.compareAndSet(false, true)) {
                            trySend(RealtimeVoiceEvent.Failure(message))
                            webSocket.close(1011, "realtime error")
                            close()
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                if (terminal.compareAndSet(false, true)) {
                    trySend(RealtimeVoiceEvent.Failure(throwable.message ?: "Realtime 连接失败"))
                    close()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminal.get() && closeRequested) {
                    terminal.set(true)
                    closeTimeoutJob?.cancel()
                    close()
                } else if (!terminal.get() && terminal.compareAndSet(false, true)) {
                    trySend(RealtimeVoiceEvent.Failure(reason.ifBlank { "Realtime 连接已关闭" }))
                    close()
                }
            }
        }

        socket = client.newWebSocket(request, listener)
        awaitClose {
            completionJob?.cancel()
            closeTimeoutJob?.cancel()
            responseCreateJob?.cancel()
            terminal.set(true)
            if (!closeRequested) socket.cancel()
        }
    }

    suspend fun testConnection(config: RealtimeModelConfig): Result<Unit> = runCatching {
        if (config.isGeminiLive) {
            GeminiLiveClient(baseClient).testConnection(config).getOrThrow()
            return@runCatching
        }
        require(config.apiKey.isNotBlank() && config.apiKey != "********") {
            "Realtime 模型缺少可用的 API Key"
        }
        withTimeout(15_000) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                val client = baseClient.withModelProxy(config.proxyUrl)
                val eventSender = RealtimeEventSender(attachEventIds = config.isStepfunRealtime)
                val request = Request.Builder()
                    .url(
                        buildRealtimeWebSocketUrl(
                            config.baseUrl.ifBlank {
                                when {
                                    config.isGrokRealtime -> "https://api.x.ai/v1"
                                    config.isStepfunRealtime -> REALTIME_STEPFUN_DEFAULT_BASE_URL
                                    else -> ""
                                }
                            },
                            config.appendBaseUrlPath,
                            config.model,
                            config.isQwenRealtime,
                            config.isStepfunRealtime
                        )
                    )
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .build()
                val socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        eventSender.send(webSocket, buildRealtimeSessionUpdate(config, "简洁地进行语音对话。"))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val type = runCatching {
                            JsonParser.parseString(text).asJsonObject.string("type")
                        }.getOrNull()
                        if (type == "session.updated" && completed.compareAndSet(false, true)) {
                            webSocket.close(1000, "test completed")
                            continuation.resume(Unit)
                        } else if (type == "error" && completed.compareAndSet(false, true)) {
                            val message = eventSender.describeError(
                                JsonParser.parseString(text).asJsonObject,
                                fallbackEventType = "session.update"
                            )
                            webSocket.close(1011, "realtime error")
                            continuation.resumeWith(Result.failure(IllegalStateException(message)))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resumeWith(Result.failure(throwable))
                        }
                    }
                })
                continuation.invokeOnCancellation { socket.cancel() }
            }
        }
    }
}

internal fun buildRealtimeSessionUpdate(
    config: RealtimeModelConfig,
    instructions: String,
    context: List<RealtimeContextMessage> = emptyList(),
    tools: List<Map<String, Any>> = emptyList()
): JsonObject {
    // StepFun 沿用 OpenAI Realtime 兼容格式；厂商识别仅用于默认地址、模型和音色。
    if (config.isGrokRealtime) {
        return Gson().toJsonTree(mapOf(
            "type" to "session.update",
            "session" to linkedMapOf<String, Any?>(
                "voice" to config.voice.ifBlank { "eve" },
                "instructions" to instructions.trim().take(REALTIME_MAX_INSTRUCTIONS_CHARS),
                "turn_detection" to null,
                "audio" to mapOf(
                    "input" to mapOf("format" to mapOf("type" to "audio/pcm", "rate" to REALTIME_INPUT_SAMPLE_RATE)),
                    "output" to mapOf("format" to mapOf("type" to "audio/pcm", "rate" to REALTIME_OUTPUT_SAMPLE_RATE))
                )
            )
        )).asJsonObject.also {
            // Gson 默认忽略 Map 中的 null，手动关闭服务端 VAD。
            it.getAsJsonObject("session").add("turn_detection", com.google.gson.JsonNull.INSTANCE)
        }
    }
    if (config.isQwenRealtime) {
        return buildQwenRealtimeSessionUpdate(config, instructions, context, tools)
    }
    if (config.usesFlatSessionUpdate) {
        return buildFlatRealtimeSessionUpdate(config, instructions)
    }
    val defaultVoice = "marin"
    val defaultTranscription = "gpt-4o-mini-transcribe"
    return JsonObject().apply {
        addProperty("type", "session.update")
        add("session", JsonObject().apply {
            addProperty("type", "realtime")
            addProperty(
                "instructions",
                instructions.trim().take(REALTIME_MAX_INSTRUCTIONS_CHARS).ifBlank {
                    "自然地进行简洁的语音对话，并使用用户当前使用的语言回答。"
                }
            )
            add("output_modalities", JsonArray().apply { add("audio") })
            addProperty("max_output_tokens", config.maxOutputTokens.coerceIn(1, 4096))
            if (config.isStepfunRealtime && tools.isNotEmpty()) {
                add("tools", buildRealtimeFunctionTools(tools))
            }
            add("audio", JsonObject().apply {
                add("input", JsonObject().apply {
                    add("format", realtimePcmFormat())
                    add("transcription", JsonObject().apply {
                        addProperty(
                            "model",
                            config.transcriptionModel.ifBlank { defaultTranscription }
                        )
                        config.language.takeUnless { it.isBlank() || it.equals("auto", true) }
                            ?.let { addProperty("language", it) }
                    })
                    add("turn_detection", null)
                })
                add("output", JsonObject().apply {
                    add("format", realtimePcmFormat())
                    addProperty("voice", config.voice.ifBlank { defaultVoice })
                })
            })
        })
    }
}

/**
 * SeedRealtime 与 GLM-Realtime 采用 OpenAI Realtime 的扁平字段版本；
 * 与 OpenAI 新版嵌套的 audio.input/audio.output 结构不能混用。
 */
private fun buildFlatRealtimeSessionUpdate(
    config: RealtimeModelConfig,
    instructions: String
): JsonObject = JsonObject().apply {
    addProperty("type", "session.update")
    add("session", JsonObject().apply {
        addProperty("model", config.model)
        add("modalities", JsonArray().apply {
            add("text")
            add("audio")
        })
        addProperty(
            "instructions",
            instructions.trim().take(REALTIME_MAX_INSTRUCTIONS_CHARS).ifBlank {
                "自然地进行简洁的语音对话，并使用用户当前使用的语言回答。"
            }
        )
        addProperty("voice", config.voice.ifBlank { RealtimeProtocol.resolve(config.provider, config.model).defaultVoice })
        addProperty("input_audio_format", "pcm16")
        addProperty("output_audio_format", "pcm16")
        add("input_audio_transcription", JsonObject().apply {
            addProperty(
                "model",
                config.transcriptionModel.ifBlank {
                    RealtimeProtocol.resolve(config.provider, config.model).defaultTranscriptionModel
                }
            )
            config.language.takeUnless { it.isBlank() || it.equals("auto", true) }
                ?.let { addProperty("language", it) }
        })
        // 由客户端在音频上传完毕后显式 commit，避免自动切轮与 UI 的录音边界冲突。
        add("turn_detection", null)
        addProperty("max_output_tokens", config.maxOutputTokens.coerceIn(1, 4096))
    })
}

internal fun buildRealtimeResponseCreate(config: RealtimeModelConfig): JsonObject = JsonObject().apply {
    addProperty("type", "response.create")
    when {
        config.isGrokRealtime -> Unit
        config.isQwenRealtime -> add("response", JsonObject().apply {
            add("modalities", JsonArray().apply {
                add("text")
                add("audio")
            })
        })
        config.usesFlatSessionUpdate -> add("response", JsonObject().apply {
            add("modalities", JsonArray().apply {
                add("text")
                add("audio")
            })
        })
        else -> add("response", JsonObject().apply {
            add("output_modalities", JsonArray().apply { add("audio") })
        })
    }
}

/**
 * Qwen/DashScope Realtime 使用扁平化的 session.update 结构，与 OpenAI Realtime 的嵌套 audio.input/audio.output
 * 不同。参考 dashscope SDK OmniRealtimeConversation.update_session 的参数：
 * model、voice、output_modalities、input_audio_format、output_audio_format、
 * enable_input_audio_transcription、input_audio_transcription_model、enable_turn_detection。
 * 模型名通过 session.model 字段传递（URL 不支持 ?model= 查询）。
 *
 * 注意：Qwen Realtime 的 conversation.item.create 仅支持 function_call_output 类型，
 * 不支持 message 类型，因此上下文需拼接到 instructions 中传递。
 */
private fun buildQwenRealtimeSessionUpdate(
    config: RealtimeModelConfig,
    instructions: String,
    context: List<RealtimeContextMessage> = emptyList(),
    tools: List<Map<String, Any>> = emptyList()
): JsonObject = JsonObject().apply {
    // Qwen3.5-Omni-Realtime 不再支持 Cherry/Serena/Chelsie 等旧音色，自动纠正为默认音色
    val resolvedVoice = config.voice.ifBlank { REALTIME_QWEN_DEFAULT_VOICE }
        .takeUnless { it in REALTIME_QWEN_DEPRECATED_VOICES } ?: REALTIME_QWEN_DEFAULT_VOICE
    // Qwen 不支持 conversation.item.create 的 message 类型，把上下文拼接到 instructions
    val mergedInstructions = buildQwenInstructions(instructions, context)
    addProperty("type", "session.update")
    add("session", JsonObject().apply {
        addProperty("model", config.model)
        addProperty("voice", resolvedVoice)
        add("modalities", JsonArray().apply {
            add("text")
            add("audio")
        })
        addProperty("input_audio_format", "pcm16")
        addProperty("output_audio_format", "pcm24")
        add("input_audio_transcription", JsonObject().apply {
            addProperty(
                "model",
                config.transcriptionModel.ifBlank { REALTIME_QWEN_DEFAULT_TRANSCRIPTION_MODEL }
            )
        })
        if (tools.isNotEmpty()) {
            add("tools", Gson().toJsonTree(tools).asJsonArray)
        }
        // 关闭服务端 VAD，使用手动 commit + response.create 触发回复，与 OpenAI 路径保持一致
        add("turn_detection", null)
        addProperty("instructions", mergedInstructions)
    })
}

/**
 * StepFun Realtime 的 tools 沿用 Chat Completions 结构，服务端按
 * session.tools[i].function.name 校验，因此必须保留 function 外层。
 * 已扁平的定义补上外层，原始工具集不做修改。
 */
internal fun buildRealtimeFunctionTools(tools: List<Map<String, Any>>): JsonArray = JsonArray().apply {
    tools.forEach { tool ->
        val definition = Gson().toJsonTree(tool).asJsonObject
        if (definition.has("function")) {
            add(definition)
        } else {
            val function = definition.deepCopy()
            function.remove("type")
            add(JsonObject().apply {
                addProperty("type", "function")
                add("function", function)
            })
        }
    }
}

/** 将本地 Agent 工具执行结果封装为 Realtime function_call_output 事件。 */
internal fun buildRealtimeFunctionCallOutput(
    callId: String,
    output: Map<String, Any>
): JsonObject = JsonObject().apply {
    addProperty("type", "conversation.item.create")
    add("item", JsonObject().apply {
        addProperty("type", "function_call_output")
        addProperty("call_id", callId)
        addProperty("output", Gson().toJson(output))
    })
}

internal fun buildQwenFunctionCallOutput(
    callId: String,
    output: Map<String, Any>
): JsonObject = buildRealtimeFunctionCallOutput(callId, output)

/** 将上下文拼接到 instructions 中，用于 Qwen Realtime（不支持 conversation.item.create 的 message 类型）。 */
private fun buildQwenInstructions(
    instructions: String,
    context: List<RealtimeContextMessage>
): String {
    val base = instructions.trim().take(REALTIME_MAX_INSTRUCTIONS_CHARS).ifBlank {
        "自然地进行简洁的语音对话，并使用用户当前使用的语言回答。"
    }
    val trimmed = trimRealtimeContext(context)
    if (trimmed.isEmpty()) return base
    val conversation = trimmed.joinToString("\n") { msg ->
        val role = if (msg.role.equals("user", true) || msg.role.equals("human", true)) "用户" else "AI"
        "$role: ${msg.content.trim()}"
    }
    val suffix = "以下是之前的对话记录，请在此基础上延续对话：\n$conversation"
    val combined = "$base\n\n$suffix"
    // 限制总长度，避免超过 instructions 上限
    return if (combined.length > REALTIME_MAX_INSTRUCTIONS_CHARS) {
        // 优先保留基础指令，截断较早的对话历史
        val remaining = REALTIME_MAX_INSTRUCTIONS_CHARS - base.length - 100
        if (remaining > 0) {
            val truncatedConv = suffix.takeLast(remaining)
            "$base\n\n$truncatedConv"
        } else {
            base
        }
    } else {
        combined
    }
}

internal fun buildRealtimeContextEvents(
    context: List<RealtimeContextMessage>,
    /** 助手历史的 content 类型：OpenAI 用 output_text，StepFun 只认 text。 */
    assistantContentType: String = "output_text"
): List<JsonObject> = trimRealtimeContext(context).map { message ->
    val user = message.role.equals("user", true) || message.role.equals("human", true)
    JsonObject().apply {
        addProperty("type", "conversation.item.create")
        add("item", JsonObject().apply {
            addProperty("type", "message")
            addProperty("role", if (user) "user" else "assistant")
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", if (user) "input_text" else assistantContentType)
                    addProperty("text", message.content.trim())
                })
            })
        })
    }
}

internal fun trimRealtimeContext(
    context: List<RealtimeContextMessage>,
    maxChars: Int = REALTIME_MAX_CONTEXT_CHARS
): List<RealtimeContextMessage> {
    val kept = ArrayDeque<RealtimeContextMessage>()
    var used = 0
    context.asReversed().forEach { message ->
        val normalized = message.content.trim()
        if (normalized.isBlank()) return@forEach
        val remaining = maxChars - used
        if (remaining <= 0) return@forEach
        val content = if (normalized.length <= remaining) normalized else normalized.takeLast(remaining)
        kept.addFirst(message.copy(content = content))
        used += content.length
    }
    return kept.toList()
}

internal fun buildRealtimeWebSocketUrl(
    baseUrl: String,
    appendRealtimePath: Boolean,
    model: String,
    qwenRealtime: Boolean = false,
    stepfunRealtime: Boolean = false
): String {
    // Qwen/DashScope Realtime 必须使用 api-ws/v1 端点；若用户误填 compatible-mode/v1（Qwen 预设默认的文本对话
    // baseurl），会导致 "URL does not appear to be valid" 或服务端 fallback 到已下线的旧模型快照。
    val correctedBase = if (qwenRealtime) {
        baseUrl.replace(Regex("/compatible-mode/v1/?$", RegexOption.IGNORE_CASE), "/api-ws/v1")
    } else {
        baseUrl
    }
    val raw = correctedBase.trim().ifBlank {
        if (stepfunRealtime) REALTIME_STEPFUN_DEFAULT_BASE_URL else "https://api.openai.com/v1"
    }.trimEnd('/')
    val websocketBase = when {
        raw.startsWith("https://", true) -> "wss://${raw.substringAfter("://")}"
        raw.startsWith("http://", true) -> "ws://${raw.substringAfter("://")}"
        raw.startsWith("wss://", true) || raw.startsWith("ws://", true) -> raw
        else -> "wss://$raw"
    }
    val hashSplit = websocketBase.substringBefore('#')
    val path = hashSplit.substringBefore('?')
    val existingQuery = hashSplit.substringAfter('?', "")
    val endpoint = if (appendRealtimePath && !path.endsWith("/realtime", true)) {
        "$path/realtime"
    } else {
        path
    }
    val existingParts = existingQuery.split('&').filter { it.isNotBlank() }.toMutableList()
    // DashScope Realtime 端点需要 ?model= 查询参数指定模型（参考 dashscope SDK 与官方示例），
    // 不带会导致服务端使用默认模型（可能是已下线的旧快照，触发 ModelNotFound）。
    val queryParts = existingParts.filter { !it.startsWith("model=") }.toMutableList()
    queryParts += "model=${URLEncoder.encode(
        model.ifBlank {
            when {
                qwenRealtime -> REALTIME_QWEN_DEFAULT_MODEL
                stepfunRealtime -> REALTIME_STEPFUN_DEFAULT_MODEL
                else -> "gpt-realtime"
            }
        },
        Charsets.UTF_8.name()
    )}"
    return "$endpoint?${queryParts.joinToString("&")}"
}

private fun realtimePcmFormat(): JsonObject = JsonObject().apply {
    addProperty("type", "audio/pcm")
    addProperty("rate", REALTIME_SAMPLE_RATE)
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

/** DashScope 新旧 Qwen Realtime 模型返回的重复创建响应错误文本并不完全一致。 */
internal fun isQwenResponseAlreadyInProgress(message: String): Boolean =
    message.contains("active response", ignoreCase = true) ||
        (
            message.contains("create response", ignoreCase = true) &&
                message.contains("in progress", ignoreCase = true)
            )

private val RealtimeProtocol.defaultModel: String
    get() = when (this) {
        RealtimeProtocol.QWEN -> REALTIME_QWEN_DEFAULT_MODEL
        RealtimeProtocol.SEED -> REALTIME_SEED_DEFAULT_MODEL
        RealtimeProtocol.GLM -> REALTIME_GLM_DEFAULT_MODEL
        RealtimeProtocol.STEPFUN -> REALTIME_STEPFUN_DEFAULT_MODEL
        RealtimeProtocol.OPENAI -> "gpt-realtime"
        RealtimeProtocol.GEMINI -> REALTIME_GEMINI_DEFAULT_MODEL
        RealtimeProtocol.GROK -> REALTIME_GROK_DEFAULT_MODEL
    }

private val RealtimeProtocol.defaultVoice: String
    get() = when (this) {
        RealtimeProtocol.QWEN -> REALTIME_QWEN_DEFAULT_VOICE
        RealtimeProtocol.SEED -> REALTIME_SEED_DEFAULT_VOICE
        RealtimeProtocol.GLM -> REALTIME_GLM_DEFAULT_VOICE
        RealtimeProtocol.STEPFUN -> REALTIME_STEPFUN_DEFAULT_VOICE
        RealtimeProtocol.OPENAI -> "marin"
        RealtimeProtocol.GEMINI -> "Kore"
        RealtimeProtocol.GROK -> "eve"
    }

private val RealtimeProtocol.defaultTranscriptionModel: String
    get() = when (this) {
        RealtimeProtocol.QWEN -> REALTIME_QWEN_DEFAULT_TRANSCRIPTION_MODEL
        RealtimeProtocol.SEED -> REALTIME_SEED_DEFAULT_TRANSCRIPTION_MODEL
        RealtimeProtocol.GLM -> REALTIME_GLM_DEFAULT_TRANSCRIPTION_MODEL
        RealtimeProtocol.STEPFUN -> ""
        RealtimeProtocol.OPENAI -> "gpt-4o-mini-transcribe"
        RealtimeProtocol.GEMINI, RealtimeProtocol.GROK -> ""
    }

const val REALTIME_SAMPLE_RATE = 24_000

/** 16kHz 单声道 PCM16 到 24kHz，保持录音时长与音调。 */
internal fun resampleRealtimeInput(input: ByteArray): ByteArray {
    val samples = input.size / 2
    if (samples == 0) return byteArrayOf()
    val output = ByteArray((samples * 3 / 2) * 2)
    fun sample(index: Int): Int {
        val i = index.coerceIn(0, samples - 1) * 2
        return ((input[i].toInt() and 255) or (input[i + 1].toInt() shl 8)).toShort().toInt()
    }
    repeat(output.size / 2) { i ->
        val position = i * 2.0 / 3.0
        val index = position.toInt()
        val fraction = position - index
        val value = (sample(index) * (1 - fraction) + sample(index + 1) * fraction).toInt()
        output[i * 2] = value.toByte()
        output[i * 2 + 1] = (value shr 8).toByte()
    }
    return output
}

const val REALTIME_GEMINI_DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
const val REALTIME_GROK_DEFAULT_MODEL = "grok-voice-latest"
/** StepFun StepAudio 3 Realtime 预览模型及官方 WebSocket API 地址。 */
const val REALTIME_STEPFUN_DEFAULT_MODEL = "stepaudio-3-realtime-preview"
const val REALTIME_STEPFUN_DEFAULT_BASE_URL = "https://api.stepfun.com/v1"
/** 沿用 OpenAI Realtime 音色：实测可用的音色名，不使用阶跃自有音色名以免服务端校验失败。 */
const val REALTIME_STEPFUN_DEFAULT_VOICE = "marin"
val REALTIME_STEPFUN_VOICES = listOf(
    "marin", "cedar", "alloy", "ash", "ballad",
    "coral", "echo", "sage", "shimmer", "verse"
)
val REALTIME_GEMINI_VOICES = listOf("Kore", "Puck", "Charon", "Aoede", "Fenrir", "Leda", "Orus", "Zephyr")
val REALTIME_GROK_VOICES = listOf("eve", "ara", "rex", "sal", "leo")
/** Qwen Realtime 输入采样率（官方默认 16000Hz，更稳定；输出保持 24000Hz）。 */
const val REALTIME_INPUT_SAMPLE_RATE = 16_000
/** Qwen Realtime 输出采样率（官方默认 24000Hz）。 */
const val REALTIME_OUTPUT_SAMPLE_RATE = 24_000
private const val REALTIME_INPUT_CHUNK_BYTES = 3_200
private const val REALTIME_MAX_INSTRUCTIONS_CHARS = 12_000
private const val REALTIME_MAX_CONTEXT_CHARS = 48_000
private const val REALTIME_QWEN_AUTO_RESPONSE_GRACE_MS = 180L
/** Qwen Realtime 手动 commit 模式下最小音频长度（约 0.5 秒，16000Hz × 0.5s × 2 bytes）。 */
private const val REALTIME_QWEN_MIN_AUDIO_BYTES = 16_000

/** Qwen Realtime 默认模型名（阿里云 DashScope qwen3.5-omni-flash-realtime，快照 2026-03-15）。
 *  注意：qwen-omni-turbo-realtime-2025-03-26 等旧快照已下线，会触发 ModelNotFound。 */
const val REALTIME_QWEN_DEFAULT_MODEL = "qwen3.5-omni-flash-realtime"
/** Qwen Realtime 默认 Base URL（DashScope WebSocket 端点，注意是 api-ws/v1 不是 compatible-mode/v1）。 */
const val REALTIME_QWEN_DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/api-ws/v1"
/** Qwen Realtime 默认音色（阿里云 DashScope qwen3.5-omni-*-realtime 系列官方默认）。
 *  注意：Cherry 是 Qwen3-Omni-Flash-Realtime 的默认音色，Qwen3.5-Omni-Realtime 已不再支持，
 *  会触发 "Voice 'Cherry' is not supported" 错误。 */
const val REALTIME_QWEN_DEFAULT_VOICE = "Tina"
/** Qwen3.5-Omni-Realtime 不再支持的旧音色，检测到时自动纠正为 [REALTIME_QWEN_DEFAULT_VOICE]。 */
val REALTIME_QWEN_DEPRECATED_VOICES = setOf("Cherry", "Serena", "Chelsie")
/** Qwen Realtime 默认输入转写模型（paraformer-realtime-v2 / gummy-realtime-v1 均可，前者覆盖更广）。 */
const val REALTIME_QWEN_DEFAULT_TRANSCRIPTION_MODEL = "paraformer-realtime-v2"
/** Qwen Realtime 支持的内置音色列表，用于 AI 配置中心下拉选项。
 *  Qwen3.5-Omni-Realtime 支持 55 种音色，此处列出官方文档示例常用的几个。 */
val REALTIME_QWEN_VOICES = listOf("Tina", "Ethan", "Cherry", "Serena", "Chelsie")

/** SeedRealtime 默认模型（火山引擎 Ark Realtime API）。 */
const val REALTIME_SEED_DEFAULT_MODEL = "doubao-seed-1.6-flash-realtime"
/** SeedRealtime 默认 Base URL，自动拼接后为 /api/v3/realtime。 */
const val REALTIME_SEED_DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
/** SeedRealtime 默认音色。 */
const val REALTIME_SEED_DEFAULT_VOICE = "zh_female_shuangkuaisisi_moon_bigtts"
/** SeedRealtime 输入转写模型。 */
const val REALTIME_SEED_DEFAULT_TRANSCRIPTION_MODEL = "doubao-1.5-asr-pro"
val REALTIME_SEED_VOICES = listOf(REALTIME_SEED_DEFAULT_VOICE)

/** GLM-Realtime 默认模型（智谱开放平台 Realtime API）。 */
const val REALTIME_GLM_DEFAULT_MODEL = "glm-realtime"
/** GLM-Realtime 默认 Base URL，自动拼接后为 /api/paas/v4/realtime。 */
const val REALTIME_GLM_DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
/** GLM-Realtime 默认音色。 */
const val REALTIME_GLM_DEFAULT_VOICE = "tongtong"
/** GLM-Realtime 输入转写模型。 */
const val REALTIME_GLM_DEFAULT_TRANSCRIPTION_MODEL = "glm-asr"
val REALTIME_GLM_VOICES = listOf(REALTIME_GLM_DEFAULT_VOICE)

/** OpenAI Realtime 默认音色列表，用于 AI 配置中心下拉选项。 */
val REALTIME_OPENAI_VOICES = listOf(
    "marin", "cedar", "alloy", "ash", "ballad",
    "coral", "echo", "sage", "shimmer", "verse"
)
