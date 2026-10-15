package com.nekobot.app.data.local

/** 记忆页展示用的实时状态；不从格式化文案反向解析数量。 */
data class CompanionMemoryOverview(
    val processedMessages: Int,
    val pendingMessages: Int,
    val lastRetrieval: String?,
    val error: String?
)
