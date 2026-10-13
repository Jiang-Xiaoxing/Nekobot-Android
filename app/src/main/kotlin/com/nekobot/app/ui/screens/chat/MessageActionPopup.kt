package com.nekobot.app.ui.screens.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Constraints
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
import kotlin.math.roundToInt

/** 长按菜单中的一个操作。 */
internal data class MessageMenuAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val testTag: String = "",
    val showInCompactToolbar: Boolean = false,
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

/**
 * 嵌套 Popup 的锚点在外层窗口里，而 [androidx.compose.material3.DropdownMenu] 按宿主窗口摆放。
 * 返回需要加到下拉偏移上的修正量；已经在宿主窗口中时为 0。
 */
internal fun nestedPopupPositionCorrection(
    positionInWindow: IntOffset,
    positionOnScreen: IntOffset,
    hostWindowOnScreen: IntOffset
): IntOffset {
    val inHostWindow = IntOffset(
        positionOnScreen.x - hostWindowOnScreen.x,
        positionOnScreen.y - hostWindowOnScreen.y
    )
    return IntOffset(
        inHostWindow.x - positionInWindow.x,
        inHostWindow.y - positionInWindow.y
    )
}

/** 长按菜单锚定的气泡段。有消息 id 时用 id，否则用调用方给的稳定回退串。 */
internal fun messageMenuAnchorKey(messageId: String?, fallback: String, segmentIndex: Int): String {
    val id = messageId?.takeIf { it.isNotBlank() } ?: fallback
    return "$id#$segmentIndex"
}

private fun popupAnchorCorrection(coords: LayoutCoordinates, view: View): IntOffset {
    if (!coords.isAttached) return IntOffset.Zero
    val inWindow = coords.positionInWindow()
    val onScreen = coords.positionOnScreen()
    return nestedPopupPositionCorrection(
        IntOffset(inWindow.x.roundToInt(), inWindow.y.roundToInt()),
        IntOffset(onScreen.x.roundToInt(), onScreen.y.roundToInt()),
        hostWindowOriginOnScreen(view)
    )
}

private fun hostWindowOriginOnScreen(view: View): IntOffset {
    val decor = view.context.findActivity()?.window?.decorView ?: return IntOffset.Zero
    val location = IntArray(2)
    decor.getLocationOnScreen(location)
    return IntOffset(location[0], location[1])
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
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
    // 边缘到边缘时窗口原点在状态栏下，顶部用真实安全区，不用写死的 40dp。
    val topInset = WindowInsets.safeDrawing.getTop(density)
    val provider = remember(anchor, gap, margin, topInset) { MessageMenuPositionProvider(anchor, gap, margin, topInset) }
    var moreOpen by remember { mutableStateOf(false) }
    // 保持 focusable，点外部和返回键仍能关闭。键盘收起后的错位靠锚点持续更新解决。
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true,
            usePlatformDefaultWidth = false)
    ) {
        // Popup 的实际窗口约束包含分屏/旋转后的可用宽度；先扣除两侧各 12dp。
        SubcomposeLayout { constraints ->
            val availableWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0)
            val measuredItems = subcompose("measure-tools") {
                actions.forEach { MessageMenuToolItem(it) }
                MessageMenuToolItem(MessageMenuAction(
                    label = stringResource(R.string.chat_menu_more), icon = Icons.Filled.MoreHoriz
                ) {})
            }.map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
            val actionWidths = measuredItems.dropLast(1).map { it.width }
            val moreWidth = measuredItems.last().width
            val horizontalPadding = 8.dp.roundToPx()
            val visibleCount = messageToolbarVisibleActionCount(
                availableWidth, actionWidths, moreWidth, horizontalPadding,
                actions.takeWhile { it.showInCompactToolbar }.size.coerceAtLeast(1)
            )
            val overflowActions = actions.drop(visibleCount)
            val dropdownActions = overflowActions + moreActions
            val wrapItems = actionWidths.take(visibleCount).sumOf { it.toLong() } + moreWidth + horizontalPadding > availableWidth
            val toolbar = subcompose("toolbar") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MenuBackground,
                    shadowElevation = 6.dp,
                    modifier = Modifier.testTag("message-action-popup")
                ) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        actions.take(visibleCount).forEach { action ->
                            MessageMenuToolItem(action, if (wrapItems) Modifier.weight(1f) else Modifier)
                        }
                        if (dropdownActions.isNotEmpty()) {
                            val view = LocalView.current
                            val moreAnchor = remember { arrayOfNulls<LayoutCoordinates>(1) }
                            var moreCorrection by remember { mutableStateOf(IntOffset.Zero) }
                            val moreOffset = with(density) { DpOffset(moreCorrection.x.toDp(), moreCorrection.y.toDp()) }
                            Box((if (wrapItems) Modifier.weight(1f) else Modifier).onGloballyPositioned { coords ->
                                moreAnchor[0] = coords
                                val next = popupAnchorCorrection(coords, view)
                                if (next != moreCorrection) moreCorrection = next
                            }) {
                                MessageMenuToolItem(MessageMenuAction(
                                    label = stringResource(R.string.chat_menu_more),
                                    icon = Icons.Filled.MoreHoriz,
                                    testTag = "message-action-more"
                                ) {
                                    moreAnchor[0]?.let { coords ->
                                        val next = popupAnchorCorrection(coords, view)
                                        if (next != moreCorrection) moreCorrection = next
                                    }
                                    moreOpen = true
                                })
                                DropdownMenu(
                                    expanded = moreOpen,
                                    onDismissRequest = { moreOpen = false },
                                    offset = moreOffset
                                ) {
                                    dropdownActions.forEachIndexed { index, action ->
                                        if (index == overflowActions.size && overflowActions.isNotEmpty() && moreActions.isNotEmpty()) {
                                            HorizontalDivider()
                                        }
                                        DropdownMenuItem(
                                            text = { Text(action.label) },
                                            leadingIcon = { Icon(action.icon, null) },
                                            enabled = action.enabled,
                                            colors = MenuDefaults.itemColors(
                                                textColor = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                                leadingIconColor = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                                disabledTextColor = if (action.destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                                disabledLeadingIconColor = if (action.destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            ),
                                            modifier = if (action.testTag.isNotBlank()) Modifier.testTag(action.testTag) else Modifier,
                                            onClick = { moreOpen = false; action.onClick() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }.single().measure(Constraints(maxWidth = availableWidth, maxHeight = constraints.maxHeight))
            layout(toolbar.width, toolbar.height) { toolbar.placeRelative(0, 0) }
        }
    }
}

@Composable
private fun MessageMenuToolItem(action: MessageMenuAction, modifier: Modifier = Modifier) {
    val base = if (action.destructive) MenuDestructive else MenuContent
    val color = if (action.enabled) base else base.copy(alpha = 0.35f)
    Column(
        modifier
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
