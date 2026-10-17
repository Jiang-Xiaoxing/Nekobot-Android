package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleWidthPolicyTest {

    @Test
    fun fillsAvailableWidthOnlyForNonStickerAgentAiContent() {
        assertTrue(
            shouldFillAvailableMessageBodyWidth(
                fillAiWidth = true,
                isUser = false,
                standaloneSticker = false
            )
        )
        assertFalse(
            shouldFillAvailableMessageBodyWidth(
                fillAiWidth = true,
                isUser = false,
                standaloneSticker = true
            )
        )
        assertFalse(
            shouldFillAvailableMessageBodyWidth(
                fillAiWidth = true,
                isUser = true,
                standaloneSticker = false
            )
        )
        assertFalse(
            shouldFillAvailableMessageBodyWidth(
                fillAiWidth = false,
                isUser = false,
                standaloneSticker = false
            )
        )
    }
}
