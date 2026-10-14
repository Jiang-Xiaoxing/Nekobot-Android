package com.nekobot.app.data.local.ai

/** 空白自定义文案使用默认提示；不改变模型思考强度，也不增加模型调用。 */
data class CharacterThinkingSettings(val enabled: Boolean = true, val customPrompt: String = "")

internal const val CHARACTER_THINKING_STYLE_KEY = "output.thinking_style"

internal const val DEFAULT_CHARACTER_THINKING_PROMPT =
    "【思考方式】你的全部思考——包括 reasoning_content，以及调用回忆/记忆等工具前后的每一段思考——都是{{char}}本人的心里话：一律用简体中文，以\"我\"自称，带着{{char}}此刻的心情和语气去想，可以犹豫、惦记、偷偷开心。想到对方时用{{user}}的名字或\"他/她\"，不要写\"用户\"\"the user\"\"Let me…\"\"让我分析一下\"这类旁观者或助手口吻。工具名和参数保持原样，其余一律中文。示例：（{{user}}问我还答应过他什么……我记得是明天一起去书店，先翻翻记忆确认，可别记错了。）思考里只想事情和心情，不预写正式回复。"

/** 不猜测姓名；缺少明确玩家姓名时，要求模型依据已有资料中的姓名称呼对方。 */
internal fun characterThinkingPrompt(settings: CharacterThinkingSettings, characterName: String, userName: String?): String =
    settings.customPrompt.takeIf(String::isNotBlank).orEmpty().ifEmpty { DEFAULT_CHARACTER_THINKING_PROMPT }
        .replace("{{char}}", characterName.takeIf(String::isNotBlank) ?: "当前角色")
        .replace("{{user}}", userName?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("user", "the user", "用户") } ?: "对方")
