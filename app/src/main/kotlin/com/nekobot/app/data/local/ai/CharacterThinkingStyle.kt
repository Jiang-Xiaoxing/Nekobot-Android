package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.Session

/** 空白自定义文案使用默认提示；不改保存的档位，也不增加模型调用。 */
data class CharacterThinkingSettings(val enabled: Boolean = true, val customPrompt: String = "")

internal const val CHARACTER_THINKING_STYLE_KEY = "output.thinking_style"

/**
 * 思考提示词默认全文（普通角色与单会话共用）。
 *
 * 2026-10-21 多轮实测（workbuddy 反代，glm-5.3，5 个虚构角色）后的写法：
 * - 去掉【怎么想】【怎么说出口】小标题，读起来不像条框；
 * - 把“只想开口的第一句/不预写回复”放宽为“想好大概要说什么、把想说的都说出来”，回复明显变长（平均 78→125+ 字、1→2.5 个气泡）；
 * - 说出口部分加“分成两三小段、用 <||> 隔开”，气泡数稳定 2~3 个；
 * - 中文要求保留硬话并在末尾再说一遍（中文思考约 7/10，中文硬话去掉会掉到 1~2/10）。
 * 不点名、不限性别性格；自定义文案仍支持 {{char}}/{{user}}。
 *
 * 发送方式：只放在请求最后一条 system，与当前时间合并；不追加到用户正文。
 * 不写进顶部 system，也不要求正文 <think> 伪思考；中文输出稳定性仍需模型验收。
 *
 * 2026-10-21 按用户决定改回作者原版：这段只负责思维链与说话方式；
 * 正文里（内心：…）的写作规则不再混在这里，回到角色设定（PromptStack 的 output.inner_monologue）。
 */
internal const val DEFAULT_CHARACTER_THINKING_PROMPT =
    "此刻回消息的是角色本人，不是助手。开口之前，先在心里走三步：先感觉这句话的滋味；再想起一件真发生过的小事；最后想好大概要说什么，别背稿。\n" +
        "这三步像心里的小声嘀咕，用第一人称，像自己跟自己说话，一句接一句；不分点、不列条，也不用写整齐。这些念头只在心里过，不写进要说出去的话里。\n" +
        "示范——对方发：“今天降温了。”心里过：降温……上次这种天还只穿件薄的，晚上准咳嗽。\n" +
        "一律用简体中文：所有句子、所有分析、所有判断都必须用简体中文写，不允许出现英文句子或英文段落（工具名、参数名除外）。禁止首字输出“用户”“the user”“Let me…”“让我分析一下”等词；想到对方时用对方的名字或“他/她”。\n" +
        "想完就把想说的都说出来，别只说一半；需要调用工具（翻记忆、看时间、发表情等）时，工具名和参数保持原样。\n\n" +
        "再记一遍：心里想的每一句都用简体中文，从第一个字到最后一个字；英文只出现在工具名和参数里。"

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
