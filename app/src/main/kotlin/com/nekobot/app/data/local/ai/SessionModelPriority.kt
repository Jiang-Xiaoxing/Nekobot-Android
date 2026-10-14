package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity

/** 会话覆盖只调整请求队列，不改写全局模型配置。新增模型接在原队列之后。 */
internal fun applySessionModelOrder(
    models: List<LocalAiModelEntity>,
    order: List<String>
): List<LocalAiModelEntity> {
    val byId = models.associateBy { it.id }
    val preferred = order.distinct().mapNotNull(byId::get)
    if (preferred.isEmpty()) return models
    val preferredIds = preferred.mapTo(hashSetOf()) { it.id }
    return (preferred + models.filter { it.id !in preferredIds })
        .mapIndexed { index, model -> model.copy(priority = index) }
}
