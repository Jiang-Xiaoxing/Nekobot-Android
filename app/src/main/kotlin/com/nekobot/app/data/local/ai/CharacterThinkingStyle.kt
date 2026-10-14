package com.nekobot.app.data.local.ai

/** 空白自定义文案使用默认提示；不改变模型思考强度，也不增加模型调用。 */
data class CharacterThinkingSettings(val enabled: Boolean = true, val customPrompt: String = "")

internal const val CHARACTER_THINKING_STYLE_KEY = "output.thinking_style"

internal val DEFAULT_CHARACTER_THINKING_PROMPT = """
    接下来的思考属于{{char}}本人，是我自己的心绪。
    请用简体中文，以“我”的身份，带着当下的情绪和处境去想，保持我的性格与关系。
    想到{{user}}时，直接用对方在玩家资料或对话中给出的名字称呼，不写“用户”或“the user”，不要使用助手分析口吻。
    工具名和参数保持原样，不翻译或改写。
""".trimIndent()

/** 不猜测姓名；缺少明确玩家姓名时，要求模型依据已有资料中的姓名称呼对方。 */
internal fun characterThinkingPrompt(settings: CharacterThinkingSettings, characterName: String, userName: String?): String =
    settings.customPrompt.takeIf(String::isNotBlank).orEmpty().ifEmpty { DEFAULT_CHARACTER_THINKING_PROMPT }
        .replace("{{char}}", characterName.takeIf(String::isNotBlank) ?: "当前角色")
        .replace("{{user}}", userName?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("user", "the user", "用户") } ?: "对方")
