package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Message

/** 时间分隔和头像分组共用边界，避免分隔线后留下无头像的续条。 */
internal fun companionStartsTimeSection(
    first: Boolean,
    streaming: Boolean,
    day: String?,
    previousDay: String?,
    time: String?,
    previousTime: String?
): Boolean {
    if (streaming) return false
    fun minutes(value: String?): Int? {
        val parts = value?.split(":") ?: return null
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour * 60 + minute
    }
    val gap = minutes(time)?.let { now -> minutes(previousTime)?.let { now - it } }
    return first || day != previousDay || gap == null || gap >= 5 || gap < 0
}

internal fun companionShowsAvatar(message: Message, previous: Message?, startsTimeSection: Boolean): Boolean {
    if (message.isUser || message.isThinkingCard) return false
    fun sender(message: Message): String =
        message.sender?.trim()?.takeIf(String::isNotEmpty)
            ?: message.name?.trim()?.takeIf(String::isNotEmpty) ?: "assistant"
    return previous == null || previous.isUser || previous.isThinkingCard || startsTimeSection ||
        sender(message) != sender(previous)
}

/** 为头像、列表边距和对侧留白留出空间；窄屏不强加最小气泡宽度。 */
internal fun companionBubbleMaxWidthDp(screenWidthDp: Int, isUser: Boolean): Int =
    (screenWidthDp - if (isUser) 64 else 96).coerceIn(1, 360)

/** 上传立绘优先；旧图标标记不是照片，继续找头像，最后由 UI 回退姓名首字。 */
internal fun companionPhotoPath(portrait: String?, avatar: String?): String? =
    sequenceOf(portrait, avatar).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull { !it.startsWith("fas ") && !it.startsWith("fa-") &&
            !it.matches(Regex("fas?fa-[a-z0-9-]+")) }
