package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import java.util.Locale

internal const val MAX_EMBEDDING_DIMENSIONS = 65_536
internal val EMBEDDING_DIMENSION_PRESETS = listOf("384", "768", "1024", "1536", "2560", "3072", "4096")

internal fun validEmbeddingDimensionsInput(value: String): Boolean =
    value.trim().toIntOrNull()?.let { it in 1..MAX_EMBEDDING_DIMENSIONS } == true

/** BGE/BCE return their native dimensions and do not accept a dimensions override. */
internal fun usesFixedEmbeddingDimensions(modelName: String): Boolean {
    val name = modelName.trim().lowercase(Locale.ROOT).substringAfterLast('/')
    return name.startsWith("bge-") || name == "bce-embedding-base_v1"
}

/** Zero means provider default; a positive number is an explicit dimensions request. */
internal fun requestedEmbeddingDimensions(model: LocalAiModelEntity): Int? {
    if (usesFixedEmbeddingDimensions(model.model)) return null
    require(model.dimensions in 0..MAX_EMBEDDING_DIMENSIONS) { "向量维度须为1至65536的整数，或选择接口默认" }
    return model.dimensions.takeIf { it > 0 }
}

internal fun embeddingCacheIdentity(model: LocalAiModelEntity): String = CompanionMemoryPolicy.digest(
    "dimensions-v1\u0000${model.id}\u0000${model.baseUrl}\u0000${model.model}\u0000${model.appendBaseUrlPath}\u0000${requestedEmbeddingDimensions(model) ?: "default"}"
)
