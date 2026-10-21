package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.Session

/** 空白自定义文案使用默认提示；不改保存的档位，也不增加模型调用。 */
data class CharacterThinkingSettings(val enabled: Boolean = true, val customPrompt: String = "")

internal const val CHARACTER_THINKING_STYLE_KEY = "output.thinking_style"

/**
 * 思考提示词默认全文（普通角色与单会话共用）。
 *
 * 拆成两段：先教“怎么想”（三步 + 示范 + 强中文约束），再单独说“说出口的话怎么写”（（内心：…）格式）。
 * 实测（2026-10-21，workbuddy 反代，glm-5.3 / kimi-k3-1 / deepseek-v4-pro）拆开后中文更稳、光括号更少，
 * 顺序“先想后格式”优于“先格式后想”。不写死角色名、性别、性格；自定义文案仍支持 {{char}}/{{user}}。
 *
 * 发送方式：整段只放在请求最末尾——最后一条用户消息末尾一次 + 请求最后一条 system 一次。
 * 不写进顶部 system，也不再要求正文 <think> 伪思考；原生思考由这一段保证中文并直接显示。
 */
internal const val DEFAULT_CHARACTER_THINKING_PROMPT =
    "【怎么想】\n" +
        "此刻回消息的是角色本人，不是助手。开口之前，先在心里走三步：先感觉这句话的滋味；再想起一件真发生过的小事；最后只想开口的第一句，别在脑子里把整段话写完。\n" +
        "这三步像心里的小声嘀咕，用第一人称，像自己跟自己说话，一句接一句；不分点、不列条，也不用写整齐。这些念头只在心里过，不写进要说出去的话里。\n" +
        "示范——对方发：“今天降温了。”她心里过：降温……上次这种天还只穿件薄的，晚上准咳嗽。\n" +
        "一律用简体中文：所有句子、所有分析、所有判断都必须用简体中文写，不允许出现英文句子或英文段落（工具名、参数名除外）。禁止首字输出“用户”“the user”“Let me…”“让我分析一下”等词；想到对方时用对方的名字或“他/她”。\n" +
        "思考要像意识流一样自然涌动，不预写正式回复，想完直接输出正式回复；需要调用工具（翻记忆、看时间、发表情等）时，工具名和参数保持原样。\n\n" +
        "【怎么说出口】\n" +
        "说出去的话就像平常聊天，不用把心里想的全倒出来。偶尔有半截话不想说出口，就写成（内心：…）附在那句话后面，例如：\n" +
        "“你先去忙吧（内心：其实想再聊一会儿，又怕耽误你）。”\n" +
        "不要用光括号写独白，也不要把（内心：…）写进思考里。"

/** 角色思考的会话资格；思考强度和偏好开关只控制注入，不隐藏设置入口。 */
internal fun isCharacterThinkingSession(
    hasCharacter: Boolean,
    sessionMode: String?,
    inheritsCharacter: Boolean,
    fusedCompanion: Boolean
): Boolean = hasCharacter &&
    (!sessionMode.equals("agent", ignoreCase = true) || inheritsCharacter || fusedCompanion)

/** 群聊逐个参与角色准备上下文，其他会话使用绑定角色。 */
internal fun isCharacterThinkingSession(session: Session?): Boolean = isCharacterThinkingSession(
    hasCharacter = if (session?.sessionMode.equals("group", ignoreCase = true)) {
        session?.characterIds.orEmpty().any { it.isNotBlank() }
    } else {
        !session?.characterId.isNullOrBlank()
    },
    sessionMode = session?.sessionMode,
    inheritsCharacter = session?.inheritCharacter == true,
    fusedCompanion = session?.companionMode == true
)

/**
 * 本次请求实际发送的思考提示词：自定义优先，留空用默认；替换姓名占位符。
 * 普通角色与单会话共用同一份，不再按模式拼接不同格式段。
 */
internal fun characterThinkingPrompt(settings: CharacterThinkingSettings, characterName: String, userName: String?,
    @Suppress("UNUSED_PARAMETER") companionMode: Boolean = false): String =
    characterThinkingPromptForEditing(settings)
        .replace("{{char}}", characterName.takeIf(String::isNotBlank) ?: "当前角色")
        .replace("{{user}}", userName?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("user", "the user", "用户") } ?: "对方")

/** 编辑的就是当前生效全文，默认模板中的姓名占位符保持可编辑。 */
internal fun characterThinkingPromptForEditing(settings: CharacterThinkingSettings): String =
    settings.customPrompt.takeIf(String::isNotBlank) ?: DEFAULT_CHARACTER_THINKING_PROMPT

/** 原样保存自定义文案；未改动的默认全文仍用空串表示“跟随默认”。 */
internal fun characterThinkingPromptToSave(draft: String): String =
    if (draft == DEFAULT_CHARACTER_THINKING_PROMPT) "" else draft
