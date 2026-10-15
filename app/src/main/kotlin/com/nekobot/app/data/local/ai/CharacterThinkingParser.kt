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
 * 先到的有效思考通道立即显示并保持到本次请求结束；后到通道不重复显示。
 * 正常文本不等待结束，也不在标签到达时撤回已显示的原生文本；主备切换重置状态。
 */
internal class CharacterThinkingStream(
    private val enabled: Boolean,
    private val downstream: LocalAiStreamCallbacks
) {
    private enum class Source { NATIVE, TAGGED }
    private var source: Source? = null
    private val displayedThinking = StringBuilder()
    private val nativePrefix = StringBuilder()
    private val taggedPrefix = StringBuilder()
    private var received = false
    private var finished = false
    private var nativeFilter = newFilter(Source.NATIVE)
    private var taggedFilter = newFilter(Source.TAGGED)
    private var parser = newParser()

    private fun newFilter(candidate: Source) = CharacterThinkingEchoFilter { chunk ->
        val prefix = if (candidate == Source.NATIVE) nativePrefix else taggedPrefix
        if (source == null) {
            prefix.append(chunk)
            // 被过滤的复述可能只剩引号或括号，不能让它抢占后续正常心里话。
            if (prefix.any(Char::isLetterOrDigit)) {
                source = candidate
                relayThinking(prefix.toString())
                prefix.setLength(0)
            }
        } else if (source == candidate) relayThinking(chunk)
    }

    private fun relayThinking(chunk: String) {
        displayedThinking.append(chunk)
        downstream.onThinkingChunk(chunk)
    }

    private fun newParser() = CharacterThinkingParser(downstream.onContentChunk) { chunk ->
        taggedFilter.append(chunk)
    }

    /** 按本次实际到达顺序落库，避免最后换成另一个通道、与流式显示不一致。 */
    fun resolve(result: LocalAiResult): LocalAiResult {
        if (!enabled) return result
        if (!received) return characterThinkingResult(result, true)
        finish()
        val content = StringBuilder()
        CharacterThinkingParser({ content.append(it) }, {}).apply { append(result.content); finish() }
        return result.copy(content = content.toString(), thinkingContent = displayedThinking.toString(),
            thinkingSignature = if (source == Source.NATIVE && displayedThinking.toString() == result.thinkingContent) {
                result.thinkingSignature
            } else "")
    }

    val callbacks = LocalAiStreamCallbacks(
        onStart = {
            source = null
            displayedThinking.setLength(0)
            nativePrefix.setLength(0)
            taggedPrefix.setLength(0)
            received = false
            finished = false
            nativeFilter = newFilter(Source.NATIVE)
            taggedFilter = newFilter(Source.TAGGED)
            parser = newParser()
            downstream.onStart()
        },
        onContentChunk = {
            received = true
            if (enabled) parser.append(it) else downstream.onContentChunk(it)
        },
        onThinkingChunk = {
            received = true
            if (enabled) nativeFilter.append(it) else downstream.onThinkingChunk(it)
        },
        onStreamObserved = downstream.onStreamObserved
    )

    fun finish() {
        if (!enabled || finished) return
        finished = true
        parser.finish()
        nativeFilter.finish()
        taggedFilter.finish()
    }
}

internal fun characterThinkingResult(result: LocalAiResult, enabled: Boolean): LocalAiResult {
    if (!enabled) return result
    // 非流式按协议顺序先读原生思考，再读正文标签，不猜测服务器的生成顺序。
    val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks())
    stream.callbacks.onThinkingChunk(result.thinkingContent)
    stream.callbacks.onContentChunk(result.content)
    return stream.resolve(result)
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

/** 普通角色有工具时也会走 ModelCall，必须接上与 Agent 相同的实时通道。 */
internal fun shouldRelayModelCall(sessionMode: String?, characterThinking: Boolean): Boolean =
    sessionMode.equals("agent", ignoreCase = true) || characterThinking

internal fun hasCharacterThinking(message: Map<String, Any>): Boolean =
    listOf("reasoning_content", "thinking_content").any { (message[it] as? String)?.isNotBlank() == true }
