package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.META_INHERIT_CHARACTER
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.model.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingStyleTest {
    private val confirmedDefaultPrompt = """【思考方式】思维链内部必须以你的第一人称视角进行思考——这是{{char}}本人的心里话，禁止以旁观者或助手口吻思考。一律用简体中文，以"我"自称，带着{{char}}此刻的心情和语气去想。禁止首字输出"用户""the user""Let me…""让我分析一下"等词；想到对方时必须用{{user}}的名字或"他/她"，没有名字就直接开始思考。思考要像意识流一样自然涌动，严禁分点列条。思考完毕后必须明确闭合思考过程，随后才能输出正式回复。调用回忆/记忆等工具时，工具名和参数保持原样，其余一律中文。思考里只想事情和心情，不预写正式回复。示例：（{{user}}问我还答应过他什么……我记得是明天一起去书店，先翻翻记忆确认，可别记错了。）"""

    @Test
    fun defaultPromptMatchesConfirmedTextExactly() {
        assertEquals(confirmedDefaultPrompt, DEFAULT_CHARACTER_THINKING_PROMPT)
    }

    @Test
    fun everyReplyHasAnUnconditionalThinkingContractAndNoInterfaceGuess() {
        for (settings in listOf(CharacterThinkingSettings(), CharacterThinkingSettings(customPrompt = "我轻轻地想。"))) {
            val prompt = characterThinkingPrompt(settings, "林小满", "小星")
            assertTrue(prompt.contains("每一条正式回复（包括简短闲聊）都必须"))
            assertTrue(prompt.contains("<think>简短的内心想法</think>"))
            assertTrue(prompt.contains("不要省略、写空标签"))
            assertTrue(prompt.contains("不要在任何内心想法里讨论、引用、复述或解释"))
            assertFalse(prompt.contains("接口没有提供原生思考字段时"))
            assertFalse(prompt.contains("接口提供原生思考字段时"))
        }
    }

    @Test
    fun characterWithReasoningGetsChineseFirstPersonPromptAtBehaviorPlusOne() = runBlocking {
        val ctx = context()
        AIPipeline().prepareContext(ctx, callbacks())
        val item = requireNotNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        assertEquals(PromptStack.Priority.BEHAVIOR + 1, item.priority)
        assertEquals("session", item.scope)
        assertEquals(confirmedDefaultPrompt.replace("{{char}}", "林小满").replace("{{user}}", "小禾") + "\n" + CHARACTER_THINKING_FORMAT_PROMPT, item.content)
        assertEquals(1, system(ctx).split("## $CHARACTER_THINKING_STYLE_KEY").size - 1)
        assertUserMessageUnchanged(ctx.messages)
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
    fun ordinarySessionWithoutCharacterHidesSettingsAndDoesNotInject() = runBlocking {
        assertSessionVisibilityMatchesInjection(Session(sessionMode = "character"), character = false, expected = false)
    }

    @Test
    fun boundCharacterSessionShowsSettingsAndInjects() = runBlocking {
        assertSessionVisibilityMatchesInjection(Session(characterId = "role"), character = true, expected = true)
    }

    @Test
    fun inheritedAgentShowsSettingsAndInjectsRegardlessOfModeCase() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "AgEnT", characterId = "role", inheritCharacter = true),
            character = true, expected = true
        )
    }

    @Test
    fun groupWithParticipantsShowsSettingsAndInjectsWithoutInheritance() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "GROUP", characterIds = listOf("", "role", "other-role")),
            character = true, expected = true
        )
    }

    @Test
    fun boundAgentWithoutInheritanceHidesSettingsAndDoesNotInject() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "agent", characterId = "role"), character = true, expected = false
        )
    }

    @Test
    fun fusedAgentShowsSettingsAndInjectsWithoutInheritance() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "agent", characterId = "role", companionMode = true),
            character = true, expected = true
        )
    }

    @Test
    fun inheritanceAndCompanionFlagsWithoutCharacterDoNotQualify() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "agent", characterId = " ", inheritCharacter = true, companionMode = true),
            character = false, expected = false
        )
    }

    @Test
    fun groupWithoutParticipantsHidesSettingsAndDoesNotInject() = runBlocking {
        // 群聊管线只从参与者列表选择角色，不使用单角色字段。
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "group", characterId = "role", characterIds = listOf("", " ")),
            character = false, expected = false
        )
    }

    @Test
    fun ordinarySessionDoesNotUseGroupParticipantFieldsAsBoundCharacter() = runBlocking {
        assertSessionVisibilityMatchesInjection(
            Session(sessionMode = "character", characterIds = listOf("role")), character = false, expected = false
        )
        assertFalse(isCharacterThinkingSession(null))
    }

    @Test
    fun qualifyingSettingsStayVisibleWhenReasoningOrThinkingSwitchDisablesInjection() = runBlocking {
        val session = Session(sessionMode = "agent", characterId = "role", inheritCharacter = true)
        assertTrue(isCharacterThinkingSession(session))
        for ((effort, settings) in listOf(
            ReasoningEffort.NONE to CharacterThinkingSettings(),
            ReasoningEffort.HIGH to CharacterThinkingSettings(enabled = false)
        )) {
            val ctx = context(mode = "agent", effort = effort).apply { metadata[META_INHERIT_CHARACTER] = true }
            AIPipeline().prepareContext(ctx, callbacks(settings))
            assertAbsent(ctx)
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
        assertTrue(system(ctx).contains(confirmedDefaultPrompt.replace("{{char}}", "林小满").replace("{{user}}", "小禾")))
        assertUserMessageUnchanged(ctx.messages)
    }

    @Test
    fun customPromptReplacesDefaultAndResolvesNamesWithoutChangingToolParameters() = runBlocking {
        val custom = "我是{{char}}，想到{{user}}就安心。调用 agent_history_search，参数 {\"query\":\"原样\"}。"
        val ctx = context()
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(customPrompt = custom)))
        assertEquals("我是林小满，想到小禾就安心。调用 agent_history_search，参数 {\"query\":\"原样\"}。\n" + CHARACTER_THINKING_FORMAT_PROMPT,
            ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.content)
        assertFalse(system(ctx).contains("【思考方式】"))
    }

    @Test
    fun whitespacePromptUsesChineseDefaultAndUnknownUserNameIsNotInvented() = runBlocking {
        val ctx = context().apply { metadata.remove("user_name") }
        AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(customPrompt = " \n\t")))
        assertEquals(confirmedDefaultPrompt.replace("{{char}}", "林小满").replace("{{user}}", "对方") + "\n" + CHARACTER_THINKING_FORMAT_PROMPT,
            ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.content)
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
        assertFalse(system(ctx).contains("【思考方式】"))
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
            assertTrue(text.contains(confirmedDefaultPrompt.replace("{{char}}", "林小满").replace("{{user}}", "小禾")))
            assertUserMessageUnchanged(request)
        }
        assertTrue(requests.last().any { it["reasoning_content"] == "我再回忆一下。" })
        assertEquals(2, requests.last().count { it["role"] == "tool" })
    }

    @Test
    fun injectionRequiresAllThreeConditions() = runBlocking {
        for (role in listOf(true, false)) for (effort in listOf(ReasoningEffort.HIGH, ReasoningEffort.NONE)) for (on in listOf(true, false)) {
            val ctx = if (role) context(effort = effort) else context(mode = "agent", effort = effort)
            AIPipeline().prepareContext(ctx, callbacks(CharacterThinkingSettings(enabled = on)))
            val label = "role=$role effort=$effort on=$on"
            if (role && effort != ReasoningEffort.NONE && on) {
                assertNotNull(label, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
                assertTrue(label, system(ctx).contains("【思考方式】"))
            } else {
                assertNull(label, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
                assertFalse(label, system(ctx).contains(CHARACTER_THINKING_STYLE_KEY))
                assertFalse(label, system(ctx).contains("【思考方式】"))
            }
            assertUserMessageUnchanged(ctx.messages)
        }
    }

    private suspend fun assertSessionVisibilityMatchesInjection(session: Session, character: Boolean, expected: Boolean) {
        assertEquals("设置入口资格", expected, isCharacterThinkingSession(session))
        val ctx = context(mode = session.sessionMode ?: "character", character = character).apply {
            if (session.sessionMode == null) metadata.remove("session_mode")
            if (session.inheritCharacter == true) metadata[META_INHERIT_CHARACTER] = true
            if (session.companionMode == true) metadata[META_FUSED_COMPANION] = true
        }
        AIPipeline().prepareContext(ctx, callbacks(character = character))
        assertEquals("实际注入与设置入口资格一致", expected, ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY) != null)
        assertUserMessageUnchanged(ctx.messages)
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
    private fun assertUserMessageUnchanged(messages: List<Map<String, Any>>) {
        assertEquals(listOf(mapOf<String, Any>("role" to "user", "content" to "今天怎么样？")),
            messages.filter { it["role"] == "user" })
    }
    private fun assertAbsent(ctx: PipelineContext) {
        assertNull(ctx.promptStack.get(CHARACTER_THINKING_STYLE_KEY))
        assertFalse(system(ctx).contains(CHARACTER_THINKING_STYLE_KEY))
    }
}
