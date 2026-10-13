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
    fun popupWiderThanWindowStaysAtMargin() {
        val pos = messageMenuPosition(IntRect(100, 800, 200, 900), IntSize(400, 2000), popup, 16, 24, 100)
        assertEquals(24, pos.x)
    }
}