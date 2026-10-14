package com.nekobot.app.data.model

data class SessionChatModel(
    val id: String,
    val name: String,
    val model: String,
    val priority: Int
)

data class SessionChatModels(
    val models: List<SessionChatModel> = emptyList(),
    val customized: Boolean = false
)
