package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionChatTitleTest {
    @Test fun renamedTitleWinsOverCharacterName() {
        assertEquals("小满的日常", companionChatTitle(Session(id = "s", characterName = "林小满", listTitleOverride = "小满的日常")))
        assertEquals("林小满", companionChatTitle(Session(id = "s", characterName = "林小满", listTitleOverride = " ")))
        assertEquals("", companionChatTitle(null))
    }
}
