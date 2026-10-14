package com.nekobot.app.data.local

import android.content.SharedPreferences
import com.nekobot.app.data.local.ai.CharacterThinkingSettings

/** 角色思考风格的全局偏好，独立于思考强度和思考显示开关。 */
internal class CharacterThinkingPreferences(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    var customPrompt: String
        get() = prefs.getString(KEY_PROMPT, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_PROMPT, value).apply() }

    fun read() = CharacterThinkingSettings(enabled, customPrompt)

    private companion object {
        const val KEY_ENABLED = "character_thinking_enabled"
        const val KEY_PROMPT = "character_thinking_prompt"
    }
}
