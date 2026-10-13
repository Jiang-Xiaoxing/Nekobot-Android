package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageToolbarLayoutTest {
    private val widths = listOf(52, 80, 52, 52)

    private fun visible(width: Int, items: List<Int> = widths, compactCount: Int = 2) =
        messageToolbarVisibleActionCount(width, items, moreWidth = 52, horizontalPadding = 8,
            compactActionCount = compactCount)

    @Test fun fullToolbarFitsExactly() { assertEquals(4, visible(296)) }
    @Test fun onePixelBelowFullFoldsEditAndDelete() { assertEquals(2, visible(295)) }
    @Test fun compactToolbarFitsExactly() { assertEquals(2, visible(192)) }
    @Test fun onePixelBelowCompactFoldsRegenerateToo() { assertEquals(1, visible(191)) }
    @Test fun minimumToolbarFitsExactly() { assertEquals(1, visible(112)) }
    @Test fun extremelyNarrowWidthKeepsCopyForWrapping() { assertEquals(1, visible(80)) }
    @Test fun zeroAvailableWidthDoesNotCrash() { assertEquals(1, visible(0)) }

    @Test fun largerFontFoldsAtSameWindowWidth() {
        assertEquals(4, visible(320))
        assertEquals(2, visible(320, listOf(80, 144, 80, 80)))
        assertEquals(1, visible(320, listOf(120, 240, 120, 120)))
    }

    @Test fun localizedLongRegenerateLabelUsesMeasuredWidth() {
        assertEquals(1, visible(250, listOf(52, 150, 52, 52)))
    }

    @Test fun messageWithoutRegenerateFoldsOnlyEditAndDelete() {
        assertEquals(3, visible(216, listOf(52, 52, 52), compactCount = 1))
        assertEquals(1, visible(215, listOf(52, 52, 52), compactCount = 1))
    }

    @Test fun windowWidthReservesBothTwelveDpMargins() {
        val windowWidth = 310
        assertEquals(2, visible(windowWidth - 12 * 2))
    }

    @Test fun emptyActionsStillAllowMoreMenu() {
        assertEquals(0, messageToolbarVisibleActionCount(100, emptyList(), 52, 8, 0))
    }

    @Test fun widthSumDoesNotOverflow() {
        assertEquals(1, visible(Int.MAX_VALUE, List(4) { Int.MAX_VALUE }))
    }
}
