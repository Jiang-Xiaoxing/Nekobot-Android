package com.nekobot.app.data.model

/** 试验场只读取展示与参数信息，不向 UI 暴露供应商密钥。 */
data class MediaPlaygroundModel(
    val id: String,
    val name: String,
    val provider: String,
    val voice: String = "",
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val canUploadVoice: Boolean = false,
    val supportsImageReference: Boolean = true
)
