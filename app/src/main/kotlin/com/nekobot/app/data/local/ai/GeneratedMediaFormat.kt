package com.nekobot.app.data.local.ai

/** 根据文件头保存真实格式，避免把 JPEG/WebP/WAV 错标为 PNG/MP3。 */
internal data class GeneratedMediaFormat(val extension: String, val mimeType: String)

internal fun generatedImageFormat(bytes: ByteArray): GeneratedMediaFormat = when {
    bytes.size >= 8 && bytes.take(8).map { it.toInt() and 0xff } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> GeneratedMediaFormat("png", "image/png")
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> GeneratedMediaFormat("jpg", "image/jpeg")
    bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> GeneratedMediaFormat("webp", "image/webp")
    bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> GeneratedMediaFormat("gif", "image/gif")
    else -> throw IllegalStateException("服务返回的内容不是支持的图片格式")
}

internal fun generatedAudioFormat(bytes: ByteArray, fallback: String = "mp3"): GeneratedMediaFormat {
    val format = when {
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE" -> "wav"
        bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OggS" -> "ogg"
        bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "fLaC" -> "flac"
        else -> fallback.lowercase().takeIf { it in setOf("mp3", "wav", "opus", "flac", "ogg") } ?: "mp3"
    }
    return GeneratedMediaFormat(format, if (format == "mp3") "audio/mpeg" else "audio/$format")
}
