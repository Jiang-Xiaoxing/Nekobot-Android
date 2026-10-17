package com.nekobot.app.data.local

import android.content.SharedPreferences
import com.nekobot.app.data.local.ai.putReasoningParams
import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ReasoningPreferencesTest {
    @Test
    fun customSurvivesReentryWithItsParametersAndCanBuildTheRequest() {
        val saved = mutableMapOf<String, Any>("reasoning_effort_agent" to "high")
        val settings = ReasoningPreferences(preferences(saved))
        settings.setEffort("资料库", "小满", true, "agent", ReasoningEffort.CUSTOM)
        val param = CustomReasoningParam("thinking.budget_tokens", "8192")
        settings.setCustom("资料库", "小满", true, "agent", param)
        val reentered = ReasoningPreferences(preferences(saved))
        val effort = reentered.effort("资料库", "小满", true, "agent")
        assertEquals(ReasoningEffort.CUSTOM, effort)
        assertEquals("自定义", effort.displayName)
        assertEquals(param, reentered.custom("资料库", "小满", true, "agent"))
        val extra = mutableMapOf<String, Any?>()
        putReasoningParams(extra, effort) { reentered.custom("资料库", "小满", true, "agent") }
        assertEquals(param, extra[CustomReasoningParam.EXTRA_KEY])
        assertEquals(mapOf("budget_tokens" to 8192L), param.applyTo(mapOf("model" to "mock"))["thinking"])
        assertEquals(ReasoningEffort.HIGH, reentered.globalEffort(true))
    }

    @Test
    fun existingOpenReaderObservesCustomSelectionAndBothParameterEdits() = runBlocking {
        val settings = ReasoningPreferences(preferences(mutableMapOf("reasoning_effort_agent" to "high")))
        val observed = mutableListOf<Pair<ReasoningEffort, CustomReasoningParam>>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            settings.changes.collect {
                observed += settings.effort("p", "s", true, "agent") to settings.custom("p", "s", true, "agent")
            }
        }
        settings.setEffort("p", "s", true, "agent", ReasoningEffort.CUSTOM)
        kotlinx.coroutines.yield()
        settings.setCustom("p", "s", true, "agent", CustomReasoningParam("reasoning.effort", ""))
        kotlinx.coroutines.yield()
        settings.setCustom("p", "s", true, "agent", CustomReasoningParam("reasoning.effort", "high"))
        kotlinx.coroutines.yield()
        reader.cancel()
        assertEquals(listOf(ReasoningEffort.HIGH, ReasoningEffort.CUSTOM, ReasoningEffort.CUSTOM, ReasoningEffort.CUSTOM),
            observed.map { it.first })
        assertEquals(CustomReasoningParam("reasoning.effort", "high"), observed.last().second)
    }

    @Test
    fun profileSessionAndAgentCharacterScopesRemainIndependent() {
        val settings = ReasoningPreferences(preferences(mutableMapOf()))
        val first = CustomReasoningParam("reasoning_effort", "low")
        settings.setEffort("p", "s", false, "character", ReasoningEffort.CUSTOM)
        settings.setCustom("p", "s", false, "character", first)
        settings.setEffort("p", "s", false, "AgEnT", ReasoningEffort.HIGH)
        settings.setEffort("p", "s", true, "agent", ReasoningEffort.MAX)
        settings.setCustom("p", "s", true, "agent", CustomReasoningParam("thinking.budget_tokens", "4096"))
        assertEquals(ReasoningEffort.CUSTOM, settings.effort("p", "s", false, "group"))
        assertEquals(first, settings.custom("p", "s", false, "group"))
        assertEquals(ReasoningEffort.HIGH, settings.effort("p", "s", false, "agent"))
        assertEquals(ReasoningEffort.MAX, settings.effort("p", "s", true, "agent"))
        assertEquals(ReasoningEffort.HIGH, settings.effort("p", "other", true, "agent"))
        assertEquals(ReasoningEffort.HIGH, settings.effort("other", "s", true, "agent"))
        assertEquals(CustomReasoningParam(), settings.custom("other", "s", true, "agent"))
    }

    @Test
    fun existingStorageKeysAndLegacyFallbackAreKeptWithoutWritingOnRead() {
        val saved = mutableMapOf<String, Any>("reasoning_effort" to "custom",
            "reasoning_custom_path_agent" to "reasoning_effort", "reasoning_custom_value_agent" to "high")
        val original = saved.toMap()
        val settings = ReasoningPreferences(preferences(saved))
        assertEquals(ReasoningEffort.CUSTOM, settings.globalEffort(false))
        assertEquals(ReasoningEffort.CUSTOM, settings.effort("p", "s", true, "agent"))
        assertEquals(CustomReasoningParam("reasoning_effort", "high"), settings.custom("p", "s", true, "agent"))
        assertEquals(original, saved)
    }

    @Test
    fun switchingNoneKeepsCustomParametersAndDisplayAndThinkingPreferences() {
        val saved = mutableMapOf<String, Any>("character_thinking_enabled" to false, "show_reasoning" to true)
        val settings = ReasoningPreferences(preferences(saved))
        val param = CustomReasoningParam("reasoning_effort", "high")
        settings.setCustom("p", "s", true, "agent", param)
        settings.setEffort("p", "s", true, "agent", ReasoningEffort.NONE)
        assertEquals(param, settings.custom("p", "s", true, "agent"))
        assertEquals(false, saved["character_thinking_enabled"])
        assertEquals(true, saved["show_reasoning"])
    }

    private fun preferences(saved: MutableMap<String, Any>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getString" -> saved[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                lateinit var editor: SharedPreferences.Editor
                editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                    when (editMethod.name) {
                        "putString" -> { pending[editArgs!![0] as String] = requireNotNull(editArgs[1]); editor }
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
