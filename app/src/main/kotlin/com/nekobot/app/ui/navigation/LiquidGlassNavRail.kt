package com.nekobot.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.adaptive.WindowWidthClass
import com.nekobot.app.ui.adaptive.rememberShouldUseNavRail
import com.nekobot.app.ui.adaptive.rememberWindowWidthClass
import com.nekobot.app.ui.components.GlassBackdrop
import com.nekobot.app.ui.components.GlassPane

/**
 * 侧边导航栏在两种平板宽度下的几何规格。
 *
 * 侧栏是竖排的圆角玻璃面板（非胶囊），宽度明显窄于底栏，因此单列固定几何即可：
 * 每个标签固定高度、图标在上文字在下，整体面板在垂直方向居中。
 */
internal data class NavRailLayout(
    /** 玻璃面板本身的宽度 */
    val railWidth: Dp,
    /** 面板到屏幕左边缘的留白 */
    val horizontalPadding: Dp,
    /** 面板到屏幕上下边的留白（面板高度不足整屏时用于居中留空） */
    val verticalPadding: Dp,
    /** 单个标签的槽位高度 */
    val itemHeight: Dp,
    val iconSize: Dp,
    /** 图标与文字之间的间距 */
    val iconTextGap: Dp,
    /** 选中指示器相对槽位的内缩 */
    val indicatorInset: Dp,
    val indicatorCorner: Dp,
    /** 玻璃面板圆角 */
    val corner: Dp,
    /** 标签是否使用更大的字号（大屏用） */
    val largerLabel: Boolean,
) {
    /** 悬浮侧栏在屏幕左侧占据的总宽度；主界面内容需按它向右避让。 */
    val clearance: Dp get() = railWidth + horizontalPadding * 2

    /** 5 个标签竖直排布时面板的自然高度。 */
    fun panelHeightFor(itemCount: Int): Dp = itemHeight * itemCount
}

/** 中等宽度（600~839dp，折叠屏展开 / 小平板）：贴近 Material 侧栏的 68dp 窄面板。 */
private val MediumNavRailLayout = NavRailLayout(
    railWidth = 68.dp,
    horizontalPadding = 8.dp,
    verticalPadding = 12.dp,
    itemHeight = 64.dp,
    iconSize = 24.dp,
    iconTextGap = 4.dp,
    indicatorInset = 6.dp,
    indicatorCorner = 18.dp,
    corner = 24.dp,
    largerLabel = false,
)

/** 大屏（≥840dp，平板横竖屏）：面板与图标同步放大，标签更易读。 */
private val ExpandedNavRailLayout = NavRailLayout(
    railWidth = 76.dp,
    horizontalPadding = 10.dp,
    verticalPadding = 14.dp,
    itemHeight = 72.dp,
    iconSize = 28.dp,
    iconTextGap = 6.dp,
    indicatorInset = 8.dp,
    indicatorCorner = 22.dp,
    corner = 28.dp,
    largerLabel = true,
)

/**
 * 纯函数：按窗口宽度断点取侧栏规格（供单元测试与 Composable 共用）。
 * 手机（Compact）不会使用侧栏，这里返回最紧凑规格兜底。
 */
internal fun navRailLayoutFor(widthClass: WindowWidthClass): NavRailLayout = when (widthClass) {
    WindowWidthClass.Compact -> MediumNavRailLayout
    WindowWidthClass.Medium -> MediumNavRailLayout
    WindowWidthClass.Expanded -> ExpandedNavRailLayout
}

/** 当前窗口宽度对应的侧栏规格。 */
@Composable
internal fun navRailLayout(): NavRailLayout = navRailLayoutFor(rememberWindowWidthClass())

/**
 * 当前形态下主界面左侧需要为悬浮侧栏预留的宽度。
 *
 * 手机（或不够高的宽窗口）不使用侧栏，返回 0；侧栏形态下返回 [NavRailLayout.clearance]。
 * 主界面内容按它向右避让，详情页不避让（仍为全屏）。
 */
@Composable
fun rememberNavRailClearance(): Dp =
    if (rememberShouldUseNavRail()) navRailLayout().clearance else 0.dp

/**
 * 平板形态的悬浮液态玻璃侧边导航栏：竖排标签的圆角玻璃面板，
 * 选中项用弹性滑动的玻璃光斑指示，交互手感与底部导航栏一致。
 *
 * 与底栏的区别只在排布方向：
 * - 面板在屏幕左侧垂直居中，高度按标签数量取自然高度（不会在竖屏平板上被拉得稀疏）；
 * - 选中指示器沿 Y 轴弹性滑动（底栏为 X 轴）；
 * - 主界面内容整体向右避让 [NavRailLayout.clearance]，面板不会压住内容。
 *
 * [backdrop] 不为 null 时采样下层页面内容做真实模糊与边缘折射，否则退回静态玻璃质感。
 */
