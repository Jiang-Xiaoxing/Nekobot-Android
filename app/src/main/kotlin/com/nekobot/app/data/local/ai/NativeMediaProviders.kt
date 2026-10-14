package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import java.util.Base64
import kotlin.math.log10
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** 原生媒体协议复用主客户端的代理、取消与故障转移机制。 */
internal class NativeMediaProviders(private val execute: suspend (Request) -> Response) {
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    suspend fun speech(
        model: LocalAiModelEntity, text: String, voice: String?, speed: Float?, volume: Float?
    ): ByteArray {
        val provider = MultimodalProviderSupport.ttsProvider(model)
        val selectedVoice = (voice ?: model.ttsVoice).takeUnless { it.isBlank() || it == "default" }
        val selectedModel = model.ttsModel.ifBlank { model.model }
        val selectedSpeed = speed ?: model.ttsSpeed
        require(model.ttsFormat.ifBlank { "mp3" } in setOf("mp3", "wav", "opus")) {
            "$provider TTS 支持 mp3、wav、opus 格式"
        }
        val builder = when (provider) {
            "elevenlabs" -> {
                val voiceId = selectedVoice ?: "JBFqnCBsd6RMkjVDRZzb"
                val url = mediaEndpoint(model.ttsUrl, model, "/text-to-speech/$voiceId", "https://api.elevenlabs.io/v1")
                    .toHttpUrl().newBuilder()
                    .setQueryParameter("output_format", when (model.ttsFormat) {
                        "wav" -> "pcm_24000"
                        "opus" -> "opus_48000_128"
                        else -> "mp3_44100_128"
                    }).build()
                Request.Builder().url(url).header("xi-api-key", model.apiKey)
                    .post(gson.toJson(mapOf(
                        "text" to text,
                        "model_id" to selectedModel.ifBlank { "eleven_multilingual_v2" },
                        "voice_settings" to mapOf("speed" to selectedSpeed.coerceIn(0.7f, 1.2f))
                    )).toRequestBody(jsonType))
            }
            "deepgram" -> {
                val url = mediaEndpoint(model.ttsUrl, model, "/speak", "https://api.deepgram.com/v1")
                    .toHttpUrl().newBuilder()
                    .setQueryParameter("model", selectedVoice ?: selectedModel.ifBlank { "aura-2-thalia-en" })
                    .setQueryParameter("encoding", when (model.ttsFormat) {
                        "wav" -> "linear16"
                        "opus" -> "opus"
                        else -> "mp3"
                    }).apply { if (model.ttsFormat == "wav") setQueryParameter("container", "wav") }.build()
                Request.Builder().url(url).header("Authorization", "Token ${model.apiKey}")
                    .post(gson.toJson(mapOf("text" to text)).toRequestBody(jsonType))
            }
            else -> {
                val name = selectedModel.ifBlank { "FunAudioLLM/CosyVoice2-0.5B" }
                val voiceId = selectedVoice ?: "$name:alex"
                val resolvedVoice = if (voiceId in SILICONFLOW_VOICES) "$name:$voiceId" else voiceId
                val url = mediaEndpoint(model.ttsUrl, model, "/audio/speech", "https://api.siliconflow.cn/v1")
                Request.Builder().url(url).header("Authorization", "Bearer ${model.apiKey}")
                    .post(gson.toJson(mapOf(
                        "model" to name, "input" to text, "voice" to resolvedVoice,
                        "response_format" to model.ttsFormat.ifBlank { "mp3" },
                        "speed" to selectedSpeed.coerceIn(0.25f, 4f),
                        "gain" to (20 * log10((volume ?: model.ttsVolume).toDouble().coerceAtLeast(0.01))).coerceIn(-10.0, 10.0)
                    )).toRequestBody(jsonType))
            }
        }
        headers(model.ttsHeaders).forEach { (key, value) -> builder.header(key, value) }
        val bytes = bytes(builder.build(), "audio/")
        return if (provider == "elevenlabs" && model.ttsFormat == "wav") {
            MultimodalProviderSupport.pcmToWav(bytes)
        } else bytes
    }

