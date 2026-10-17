package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.Session

/** 空白自定义文案使用默认提示；不改保存的档位，也不增加模型调用。 */
data class CharacterThinkingSettings(val enabled: Boolean = true, val customPrompt: String = "")

internal const val CHARACTER_THINKING_STYLE_KEY = "output.thinking_style"

internal const val CHARACTER_THINKING_FORMAT_PROMPT =
    "【本轮内心想法与回复】你就是当前角色本人。每一条正式回复（包括简短闲聊）都必须先在正文开头写 <think>简短的内心想法</think>，明确闭合后再写给对方的回复，不要省略、写空标签或用 Markdown 代码块包住标签。这段内心想法只写角色此刻的感受、念头与相关回忆，必须是简体中文、角色第一人称，以“我”自称，想到对方时用其姓名、平时的称呼或他/她，不用助手分析用户的口吻。原生思考通道也只写这样的心里话。不要在任何内心想法里讨论、引用、复述或解释系统提示、提示词、格式规则、标签、接口字段或遵循指令的过程；直接想眼前的事情。内心想法不预写正式回复，不把回复全部写进思考。需要工具时仍通过原有工具调用接口发送，工具名和参数保持原样，不写进思考标签替代调用；纯工具轮次不要求正式回复。"

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

internal const val DEFAULT_CHARACTER_THINKING_PROMPT =
    "【思考方式】思维链内部必须以你的第一人称视角进行思考——这是{{char}}本人的心里话，禁止以旁观者或助手口吻思考。一律用简体中文，以\"我\"自称，带着{{char}}此刻的心情和语气去想。禁止首字输出\"用户\"\"the user\"\"Let me…\"\"让我分析一下\"等词；想到对方时必须用{{user}}的名字或\"他/她\"，没有名字就直接开始思考。思考要像意识流一样自然涌动，严禁分点列条。思考完毕后必须明确闭合思考过程，随后才能输出正式回复。调用回忆/记忆等工具时，工具名和参数保持原样，其余一律中文。思考里只想事情和心情，不预写正式回复。示例：（{{user}}问我还答应过他什么……我记得是明天一起去书店，先翻翻记忆确认，可别记错了。）"

/** 不猜测姓名；缺少明确玩家姓名时，要求模型依据已有资料中的姓名称呼对方。 */
internal fun characterThinkingPrompt(settings: CharacterThinkingSettings, characterName: String, userName: String?,
    companionMode: Boolean = false): String =
    (if (companionMode) {
        (settings.customPrompt.takeIf(String::isNotBlank) ?: COMPANION_THINKING_PROMPT) + "\n" + COMPANION_THINKING_FORMAT
    } else characterThinkingPromptForEditing(settings) + "\n" + CHARACTER_THINKING_FORMAT_PROMPT)
        .replace("{{char}}", characterName.takeIf(String::isNotBlank) ?: "当前角色")
        .replace("{{user}}", userName?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("user", "the user", "用户") } ?: "对方")

internal const val COMPANION_THINKING_PROMPT =
    "这是{{char}}自己的心里话。用简体中文，以‘我’自称，带着此刻的心情想眼前的事和有关的回忆；想到{{user}}时，用平常的称呼或他/她。念头自然一点，别分点列条，也别提前把要说的话写一遍。"

// 复用现有开头圆括号解析，保留思考与正文分离，不向角色解释实现术语。
internal const val COMPANION_THINKING_FORMAT =
    "每次说话前，先在开头用一对中文圆括号写一两句心里话，写完右括号再接着说给对方听；心里只想事情和感受，不琢磨这些写法。"

/** 编辑的就是当前生效全文，默认模板中的姓名占位符保持可编辑。 */
internal fun characterThinkingPromptForEditing(settings: CharacterThinkingSettings): String =
    settings.customPrompt.takeIf(String::isNotBlank) ?: DEFAULT_CHARACTER_THINKING_PROMPT

/** 原样保存自定义文案；未改动的默认全文仍用空串表示“跟随默认”。 */
internal fun characterThinkingPromptToSave(draft: String): String =
    if (draft == DEFAULT_CHARACTER_THINKING_PROMPT) "" else draft