@Composable
fun LiquidGlassNavRail(
    items: List<BottomItem>,
    selectedRoute: String?,
    onItemSelected: (BottomItem) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
) {
    if (items.isEmpty()) return
    val dark = isSystemInDarkTheme()
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val density = LocalDensity.current
    val layout = navRailLayout()

    // 按压时玻璃进入“液态”：变透明、折射增强（与底栏一致的手感）。
    val barInteraction = remember { MutableInteractionSource() }
    val pressed by barInteraction.collectIsPressedAsState()
    val liquidProgress = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "navRailLiquidProgress"
    )
    val liquid: () -> Float = { liquidProgress.value }

    BoxWithConstraints(
        // 侧栏贴屏幕左边缘，垂直居中悬浮：横屏下系统导航栏位于右侧或底部，与左侧互不冲突，
        // 面板高度（5 个标签约 320~360dp）远小于平板可用高度，也不会碰到状态栏。
        modifier = modifier
            .fillMaxHeight()
            .width(layout.clearance)
            .padding(horizontal = layout.horizontalPadding, vertical = layout.verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        // 面板高度取标签总高度，屏幕过矮时才压缩；居中悬浮，四周留出背景。
        val panelHeight = layout.panelHeightFor(items.size).coerceAtMost(maxHeight)
        val itemHeight = panelHeight / items.size

        NavGlassSurface(
            dark = dark,
            backdrop = backdrop,
            corner = layout.corner,
            liquidProgress = liquid,
            // 侧栏下方始终是纯背景色（主界面内容已避让），采样玻璃无从发挥，
            // 因此用主题 surface 做淡染色：面板在纯色背景上仍有明确层次，且随主题自适应。
            tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
            modifier = Modifier
                .width(layout.railWidth)
                .height(panelHeight)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 选中指示器：选中项变化时弹性滑到新槽位（阻尼长尾，与底栏指示器同一套手感）。
                val itemHeightPx = with(density) { itemHeight.toPx() }
                val insetPx = with(density) { layout.indicatorInset.toPx() }
                val indicatorOffset by animateFloatAsState(
                    targetValue = selectedIndex * itemHeightPx,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                    label = "navRailIndicatorOffset"
                )

                RailIndicator(
                    layout = layout,
                    dark = dark,
                    backdrop = backdrop,
                    liquidProgress = liquid,
                    modifier = Modifier
                        .padding(horizontal = layout.indicatorInset)
                        .fillMaxWidth()
                        .height((itemHeight - layout.indicatorInset * 2).coerceAtLeast(1.dp))
                        .graphicsLayer {
                            // 在绘制阶段读取动画值：滑动逐帧只失效这一层，不重组、不重新布局。
                            translationY = indicatorOffset + insetPx
                        }
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, item ->
                        RailItem(
                            item = item,
                            selected = index == selectedIndex,
                            dark = dark,
                            layout = layout,
                            itemHeight = itemHeight,
                            interactionSource = barInteraction,
                            onClick = { onItemSelected(item) }
                        )
                    }
                }
            }
        }
    }
}

/** 侧栏选中指示器：真玻璃时是采样下层内容的透明光斑，否则退回主色渐变圆角块。 */
@Composable
private fun RailIndicator(
    layout: NavRailLayout,
    dark: Boolean,
    backdrop: GlassBackdrop?,
    liquidProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val corner = layout.indicatorCorner
    val indicatorFill = if (dark) {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
            )
        )
    }
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.30f else 0.18f)

    Box(modifier = modifier) {
        if (backdrop != null) {
            GlassPane(
                backdrop = backdrop,
                cornerRadius = corner,
                modifier = Modifier.matchParentSize(),
                blur = 4.dp,
                saturation = 1.15f,
                refraction = 8.dp,
                dispersion = 0f,
                // 与底栏一致：静止时是一块均匀的中性深色，交互时完全透明露出被折射的背景。
                tint = if (dark) Color(0x40000000) else Color(0x1A000000),
                rimColors = if (dark) {
                    listOf(Color(0x2EFFFFFF), Color(0x0AFFFFFF))
                } else {
                    listOf(Color(0xB3FFFFFF), Color(0x14000000))
                },
                innerShadowAlpha = 0f,
                liquidProgress = liquidProgress,
                scaleBoost = 0.08f,
                tintFade = 1f,
                rimRestAlpha = 0.35f,
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .shadow(10.dp, RoundedCornerShape(corner), clip = false, spotColor = glow, ambientColor = glow)
                    .clip(RoundedCornerShape(corner))
                    .background(indicatorFill)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.5f else 0.35f),
                        RoundedCornerShape(corner)
                    )
            )
        }
    }
}

@Composable
private fun RailItem(
    item: BottomItem,
    selected: Boolean,
    dark: Boolean,
    layout: NavRailLayout,
    itemHeight: Dp,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dark) 0.85f else 1f)
    val haptics = LocalHapticFeedback.current

    // 轻微震动反馈：仅点击「不同」标签时触发，避免重复点击当前标签反复震动。
    val onTabClick = {
        if (!selected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onClick()
    }

    val contentColor by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "railItemColor"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "railIconScale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(itemHeight)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onTabClick
            )
            // 显式设置 contentDescription，TalkBack 朗读一次即可（覆盖子节点的 text）
            .semantics { contentDescription = item.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
                .size(layout.iconSize)
        )
        Spacer(modifier = Modifier.height(layout.iconTextGap))
        Text(
            text = item.label,
            color = contentColor,
            style = if (layout.largerLabel) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.labelSmall
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        )
    }
}
