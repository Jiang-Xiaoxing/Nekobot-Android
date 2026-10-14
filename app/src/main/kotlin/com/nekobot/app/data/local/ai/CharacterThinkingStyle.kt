package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.Session

/** 空白自定义文案使用默认提示；不改保存的档位，也不增加模型调用。 */
data class CharacterThinkingSettings(val enabled: Boolean = true, val customPrompt: String = "")

internal const val CHARACTER_THINKING_STYLE_KEY = "output.thinking_style"

internal const val CHARACTER_THINKING_FORMAT_PROMPT =
    "【思考输出格式】每次回复或调用工具前，在回复正文开头用 <think> 和 </think> 包住简体中文第一人称的内心想法。思考结束必须输出 </think>，正式回复只写在标签外；不要用 Markdown 代码块包住标签。工具仍通过原有工具调用接口发送，工具名和参数不写进思考标签替代调用。"

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
internal fun characterThinkingPrompt(settings: CharacterThinkingSettings, characterName: String, userName: String?): String =
    (characterThinkingPromptForEditing(settings) + "\n" + CHARACTER_THINKING_FORMAT_PROMPT)
        .replace("{{char}}", characterName.takeIf(String::isNotBlank) ?: "当前角色")
        .replace("{{user}}", userName?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("user", "the user", "用户") } ?: "对方")

/** 编辑的就是当前生效全文，默认模板中的姓名占位符保持可编辑。 */
internal fun characterThinkingPromptForEditing(settings: CharacterThinkingSettings): String =
    settings.customPrompt.takeIf(String::isNotBlank) ?: DEFAULT_CHARACTER_THINKING_PROMPT

/** 原样保存自定义文案；未改动的默认全文仍用空串表示“跟随默认”。 */
internal fun characterThinkingPromptToSave(draft: String): String =
    if (draft == DEFAULT_CHARACTER_THINKING_PROMPT) "" else draft
