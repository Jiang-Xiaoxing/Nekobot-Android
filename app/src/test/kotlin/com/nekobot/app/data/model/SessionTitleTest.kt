package com.nekobot.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {

    private val renamedCompanion = Session(
        id = "s1",
        name = "林小满",
        listTitleOverride = "林小满",
        characterName = "林小满（转换测试）（Agent）",
        senderName = "林小满 (转换测试)",
        companionMode = true,
        sessionMode = "agent"
    )

    @Test
    fun customTitleWinsOverCharacterAndSenderNames() {
        assertEquals("林小满", renamedCompanion.userFacingTitle())
    }

    @Test
    fun withoutCustomTitleFallsBackToCharacterName() {
        assertEquals("林小满（转换测试）（Agent）", renamedCompanion.copy(listTitleOverride = null).userFacingTitle())
        assertEquals("林小满（转换测试）（Agent）", renamedCompanion.copy(listTitleOverride = "  ").userFacingTitle())
    }

    @Test
    fun renamedCompanionUsesSameTitleAcrossSessionAndChatConsumersWithoutChangingIdentity() {
        val session = renamedCompanion.copy(listTitleOverride = " 小满 ")
        assertEquals("小满", session.userFacingTitle())
        assertEquals(session.userFacingTitle(), com.nekobot.app.ui.screens.chat.companionChatTitle(session))
        val row = com.nekobot.app.ui.screens.sessions.buildSessionListRows(listOf(session), emptyList()).single()
        assertEquals(session.userFacingTitle(), row.listTitle)
        assertEquals("林小满（转换测试）（Agent）", session.characterName)
        assertEquals("另一名字", session.copy(id = "s2", listTitleOverride = "另一名字").userFacingTitle())
    }

    @Test
    fun missingCharacterUsesSenderThenSessionFallback() {
        val session = renamedCompanion.copy(listTitleOverride = null, characterName = " ")
        assertEquals("林小满 (转换测试)", session.userFacingTitle())
        assertEquals("林小满", session.copy(senderName = "").userFacingTitle())
    }

    @Test
    fun sessionTitleTrimsAndFallsBack() {
        assertEquals("新名字", sessionTitle(" 新名字 ", "旧"))
        assertEquals("旧", sessionTitle(null, "旧"))
        assertEquals("旧", sessionTitle("", "旧"))
    }
}
