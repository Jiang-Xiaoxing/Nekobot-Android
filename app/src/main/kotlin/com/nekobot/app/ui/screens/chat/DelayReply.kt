package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.ReasoningEffort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val DEFAULT_DELAY_REPLY_SECONDS = 2
internal const val MIN_DELAY_REPLY_SECONDS = 1
internal const val MAX_DELAY_REPLY_SECONDS = 30

internal fun normalizeDelayReplySeconds(value: Int?): Int =
    (value ?: DEFAULT_DELAY_REPLY_SECONDS).coerceIn(
        MIN_DELAY_REPLY_SECONDS,
        MAX_DELAY_REPLY_SECONDS
    )

internal fun shouldUseDelayReply(
    sessionEnabled: Boolean,
    allowDelay: Boolean,
    isLocalMode: Boolean,
    sessionMode: String?,
    inheritCharacter: Boolean?,
    isSlashCommand: Boolean
): Boolean =
    sessionEnabled &&
        allowDelay &&
        isLocalMode &&
        (
            sessionMode.equals("character", ignoreCase = true) ||
                (
                    sessionMode.equals("agent", ignoreCase = true) &&
                        inheritCharacter == true
                    )
            ) &&
        !isSlashCommand

/** 延迟窗口内的一条用户消息；气泡仍逐条显示，批次只控制何时触发一次回复。 */
data class DelayedReplyMessage(
    val bubbleId: String,
    val content: String,
    val attachments: List<Map<String, Any>> = emptyList(),
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val timestamp: String
)

/** 状态变化后需要安装的新计时任务；[delayMs] 为 null 表示当前处于暂停态。 */
data class DelayReplyPlan(
    val generation: Long,
    val delayMs: Long?
)

sealed interface DelayReplyTimerResult {
    data object Stale : DelayReplyTimerResult
    data class Paused(val plan: DelayReplyPlan) : DelayReplyTimerResult
    data class Reschedule(val plan: DelayReplyPlan) : DelayReplyTimerResult
    data class Ready(val messages: List<DelayedReplyMessage>) : DelayReplyTimerResult
}

/**
 * 单会话延迟回复状态机。
 *
 * 时间由调用方传入单调时钟值，便于测试，也避免系统校时改变剩余时间。
 * 所有复合状态转换都同步执行；generation 让已取消但恰好到期的旧 Job 无法消费新批次。
 */
class DelayReplyStateMachine {
    private val _pendingState = MutableStateFlow(false)
    val pendingState: StateFlow<Boolean> = _pendingState
    private val pending = mutableListOf<DelayedReplyMessage>()
    private var generation = 0L
    private var inputActive = false
    private var paused = false
    private var remainingMs = 0L
    private var deadlineMs = 0L

    @Synchronized
    fun arm(
        message: DelayedReplyMessage,
        nowMs: Long,
        delayMs: Long
    ): DelayReplyPlan {
        pending += message
        _pendingState.value = true
        generation += 1
        remainingMs = delayMs.coerceAtLeast(1L)
        paused = inputActive
        deadlineMs = if (paused) 0L else nowMs + remainingMs
        return DelayReplyPlan(generation, remainingMs.takeUnless { paused })
    }

    @Synchronized
    fun setInputActive(active: Boolean, nowMs: Long): DelayReplyPlan? {
        if (inputActive == active) return null
        inputActive = active
        if (pending.isEmpty()) return null

        generation += 1
        if (active) {
            if (!paused) {
                remainingMs = (deadlineMs - nowMs).coerceAtLeast(1L)
                paused = true
                deadlineMs = 0L
            }
            return DelayReplyPlan(generation, null)
        }

        if (!paused) return null
        paused = false
        deadlineMs = nowMs + remainingMs.coerceAtLeast(1L)
        return DelayReplyPlan(generation, remainingMs.coerceAtLeast(1L))
    }

    @Synchronized
    fun onTimerFired(expectedGeneration: Long, nowMs: Long): DelayReplyTimerResult {
        if (expectedGeneration != generation || pending.isEmpty() || paused) {
            return DelayReplyTimerResult.Stale
        }
        if (inputActive) {
            generation += 1
            paused = true
            remainingMs = (deadlineMs - nowMs).coerceAtLeast(1L)
            deadlineMs = 0L
            return DelayReplyTimerResult.Paused(DelayReplyPlan(generation, null))
        }
        if (nowMs < deadlineMs) {
            generation += 1
            remainingMs = deadlineMs - nowMs
            return DelayReplyTimerResult.Reschedule(DelayReplyPlan(generation, remainingMs))
        }

        val ready = pending.toList()
        clearLocked()
        return DelayReplyTimerResult.Ready(ready)
    }

    @Synchronized
    fun cancel(): List<DelayedReplyMessage> {
        val cancelled = pending.toList()
        generation += 1
        clearLocked(resetGeneration = false)
        return cancelled
    }

    @Synchronized
    fun flush(): List<DelayedReplyMessage> {
        val ready = pending.toList()
        generation += 1
        clearLocked(resetGeneration = false)
        return ready
    }

    @Synchronized
    fun pendingMessages(): List<DelayedReplyMessage> = pending.toList()

    @Synchronized
    fun currentPlan(nowMs: Long): DelayReplyPlan? {
        if (pending.isEmpty()) return null
        return DelayReplyPlan(
            generation = generation,
            delayMs = if (paused) null else (deadlineMs - nowMs).coerceAtLeast(1L)
        )
    }

    @Synchronized
    fun hasPending(): Boolean = pending.isNotEmpty()

    @Synchronized
    fun remainingMs(nowMs: Long): Long = when {
        pending.isEmpty() -> 0L
        paused -> remainingMs
        else -> (deadlineMs - nowMs).coerceAtLeast(0L)
    }

    @Synchronized
    fun isPaused(): Boolean = pending.isNotEmpty() && paused

    private fun clearLocked(resetGeneration: Boolean = true) {
        pending.clear()
        _pendingState.value = false
        paused = false
        remainingMs = 0L
        deadlineMs = 0L
        if (resetGeneration) generation += 1
    }
}

data class DelayReplyRequest(
    val messageContent: String,
    val precedingMessages: List<String>,
    val attachments: List<Map<String, Any>>,
    val reasoningEffort: ReasoningEffort
)

internal fun buildDelayReplyRequest(messages: List<DelayedReplyMessage>): DelayReplyRequest? {
    val last = messages.lastOrNull() ?: return null
    return DelayReplyRequest(
        messageContent = last.content,
        precedingMessages = messages.dropLast(1).map { it.content },
        attachments = messages.flatMap { it.attachments },
        reasoningEffort = last.reasoningEffort
    )
}
