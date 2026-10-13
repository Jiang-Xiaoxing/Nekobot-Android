package com.nekobot.app.data.local.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.Keep
import com.nekobot.app.data.local.db.LocalAiModelEntity
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Keep enum class ModelCapability { STREAM, TOOLS, VISION, REASONING }
@Keep enum class CapabilityObservation { OBSERVED, NOT_OBSERVED, ERROR }
@Keep data class ModelCapabilityResult(
    val capability: ModelCapability,
    val observation: CapabilityObservation,
    val durationMs: Long,
    val error: String? = null
)
@Keep data class ModelCapabilityReport(
    val modelId: String,
    val fingerprint: String,
    val testedAt: String,
    val results: List<ModelCapabilityResult>
)

internal fun capabilityFingerprint(model: LocalAiModelEntity): String {
    val value = listOf(model.protocol, model.baseUrl, model.model, model.provider.orEmpty(),
        model.proxyUrl, model.appendBaseUrlPath.toString(), model.apiKey, model.oauthAccountId.orEmpty()).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal data class VisionChallenge(val dataUri: String, val answer: String)
internal fun capabilityVisionChallenge(): VisionChallenge {
    val answer = (SecureRandom().nextInt(9000) + 1000).toString()
    val bitmap = Bitmap.createBitmap(180, 80, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(answer, 18f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 48f; typeface = Typeface.DEFAULT_BOLD
        })
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        return VisionChallenge("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes), answer)
    } finally { bitmap.recycle() }
}

/** 每项使用独立小请求；不把普通文本或隐藏思考误认成已验证能力。 */
internal class ModelCapabilityProber(
    private val client: LocalAiClient,
    private val visionChallenge: () -> VisionChallenge = ::capabilityVisionChallenge
) {
    suspend fun run(
        model: LocalAiModelEntity, requestTag: String,
        onProgress: (ModelCapabilityResult) -> Unit = {},
        onUsage: (LocalAiResult) -> Unit = {}
    ): ModelCapabilityReport {
        require(model.purpose.ifBlank { "chat" } in setOf("chat", "vision"))
        val runtime = model.copy(supportsStream = true, supportsTools = true, supportsVision = true,
            supportsReasoning = true, temperature = null, maxTokens = 2048, stopSequences = null)
        val results = ModelCapability.entries.map { capability ->
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            try {
                val (result, observed) = coroutineScope {
                    val timeout = launch { delay(45_000); client.cancelRequests(requestTag) }
                    try {
                        when (capability) {
                            ModelCapability.STREAM -> {
                                var streaming = false
                                val result = client.chatOnce(runtime,
                                    listOf(mapOf("role" to "user", "content" to "Write only: alpha beta gamma.")),
                                    extra = mapOf("max_tokens" to 64), requestTag = requestTag,
                                    streamCallbacks = LocalAiStreamCallbacks(onStreamObserved = { streaming = true }))
                                result to (streaming && result.content.isNotBlank())
                            }
                            ModelCapability.TOOLS -> {
                                val tool = mapOf(
                                    "type" to "function",
                                    "function" to mapOf(
                                        "name" to "probe_echo", "description" to "Echo the provided marker",
                                        "parameters" to mapOf(
                                            "type" to "object", "properties" to mapOf("marker" to mapOf("type" to "string")),
                                            "required" to listOf("marker")
                                        )
                                    )
                                )
                                val result = client.chatOnce(runtime,
                                    listOf(mapOf("role" to "user", "content" to "Call probe_echo with marker capability_ok. Do not answer with text.")),
                                    extra = mapOf("max_tokens" to 128, "tools" to listOf(tool)),
                                    requestTag = requestTag)
                                result to result.toolCalls.any {
                                    it["name"] == "probe_echo" && (it["arguments"] as? Map<*, *>)?.get("marker") == "capability_ok"
                                }
                            }
                            ModelCapability.VISION -> {
                                val challenge = visionChallenge()
                                val result = client.chatOnce(runtime, listOf(mapOf("role" to "user", "content" to listOf(
                                    mapOf("type" to "text", "text" to "Read the number in the image. Reply with only the number."),
                                    mapOf("type" to "image_url", "image_url" to mapOf("url" to challenge.dataUri))))),
                                    extra = mapOf("max_tokens" to 64), requestTag = requestTag)
                                result to (Regex("(?<![0-9])${challenge.answer}(?![0-9])").containsMatchIn(result.content))
                            }
                            ModelCapability.REASONING -> {
                                val result = client.chatOnce(runtime,
                                    listOf(mapOf("role" to "user", "content" to "Compute 17 times 19. Reply with only the answer.")),
                                    extra = mapOf("max_tokens" to 2048, "reasoning_effort" to "low"), requestTag = requestTag)
                                result to result.thinkingContent.isNotBlank()
                            }
                        }
                    } finally { timeout.cancel() }
                }
                currentCoroutineContext().ensureActive()
                onUsage(result)
                ModelCapabilityResult(capability,
                    if (observed) CapabilityObservation.OBSERVED else CapabilityObservation.NOT_OBSERVED,
                    (System.nanoTime() - start) / 1_000_000)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                var detail = error.message.orEmpty()
                if (model.apiKey.isNotBlank()) detail = detail.replace(model.apiKey, "•••")
                ModelCapabilityResult(capability, CapabilityObservation.ERROR,
                    (System.nanoTime() - start) / 1_000_000, detail.take(180))
            }.also(onProgress)
        }
        return ModelCapabilityReport(model.id, capabilityFingerprint(model), Instant.now().toString(), results)
    }
}