    suspend fun transcribe(
        model: LocalAiModelEntity, audio: ByteArray, filename: String, language: String?
    ): LocalAiResult {
        val provider = MultimodalProviderSupport.sttProvider(model)
        val lang = language?.takeUnless { it.isBlank() || it.equals("auto", true) }
        val selectedModel = model.sttModel.ifBlank { model.model }
        val builder = if (provider == "elevenlabs") {
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model_id", selectedModel.ifBlank { "scribe_v2" })
                .addFormDataPart("file", filename, audio.toRequestBody(audioType(filename).toMediaType()))
                .addFormDataPart("tag_audio_events", "false")
                .apply { lang?.let { addFormDataPart("language_code", it) } }.build()
            Request.Builder().url(mediaEndpoint(model.sttUrl, model, "/speech-to-text", "https://api.elevenlabs.io/v1"))
                .header("xi-api-key", model.apiKey).post(form)
        } else {
            val url = mediaEndpoint(model.sttUrl, model, "/listen", "https://api.deepgram.com/v1")
                .toHttpUrl().newBuilder()
                .setQueryParameter("model", selectedModel.ifBlank { "nova-3" })
                .setQueryParameter("smart_format", "true")
                .apply {
                    if (lang != null) setQueryParameter("language", lang)
                    else setQueryParameter("detect_language", "true")
                }.build()
            Request.Builder().url(url).header("Authorization", "Token ${model.apiKey}")
                .post(audio.toRequestBody(audioType(filename).toMediaType()))
        }
        headers(model.sttHeaders).forEach { (key, value) -> builder.header(key, value) }
        val root = json(builder.build())
        val text = if (provider == "elevenlabs") root.get("text")?.asString else {
            root.getAsJsonObject("results")?.getAsJsonArray("channels")?.firstOrNull()?.asJsonObject
                ?.getAsJsonArray("alternatives")?.firstOrNull()?.asJsonObject?.get("transcript")?.asString
        }
        require(text != null) { "$provider STT 响应缺少转录文本" }
        return LocalAiResult(text, usedModelId = model.id, usedModelName = model.name, usedModelActualName = selectedModel)
    }

    suspend fun images(
        provider: String, baseUrl: String, apiKey: String, model: String, prompt: String,
        size: String, n: Int, appendPath: Boolean, reference: ImageGenerationReference?
    ): List<GeneratedImage> {
        val count = n.coerceIn(1, 4)
        if (provider == "stability") {
            val service = model.ifBlank { "core" }
            val suffix = if (service in setOf("core", "ultra")) service else "sd3"
            val url = MultimodalProviderSupport.endpoint(baseUrl, "/stable-image/generate/$suffix", "https://api.stability.ai/v2beta/stable-image/generate/$suffix", appendPath)
            return buildList {
                repeat(count) {
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("prompt", prompt)
                        .addFormDataPart("aspect_ratio", stabilityAspectRatio(size))
                        .addFormDataPart("output_format", "png")
                        .apply { if (suffix == "sd3") addFormDataPart("model", service) }.build()
                    val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey")
                        .header("Accept", "image/*").post(body).build()
                    add(GeneratedImage(url = null, bytes = bytes(request, "image/")))
                }
            }
        }
        val defaultBase = when (provider) {
            "siliconflow" -> "https://api.siliconflow.cn/v1"
            "glm" -> "https://open.bigmodel.cn/api/paas/v4"
            else -> "https://api.x.ai/v1"
        }
        val suffix = if (provider == "grok" && reference != null) "/images/edits" else "/images/generations"
        val url = MultimodalProviderSupport.endpoint(baseUrl, suffix, defaultBase + suffix, appendPath)
        val payload = linkedMapOf<String, Any>("model" to model, "prompt" to prompt)
        when (provider) {
            "siliconflow" -> {
                if (!model.contains("Image-Edit", true)) payload["image_size"] = siliconFlowImageSize(model, size)
                reference?.let { payload["image"] = it.dataUrl() }
            }
            "glm" -> payload["size"] = size
            else -> {
                payload["n"] = count
                payload["aspect_ratio"] = MultimodalProviderSupport.imageAspectRatio(size)
                payload["response_format"] = "b64_json"
                reference?.let { payload["image"] = mapOf("url" to it.dataUrl(), "type" to "image_url") }
            }
        }
        // 硅基流动和智谱原生端点一次生成一张，按数量逐次请求。
        return buildList {
            repeat(if (provider == "grok") 1 else count) {
                val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey")
                    .post(gson.toJson(payload).toRequestBody(jsonType)).build()
                val root = json(request)
                val entries = root.getAsJsonArray(if (provider == "siliconflow") "images" else "data")
                val images = entries?.mapNotNull { entry ->
                    val obj = entry.asJsonObject
                    val encoded = obj.get("b64_json")?.takeUnless { it.isJsonNull }?.asString
                    val imageUrl = obj.get("url")?.takeUnless { it.isJsonNull }?.asString
                    when {
                        !encoded.isNullOrBlank() -> GeneratedImage(null, Base64.getDecoder().decode(encoded))
                        !imageUrl.isNullOrBlank() -> GeneratedImage(imageUrl, null)
                        else -> null
                    }
                }.orEmpty()
                check(images.isNotEmpty()) { "$provider 未返回图片" }
                addAll(images)
            }
        }
    }

