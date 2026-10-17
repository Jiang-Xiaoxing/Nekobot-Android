package com.nekobot.app.ui.screens.chat

import android.content.SharedPreferences
import com.nekobot.app.data.local.CharacterThinkingPreferences
import com.nekobot.app.data.local.ReasoningPreferences
import com.nekobot.app.data.model.ReasoningEffort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class CompanionThoughtVisibilityTest {
    @Test
    fun savedThoughtAndStreamingDeltasFollowBothSwitchesWithoutErasingSourceOrChangingEffort() = runBlocking {
        val saved = mutableMapOf<String, Any>("reasoning_effort_agent" to "high")
        val prefs = preferences(saved)
        val thinking = CharacterThinkingPreferences(prefs)
        val display = MutableStateFlow(false)
        val streamed = MutableStateFlow("")
        val history = "我记得他昨天很开心。"
        val observations = mutableListOf<Pair<String, String>>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(display, thinking.settings, streamed) { show, settings, delta ->
                val enabled = companionThoughtsEnabled(show, settings.enabled)
                (if (enabled) history else "") to (if (enabled) delta else "")
            }.collect { observations += it }
        }
        repeat(3) { yield() }
        display.value = true
        repeat(3) { yield() }
        streamed.value = "我"
        repeat(3) { yield() }
        thinking.enabled = false
        repeat(3) { yield() }
        streamed.value = "我听见小星笑了。"
        repeat(3) { yield() }
        thinking.enabled = true
        repeat(3) { yield() }
        display.value = false
        repeat(3) { yield() }
        job.cancel()

        assertEquals(listOf("" to "", history to "", history to "我", "" to "", "" to "",
            history to "我听见小星笑了。", "" to ""), observations)
        assertEquals("我听见小星笑了。", streamed.value)
        assertEquals("我记得他昨天很开心。", history)
        assertEquals(ReasoningEffort.HIGH, ReasoningPreferences(prefs).globalEffort(true))
        assertEquals("", thinking.customPrompt)
    }

    @Test
    fun noneForNewRequestsDoesNotTurnOffEitherSavedDisplayChoice() {
        val saved = mutableMapOf<String, Any>("show_reasoning" to true)
        val prefs = preferences(saved)
        val thinking = CharacterThinkingPreferences(prefs)
        val reasoning = ReasoningPreferences(prefs)
        reasoning.setEffort("p", "s", true, "agent", ReasoningEffort.NONE)
        assertTrue(companionThoughtsEnabled(saved["show_reasoning"] == true, thinking.settings.value.enabled))
        assertEquals(ReasoningEffort.NONE, reasoning.effort("p", "s", true, "agent"))
        assertEquals(true, saved["show_reasoning"])
        assertTrue(thinking.settings.value.enabled)
    }

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
                        "putBoolean", "putString" -> { pending[editArgs!![0] as String] = requireNotNull(editArgs[1]); editor }
                        "apply", "commit" -> { saved.putAll(pending); true }
                        else -> error(editMethod.name)
                    }
                } as SharedPreferences.Editor
                editor
            }
            else -> error(method.name)
        }
    } as SharedPreferences
}
