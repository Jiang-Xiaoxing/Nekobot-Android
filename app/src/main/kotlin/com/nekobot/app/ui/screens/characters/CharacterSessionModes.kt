package com.nekobot.app.ui.screens.characters

import com.nekobot.app.data.model.Session
import com.nekobot.app.ui.screens.sessions.SessionModeLabel
import com.nekobot.app.ui.screens.sessions.isAgentCharacterSession
import com.nekobot.app.ui.screens.sessions.isAgentSession

/** A character can be used by several conversation types; its name is never a mode flag. */
internal fun buildCharacterSessionModes(sessions: List<Session>): Map<String, List<SessionModeLabel>> {
    val modes = mutableMapOf<String, MutableSet<SessionModeLabel>>()
    for (session in sessions) {
        if (session.isArchive == true) continue
        val mode = when {
            session.isAgentCharacterSession() -> SessionModeLabel.COMPANION
            session.isAgentSession() -> SessionModeLabel.AGENT
            session.sessionMode.equals("group", ignoreCase = true) -> SessionModeLabel.GROUP
            else -> SessionModeLabel.CHARACTER
        }
        val characterIds = if (mode == SessionModeLabel.GROUP) {
            session.characterIds.orEmpty() + listOfNotNull(session.characterId)
        } else listOfNotNull(session.characterId)
        for (id in characterIds.filter(String::isNotBlank)) {
            modes.getOrPut(id) { mutableSetOf() }.add(mode)
        }
    }
    val order = listOf(SessionModeLabel.COMPANION, SessionModeLabel.CHARACTER,
        SessionModeLabel.AGENT, SessionModeLabel.GROUP)
    return modes.mapValues { (_, present) -> order.filter { it in present } }
}
