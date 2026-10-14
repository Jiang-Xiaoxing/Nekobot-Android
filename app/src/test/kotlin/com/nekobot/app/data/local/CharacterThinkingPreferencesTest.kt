package com.nekobot.app.data.local

import android.content.SharedPreferences
import com.nekobot.app.data.local.ai.CharacterThinkingSettings
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingPreferencesTest {
    @Test
    fun defaultsAreEnabledAndEmptyWithoutWritingExistingPreferences() {
        val saved = mutableMapOf<String, Any>()
        assertEquals(CharacterThinkingSettings(), CharacterThinkingPreferences(preferences(saved)).read())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun disabledSwitchAndCustomPromptSurviveReaderRecreation() {
        val saved = mutableMapOf<String, Any>()
        CharacterThinkingPreferences(preferences(saved)).apply {
            enabled = false
            customPrompt = "以我的身份想：今天很开心。"
        }
        val restored = CharacterThinkingPreferences(preferences(saved)).read()
        assertFalse(restored.enabled)
        assertEquals("以我的身份想：今天很开心。", restored.customPrompt)
    }

    @Test
    fun clearingPromptAndReenablingDoNotChangeUnrelatedPreferences() {
        val saved = mutableMapOf<String, Any>("reasoning_effort_character" to "high", "show_reasoning" to false)
        val store = CharacterThinkingPreferences(preferences(saved))
        store.enabled = false
        store.customPrompt = "旧文案"
        store.customPrompt = ""
        store.enabled = true
        assertEquals(CharacterThinkingSettings(), CharacterThinkingPreferences(preferences(saved)).read())
        assertEquals("high", saved["reasoning_effort_character"])
        assertEquals(false, saved["show_reasoning"])
    }

    /** JVM 测试只模拟所需 SharedPreferences API，写入必须经过 apply/commit 才能读回。 */
    private fun preferences(saved: MutableMap<String, Any>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getBoolean", "getString" -> saved[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                lateinit var editor: SharedPreferences.Editor
                editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                    when (editMethod.name) {
                        "putBoolean", "putString" -> {
                            pending[editArgs!![0] as String] = requireNotNull(editArgs[1])
                            editor
                        }
                        "apply", "commit" -> { saved.putAll(pending); true }
                        else -> error("未模拟的编辑方法：${editMethod.name}")
                    }
                } as SharedPreferences.Editor
                editor
            }
            else -> error("未模拟的偏好方法：${method.name}")
        }
    } as SharedPreferences
}
