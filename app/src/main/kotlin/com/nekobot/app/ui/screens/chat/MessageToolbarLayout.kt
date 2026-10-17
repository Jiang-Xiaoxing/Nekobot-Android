package com.nekobot.app.ui.screens.chat

/**
 * 根据未缩小字体的实测宽度，依次选择完整、常用（复制/重新回复）、最小（复制）工具条。
 * [actionWidths] 按显示顺序传入，常用操作必须位于开头；各档始终为「更多」预留宽度。
 * 最小档仍超宽时由布局让文字换行，不缩小字号。
 */
internal fun messageToolbarVisibleActionCount(
    availableWidth: Int,
    actionWidths: List<Int>,
    moreWidth: Int,
    horizontalPadding: Int,
    compactActionCount: Int
): Int {
    require(availableWidth >= 0 && moreWidth >= 0 && horizontalPadding >= 0)
    require(actionWidths.all { it >= 0 })
    if (actionWidths.isEmpty()) return 0
    require(compactActionCount in 1..actionWidths.size)

    fun fits(count: Int): Boolean =
        actionWidths.take(count).sumOf { it.toLong() } + moreWidth + horizontalPadding <= availableWidth

    return when {
        fits(actionWidths.size) -> actionWidths.size
        fits(compactActionCount) -> compactActionCount
        else -> 1
    }
}
