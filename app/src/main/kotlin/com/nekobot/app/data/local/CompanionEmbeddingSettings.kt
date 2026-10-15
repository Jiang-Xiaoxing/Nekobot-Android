package com.nekobot.app.data.local

/** 页面只接收展示字段，不传递密钥、完整接口地址或认证配置。 */
internal data class CompanionEmbeddingOption(val id: String, val name: String, val provider: String, val model: String)

internal data class CompanionEmbeddingSettings(
    val enabled: Boolean, val selectedId: String?, val options: List<CompanionEmbeddingOption>
) {
    val selected get() = options.firstOrNull { it.id == selectedId }
    val unavailable get() = !selectedId.isNullOrEmpty() && selected == null
}

/** 同一资料库内的单会话角色共用；不继承旧角色绑定或默认模型，首次必须主动开启。 */
internal data class CompanionRetrievalSettings(
    val enabled: Boolean = false, val modelId: String? = null, val revision: Long = 0
)
