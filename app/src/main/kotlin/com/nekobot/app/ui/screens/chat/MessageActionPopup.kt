package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.nekobot.app.R

/** 长按菜单中的一个操作。 */
internal data class MessageMenuAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val testTag: String = "",
    val onClick: () -> Unit
)

/**
 * 计算悬浮菜单位置（窗口坐标）：优先放在气泡上方，空间不够放下方，都不够时夹在屏幕内。
 * 水平方向以气泡中心对齐，并限制在屏幕左右边距内。纯函数，便于 JVM 单测。
 */
internal fun messageMenuPosition(
    anchor: IntRect?,
    window: IntSize,
    popup: IntSize,
    gap: Int,
    margin: Int,
    topInset: Int = margin
): IntOffset {
    val a = anchor ?: IntRect(window.width / 2, window.height / 2, window.width / 2, window.height / 2)
    val minX = margin
    val maxX = (window.width - popup.width - margin).coerceAtLeast(minX)
    val x = (a.center.x - popup.width / 2).coerceIn(minX, maxX)
    val minY = topInset
    val maxY = (window.height - popup.height - margin).coerceAtLeast(minY)
    val above = a.top - gap - popup.height
    val below = a.bottom + gap
    val y = when {
        above >= minY -> above
        below <= maxY -> below
        // 超长气泡：上下都放不下时贴在可见区域内，靠近手指常见的上部
        else -> (minY + gap).coerceAtMost(maxY)
    }
    return IntOffset(x, y.coerceIn(minY, maxY))
}

private class MessageMenuPositionProvider(
    private val anchor: IntRect?,
    private val gap: Int,
    private val margin: Int,
    private val topInset: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = messageMenuPosition(anchor, windowSize, popupContentSize, gap, margin, topInset)
}

private val MenuBackground = Color(0xFF2C2C2E)
private val MenuContent = Color(0xFFF2F2F2)
private val MenuDestructive = Color(0xFFFF6B6B)

/** 微信式长按悬浮菜单：深色圆角横排工具条；「更多」展开小下拉。点外部或返回键关闭。 */
@Composable
internal fun MessageActionPopup(
    anchor: IntRect?,
    actions: List<MessageMenuAction>,
    moreActions: List<MessageMenuAction>,
    onDismiss: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val margin = with(density) { 12.dp.roundToPx() }
    val topInset = with(density) { 40.dp.roundToPx() }
    val provider = remember(anchor, gap, margin, topInset) { MessageMenuPositionProvider(anchor, gap, margin, topInset) }
    var moreOpen by remember { mutableStateOf(false) }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MenuBackground,
            shadowElevation = 6.dp,
            modifier = Modifier.testTag("message-action-popup")
        ) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                actions.forEach { action -> MessageMenuToolItem(action) }
                if (moreActions.isNotEmpty()) {
                    Box {
                        MessageMenuToolItem(MessageMenuAction(
                            label = stringResource(R.string.chat_menu_more),
                            icon = Icons.Filled.MoreHoriz,
                            testTag = "message-action-more"
                        ) { moreOpen = true })
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            moreActions.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label) },
                                    leadingIcon = { Icon(action.icon, null) },
                                    enabled = action.enabled,
                                    modifier = if (action.testTag.isNotBlank()) Modifier.testTag(action.testTag) else Modifier,
                                    onClick = { moreOpen = false; action.onClick() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageMenuToolItem(action: MessageMenuAction) {
    val base = if (action.destructive) MenuDestructive else MenuContent
    val color = if (action.enabled) base else base.copy(alpha = 0.35f)
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = action.enabled, onClick = action.onClick)
            .then(if (action.testTag.isNotBlank()) Modifier.testTag(action.testTag) else Modifier)
            .widthIn(min = 52.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(action.icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(action.label, color = color, fontSize = 11.sp, style = MaterialTheme.typography.labelSmall)
    }
}