package com.nekobot.app.data.local.ai

/**
 * 拦截已知的指令复述。正常心里话逐片转发，只保留可能跨片的关键词尾缀；
 * 命中后丢弃该句剩余内容，不把整轮原生思考攒到结束。不是通用语义审核器。
 */
internal class CharacterThinkingEchoFilter(private val emit: (String) -> Unit) {
    private var pending = ""
    private var suppressing = false

    fun append(chunk: String) {
        pending += chunk
        while (pending.isNotEmpty()) {
            if (suppressing) {
                val end = pending.indexOfFirst { it in SENTENCE_END }
                if (end < 0) { pending = ""; return }
                pending = pending.substring(end + 1)
                suppressing = false
                continue
            }
            val lower = pending.lowercase()
            val match = MARKERS.mapNotNull { marker ->
                lower.indexOf(marker).takeIf { it >= 0 }?.let { it to marker.length }
            }.minByOrNull { it.first }
            if (match != null) {
                if (match.first > 0) emit(pending.take(match.first))
                pending = pending.substring(match.first + match.second)
                suppressing = true
                continue
            }
            val held = (1..minOf(lower.length, MAX_MARKER_LENGTH)).lastOrNull { size ->
                MARKERS.any { it.startsWith(lower.takeLast(size)) }
            } ?: 0
            val ready = pending.length - held
            if (ready > 0) emit(pending.take(ready))
            pending = pending.takeLast(held)
            return
        }
    }

    fun finish() {
        // “我”“在”等也是正常句尾，只有完整命中的复述才丢弃，不能误吞普通文字。
        if (!suppressing && pending.isNotEmpty()) emit(pending)
        pending = ""
    }

    private companion object {
        const val SENTENCE_END = "。！？!?；;\n\r"
        val MARKERS = listOf(
            "按thinking_style", "按 thinking_style", "thinking_style", "reasoning_content", "thinking_content",
            "system prompt", "system提示", "system 提示", "系统提示", "底层控制提示", "提示词",
            "接口没有原生思考字段", "原生思考字段", "原生推理字段", "思考标签", "think标签", "think 标签",
            "我应该输出", "在正文开头用", "不要在正文重复思考"
        )
        val MAX_MARKER_LENGTH = MARKERS.maxOf(String::length)
    }
}
