package com.nekobot.app.ui.screens.chat

/** 「更多」的固定操作；窄屏折叠的工具条操作由 Popup 另行放在这些操作之前。 */
internal enum class MessageMenuMoreAction { MULTI_SELECT, DETAILS }

internal fun messageMenuMoreActionTypes(hasDetails: Boolean): List<MessageMenuMoreAction> = buildList {
    add(MessageMenuMoreAction.MULTI_SELECT)
    if (hasDetails) add(MessageMenuMoreAction.DETAILS)
}
