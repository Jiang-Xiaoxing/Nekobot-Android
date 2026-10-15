package com.nekobot.app.data.model

/**
 * 会话对用户显示的名称：用户手动改过的标题永远优先，其次才是各界面原有的回退。
 * 聊天顶栏、会话列表、会话详情大卡片与“会话名称”输入框都经由这里取名，保证改名后处处一致。
 */
fun sessionTitle(listTitleOverride: String?, fallback: String): String =
    listTitleOverride?.trim()?.takeIf(String::isNotEmpty) ?: fallback

/** 角色/伴侣会话的显示名：自定义标题 → 角色名 → 发送者名 → 会话名。 */
fun Session.userFacingTitle(): String = sessionTitle(
    listTitleOverride,
    characterName?.takeIf(String::isNotBlank)
        ?: senderName?.takeIf(String::isNotBlank)
        ?: displayName
)
