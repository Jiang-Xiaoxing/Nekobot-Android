package com.nekobot.app.data.model

data class SessionChatModel(
    val id: String,
    val name: String,
    val model: String,
    val priority: Int
)

data class SessionChatModels(
    val models: List<SessionChatModel> = emptyList(),
    val customized: Boolean = false,
    /** 角色已配置独立主备队列时，仍由角色设置维护，不提供会话覆盖。 */
    val characterQueueConfigured: Boolean = false
)
