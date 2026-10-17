package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.local.ai.CHARACTER_THINKING_STYLE_KEY
import com.nekobot.app.data.local.ai.CharacterThinkingSettings
import com.nekobot.app.data.local.ai.PromptStack
import com.nekobot.app.data.local.ai.characterThinkingPrompt
import com.nekobot.app.data.local.ai.isCharacterThinkingSession
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.model.Session

/** 只把思考段投影成当前设置；其他段保留实际请求记录，不重跑角色管线或写入数据库。 */
internal fun liveCharacterThinkingDebugItems(
    recorded: List<PromptStackItem>,
    session: Session?,
    settings: CharacterThinkingSettings,
    effort: ReasoningEffort,
    characterName: String
): List<PromptStackItem> {
    val items = recorded.filterNot { it.key == CHARACTER_THINKING_STYLE_KEY }
    if (!isCharacterThinkingSession(session) || !settings.enabled || effort == ReasoningEffort.NONE) return items
    return (items + PromptStackItem(
        key = CHARACTER_THINKING_STYLE_KEY,
        content = characterThinkingPrompt(settings, characterName, null).trim(),
        priority = PromptStack.Priority.BEHAVIOR + 1,
        role = "system",
        scope = "session"
    )).sortedBy { it.priority }
}
