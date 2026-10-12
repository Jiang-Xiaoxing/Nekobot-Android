package com.nekobot.app.data.local.ai

/**
 * 伴侣会话一次回复可能经历多轮工具调用（如回忆）。每轮模型请求都会触发“开始流式”，
 * 若每轮都转发给界面，会清空思考预览并重建占位消息，造成思考区闪烁。
 * 这里保证伴侣会话每个回复回合只转发一次开始事件；非伴侣会话保持原行为。
 */
internal object CompanionStreamTurnGate {
    const val METADATA_KEY = "companion_stream_started"

    fun shouldEmitStart(companionMode: Boolean, metadata: MutableMap<String, Any>): Boolean {
        if (!companionMode) return true
        if (metadata[METADATA_KEY] == true) return false
        metadata[METADATA_KEY] = true
        return true
    }
}
