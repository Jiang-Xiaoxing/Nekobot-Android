package com.nekobot.app.data.local

/** 页面只接收展示字段，不传递密钥、完整接口地址或认证配置。 */
internal data class CompanionEmbeddingOption(val id: String, val name: String, val provider: String, val model: String)

internal data class CompanionEmbeddingSettings(
    // null：尚未选择，兼容已有全局配置；空字符串：用户明确选择仅关键词。
    val selectedId: String?, val global: CompanionEmbeddingOption?, val options: List<CompanionEmbeddingOption>
) {
    val selected get() = options.firstOrNull { it.id == selectedId }
    val unavailable get() = !selectedId.isNullOrEmpty() && selected == null
}
