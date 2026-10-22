package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ReasoningEffort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompanionPromptSettingsTest {
    @Test fun defaultsReuseExistingWordsAndResetDoesNotChangeTheSwitch() {
        val settings = CompanionPromptSettings(thinkingEnabled = false)
        assertEquals(CompanionPromptLanguage.styleReminder.replace(CompanionPromptLanguage.bubbleRule, ""), settings.speakingForEditing)
        assertEquals(DEFAULT_CHARACTER_THINKING_PROMPT, characterThinkingPromptForEditing(settings.thinking))
        assertEquals("", companionSpeakingPromptToSave(settings.speakingForEditing))
        assertEquals("", companionSpeakingPromptToSave(" \n"))
        assertEquals("  自定义\n原文  ", companionSpeakingPromptToSave("  自定义\n原文  "))
        assertFalse(settings.thinking.enabled)
    }

    @Test fun placeholdersAreExpandedOnlyInTheSendingCopy() {
        val settings = CompanionPromptSettings("{{char}}对{{user}}说", "{{char}}想起{{user}}")
        assertEquals("小满对小舟说", settings.speakingForRequest("小满", "小舟"))
        assertEquals("小满想起小舟", characterThinkingPrompt(settings.thinking, "小满", "小舟"))
        assertEquals("当前角色对对方说", settings.speakingForRequest("", "user"))
        assertEquals("{{char}}对{{user}}说", settings.speakingPrompt)
    }

    @Test fun customSpeakingReplacesDefaultAndThinkingStaysOnlyAtTail() = runBlocking {
        val settings = CompanionPromptSettings("说话甲：{{char}}", "思考甲：{{char}}", true)
        val sent = prepare(settings)
        val opening = sent.first()["content"].toString()
        assertTrue(opening.contains("说话甲：林小满"))
        assertFalse(opening.contains(CompanionPromptLanguage.speakingRule))
        assertTrue(opening.contains(CompanionPromptLanguage.bubbleRule))
        assertFalse(opening.contains("思考甲"))
        assertEquals("现在是测试时间\n\n思考甲：林小满", sent.last()["content"])
        assertEquals("本轮原话", sent.last { it["role"] == "user" }["content"])
        assertEquals(1, sent.sumOf { it["content"].toString().split("思考甲").size - 1 })
        assertEquals(1, sent.sumOf { it["content"].toString().split("说话甲").size - 1 })
    }

    @Test fun disabledAndNoneKeepTimeAndSavedTextWithoutInjectingThinking() = runBlocking {
        val disabled = CompanionPromptSettings("说话乙", "暂存未启用思考", false)
        assertEquals("现在是测试时间", prepare(disabled).last()["content"])
        val none = prepare(disabled.copy(thinkingEnabled = true), ReasoningEffort.NONE)
        assertEquals("现在是测试时间", none.last()["content"])
        assertTrue(none.first()["content"].toString().contains("说话乙"))
        assertEquals("暂存未启用思考", disabled.thinkingPrompt)
    }

    @Test fun secondRoleAndRestoredDefaultsDoNotReuseFirstRoleCustomText() = runBlocking {
        val first = prepare(CompanionPromptSettings("甲的说话方式", "甲的思考"))
        val second = prepare(CompanionPromptSettings())
        assertTrue(first.first()["content"].toString().contains("甲的说话方式"))
        assertTrue(second.first()["content"].toString().contains(CompanionPromptLanguage.speakingRule))
        assertFalse(second.toString().contains("甲的"))
        assertEquals("现在是测试时间\n\n$DEFAULT_CHARACTER_THINKING_PROMPT", second.last()["content"])
    }

    private suspend fun prepare(settings: CompanionPromptSettings, effort: ReasoningEffort = ReasoningEffort.HIGH): List<Map<String, Any>> {
        val ctx = PipelineContext(ChatRequest.forLocal("synthetic", "本轮原话")).apply {
            metadata[META_FUSED_COMPANION] = true
            metadata["session_mode"] = "agent"
        }
        val runtime = CharacterRuntime(profileRepo = object : ProfileRepository {
            override suspend fun getById(id: String) = CharacterProfile(id = id, name = "林小满", systemPrompt = "虚构角色设定")
        })
        var prepared = false
        var result = emptyList<Map<String, Any>>()
        val callbacks = object : PipelineCallbacks() {
            override suspend fun prepareCharacterPromptSettings(ctx: PipelineContext) { prepared = true }
            override fun getCompanionSpeakingPrompt(ctx: PipelineContext): String {
                check(prepared)
                return settings.speakingPrompt
            }
            override fun getCharacterThinkingSettings(ctx: PipelineContext) = settings.thinking
            override fun getReasoningEffort(ctx: PipelineContext) = effort
            override fun getCharacterRuntime(ctx: PipelineContext) = runtime
            override fun getCharacterContext(ctx: PipelineContext) = CharacterIdentity("role", "player", "synthetic")
            override fun loadMessages(ctx: PipelineContext) = listOf(mapOf<String, Any>("role" to "user", "content" to "本轮原话"))
            override suspend fun finalizeContext(ctx: PipelineContext, messages: List<Map<String, Any>>, tools: List<Map<String, Any>>, dynamic: String): List<Map<String, Any>> {
                val clock = mapOf<String, Any>("role" to "system", "content" to "现在是测试时间", CHARACTER_TURN_CONTEXT to "现在是测试时间")
                result = withCharacterThinkingRule(messages + clock, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.content, ctx.usesCharacterThinking())
                assertEquals(messages.first()["content"].toString().contains(settings.speakingForRequest("林小满", null)), true)
                assertFalse(dynamic.contains(settings.speakingForRequest("林小满", null)))
                return result
            }
        }
        AIPipeline().prepareContext(ctx, callbacks)
        return result
    }
}
