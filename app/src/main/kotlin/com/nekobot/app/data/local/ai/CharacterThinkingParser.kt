package com.nekobot.app.data.local.ai

/** 只在本轮模型返回值与工具循环之间传递，不写入 HTTP 请求或持久化消息。 */
internal const val NATIVE_CHARACTER_THINKING_CONTENT = "_native_character_thinking_content"
internal const val NATIVE_CHARACTER_THINKING_SIGNATURE = "_native_character_thinking_signature"

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

/**
 * 标签内心想法优先，否则保留原生思考。原生片段暂存到本次响应结束，避免先显示
 * 原生文本、后收到标签时重复或与最终保存不一致；每次主备切换清空暂存。
 */
internal class CharacterThinkingStream(
    private val enabled: Boolean,
    private val downstream: LocalAiStreamCallbacks
) {
    private val nativeThinking = StringBuilder()
    private val taggedPrefix = StringBuilder()
    private var hasTaggedThinking = false
    private var finished = false
    private var parser = newParser()
    private fun newParser() = CharacterThinkingParser(downstream.onContentChunk) { chunk ->
        if (hasTaggedThinking) downstream.onThinkingChunk(chunk) else {
            taggedPrefix.append(chunk)
            if (taggedPrefix.isNotBlank()) {
                hasTaggedThinking = true
                downstream.onThinkingChunk(taggedPrefix.toString())
                taggedPrefix.setLength(0)
            }
        }
    }

    val callbacks = LocalAiStreamCallbacks(
        onStart = {
            nativeThinking.setLength(0)
            taggedPrefix.setLength(0)
            hasTaggedThinking = false
            finished = false
            parser = newParser()
            downstream.onStart()
        },
        onContentChunk = { if (enabled) parser.append(it) else downstream.onContentChunk(it) },
        onThinkingChunk = { if (enabled) nativeThinking.append(it) else downstream.onThinkingChunk(it) },
        onStreamObserved = downstream.onStreamObserved
    )

    fun finish() {
        if (!enabled || finished) return
        finished = true
        parser.finish()
        if (!hasTaggedThinking && nativeThinking.isNotBlank()) {
            downstream.onThinkingChunk(nativeThinking.toString())
        }
        nativeThinking.setLength(0)
    }
}

internal fun characterThinkingResult(result: LocalAiResult, enabled: Boolean): LocalAiResult {
    if (!enabled) return result
    val content = StringBuilder()
    val thinking = StringBuilder()
    CharacterThinkingParser({ content.append(it) }, { thinking.append(it) }).apply {
        append(result.content)
        finish()
    }
    return result.copy(content = content.toString(),
        thinkingContent = thinking.toString().ifBlank { result.thinkingContent },
        thinkingSignature = if (thinking.isNotBlank()) "" else result.thinkingSignature)
}

/** 历史思考不回传；当前用户轮次的原生工具思考及签名必须保留。 */
internal fun characterThinkingMessages(messages: List<Map<String, Any>>, enabled: Boolean): List<Map<String, Any>> =
    if (!enabled) messages else messages.indexOfLast { it["role"] == "user" }.let { currentUser ->
        messages.mapIndexed { index, message ->
            if (message["role"] == "assistant" && (currentUser < 0 || index < currentUser)) {
                withoutHistoricalReasoning(message)
            } else message
        }
    }

internal fun PipelineContext.usesCharacterThinking(): Boolean =
    promptStack.get(CHARACTER_THINKING_STYLE_KEY)?.let { it.enabled && it.content.isNotBlank() } == true

internal fun hasCharacterThinking(message: Map<String, Any>): Boolean =
    listOf("reasoning_content", "thinking_content").any { (message[it] as? String)?.isNotBlank() == true }
