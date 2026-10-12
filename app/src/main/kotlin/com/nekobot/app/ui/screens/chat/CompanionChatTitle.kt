package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Session

/** 单会话角色顶部标题：优先用户重命名的标题，其次角色名。 */
internal fun companionChatTitle(session: Session?): String =
    session?.listTitleOverride?.takeIf { it.isNotBlank() }
        ?: session?.characterName?.takeIf { it.isNotBlank() }
        ?: session?.senderName?.takeIf { it.isNotBlank() }
        ?: session?.displayName.orEmpty()
