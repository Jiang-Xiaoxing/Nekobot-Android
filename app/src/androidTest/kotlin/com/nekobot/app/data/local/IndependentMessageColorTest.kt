package com.nekobot.app.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class IndependentMessageColorTest {
    @Test fun userAndAssistantColorsPersistIndependently() {
        val context = context()
        val prefs = PrefsManager(context)
        assertNull(prefs.assistantMessageColorOverride)
        prefs.messageColorOverride = "#FFE2EC"
        assertNull(prefs.assistantMessageColorOverride)
        prefs.assistantMessageColorOverride = "#102030"
        prefs.themeColorOverride = "#8B6CFF"
        val restored = PrefsManager(context)
        assertEquals("#FFE2EC", restored.messageColorOverride)
        assertEquals("#102030", restored.assistantMessageColorOverride)
        restored.assistantMessageColorOverride = null
        assertEquals("#FFE2EC", restored.messageColorOverride)
        assertNull(restored.assistantMessageColorOverride)
    }

    private fun context() = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val prefix = "color-test-${UUID.randomUUID()}-"
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
    }
    @Test fun changingThemeBeforeOpeningAppearancePreservesLegacyBubbleColor() {
        val context = context()
        val prefs = PrefsManager(context)
        // 写入旧版本保存方式，不提前调用新 getter 或 setter。
        context.getSharedPreferences("nekobot_prefs", Context.MODE_PRIVATE).edit()
            .putString(PrefsManager.KEY_THEME_COLOR, "#6BCB77").commit()
        prefs.themeColorOverride = "#8B6CFF"
        assertEquals("#6BCB77", prefs.messageColorOverride)
        prefs.messageColorOverride = "#4D96FF"
        assertEquals("#8B6CFF", prefs.themeColorOverride)
        assertEquals("#4D96FF", PrefsManager(context).messageColorOverride)
    }
    @Test fun defaultBubbleDoesNotStartFollowingNewTheme() {
        val prefs = PrefsManager(context())
        assertNull(prefs.messageColorOverride)
        prefs.themeColorOverride = "#6BCB77"
        assertNull(prefs.messageColorOverride)
        prefs.messageColorOverride = "#4D96FF"
        prefs.messageColorOverride = null
        prefs.themeColorOverride = "#8B6CFF"
        assertNull(prefs.messageColorOverride)
    }
}
