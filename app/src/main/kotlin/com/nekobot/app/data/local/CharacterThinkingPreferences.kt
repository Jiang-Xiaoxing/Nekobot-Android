package com.nekobot.app.data.local

import android.content.SharedPreferences
import com.nekobot.app.data.local.ai.CharacterThinkingSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 角色思考风格的全局偏好，独立于思考强度和思考显示开关。 */
internal class CharacterThinkingPreferences(private val prefs: SharedPreferences) {
    private val current = MutableStateFlow(read())
    val settings = current.asStateFlow()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        @Synchronized set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            current.value = read()
        }

    var customPrompt: String
        get() = prefs.getString(KEY_PROMPT, "").orEmpty()
        @Synchronized set(value) {
            prefs.edit().putString(KEY_PROMPT, value).apply()
            current.value = read()
        }

    fun read() = CharacterThinkingSettings(enabled, customPrompt)

    private companion object {
        const val KEY_ENABLED = "character_thinking_enabled"
        const val KEY_PROMPT = "character_thinking_prompt"
    }
}
