package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.model.Session
import com.nekobot.app.data.model.userFacingTitle
import com.nekobot.app.ui.screens.chat.companionChatTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleConsistencyTest {

    @Test
    fun renamedCompanionShowsSameNameInListTopBarAndDetailCard() {
        val session = Session(
            id = "s1",
            name = "林小满",
            listTitleOverride = "林小满",
            characterName = "林小满 (转换测试)",
            sessionMode = "agent",
            companionMode = true
        )
        val row = buildSessionListRows(listOf(session), emptyList()).single()
        assertEquals("林小满", row.listTitle)
        assertEquals("林小满", companionChatTitle(session))
        assertEquals("林小满", session.userFacingTitle())
    }
}
