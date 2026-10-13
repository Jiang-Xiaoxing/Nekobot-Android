package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionRelationshipEntity
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import java.net.SocketTimeoutException

/** notice 同时保留失败原因和中断标记；重试次数不能随页面重建而重置。 */
internal object CompanionRelationshipRecovery {
    const val TIMEOUT_SECONDS = 300
    const val IN_FLIGHT = "上次评估未完成，可手动重试；已有状态保留。"
    const val RETRY_IN_FLIGHT = "上次自动重试被打断了，已有状态保留。下次聊天会再尝试，也可手动重试。"
    const val TIMEOUT = "评估模型回复太慢，已有状态保留。稍后会自动缩小范围重试一次。"
    const val TIMEOUT_EXHAUSTED = "评估模型重试后仍然太慢，已有状态保留。下次聊天会再尝试，也可检查评估模型后手动重试。"

    fun interrupted(notice: String?) = notice?.startsWith("上次评估未完成") == true || notice == RETRY_IN_FLIGHT
    fun timedOut(notice: String?) = notice == TIMEOUT || notice?.startsWith("状态评估超时") == true
    fun clearInterruptedNotice(state: CompanionRelationshipEntity): CompanionRelationshipEntity =
        if (interrupted(state.notice)) state.copy(notice = null) else state
    fun retryable(notice: String?) = interrupted(notice) || notice == TIMEOUT ||
        notice?.startsWith("状态评估超时") == true
    private fun exhausted(notice: String?) = notice == TIMEOUT_EXHAUSTED || notice == RETRY_IN_FLIGHT

    fun automaticReady(state: CompanionRelationshipEntity, pending: Int, interrupted: Boolean = false,
        afterEvaluation: Boolean = false): Boolean = state.enabled && state.automatic &&
        !state.awaitingHistory && !state.historyReviewRequired && pending > 0 &&
        (interrupted || retryable(state.notice) || pending >= state.interval &&
            (state.notice == null || !afterEvaluation && exhausted(state.notice)))

    /** 占位标记不是给玩家的失败提示；版本变化代表另一条失败，即使原因文字相同。 */
    fun noticeToken(state: CompanionRelationshipEntity, busy: Boolean): String? =
        state.notice?.takeIf { state.enabled && !busy && !interrupted(it) }?.let {
            "${state.version}:${CompanionMemoryPolicy.digest(it)}"
        }

    fun readableNotice(notice: String): String = if (notice.startsWith("状态评估超时"))
        "评估模型回复太慢，已有状态保留。会自动重试一次，可在关系状态页查看结果。" else notice

    fun noticeToShow(state: CompanionRelationshipEntity, busy: Boolean, lastToken: String?): Pair<String, String>? {
        val token = noticeToken(state, busy)?.takeUnless { it == lastToken } ?: return null
        return token to readableNotice(requireNotNull(state.notice))
    }
}

/** 只重试一次超时，普通取消和格式/配置错误不能被当成超时循环重试。 */
internal suspend fun runRelationshipEvaluation(recovering: Boolean,
    beforeRetry: suspend () -> Unit = { delay(3_000L) }, evaluate: suspend (Boolean) -> Unit) {
    try {
        evaluate(recovering)
    } catch (timeout: Exception) {
        if (recovering || timeout !is TimeoutCancellationException && timeout !is SocketTimeoutException) throw timeout
        beforeRetry()
        evaluate(true)
    }
}

/** 平时一次评完；只有实际超时后的恢复尝试才逐轮处理，不改变每 N 轮触发设置。 */
internal class RelationshipBatchSizer {
    fun select(pending: List<RelationshipTurn>, maxChars: Int, retrying: Boolean): List<RelationshipTurn> {
        if (!retrying) return pending
        return pending.take(1).takeIf { batch ->
            batch.flatMap { it.rows }.sumOf { it.content.length.toLong() + 100 } <= maxChars
        }.orEmpty()
    }
}
