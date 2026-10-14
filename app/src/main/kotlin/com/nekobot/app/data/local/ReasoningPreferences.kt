package com.nekobot.app.data.local

import android.content.SharedPreferences
import com.nekobot.app.data.local.ai.CompanionMemoryPolicy
import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 思考档位和参数使用相同作用域；保存后通知仍在显示的设置入口重新读取。 */
internal class ReasoningPreferences(private val prefs: SharedPreferences) {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    private fun companionKey(profile: String, sessionId: String) =
        "companion_recall:" + CompanionMemoryPolicy.digest("$profile\n$sessionId")

    fun globalEffort(agent: Boolean): ReasoningEffort = ReasoningEffort.fromValue(
        prefs.getString(if (agent) "reasoning_effort_agent" else "reasoning_effort_character",
            prefs.getString("reasoning_effort", null)))

    fun companionEffort(profile: String, sessionId: String): ReasoningEffort = ReasoningEffort.fromValue(
        prefs.getString(companionKey(profile, sessionId) + ":reasoning", globalEffort(true).wireValue))

    fun effort(profile: String, sessionId: String, companion: Boolean, mode: String?): ReasoningEffort =
        if (companion) companionEffort(profile, sessionId) else globalEffort(mode.equals("agent", true))

    @Synchronized
    fun setEffort(profile: String, sessionId: String, companion: Boolean, mode: String?, value: ReasoningEffort) {
        val key = if (companion) companionKey(profile, sessionId) + ":reasoning"
            else if (mode.equals("agent", true)) "reasoning_effort_agent" else "reasoning_effort_character"
        prefs.edit().putString(key, value.wireValue).apply()
        revision.value += 1
    }

    fun globalCustom(agent: Boolean): CustomReasoningParam {
        val suffix = if (agent) "agent" else "character"
        return CustomReasoningParam(prefs.getString("reasoning_custom_path_$suffix", "").orEmpty(),
            prefs.getString("reasoning_custom_value_$suffix", "").orEmpty())
    }

    fun companionCustom(profile: String, sessionId: String): CustomReasoningParam {
        val key = companionKey(profile, sessionId)
        val global = globalCustom(true)
        return CustomReasoningParam(prefs.getString("$key:reasoning_custom_path", null) ?: global.path,
            prefs.getString("$key:reasoning_custom_value", null) ?: global.value)
    }

    fun custom(profile: String, sessionId: String, companion: Boolean, mode: String?): CustomReasoningParam =
        if (companion) companionCustom(profile, sessionId) else globalCustom(mode.equals("agent", true))

    @Synchronized
    fun setCustom(profile: String, sessionId: String, companion: Boolean, mode: String?, value: CustomReasoningParam) {
        val prefix = if (companion) companionKey(profile, sessionId) else null
        val suffix = if (mode.equals("agent", true)) "agent" else "character"
        prefs.edit()
            .putString(prefix?.let { "$it:reasoning_custom_path" } ?: "reasoning_custom_path_$suffix", value.path)
            .putString(prefix?.let { "$it:reasoning_custom_value" } ?: "reasoning_custom_value_$suffix", value.value)
            .apply()
        revision.value += 1
    }
}
