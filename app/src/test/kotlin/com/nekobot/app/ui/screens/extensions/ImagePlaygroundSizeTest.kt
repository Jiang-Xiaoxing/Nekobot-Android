package com.nekobot.app.ui.screens.extensions

import org.junit.Assert.*
import org.junit.Test

class ImagePlaygroundSizeTest {
    @Test
    fun acceptsRectangularAndCustomDimensions() {
        listOf("1024x1024", "1792x1024", "1024X1536", "64x4096").forEach { assertTrue(it, isValidImageSize(it)) }
    }

    @Test
    fun rejectsMalformedOrUnboundedDimensions() {
        listOf("", "auto", "1024", "0x1024", "1024x-1", "4097x1024", "1024x1024x1024",
            "2147483648x1024", "64.5x1024").forEach { assertFalse(it, isValidImageSize(it)) }
    }
}
