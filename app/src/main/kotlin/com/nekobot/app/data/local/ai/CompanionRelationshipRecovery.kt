package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionRelationshipEntity
import kotlinx.coroutines.withTimeout

/** 兼容已保存的中断和旧超时标记；本轮失败不能在完成回调中立即追加请求。 */
internal object CompanionRelationshipRecovery {
    const val TIMEOUT_SECONDS = 300
    const val IN_FLIGHT = "上次评估未完成，可手动重试；已有状态保留。"
    const val RETRY_IN_FLIGHT = "上次自动重试被打断了，已有状态保留。下次聊天会再尝试，也可手动重试。"
    const val TIMEOUT = "评估模型回复太慢，本批尚未完成，已有状态和待评估对话保留。请稍后重试。"
    const val LEGACY_TIMEOUT = "评估模型回复太慢，已有状态保留。稍后会自动缩小范围重试一次。"
    const val TIMEOUT_EXHAUSTED = "评估模型重试后仍然太慢，已有状态保留。下次聊天会再尝试，也可检查评估模型后手动重试。"

    fun interrupted(notice: String?) = notice?.startsWith("上次评估未完成") == true || notice == RETRY_IN_FLIGHT
    fun timedOut(notice: String?) = notice == TIMEOUT || notice == LEGACY_TIMEOUT || notice?.startsWith("状态评估超时") == true
    fun clearInterruptedNotice(state: CompanionRelationshipEntity): CompanionRelationshipEntity =
        if (interrupted(state.notice)) state.copy(notice = null) else state
    fun retryable(notice: String?) = interrupted(notice) || notice == LEGACY_TIMEOUT ||
        notice?.startsWith("状态评估超时") == true
    private fun exhausted(notice: String?) = notice == TIMEOUT || notice == TIMEOUT_EXHAUSTED || notice == RETRY_IN_FLIGHT

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
        "评估模型回复太慢，已有状态和待评估对话保留，可在关系状态页重试。" else notice

    fun noticeToShow(state: CompanionRelationshipEntity, busy: Boolean, lastToken: String?): Pair<String, String>? {
        val token = noticeToken(state, busy)?.takeUnless { it == lastToken } ?: return null
        return token to readableNotice(requireNotNull(state.notice))
    }
}

/** 每次作业只请求整批一次；超时或失败向外传播，不能暗中拆轮或追加模型调用。 */
internal suspend fun <T> runRelationshipEvaluation(
    timeoutMillis: Long = CompanionRelationshipRecovery.TIMEOUT_SECONDS * 1000L,
    evaluate: suspend () -> T
): T = withTimeout(timeoutMillis) { evaluate() }
