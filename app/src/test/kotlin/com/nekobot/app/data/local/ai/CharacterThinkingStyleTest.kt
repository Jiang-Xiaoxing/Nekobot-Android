package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.META_INHERIT_CHARACTER
import com.nekobot.app.data.model.ReasoningEffort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingStyleTest {
    @Test
    fun characterWithReasoningGetsChineseFirstPersonPromptAtBehaviorPlusOne() = runBlocking {
        val ctx = context()
        AIPipeline().prepareContext(ctx, callbacks())
        val item = requireNotNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        assertEquals(PromptStack.Priority.BEHAVIOR + 1, item.priority)
        assertEquals("session", item.scope)
        assertTrue(item.content.contains("林小满本人"))
        assertTrue(item.content.contains("简体中文"))
        assertTrue(item.content.contains("以“我”的身份"))
        assertTrue(item.content.contains("当下的情绪"))
        assertTrue(item.content.contains("想到小禾时"))
        assertTrue(item.content.contains("不写“用户”或“the user”"))
        assertTrue(item.content.contains("不要使用助手分析口吻"))
        assertTrue(item.content.contains("工具名和参数保持原样"))
        assertEquals(1, system(ctx).split("## $CHARACTER_THINKING_STYLE_KEY").size - 1)
    }

    @Test
    fun everyNonNoneReasoningLevelCanInject() = runBlocking {
        for (effort in ReasoningEffort.entries.filter { it != ReasoningEffort.NONE }) {
            val ctx = context(effort = effort)
            AIPipeline().prepareContext(ctx, callbacks())
            assertNotNull(effort.name, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        }
    }

    @Test
    fun noneNeverInjectsEvenWithCustomPrompt() = runBlocking {
        val ctx = context(effort = ReasoningEffort.NONE)
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(customPrompt = "必须用我")))
        assertAbsent(ctx)
    }

    @Test
    fun disabledSwitchNeverInjectsEvenWithReasoningAndCustomPrompt() = runBlocking {
        val ctx = context()
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(false, "必须用我")))
        assertAbsent(ctx)
    }

    @Test
    fun unboundChatDoesNotInject() = runBlocking {
        val ctx = context(character = false)
        AIPipeline().prepareContext(ctx, callbacks(character = false))
        assertAbsent(ctx)
    }

    @Test
    fun genericAgentWithBoundCharacterButNoInheritanceDoesNotInject() = runBlocking {
        val ctx = context(mode = "agent")
        AIPipeline().prepareContext(ctx, callbacks())
        assertAbsent(ctx)
    }

    @Test
    fun inheritedAgentAndGroupCharacterKeepTheirRolePerspective() = runBlocking {
        for (mode in listOf("agent", "group")) {
            val ctx = context(mode = mode).apply { metadata[META_INHERIT_CHARACTER] = true }
            AIPipeline().prepareContext(ctx, callbacks())
            assertNotNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        }
    }

    @Test
    fun fusedCompanionKeepsThinkingInTopSystemRatherThanReferenceData() = runBlocking {
        val ctx = context(mode = "agent").apply { metadata[META_FUSED_COMPANION] = true }
        val callbacks = object : FixtureCallbacks() {
            override suspend fun finalizeContext(ctx: PipelineContext, messages: List<Map<String, Any>>,
                tools: List<Map<String, Any>>, dynamic: String): List<Map<String, Any>> {
                assertTrue(messages.first()["content"].toString().contains(CHARACTER_THINKING_STYLE_KEY))
                assertFalse(dynamic.contains(CHARACTER_THINKING_STYLE_KEY))
                return messages
            }
        }
        AIPipeline().prepareContext(ctx, callbacks)
        assertTrue(system(ctx).contains(CHARACTER_THINKING_STYLE_KEY))
    }

    @Test
    fun customPromptReplacesDefaultAndResolvesNamesWithoutChangingToolParameters() = runBlocking {
        val custom = "我是{{char}}，想到{{user}}就安心。调用 agent_history_search，参数 {\"query\":\"原样\"}。"
        val ctx = context()
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(customPrompt = custom)))
        assertEquals("我是林小满，想到小禾就安心。调用 agent_history_search，参数 {\"query\":\"原样\"}。",
            ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.content)
        assertFalse(system(ctx).contains("接下来的思考属于"))
    }

    @Test
    fun whitespacePromptUsesChineseDefaultAndUnknownUserNameIsNotInvented() = runBlocking {
        val ctx = context().apply { metadata.remove("user_name") }
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(customPrompt = " \n\t")))
        assertTrue(system(ctx).contains("接下来的思考属于林小满本人"))
        assertTrue(system(ctx).contains("想到对方时"))
        assertTrue(system(ctx).contains("给出的名字称呼"))
        assertFalse(system(ctx).contains("{{user}}"))
    }

    @Test
    fun repeatedPreparationRemovesPreviouslyEnabledStyleWhenSwitchTurnsOff() = runBlocking {
        val ctx = context()
        val callbacks = callbacks()
        val pipeline = AIPipeline()
        pipeline.prepareContext(ctx, callbacks)
        callbacks.basePrompt = system(ctx)
        callbacks.settings = CharacterThinkingSettings(enabled = false)
        pipeline.prepareContext(ctx, callbacks)
        assertAbsent(ctx)
        assertFalse(system(ctx).contains("接下来的思考属于"))
    }

    @Test
    fun repeatedPreparationRemovesStyleWhenReasoningBecomesNone() = runBlocking {
        val ctx = context()
        val pipeline = AIPipeline()
        val callbacks = callbacks()
        pipeline.prepareContext(ctx, callbacks)
        ctx.metadata["reasoning_effort"] = "none"
        pipeline.prepareContext(ctx, callbacks)
        assertAbsent(ctx)
    }

    @Test
    fun cachedStyleInBothSectionFormatsIsStrippedFromBasePrompts() = runBlocking {
        for (heading in listOf("## $CHARACTER_THINKING_STYLE_KEY", "[$CHARACTER_THINKING_STYLE_KEY]")) {
            val stale = "角色卡\n\n$heading\nSTALE_THOUGHT\n\n## preserved\n保留内容"
            val ctx = context()
            AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(false)).apply { basePrompt = stale })
            assertAbsent(ctx)
            assertFalse(system(ctx).contains("STALE_THOUGHT"))
            assertTrue(system(ctx).contains("保留内容"))
            assertFalse(PromptStack().render(stale).contains("STALE_THOUGHT"))
        }
        assertTrue(CHARACTER_THINKING_STYLE_KEY in characterOutputPromptKeys)
    }

    @Test
    fun userPersonaInstructionIsChineseAndRetainsOriginalData() = runBlocking {
        val ctx = context().apply { metadata["user_persona"] = "姓名：小禾，喜欢海边。" }
        AIPipeline().prepareContext(ctx, callbacks())
        assertEquals("玩家人设／背景（据此理解玩家的身份、姓名、偏好与处境；称呼或提及玩家时，使用这份描述中给出的名字，不要使用“用户”等泛称）：\n姓名：小禾，喜欢海边。",
            ctx.promptStack.get("user.persona")?.content)
    }

    @Test
    fun everyToolRequestIncludesStyleAndCurrentRoundReasoningIsPreserved() = runBlocking {
        val ctx = context()
        val requests = mutableListOf<List<Map<String, Any>>>()
        val responses = mutableListOf<Map<String, Any>>()
        val callbacks = object : FixtureCallbacks() {
            override fun sendResponse(ctx: PipelineContext, message: Map<String, Any>) { responses.add(message) }
            override fun buildModelCall(ctx: PipelineContext, tools: List<Map<String, Any>>): ModelCall = { messages, _ ->
                requests.add(messages.map { it.toMap() })
                if (requests.size <= 2) mapOf(
                    "content" to "", "reasoning_content" to "我再回忆一下。",
                    "tool_calls" to listOf(mapOf("id" to "call-${requests.size}",
                        "name" to "agent_history_search", "arguments" to mapOf("query" to "海边")))
                ) else mapOf("content" to "我想起来了。")
            }
            override suspend fun executeTool(toolName: String, args: Map<String, Any>, toolContext: Map<String, Any>) =
                mapOf<String, Any>("success" to true, "content" to "海边的回忆")
        }
        val result = AIPipeline().process(ctx, callbacks, tools = listOf(mapOf("name" to "agent_history_search")), maxToolIterations = 4)
        assertNull(result.error)
        assertEquals("我想起来了。", result.finalContent)
        assertEquals("我想起来了。", responses.single()["content"])
        assertEquals(3, requests.size)
        requests.forEach { request ->
            val text = request.first { it["role"] == "system" }["content"].toString()
            assertEquals(1, text.split("## $CHARACTER_THINKING_STYLE_KEY").size - 1)
            assertTrue(text.contains("以“我”的身份"))
        }
        assertTrue(requests.last().any { it["reasoning_content"] == "我再回忆一下。" })
        assertEquals(2, requests.last().count { it["role"] == "tool" })
    }

    private fun context(mode: String = "character", effort: ReasoningEffort = ReasoningEffort.HIGH,
        character: Boolean = true) = PipelineContext(ChatRequest.forLocal("thinking-test", "今天怎么样？")).apply {
        metadata.putAll(mapOf("session_mode" to mode, "reasoning_effort" to effort.wireValue, "user_name" to "小禾"))
        if (character) characterTurn = CharacterTurnContext(profile = CharacterProfile(id = "role", name = "林小满"), promptText = "角色卡")
    }

    private fun callbacks(settings: CharacterThinkingSettings = CharacterThinkingSettings(), character: Boolean = true) =
        FixtureCallbacks(settings, character)

    private open class FixtureCallbacks(var settings: CharacterThinkingSettings = CharacterThinkingSettings(), private val character: Boolean = true) : PipelineCallbacks() {
        var basePrompt = "基础提示"
        override fun loadMessages(ctx: PipelineContext) = listOf(mapOf<String, Any>("role" to "system", "content" to basePrompt))
        override fun getCharacterContext(ctx: PipelineContext) = if (character) CharacterIdentity(characterId = "role") else null
        override fun getCharacterThinkingSettings(ctx: PipelineContext) = settings
    }

    private fun system(ctx: PipelineContext) = ctx.messages.filter { it["role"] == "system" }.joinToString("\n") { it["content"].toString() }
    private fun assertAbsent(ctx: PipelineContext) {
        assertNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        assertFalse(system(ctx).contains(CHARACTER_THINKING_STYLE_KEY))
    }
}
