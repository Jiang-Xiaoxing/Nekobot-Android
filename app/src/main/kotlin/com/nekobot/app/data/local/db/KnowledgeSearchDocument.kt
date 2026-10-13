package com.nekobot.app.data.local.db

import androidx.room.ColumnInfo

/** 检索只读取目录信息，避免把整份原文载入内存。 */
data class KnowledgeSearchDocument(
    val id: String,
    val title: String,
    val source: String?,
    @ColumnInfo(name = "tags_json") val tagsJson: String
)
