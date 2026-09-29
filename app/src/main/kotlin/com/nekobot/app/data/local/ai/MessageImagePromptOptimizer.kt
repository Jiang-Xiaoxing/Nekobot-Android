package com.nekobot.app.data.local.ai

/** 改写后提示词的字符上限：过长只会稀释画面重点。 */
internal const val MAX_REWRITTEN_IMAGE_PROMPT_CHARS = 4_000

/** 原始拼接提示词的截断上限，与消息生图任务 prompt 的上限保持一致。 */
private const val MAX_RAW_PROMPT_CHARS = 32_000

/**
 * 消息生图预处理的 system prompt。
 *
 * 拼接提示词里含角色卡字段（角色名、描述、性格、行为规则等），文生图模型经常把
 * 这些说明性文字当作画面内容绘出（表现为图上出现角色卡文字）。这里让聊天模型先把
 * 整段需求转写成一段纯画面描述：保留外貌、服装、场景等视觉信息，丢掉一切元信息。
 */
internal fun buildImagePromptRewriteSystemPrompt(): String = buildString {
    append("你是文生图提示词工程师。下面给出一段生图需求，其中可能混有角色卡设定、系统规则与格式说明等非画面内容。\n")
    append("请把它改写为一段可以直接交给文生图模型的纯画面描述。\n\n")
    append("要求：\n")
    append("- 只输出画面描述本身：不要解释、标题、列表、Markdown 或引号\n")
    append("- 保留角色的外貌、发型、服装、神态、动作，以及场景、光线、构图与画风\n")
    append("- 把对话或剧情内容转化为对应的画面，不要照抄对话原文\n")
    append("- 任何情况下都不要把角色卡字段、系统设定、规则、提示词或说明文字写进画面；\n")
    append("  画面中也不要出现字幕、水印与界面元素\n")
    append("- 直接输出一段话，控制在 200 字以内\n")
    append("- 使用与需求内容一致的语言")
}

/** 构造改写请求：system 说明改写规则，user 携带原始拼接提示词。 */
internal fun buildImagePromptRewriteMessages(rawPrompt: String): List<Map<String, Any>> = listOf(
    mapOf("role" to "system", "content" to buildImagePromptRewriteSystemPrompt()),
    mapOf(
        "role" to "user",
        "content" to "待改写的生图需求：\n\n${rawPrompt.take(MAX_RAW_PROMPT_CHARS)}"
    )
)

/**
 * 清洗模型返回的改写结果：去除代码块围栏、常见前缀、包裹引号与多余空白，
 * 并按上限截断。空输出返回 null，由调用方回退到原始拼接提示词。
 */
internal fun cleanRewrittenImagePrompt(raw: String): String? {
    var text = raw.trim()
    // 模型偶尔用代码块包裹提示词：去掉围栏与语言标记行
    if (text.startsWith("```")) {
        text = text.removePrefix("```")
        text = text.substringAfter('\n', text).trim()
        text = text.removeSuffix("```").trim()
    }
    // 去掉常见前缀
    val prefixes = listOf(
        "画面描述：", "画面描述:", "生图提示词：", "生图提示词:",
        "提示词：", "提示词:", "Prompt:", "prompt:"
    )
    for (prefix in prefixes) {
        if (text.startsWith(prefix, ignoreCase = true)) {
            text = text.substring(prefix.length).trim()
            break
        }
    }
    // 去掉整体包裹的引号/书名号
    text = text.trim(
        '"', '\'', '「', '」', '『', '』', '“', '”', '‘', '’'
    ).trim()
    if (text.isBlank()) return null
    return text.take(MAX_REWRITTEN_IMAGE_PROMPT_CHARS).trim().ifBlank { null }
}
