package com.nekobot.app.data.local.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Agent 前台通知使用的中间回复状态。 */
object AgentIntermediateReplyBus {

    data class Reply(
        val sessionId: String,
        val content: String,
        val updatedAt: Long
    )

    data class State(val repliesBySession: Map<String, Reply> = emptyMap()) {
        val latestReply: Reply?
            get() = repliesBySession.values.maxByOrNull(Reply::updatedAt)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 新一轮开始时清掉同一会话上次运行留下的回复。 */
    fun beginRun(sessionId: String) {
        if (sessionId.isBlank()) return
        _state.update { current ->
            if (sessionId !in current.repliesBySession) current
            else current.copy(repliesBySession = current.repliesBySession - sessionId)
        }
    }

    /** 保存该会话最新一条中间回复的完整正文。 */
    fun onIntermediateContent(sessionId: String, content: String) {
        val text = content.trim()
        if (sessionId.isBlank() || text.isEmpty()) return
        val reply = Reply(sessionId, text, System.currentTimeMillis())
        _state.update { current ->
            current.copy(repliesBySession = current.repliesBySession + (sessionId to reply))
        }
    }

    /** 会话结束或停止后，不再把已结束运行的正文留在常驻通知里。 */
    fun finishRun(sessionId: String) {
        if (sessionId.isBlank()) return
        _state.update { current ->
            if (sessionId !in current.repliesBySession) current
            else current.copy(repliesBySession = current.repliesBySession - sessionId)
        }
    }

    fun clear() {
        _state.value = State()
    }
}
