package com.nekobot.app.data.local.ai

/**
 * 记忆整理时旧记忆的相关度。关键词余弦和向量余弦都在 0～1。
 * 有向量时两者各占一半；没有向量时只用关键词，不把缺失当成 0。
 * 两边相等时分数相同。日期加分由调用方另加，不参与平均。
 */
internal object CompanionConsolidationScoring {
    fun hybrid(keywordCosine: Float, vector: Float?, dateBonus: Float = 0f): Float {
        val keyword = keywordCosine.coerceIn(0f, 1f)
        val blended = if (vector == null) keyword else keyword * 0.5f + vector.coerceIn(0f, 1f) * 0.5f
        return blended + dateBonus.coerceAtLeast(0f)
    }
}
