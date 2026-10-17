package com.nekobot.app.ui.screens.chat

/** 整宽 Agent 正文填满气泡可用空间，用户消息与独立表情包维持原有宽度。 */
internal fun shouldFillAvailableMessageBodyWidth(
    fillAiWidth: Boolean,
    isUser: Boolean,
    standaloneSticker: Boolean
): Boolean = fillAiWidth && !isUser && !standaloneSticker
