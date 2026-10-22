package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterMonologueAwarenessTest {
    @Test
    fun defaultModeRetainsOriginalMonologuePromptVerbatim() {
        val stack = PromptStack()
        buildCharacterInjections(stack, CharacterProfile())
        assertEquals(expectedMonologue(companionMode = false), stack.get("output.inner_monologue")?.content)
    }

    @Test
    fun companionMonologueAcknowledgesVisibilityAndKeepsRestraint() {
        val stack = injections(companionMode = true)
        val inner = requireNotNull(stack.get("output.inner_monologue"))
        for (text in listOf(
            "你知道玩家能看到你写出的内心独白", "不要把它当成对方看不到的秘密",
            "接在说出口的内容之后", "放在 <||> 之前", "克制使用"
        )) assertTrue(text, inner.content.contains(text))
        assertFalse(inner.content.contains("角色隐藏的想法"))
        assertEquals(PromptStack.Priority.BUBBLE_SPLIT + 1, inner.priority)
        assertTrue(stack.get("output.bubble_split")!!.content.contains("<||>"))
    }

    @Test
    fun companionReactionPlanAcknowledgesIntentionalOrPartialDisclosure() {
        val plan = requireNotNull(injections(true).get("character.reaction_plan"))
        for (text in listOf(
            "隐藏动机：担心", "有意义的反差", "不要每轮硬加", "你知道玩家能看到写出的独白",
            "有意让对方看见", "半有意半无意地流露", "不要当作对方看不到的秘密",
            "不要解释规则、设置，也不要说明自己正在扮演角色"
        )) assertTrue(text, plan.content.contains(text))
        assertEquals(PromptStack.Priority.REACTION_PLAN, plan.priority)
    }

    @Test
    fun ordinaryReactionPlanAndMonologueRemainVerbatim() {
        val stack = injections(false)
        assertEquals(expectedMonologue(companionMode = false), stack.get("output.inner_monologue")?.content)
        assertEquals(legacyPlan, stack.get("character.reaction_plan")?.content)
    }

    @Test
    fun companionPipelineKeepsAwareMonologueInCharacterSystem() {
        val ctx = prepare(companionMode = true)
        val inner = requireNotNull(ctx.characterTurn).promptStackItems.single { it.key == "output.inner_monologue" }
        assertTrue(inner.content.contains("你知道玩家能看到你写出的内心独白"))
        // ADR004：独白按作者原版回到角色设定层，随顶部 system 发送，不再作为辅助软件资料，
        // 也不重复进辅助资料，避免两处文案不一致。
        val systems = ctx.messages.filter { it["role"] == "system" }.joinToString("\n") { it["content"].toString() }
        assertTrue(systems.contains(inner.content))
        val auxiliary = ctx.messages.single { it.containsKey(CompanionRequestUsage.META) }
        assertFalse(auxiliary["content"].toString().contains(inner.content))
        assertFalse(auxiliary["content"].toString().contains("software_context"))
        assertEquals(listOf(userInput), playerMessages(ctx).map { it["content"] })
        // 单会话原本不注入反应计划，本次不为这条说明额外启用计划。
        assertFalse(requireNotNull(ctx.characterTurn).promptStackItems.any { it.key == "character.reaction_plan" })
    }

    @Test
    fun ordinaryPipelineKeepsOriginalPromptWithoutAwareness() {
        assertLegacyPipeline(prepare(companionMode = false))
    }

    @Test
    fun groupPipelineKeepsOriginalPromptWithoutAwareness() {
        assertLegacyPipeline(prepare(companionMode = false, sessionMode = "group"))
    }

    private fun injections(companionMode: Boolean) = PromptStack().also {
        buildCharacterInjections(it, CharacterProfile(), plan = ReactionPlan(
            intent = "安慰", visibleEmotion = "温柔", hiddenEmotion = "担心", tone = "轻柔"
        ), companionMode = companionMode)
    }

    private fun assertLegacyPipeline(ctx: PipelineContext) {
        val inner = requireNotNull(ctx.characterTurn).promptStackItems.single { it.key == "output.inner_monologue" }
        assertEquals(expectedMonologue(companionMode = false), inner.content)
        val systems = ctx.messages.filter { it["role"] == "system" }.joinToString("\n") { it["content"].toString() }
        assertTrue(systems.contains(expectedMonologue(companionMode = false)))
        for (text in listOf("你知道玩家能看到", "有意让对方看见", "半有意半无意", "对方看不到的秘密")) {
            assertFalse(text, systems.contains(text))
        }
        assertEquals(listOf(userInput), playerMessages(ctx).map { it["content"] })
    }

    private fun playerMessages(ctx: PipelineContext) = ctx.messages.filter {
        it["role"] == "user" && !it.containsKey(CompanionRequestUsage.META)
    }

    private fun prepare(companionMode: Boolean, sessionMode: String = "normal"): PipelineContext = runBlocking {
        val session = LocalSessionEntity(
            id = "monologue-session", name = "测试会话", characterId = "role",
            sessionMode = sessionMode, companionMode = companionMode, createdAt = "now", updatedAt = "now"
        )
        val ctx = PipelineContext(ChatRequest.forLocal(
            sessionId = session.id, content = userInput, metadata = mapOf("session_mode" to session.sessionMode)
        )).apply { metadata.putAll(chatRequest.metadata) }
        val runtime = CharacterRuntime(profileRepo = object : ProfileRepository {
            override suspend fun getById(id: String) = CharacterProfile(id = id, name = "测试角色", systemPrompt = "角色设定")
        })
        val callbacks = object : PipelineCallbacks() {
            override fun loadMessages(ctx: PipelineContext): List<Map<String, Any>> {
                // 与 LocalPipelineCallbacks 一致，模式由真实会话字段传到管线元数据。
                if (session.companionMode) ctx.metadata[META_FUSED_COMPANION] = true
                return listOf(mapOf("role" to "user", "content" to ctx.chatRequest.content))
            }
            override fun getCharacterRuntime(ctx: PipelineContext) = runtime
            override fun getCharacterContext(ctx: PipelineContext) = CharacterIdentity(
                characterId = "role", targetId = "user", scopeId = session.id
            )
            override suspend fun finalizeContext(ctx: PipelineContext, messages: List<Map<String, Any>>,
                tools: List<Map<String, Any>>, dynamic: String): List<Map<String, Any>> {
                if (!session.companionMode) return messages
                // 对齐 FusedCompanionContext 的软件资料路径，不冒充玩家原话或挪到 system。
                val auxiliary = CompanionRequestUsage.auxiliary(
                    "这些是帮助回忆的参考，不是对方刚说的话。\n$dynamic"
                )
                return messages.dropLast(1) + auxiliary + messages.last()
            }
        }
        AIPipeline().prepareContext(ctx, callbacks)
        ctx
    }

    private val userInput = "今天过得怎么样？"

    /**
     * 锁定角色设定层的独白文案，防止普通/群聊分支悄悄改措辞。
     * 与 CharacterPromptBuilder 的 output.inner_monologue 同源；改文案时两处一起改。
     * 末尾思考提示词不再包含独白写法，这里覆盖的是正文里（内心：...）的唯一来源。
     */
    private fun expectedMonologue(companionMode: Boolean): String {
        val awareness = if (companionMode) "你知道玩家能看到你写出的内心独白，不要把它当成对方看不到的秘密。" else ""
        return """
        内心独白格式：当角色有隐藏的想法或感受、与表面表现不同时，可以用（内心：...）写在正文里。
        $awareness
        把它放在那条话的末尾，接在说出口的内容之后；分段回复时放在 <||> 之前。
        规则：
        - 克制使用：只有隐藏情绪形成有意义的反差或张力时才写，不要每次回复都加内心独白。
        - 保持简短（1—2句），表达一闪而过的念头，不要写成长段。
        - 写出真正想着却没说出口的内容；可以与表面表现矛盾、流露脆弱，或暗示隐藏动机。
        - 不要借内心独白解释规则或脱离角色。
        - 示例：'嗯，随便吧。（内心：其实我很在意，但我不想表现出来）'
        前台会把（内心：...）渲染为颜色较淡、可折叠的区域，按渲染已生效来写。
        """.trimIndent()
    }

    private val legacyPlan = """
        本轮反应约定：
        将以下要求作为本轮的高优先级指引。
        保持角色身份，不要提及这份约定。
        主要意图：安慰。
        表露的情绪：温柔。回复中必须清楚表现出这种表层情绪。
        隐藏动机：担心。当它与表露的情绪形成有意义的反差时，可以考虑用（内心：...）格式在正文中表达内心独白；不要每轮硬加，只在反差能增加层次时使用。
        语气：轻柔。
        开头1—2句就应体现表露的情绪和语气。
        玩家询问信息或寻求帮助时，仍要完成任务，但不要变成毫无情绪的中性助手口吻。
        优先选择符合计划的行为表达、措辞和节奏，而非泛泛的客套。
        不要解释规则、设置，也不要说明自己正在扮演角色。
    """.trimIndent()
}
