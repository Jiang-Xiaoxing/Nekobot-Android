package com.nekobot.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {

    private val renamedCompanion = Session(
        id = "s1",
        name = "林小满",
        listTitleOverride = "林小满",
        characterName = "林小满（Agent）",
        senderName = "林小满 (转换测试)"
    )

    @Test
    fun customTitleWinsOverCharacterAndSenderNames() {
        assertEquals("林小满", renamedCompanion.userFacingTitle())
    }

    @Test
    fun withoutCustomTitleFallsBackToCharacterName() {
        assertEquals("林小满（Agent）", renamedCompanion.copy(listTitleOverride = null).userFacingTitle())
        assertEquals("林小满（Agent）", renamedCompanion.copy(listTitleOverride = "  ").userFacingTitle())
    }

    @Test
    fun sessionTitleTrimsAndFallsBack() {
        assertEquals("新名字", sessionTitle(" 新名字 ", "旧"))
        assertEquals("旧", sessionTitle(null, "旧"))
        assertEquals("旧", sessionTitle("", "旧"))
    }
}
