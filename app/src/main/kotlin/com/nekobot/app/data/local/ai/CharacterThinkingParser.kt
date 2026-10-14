package com.nekobot.app.data.local.ai

/** 每次模型请求独立解析；只缓冲可能跨 chunk 的标签，不把思考片段先发给正文再撤回。 */
internal class CharacterThinkingParser(
    private val onContent: (String) -> Unit,
    private val onThinking: (String) -> Unit
) {
    private var pending = ""
    private var thinking = false
    private var finished = false
    private var skipTagNewlines = false

    fun append(chunk: String) {
        check(!finished)
        pending += chunk
        while (pending.isNotEmpty()) {
            val tagStart = pending.indexOf('<')
            if (tagStart < 0) {
                emit(pending)
                pending = ""
                break
            }
            if (tagStart > 0) {
                emit(pending.substring(0, tagStart))
                pending = pending.substring(tagStart)
            }
            val lower = pending.take(TAGS.maxOf(String::length)).lowercase()
            val tag = TAGS.firstOrNull { lower.startsWith(it) }
            when {
                tag != null -> {
                    thinking = !tag.startsWith("</")
                    skipTagNewlines = true
                    pending = pending.substring(tag.length)
                }
                TAGS.any { it.startsWith(lower) } -> break
                else -> {
                    emit(pending.take(1))
                    pending = pending.drop(1)
                }
            }
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        // 未完整输出的 <thi / </thi 等格式碎片不进入任何气泡。
        pending = ""
    }

    private fun emit(text: String) {
        // 标签后的 CR/LF 可跨分片；不把预填的换行算成一段思考，也保留正文内部空白。
        val value = if (skipTagNewlines) text.trimStart('\r', '\n') else text
        if (value.isNotEmpty()) {
            skipTagNewlines = false
            (if (thinking) onThinking else onContent)(value)
        }
    }

    private companion object {
        val TAGS = listOf("<think>", "</think>", "<thinking>", "</thinking>")
    }
}

/** 原生思考只在旧模式转发；伪思考始终来自正文标签，不能带原生签名回传。 */
internal class CharacterThinkingStream(
    private val enabled: Boolean,
    private val downstream: LocalAiStreamCallbacks
) {
    private var parser = newParser()
    private fun newParser() = CharacterThinkingParser(downstream.onContentChunk, downstream.onThinkingChunk)

    val callbacks = LocalAiStreamCallbacks(
        onStart = {
            parser = newParser()
            downstream.onStart()
        },
        onContentChunk = { if (enabled) parser.append(it) else downstream.onContentChunk(it) },
        onThinkingChunk = { if (!enabled) downstream.onThinkingChunk(it) },
        onStreamObserved = downstream.onStreamObserved
    )

    fun finish() { if (enabled) parser.finish() }
}

internal fun characterThinkingResult(result: LocalAiResult, enabled: Boolean): LocalAiResult {
    if (!enabled) return result
    val content = StringBuilder()
    val thinking = StringBuilder()
    CharacterThinkingParser({ content.append(it) }, { thinking.append(it) }).apply {
        append(result.content)
        finish()
    }
    return result.copy(content = content.toString(), thinkingContent = thinking.toString(), thinkingSignature = "")
}

/** 伪思考在 App 的思考字段留档，发往模型时不冒充需要签名的原生思考块。 */
internal fun characterThinkingMessages(messages: List<Map<String, Any>>, enabled: Boolean): List<Map<String, Any>> =
    if (!enabled) messages else messages.map {
        if (it["role"] == "assistant") withoutHistoricalReasoning(it) else it
    }

internal fun PipelineContext.usesFakeCharacterThinking(): Boolean =
    promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.let { it.enabled && it.content.isNotBlank() } == true

internal fun hasFakeCharacterThinking(message: Map<String, Any>): Boolean =
    listOf("reasoning_content", "thinking_content").any { (message[it] as? String)?.isNotBlank() == true }
