package com.nekobot.app.data.local.ai

/** 记忆整理时旧记忆的相关度：关键词分＋向量分（与回查工具的混合检索一致）。 */
internal object CompanionConsolidationScoring {
    fun hybrid(lexical: Float, vector: Float?): Float = lexical + (vector ?: 0f)
}
