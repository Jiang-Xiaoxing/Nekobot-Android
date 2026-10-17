package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionRelationshipEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 执行真实管线，模拟保存后的界面结束/取消；评估请求只用计数器，不调用模型。 */
class CompanionRelationshipAutoTriggerTest {
    private class Fixture(val mode: String = "stream", val delivery: String = "stop",
        var state: CompanionRelationshipEntity = CompanionRelationshipEntity(
            "s", "c", enabled = true, automatic = true, interval = 5)) : PipelineCallbacks() {
        val rows = mutableListOf<LocalMessageEntity>()
        val evaluations = mutableListOf<Int>()
        val order = mutableListOf<String>()
        var modelCalls = 0
        var notifications = 0
        var lateCompletions = 0
        var failGeneration = false
        var stopGeneration = false
        var failSave = false
        var regenerated = false
        var companion = true
        var source: String? = null
        var response = "回答"
        private val activeGenerations = mutableMapOf<String, LocalGenerationController>()

        private fun row(id: String, role: String, content: String) = LocalMessageEntity(
            id, "s", role, content, timestamp = "0", createdAt = "2026-10-15T08:00:00Z",
            source = source.takeIf { role == "assistant" })

        fun context(index: Int): PipelineContext {
            rows += row("u$index", "user", "原话$index")
            val controller = LocalGenerationController()
            activeGenerations.put("s", controller)?.requestStop()
            return PipelineContext(ChatRequest.forLocal("s", "原话$index")).apply {
                metadata[META_FUSED_COMPANION] = true
                stopRequested = { controller.isStopped }
            }
        }

        private fun generate(ctx: PipelineContext): Map<String, Any> {
            modelCalls++
            if (failGeneration) throw IllegalStateException("模拟生成失败")
            if (stopGeneration) activeGenerations.getValue("s").requestStop()
            return mapOf("content" to response, "finish_reason" to "stop")
        }

        override fun buildModelCall(ctx: PipelineContext, tools: List<Map<String, Any>>): ModelCall =
            { _, _ -> generate(ctx) }

        override fun buildModelCallStreaming(ctx: PipelineContext, tools: List<Map<String, Any>>): StreamModelCall? =
            if (mode == "stream") { _, _ -> listOf(generate(ctx)) } else null

        override fun saveAssistantMessage(ctx: PipelineContext, message: Map<String, Any>) {
            if (failSave) throw IllegalStateException("模拟落库失败")
            rows += row(message.getValue("id").toString(), "assistant", message["content"].toString())
                .copy(source = if (ctx.error != null || ctx.stoppedPrematurely) "companion_incomplete" else source)
            order += "saved"
        }

        override fun onAssistantMessageSaved(ctx: PipelineContext, message: Map<String, Any>) {
            assertEquals(message["content"], rows.last().content)
            CompanionRelationshipPolicy.onTurnSaved(ctx, message, companion, regenerated, source) {
                notifications++
                order += "notified"
                val all = CompanionRelationshipPolicy.turns(rows)
                val pending = CompanionRelationshipPolicy.pending(all, state.cursorId)
                if (CompanionRelationshipRecovery.automaticReady(state, pending.size)) {
                    evaluations += pending.size
                    // 模拟后台评估成功后的游标；验证第六轮不会追加一次评估。
                    state = state.copy(cursorId = all.last().endId)
                }
            }
        }

        private fun delivered(ctx: PipelineContext) {
            order += "delivered"
            // 与 LocalRepository 启动下一轮一致：替换控制器会停止上一轮，后处理可能被跳过。
            if (delivery == "stop") activeGenerations.put("s", LocalGenerationController())?.requestStop()
            if (delivery == "cancel") throw CancellationException("最终回复已显示后取消旧收集器")
        }

        override fun sendResponse(ctx: PipelineContext, message: Map<String, Any>) = delivered(ctx)
        override fun onStreamEnd(ctx: PipelineContext, messageId: String) = delivered(ctx)
        override fun onResponseComplete(ctx: PipelineContext, result: PipelineResult) { lateCompletions++ }

        suspend fun run(ctx: PipelineContext): PipelineResult = AIPipeline().process(ctx, this,
            tools = if (mode == "tools") listOf(mapOf("type" to "function",
                "function" to mapOf("name" to "lookup", "parameters" to mapOf("type" to "object")))) else emptyList())
    }