    private suspend fun json(request: Request): JsonObject =
        JsonParser.parseString(bytes(request).toString(Charsets.UTF_8)).asJsonObject.also {
            check(!it.has("error")) { it.get("error").toString().take(300) }
        }

    private suspend fun bytes(request: Request, mediaType: String? = null): ByteArray = execute(request).use { response ->
        val data = response.body?.bytes() ?: ByteArray(0)
        if (!response.isSuccessful) throw FailoverHttpException(response.code, "媒体接口 HTTP ${response.code}: ${data.toString(Charsets.UTF_8).take(500)}")
        if (mediaType != null && response.header("Content-Type").orEmpty().contains("json", true)) {
            error("媒体接口未返回媒体数据: ${data.toString(Charsets.UTF_8).take(300)}")
        }
        check(data.isNotEmpty()) { "媒体接口返回空内容" }
        data
    }

    private fun mediaEndpoint(custom: String, model: LocalAiModelEntity, suffix: String, defaultBase: String) =
        custom.takeIf { it.isNotBlank() } ?: MultimodalProviderSupport.endpoint(model.baseUrl, suffix, defaultBase + suffix, model.appendBaseUrlPath)

    private fun headers(raw: String): Map<String, String> = if (raw.isBlank()) emptyMap() else
        JsonParser.parseString(raw).asJsonObject.entrySet().associate { it.key to it.value.asString }

    private fun ImageGenerationReference.dataUrl() = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes)

    private fun audioType(filename: String) = when (filename.substringAfterLast('.').lowercase()) {
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        "aac" -> "audio/aac"
        else -> "audio/mpeg"
    }
}

internal val SILICONFLOW_VOICES = listOf("alex", "benjamin", "charles", "david", "anna", "bella", "claire", "diana")

/** 将通用尺寸匹配到厂商公开支持的分辨率，保留已有的合法自定义尺寸。 */
internal fun siliconFlowImageSize(model: String, size: String): String {
    val sizes = when {
        model.contains("Qwen-Image", true) -> listOf("1328x1328", "1664x928", "928x1664", "1472x1140", "1140x1472", "1584x1056", "1056x1584")
        model.contains("Kolors", true) -> listOf("1024x1024", "1280x960", "960x1280", "1024x768", "768x1024", "1440x720", "720x1440", "1280x720", "720x1280")
        else -> return size
    }
    if (size in sizes) return size
    return sizes.minBy { kotlin.math.abs(sizeRatio(it) - sizeRatio(size)) }
}

internal fun stabilityAspectRatio(size: String): String {
    val ratios = listOf("1:1", "16:9", "21:9", "2:3", "3:2", "4:5", "5:4", "9:16", "9:21")
    return ratios.minBy { kotlin.math.abs(sizeRatio(it.replace(':', 'x')) - sizeRatio(size)) }
}

private fun sizeRatio(size: String): Double {
    val dimensions = size.lowercase().split('x', '*')
    val width = dimensions.getOrNull(0)?.toDoubleOrNull() ?: return 1.0
    val height = dimensions.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0 } ?: return 1.0
    return width / height
}
