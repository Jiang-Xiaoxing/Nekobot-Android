package com.nekobot.app.data.local.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 仅供界面显示“回忆一下”：记录哪些会话正在实际执行记忆查找。 */
object CompanionRecallActivity {
    private val running = MutableStateFlow<Map<String, Int>>(emptyMap())
    val active: StateFlow<Map<String, Int>> = running.asStateFlow()

    fun isActive(sessionId: String): Boolean = (running.value[sessionId] ?: 0) > 0

    suspend fun <T> track(sessionId: String, block: suspend () -> T): T {
        running.update { it + (sessionId to ((it[sessionId] ?: 0) + 1)) }
        try {
            return block()
        } finally {
            running.update {
                val left = (it[sessionId] ?: 1) - 1
                if (left <= 0) it - sessionId else it + (sessionId to left)
            }
        }
    }
}