    @Test fun fifthSavedTurnSchedulesEvenWhenDeliverySkipsLateCompletionInAllResponsePaths() = runBlocking {
        for (mode in listOf("stream", "simple", "tools")) {
            val f = Fixture(mode)
            for (index in 1..6) {
                f.run(f.context(index))
                assertEquals(if (index < 5) emptyList<Int>() else listOf(5), f.evaluations)
            }
            assertEquals(6, f.modelCalls)
            assertEquals(6, f.notifications)
            // 原来仅靠这个后处理回调会完全漏触发，而持久化的六轮仍可在页面计数。
            assertEquals(0, f.lateCompletions)
            assertEquals(6, CompanionRelationshipPolicy.turns(f.rows).size)
            assertEquals(List(6) { listOf("saved", "notified", "delivered") }.flatten(), f.order)
        }
    }

    @Test fun cancellationAfterDeliveryCannotLoseTheFifthPersistedTurn() = runBlocking {
        for (mode in listOf("stream", "simple", "tools")) {
            val f = Fixture(mode, delivery = "cancel")
            for (index in 1..5) {
                try { f.run(f.context(index)); fail("交付后取消应继续向调用方传播") }
                catch (_: CancellationException) { }
            }
            assertEquals(listOf(5), f.evaluations)
            assertEquals(5, f.modelCalls)
            assertEquals(0, f.lateCompletions)
        }
    }

    @Test fun successfulDeliveryAndRepeatedSavedCallbackNotifyOnlyOnce() = runBlocking {
        val f = Fixture(delivery = "normal")
        val ctx = f.context(1)
        val result = f.run(ctx)
        repeat(3) { f.onAssistantMessageSaved(ctx, requireNotNull(result.assistantMessage)) }
        assertEquals(1, f.notifications)
        assertEquals(1, f.lateCompletions)
        assertEquals(1, f.modelCalls)
    }

    @Test fun failedOrStoppedGenerationAndThinkingOnlyResponsesDoNotCountCompletedTurns() = runBlocking {
        for (kind in listOf("failed", "stopped", "thinkingOnly")) {
            val f = Fixture(mode = "simple", delivery = "normal")
            f.failGeneration = kind == "failed"
            f.stopGeneration = kind == "stopped"
            if (kind == "thinkingOnly") f.response = ""
            f.run(f.context(1))
            assertEquals(0, f.notifications)
            assertTrue(CompanionRelationshipPolicy.turns(f.rows).isEmpty())
        }
    }

    @Test fun failedPersistenceDoesNotNotifyOrAdvanceEvaluation() = runBlocking {
        val f = Fixture().apply { failSave = true }
        try { f.run(f.context(1)); fail("落库失败应传播") }
        catch (error: IllegalStateException) { assertEquals("模拟落库失败", error.message) }
        assertEquals(0, f.notifications)
        assertTrue(f.evaluations.isEmpty())
        assertTrue(CompanionRelationshipPolicy.turns(f.rows).isEmpty())
    }

    @Test fun regeneratedSilentProactiveAndNonCompanionRepliesDoNotSchedule() = runBlocking {
        for (kind in listOf("regenerated", "silent", "proactive", "task", "ordinary")) {
            val f = Fixture(delivery = "normal")
            f.regenerated = kind == "regenerated"
            f.companion = kind != "ordinary"
            f.source = when (kind) { "proactive" -> "proactive_chat"; "task" -> "task_center"; else -> null }
            val ctx = f.context(1)
            if (kind == "silent") ctx.metadata["skip_character_after_turn"] = true
            f.run(ctx)
            assertEquals(kind, 0, f.notifications)
        }
    }

    @Test fun automaticSettingsHistoryConfirmationAndFailureGatesRemainEffective() = runBlocking {
        val enabled = Fixture().state
        for (state in listOf(enabled.copy(enabled = false), enabled.copy(automatic = false),
            enabled.copy(awaitingHistory = true), enabled.copy(historyReviewRequired = true),
            enabled.copy(notice = "所选评估模型已不可用，请重新选择"))) {
            val f = Fixture(state = state)
            for (index in 1..6) f.run(f.context(index))
            assertEquals(6, f.notifications)
            assertTrue(f.evaluations.isEmpty())
            assertEquals(state, f.state)
        }
    }

    @Test fun configuredIntervalsTriggerExactlyOnTheNthSavedTurn() = runBlocking {
        for (interval in listOf(1, 5, 12)) {
            val f = Fixture().apply { state = state.copy(interval = interval) }
            for (index in 1..interval) {
                f.run(f.context(index))
                assertEquals(if (index < interval) emptyList<Int>() else listOf(interval), f.evaluations)
            }
            assertEquals(interval, f.modelCalls)
        }
    }
}
