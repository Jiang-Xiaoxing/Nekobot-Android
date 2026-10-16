package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionRelationshipEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
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
    @Test fun timeoutStopsAfterOneRequestAndKeepsAllFiveTurnsPending() = runBlocking {
        val all = turns(5)
        var cursor: String? = null
        var count = 0
        try {
            cursor = runRelationshipEvaluation(timeoutMillis = 25L) {
                count++
                awaitCancellation()
            }
            fail("超时应结束本次作业，不能追加或拆分请求")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(1, count)
        assertNull(cursor)
        assertEquals(5, CompanionRelationshipPolicy.pending(all, cursor).size)
    }
    @Test fun networkReadTimeoutDoesNotStartASecondRequest() = runBlocking {
        var count = 0
        try {
            runRelationshipEvaluation { count++; throw SocketTimeoutException("read timed out") }
            fail("网络超时应结束作业")
        } catch (_: SocketTimeoutException) { }
        assertEquals(1, count)
    }
    @Test fun ordinaryCancellationDoesNotRetry() = runBlocking {
        var count = 0
        try {
            runRelationshipEvaluation {
                count++; throw CancellationException("设置已修改")
            }
        } catch (_: CancellationException) { }
        assertEquals(1, count)
    }
    @Test fun invalidResultDoesNotRetry() = runBlocking {
        var count = 0
        try {
            runRelationshipEvaluation {
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
        for (notice in listOf(CompanionRelationshipRecovery.TIMEOUT, CompanionRelationshipRecovery.TIMEOUT_EXHAUSTED)) {
            val failed = state(notice)
            assertFalse(CompanionRelationshipRecovery.automaticReady(failed, 12, afterEvaluation = true))
            assertTrue(CompanionRelationshipRecovery.automaticReady(failed, 12))
            assertFalse(CompanionRelationshipRecovery.automaticReady(failed, 4))
            assertFalse(CompanionRelationshipRecovery.retryable(notice))
        }
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
    @Test fun successfulBatchUsesOneRequestAndConsumesEveryPendingTurn() = runBlocking {
        for (size in listOf(1, 5, 6, 12, 100)) {
            val all = turns(size)
            val batch = CompanionRelationshipPolicy.pending(
                CompanionRelationshipPolicy.throughBoundary(all, all.last().endId), null)
            var calls = 0
            val cursor = runRelationshipEvaluation {
                calls++
                assertEquals(all, batch)
                batch.last().endId
            }
            assertEquals(1, calls)
            assertTrue(CompanionRelationshipPolicy.pending(all, cursor).isEmpty())
        }
        assertEquals(300, CompanionRelationshipRecovery.TIMEOUT_SECONDS)
    }
    @Test fun legacyTimeoutRecoveryConsumesTheEntireRemainderInOneRequest() = runBlocking {
        val all = turns(12)
        // 旧版已单独消费三轮；升级后一次处理余下九轮，不重评前三轮。
        val saved = state(CompanionRelationshipRecovery.LEGACY_TIMEOUT).copy(cursorId = "a3")
        assertTrue(CompanionRelationshipRecovery.automaticReady(saved, 9))
        val batch = CompanionRelationshipPolicy.pending(
            CompanionRelationshipPolicy.throughBoundary(all, "a12"), saved.cursorId)
        var calls = 0
        val cursor = runRelationshipEvaluation {
            calls++
            assertEquals((4..12).map { "a$it" }, batch.map { it.endId })
            batch.last().endId
        }
        assertEquals(1, calls)
        assertTrue(CompanionRelationshipPolicy.pending(all, cursor).isEmpty())
    }
    @Test fun newTurnsDuringEvaluationRemainPendingAndDoNotEnterThisRequest() = runBlocking {
        val first = turns(5)
        val boundary = first.last().endId
        var withNew = first
        val batch = CompanionRelationshipPolicy.pending(
            CompanionRelationshipPolicy.throughBoundary(withNew, boundary), null)
        var calls = 0
        val cursor = runRelationshipEvaluation {
            calls++
            withNew = turns(7)
            assertEquals(first, batch)
            batch.last().endId
        }
        assertEquals(1, calls)
        assertEquals(listOf("a6", "a7"), CompanionRelationshipPolicy.pending(withNew, cursor).map { it.endId })
    }
    @Test fun boundaryAlsoExcludesNewTurnsSavedBeforeRequestPreparation() {
        val first = turns(5)
        assertEquals(first, CompanionRelationshipPolicy.throughBoundary(turns(7), first.last().endId))
    }
    @Test fun largeBatchIsNotSilentlyTruncatedOrSplitBeforeContextValidation() {
        val all = turns(5, chars = 12_000)
        val batch = CompanionRelationshipPolicy.pending(
            CompanionRelationshipPolicy.throughBoundary(all, "a5"), null)
        assertEquals(all, batch)
        assertEquals(120_000, batch.flatMap { it.rows }.sumOf { it.content.length })
    }
    @Test fun deletedBoundaryIsRejectedAndEmptyBoundaryDoesNotConsumeNewHistory() {
        assertTrue(CompanionRelationshipPolicy.throughBoundary(turns(5), null).isEmpty())
        try {
            CompanionRelationshipPolicy.throughBoundary(turns(4), "a5")
            fail("边界已变化时必须拒绝旧作业")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun failedRecoveryPreservesThePreviouslySavedCursor() = runBlocking {
        val all = turns(7)
        var cursor = "a2"
        val batch = CompanionRelationshipPolicy.pending(
            CompanionRelationshipPolicy.throughBoundary(all, "a7"), cursor)
        var calls = 0
        try {
            val response = runRelationshipEvaluation {
                calls++
                assertEquals(5, batch.size)
                "无效评估结果"
            }
            CompanionRelationshipResponse.json(response)
            cursor = batch.last().endId
            fail("无效结果不能保存进度")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls)
        assertEquals("a2", cursor)
        assertEquals(5, CompanionRelationshipPolicy.pending(all, cursor).size)
    }
    @Test fun retryInterruptionPlaceholderIsAlsoClearedWithoutChangingProgress() {
        val interrupted = state(CompanionRelationshipRecovery.RETRY_IN_FLIGHT).copy(cursorId = "a3", version = 9)
        assertEquals(interrupted.copy(notice = null), CompanionRelationshipRecovery.clearInterruptedNotice(interrupted))
        assertNull(CompanionRelationshipRecovery.noticeToShow(interrupted, false, null))
        assertFalse(CompanionRelationshipRecovery.timedOut(interrupted.notice))
        assertTrue(CompanionRelationshipRecovery.timedOut(CompanionRelationshipRecovery.TIMEOUT))
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

    @Test fun savedInitializationValidationDoesNotAppearAsFailureOrBlockFutureUpdates() {
        val old = state("没有分数的关系项目需要按人设初始化").copy(automatic = true, interval = 1)
        assertTrue(CompanionRelationshipRecovery.obsoleteInitializationNotice(old.notice))
        assertNull(CompanionRelationshipRecovery.noticeToShow(old, false, null))
        assertTrue(CompanionRelationshipRecovery.automaticReady(old, 1))
        assertFalse(CompanionRelationshipRecovery.obsoleteInitializationNotice(CompanionRelationshipRecovery.TIMEOUT))
        assertNotNull(CompanionRelationshipRecovery.noticeToShow(old.copy(notice = CompanionRelationshipRecovery.TIMEOUT), false, null))
    }
}
