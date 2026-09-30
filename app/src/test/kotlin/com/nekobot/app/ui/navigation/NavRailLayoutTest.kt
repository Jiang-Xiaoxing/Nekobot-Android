package com.nekobot.app.ui.navigation

import androidx.compose.ui.unit.dp
import com.nekobot.app.ui.adaptive.NavRailMinHeightDp
import com.nekobot.app.ui.adaptive.WindowWidthClass
import com.nekobot.app.ui.adaptive.shouldUseNavRail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试平板形态的侧边导航栏：
 * 形态判定（够宽 + 够高）、面板几何规格（大屏更大）、以及主界面避让宽度是否算得对。
 */
class NavRailLayoutTest {

    @Test
    fun phoneWindow_keepsBottomBar() {
        // 手机竖屏（411x891）
        assertFalse(shouldUseNavRail(widthDp = 411, heightDp = 891))
        // 手机横屏：够宽但过矮，竖直排 5 个标签会挤，仍用底栏
        assertFalse(shouldUseNavRail(widthDp = 915, heightDp = 412))
        // 宽度不足的一般窗口
        assertFalse(shouldUseNavRail(widthDp = 599, heightDp = 1000))
    }

    @Test
    fun tabletWindow_usesNavRail() {
        // 平板竖屏 / 横屏，以及折叠屏展开
        assertTrue(shouldUseNavRail(widthDp = 800, heightDp = 1280))
        assertTrue(shouldUseNavRail(widthDp = 1280, heightDp = 800))
        assertTrue(shouldUseNavRail(widthDp = 673, heightDp = 841))
        // 边界：刚好达到宽度与高度门槛
        assertTrue(shouldUseNavRail(widthDp = 600, heightDp = NavRailMinHeightDp))
    }

    @Test
    fun clearance_leavesRoomForPanelAndMargins() {
        listOf(WindowWidthClass.Medium, WindowWidthClass.Expanded).forEach { widthClass ->
            val layout = navRailLayoutFor(widthClass)
            assertEquals(layout.railWidth + layout.horizontalPadding * 2, layout.clearance)
            // 侧栏不能吃掉太多内容宽度（折叠屏展开约 673dp 时也要留出足够列表宽度）
            assertTrue("$widthClass 侧栏过宽", layout.clearance <= 100.dp)
        }
    }

    @Test
    fun expandedRail_isLargerThanMedium() {
        val medium = navRailLayoutFor(WindowWidthClass.Medium)
        val expanded = navRailLayoutFor(WindowWidthClass.Expanded)
        assertTrue(expanded.railWidth > medium.railWidth)
        assertTrue(expanded.itemHeight > medium.itemHeight)
        assertTrue(expanded.iconSize > medium.iconSize)
        assertTrue(expanded.largerLabel)
    }

    @Test
    fun panelIsRoundedRect_notPill() {
        // 侧栏是竖直面板，圆角必须明显小于宽度一半（否则会像个诡异的胶囊）
        listOf(WindowWidthClass.Medium, WindowWidthClass.Expanded).forEach { widthClass ->
            val layout = navRailLayoutFor(widthClass)
            assertTrue("$widthClass 圆角过大", layout.corner < layout.railWidth / 2)
            assertTrue("$widthClass 指示器圆角过大", layout.indicatorCorner < layout.railWidth / 2)
            assertTrue("$widthClass 指示器内缩过大", layout.indicatorInset * 2 < layout.itemHeight)
        }
    }

    @Test
    fun railWidthFitsIconAndLabel() {
        listOf(WindowWidthClass.Medium, WindowWidthClass.Expanded).forEach { widthClass ->
            val layout = navRailLayoutFor(widthClass)
            // 去掉指示器左右内缩后剩下的宽度要放得下图标与 3~4 字中文标签
            assertTrue("$widthClass 图标放不下", layout.railWidth - layout.indicatorInset * 2 >= layout.iconSize)
            assertTrue("$widthClass 标签放不下", layout.railWidth - layout.indicatorInset * 2 >= 48.dp)
        }
    }

    @Test
    fun fiveItemsFitWithinMinimumRailHeight() {
        // 门槛高度下，5 个标签的竖直排布必须仍然放得下（含上下留白）
        listOf(WindowWidthClass.Medium, WindowWidthClass.Expanded).forEach { widthClass ->
            val layout = navRailLayoutFor(widthClass)
            val needed = layout.panelHeightFor(bottomRoutes.size) + layout.verticalPadding * 2
            assertTrue("$widthClass 标签总高超出门槛高度：$needed", needed <= NavRailMinHeightDp.dp)
        }
    }
}
