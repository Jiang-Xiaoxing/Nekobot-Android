package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionRelationshipEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class CompanionRelationshipRecoveryTest {
    private fun state(notice: String? = null, interval: Int = 5) = CompanionRelationshipEntity(
        "s", "c", enabled = true, automatic = true, interval = interval, notice = notice)
    private fun turns(count: Int, chars: Int = 1) = (1..count).map { index ->
        RelationshipTurn(listOf(LocalMessageEntity("u$index", "s", "user", "u".repeat(chars), timestamp = "0", createdAt = "2026-10-10T00:00:00Z"),
            LocalMessageEntity("a$index", "s", "assistant", "a".repeat(chars), timestamp = "0", createdAt = "2026-10-10T00:00:00Z")))
    }
    private suspend fun timeout(): Nothing = withTimeout(1L) { awaitCancellation() }

    @Test fun timeoutRetriesOnceAndUsesReducedBatchMode() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        runRelationshipEvaluation(false, beforeRetry = {}) { retrying ->
            attempts.add(retrying)
            if (!retrying) timeout()
        }
        assertEquals(listOf(false, true), attempts)
    }
    @Test fun repeatedTimeoutStopsAfterTwoAttempts() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        try {
            runRelationshipEvaluation(false, beforeRetry = {}) { attempts.add(it); timeout() }
            fail("超时应向外结束作业")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(listOf(false, true), attempts)
    }
    @Test fun networkReadTimeoutAlsoRetriesOnce() = runBlocking {
        var count = 0
        runRelationshipEvaluation(false, beforeRetry = {}) { retrying ->
            count++
            if (!retrying) throw SocketTimeoutException("read timed out")
        }
        assertEquals(2, count)
    }
    @Test fun repeatedNetworkTimeoutStopsInsteadOfStartingAThirdRequest() = runBlocking {
        var count = 0
        try {
            runRelationshipEvaluation(false, beforeRetry = {}) { count++; throw SocketTimeoutException("read timed out") }
            fail("第二次超时应结束作业")
        } catch (_: SocketTimeoutException) { }
        assertEquals(2, count)
    }
    @Test fun restoredTimeoutOrInterruptedRunHasOnlyOneRecoveryAttempt() = runBlocking {
        var count = 0
        try {
            runRelationshipEvaluation(true, beforeRetry = { fail("恢复作业不应再递归重试") }) { retrying ->
                assertTrue(retrying); count++; timeout()
            }
        } catch (_: TimeoutCancellationException) { }
        assertEquals(1, count)
    }
    @Test fun ordinaryCancellationDoesNotRetry() = runBlocking {
        var count = 0
        try {
            runRelationshipEvaluation(false, beforeRetry = { fail("取消不能变成超时重试") }) {
                count++; throw CancellationException("设置已修改")
            }
        } catch (_: CancellationException) { }
        assertEquals(1, count)
    }
    @Test fun invalidResultDoesNotRetry() = runBlocking {
        var count = 0
        try {
            runRelationshipEvaluation(false, beforeRetry = { fail("格式错误不应重试") }) {
                count++; throw IllegalArgumentException("来源无效")
            }
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, count)
    }
    @Test fun configuredIntervalStillTriggersOnlyAtNCompletedTurns() {
        for (interval in listOf(1, 5, 12, 100)) {
            val state = state(interval = interval)
            assertFalse(CompanionRelationshipRecovery.automaticReady(state, interval - 1))
            assertTrue(CompanionRelationshipRecovery.automaticReady(state, interval))
            assertEquals(interval, state.interval)
        }
    }
    @Test fun legacyTimeoutAndClearedInterruptionResumeTheAlreadyTriggeredEvaluation() {
        assertTrue(CompanionRelationshipRecovery.automaticReady(state("状态评估超时，旧状态保留。可手动重试。"), 12))
        assertTrue(CompanionRelationshipRecovery.automaticReady(state(CompanionRelationshipRecovery.IN_FLIGHT), 1))
        assertTrue(CompanionRelationshipRecovery.automaticReady(state(), 1, interrupted = true))
        assertFalse(CompanionRelationshipRecovery.automaticReady(state(), 1))
    }
    @Test fun startupOnlyClearsInterruptedPlaceholderAndPreservesProgressAndSettings() {
        val interrupted = state(CompanionRelationshipRecovery.IN_FLIGHT, interval = 12).copy(cursorId = "a3", version = 9)
        assertEquals(interrupted.copy(notice = null), CompanionRelationshipRecovery.clearInterruptedNotice(interrupted))
        val timeout = state(CompanionRelationshipRecovery.TIMEOUT)
        assertEquals(timeout, CompanionRelationshipRecovery.clearInterruptedNotice(timeout))
        val invalidModel = state("所选评估模型已不可用，请重新选择")
        assertEquals(invalidModel, CompanionRelationshipRecovery.clearInterruptedNotice(invalidModel))
    }
    @Test fun repeatedFailureDoesNotLoopInCompletionButNewChatCanResume() {
        val failed = state(CompanionRelationshipRecovery.TIMEOUT_EXHAUSTED)
        assertFalse(CompanionRelationshipRecovery.automaticReady(failed, 12, afterEvaluation = true))
        assertTrue(CompanionRelationshipRecovery.automaticReady(failed, 12))
        assertFalse(CompanionRelationshipRecovery.automaticReady(failed, 4))
    }
    @Test fun recoveryRespectsDisabledAutomaticAndUnconfirmedHistory() {
        val failed = state(CompanionRelationshipRecovery.TIMEOUT)
        listOf(failed.copy(enabled = false), failed.copy(automatic = false), failed.copy(awaitingHistory = true),
            failed.copy(historyReviewRequired = true)).forEach {
            assertFalse(CompanionRelationshipRecovery.automaticReady(it, 12, interrupted = true))
        }
        assertFalse(CompanionRelationshipRecovery.automaticReady(failed, 0))
        assertFalse(CompanionRelationshipRecovery.automaticReady(state("所选评估模型已不可用，请重新选择"), 12))
    }
    @Test fun twelveTurnsAreProcessedInOrderedSmallBatchesWithoutLosingCursorProgress() {
        val all = turns(12)
        val sizer = RelationshipBatchSizer(120)
        var cursor: String? = null
        val seen = mutableListOf<String>()
        while (CompanionRelationshipPolicy.pending(all, cursor).isNotEmpty()) {
            val batch = sizer.select(CompanionRelationshipPolicy.pending(all, cursor), 22_000, false)
            assertTrue(batch.size in 1..3)
            seen.addAll(batch.map { it.endId }); cursor = batch.last().endId
        }
        assertEquals(all.map { it.endId }, seen)
        assertEquals(12, seen.distinct().size)
    }
    @Test fun shortTimeoutAndSlowMeasuredResponseShrinkFollowingBatch() {
        assertEquals(1, RelationshipBatchSizer(30).select(turns(12), 22_000, false).size)
        val sizer = RelationshipBatchSizer(120)
        assertEquals(3, sizer.select(turns(12), 22_000, false).size)
        sizer.completed(110_000, 3)
        assertEquals(1, sizer.select(turns(12), 22_000, false).size)
        assertEquals(1, RelationshipBatchSizer(120).select(turns(12), 22_000, true).size)
    }
    @Test fun batchHonorsCharacterBudgetAndKeepsOversizedTurnPending() {
        val sizer = RelationshipBatchSizer(120)
        assertEquals(1, sizer.select(turns(12, 4_000), 22_000, false).size)
        assertTrue(sizer.select(turns(1, 12_000), 22_000, true).isEmpty())
        assertEquals(1, sizer.select(turns(1, 4_000), 22_000, true).size)
    }
    @Test fun noticeUsesRealReasonAndPersistsDeduplicationAcrossPageRecreation() {
        val state = state("所选评估模型已不可用，请重新选择").copy(version = 9)
        val first = requireNotNull(CompanionRelationshipRecovery.noticeToShow(state, false, null))
        assertEquals(state.notice, first.second)
        assertNull(CompanionRelationshipRecovery.noticeToShow(state, false, first.first))
        assertNull(CompanionRelationshipRecovery.noticeToShow(state, true, null))
        assertNotNull(CompanionRelationshipRecovery.noticeToShow(state.copy(version = 10), false, first.first))
    }
    @Test fun interruptedPlaceholderIsNotShownAsFailureAndLegacyTimeoutIsPlainLanguage() {
        assertNull(CompanionRelationshipRecovery.noticeToShow(state(CompanionRelationshipRecovery.IN_FLIGHT), false, null))
        val notice = CompanionRelationshipRecovery.noticeToShow(state("状态评估超时，旧状态保留。"), false, null)
        assertTrue(requireNotNull(notice).second.contains("回复太慢"))
        assertNull(CompanionRelationshipRecovery.noticeToShow(state().copy(enabled = false), false, null))
    }
}
