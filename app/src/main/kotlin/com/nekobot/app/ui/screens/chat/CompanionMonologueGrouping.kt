package com.nekobot.app.ui.screens.chat

import com.nekobot.app.ui.components.MdBlock
import com.nekobot.app.ui.components.companionMonologueBlocks
import com.nekobot.app.ui.components.visibleMarkdownBlocks

/** 源段索引同时用于菜单锚点、进场动画和 Compose 身份，不以合并后的序号替换。 */
internal data class ChatBubbleDisplayGroup(val sourceIndices: List<Int>) {
    val sourceIndex: Int get() = sourceIndices.first()
}

/** 普通角色与单会话共用：助手文字气泡的内心独白统一收在气泡下方，带分隔线、可折叠。 */
@Suppress("UNUSED_PARAMETER")
internal fun usesCompanionMonologueFooter(
    companionMode: Boolean,
    isUser: Boolean,
    useSafePlainText: Boolean
): Boolean = !isUser && !useSafePlainText

/** 只接收同一条消息的显示段；普通/群聊和用户消息直接返回原分段。 */
internal fun groupCompanionMonologueSegments(
    segments: List<String>,
    parsedSegments: List<List<ContentSegment>>,
    companionMode: Boolean,
    isUser: Boolean,
    useSafePlainText: Boolean,
    showInnerMonologue: Boolean,
    isStreaming: Boolean
): List<ChatBubbleDisplayGroup> {
    val original = segments.indices.map { ChatBubbleDisplayGroup(listOf(it)) }
    if (!usesCompanionMonologueFooter(companionMode, isUser, useSafePlainText)) return original

    fun isText(index: Int): Boolean = parsedSegments[index].all { it.type == SegmentType.TEXT }
    // 复用 Markdown 解析；代码、媒体、混合正文和完成后未闭合的语法均不算纯独白。
    fun isMonologue(index: Int): Boolean = isText(index) &&
        companionMonologueBlocks(segments[index], isStreaming) != null

    val groups = mutableListOf<ChatBubbleDisplayGroup>()
    for (index in segments.indices) {
        val previous = groups.lastOrNull()
        val bodyIndex = previous?.sourceIndex
        val bodyBlocks = if (bodyIndex != null && isText(bodyIndex)) {
            visibleMarkdownBlocks(segments[bodyIndex], true, false, isStreaming)
        } else emptyList()
        // 分条中间的独白也跟随紧邻的那条正文；不跨过媒体或其他消息找归属。
        val canAttach = isMonologue(index) && bodyBlocks.isNotEmpty() && bodyBlocks.none { it is MdBlock.Image }
        if (canAttach) {
            groups[groups.lastIndex] = ChatBubbleDisplayGroup(requireNotNull(previous).sourceIndices + index)
        } else {
            groups += ChatBubbleDisplayGroup(listOf(index))
        }
    }

    // 关闭独白时连同外层空泡一起跳过；流式纯独白尚未闭合时先不建立新气泡。
    return groups.filterNot { group ->
        group.sourceIndices.all { index ->
            isText(index) && (companionMonologueBlocks(segments[index], isStreaming)?.isEmpty() == true ||
                visibleMarkdownBlocks(segments[index], true, showInnerMonologue, isStreaming).isEmpty())
        }
    }
}
