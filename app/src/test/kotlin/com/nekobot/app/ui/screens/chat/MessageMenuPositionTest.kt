package com.nekobot.app.ui.screens.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMenuPositionTest {
    private val window = IntSize(1080, 2000)
    private val popup = IntSize(600, 140)

    @Test
    fun placedAboveBubbleWhenRoom() {
        val pos = messageMenuPosition(IntRect(100, 800, 700, 1000), window, popup, gap = 16, margin = 24, topInset = 100)
        assertEquals(IntOffset(100, 800 - 16 - 140), pos)
    }

    @Test
    fun placedBelowWhenNoRoomAbove() {
        val pos = messageMenuPosition(IntRect(100, 150, 700, 400), window, popup, gap = 16, margin = 24, topInset = 100)
        assertEquals(400 + 16, pos.y)
    }

    @Test
    fun clampedHorizontallyOnScreen() {
        val right = messageMenuPosition(IntRect(950, 800, 1070, 900), window, popup, 16, 24, 100)
        assertEquals(1080 - 600 - 24, right.x)
        val left = messageMenuPosition(IntRect(0, 800, 60, 900), window, popup, 16, 24, 100)
        assertEquals(24, left.x)
    }

    @Test
    fun tallBubbleStillOnScreen() {
        val pos = messageMenuPosition(IntRect(0, -500, 1080, 2600), window, popup, 16, 24, 100)
        assertTrue(pos.y >= 100 && pos.y + popup.height <= window.height - 24)
    }

    @Test
    fun missingAnchorCentersOnScreen() {
        val pos = messageMenuPosition(null, window, popup, 16, 24, 100)
        assertEquals((1080 - 600) / 2, pos.x)
        assertTrue(pos.y in 100..(2000 - 140 - 24))
    }

    @Test
    fun collapsedToolbarKeepsBothMarginsOnNarrowWindow() {
        val narrowWindow = IntSize(400, 2000)
        val margin = 24 // 12dp at density 2
        val widths = listOf(80, 128, 80, 80)
        val count = messageToolbarVisibleActionCount(narrowWindow.width - margin * 2,
            widths, moreWidth = 80, horizontalPadding = 16, compactActionCount = 2)
        assertEquals(2, count)
        val collapsedPopup = IntSize(widths.take(count).sum() + 80 + 16, 140)
        for (anchor in listOf(IntRect(0, 800, 60, 900), IntRect(350, 800, 400, 900))) {
            val pos = messageMenuPosition(anchor, narrowWindow, collapsedPopup, 16, margin, 100)
            assertTrue(pos.x >= margin)
            assertTrue(pos.x + collapsedPopup.width <= narrowWindow.width - margin)
        }
    }

    @Test
    fun rightEdgeStaysInsideMarginWhenPopupFits() {
        val pos = messageMenuPosition(IntRect(900, 800, 1080, 900), window, popup, 16, 24, 100)
        assertTrue(pos.x >= 24)
        assertEquals(window.width - popup.width - 24, pos.x)
        assertTrue(pos.x + popup.width <= window.width - 24)
    }

    @Test
    fun menuDoesNotEnterTopInset() {
        val topInset = 140
        val pos = messageMenuPosition(IntRect(100, 90, 500, 180), window, popup, gap = 8, margin = 12, topInset = topInset)
        assertTrue(pos.y >= topInset)
        assertTrue(pos.y + popup.height <= window.height - 12)
    }

    @Test
    fun nestedCorrectionIsZeroWhenAlreadyInHostWindow() {
        val host = IntOffset(0, 60)
        val windowPos = IntOffset(120, 400)
        val screen = IntOffset(windowPos.x + host.x, windowPos.y + host.y)
        assertEquals(IntOffset.Zero, nestedPopupPositionCorrection(windowPos, screen, host))
    }

    @Test
    fun nestedCorrectionShiftsPopupLocalPointIntoHostWindow() {
        val local = IntOffset(30, 8)
        val screen = IntOffset(530, 908)
        assertEquals(IntOffset(500, 900), nestedPopupPositionCorrection(local, screen, IntOffset.Zero))
    }

    @Test
    fun nestedCorrectionSubtractsHostWindowOrigin() {
        val local = IntOffset(30, 8)
        val screen = IntOffset(580, 1008)
        val host = IntOffset(50, 100)
        assertEquals(IntOffset(500, 900), nestedPopupPositionCorrection(local, screen, host))
    }

    @Test
    fun anchorKeyPrefersMessageId() {
        assertEquals("m1#2", messageMenuAnchorKey("m1", "fallback", 2))
        assertEquals("fallback#0", messageMenuAnchorKey(null, "fallback", 0))
        assertEquals("fallback#1", messageMenuAnchorKey("  ", "fallback", 1))
    }
}
