package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 嵌套子代理完成通知的路由测试：
 * 父任务（也是子代理）仍在运行 → 任务级通知队列（父任务循环内插队消费）；
 * 父任务已结束/不存在 → 会话级通知（循环内注入或唤醒空闲会话）。
 */
class SubagentNoticeRoutingTest {

    private fun registerTask(
        sessionId: String,
        depth: Int,
        parentTaskId: String?,
        description: String
    ): SubagentTask = SubagentTaskStore.register(
        sessionId = sessionId,
        parentRunId = "run-$description",
        description = description,
        prompt = "p-$description",
        depth = depth,
        parentTaskId = parentTaskId
    )

    @Test
    fun nestedCompletion_parentStillRunning_routesToParentTaskQueue() {
        val sessionId = "nested-route-1"
        SubagentTaskStore.clearSession(sessionId)
        AgentNoticeBus.clear(sessionId)
        val t1 = registerTask(sessionId, depth = 1, parentTaskId = null, description = "主任务")
        val t2 = registerTask(sessionId, depth = 2, parentTaskId = t1.id, description = "子任务")
        val t3 = registerTask(sessionId, depth = 3, parentTaskId = t2.id, description = "孙任务")
        // T1（主代理委派的子代理）已完成，T2（T1 委派的后台孙任务）仍在运行。
        SubagentTaskStore.update(t1.id, status = SubagentTaskStatus.SUCCEEDED, result = "done")

        SubagentTaskStore.update(t3.id, status = SubagentTaskStatus.SUCCEEDED, result = "孙任务结果")
        routeSubagentCompletionNotice(sessionId, SubagentTaskStore.get(t3.id)!!)

        // T2 仍在运行：通知进 T2 的任务队列，供其工具循环下一轮注入；会话级不投递。
        assertTrue(SubagentTaskNoticeBus.hasPending(t2.id))
        assertFalse(AgentNoticeBus.hasPending(sessionId))
        val drained = SubagentTaskNoticeBus.drain(t2.id)
        assertEquals(1, drained.size)
        assertTrue(drained[0].contains("你委派的后台子任务已结束"))
        assertTrue(drained[0].contains(t3.id))

        SubagentTaskStore.clearSession(sessionId)
    }

    @Test
    fun nestedCompletion_parentDead_fallsBackToSessionNotice() {
        val sessionId = "nested-route-2"
        SubagentTaskStore.clearSession(sessionId)
        AgentNoticeBus.clear(sessionId)
        val t1 = registerTask(sessionId, depth = 1, parentTaskId = null, description = "主任务")
        val t2 = registerTask(sessionId, depth = 2, parentTaskId = t1.id, description = "子任务")
        val t3 = registerTask(sessionId, depth = 3, parentTaskId = t2.id, description = "孙任务")
        // T1、T2 都已结束：孙任务的结果只能由主会话接手。
        SubagentTaskStore.update(t1.id, status = SubagentTaskStatus.SUCCEEDED)
        SubagentTaskStore.update(t2.id, status = SubagentTaskStatus.SUCCEEDED)

        SubagentTaskStore.update(t3.id, status = SubagentTaskStatus.FAILED, error = "超时")
        routeSubagentCompletionNotice(sessionId, SubagentTaskStore.get(t3.id)!!)

        assertFalse(SubagentTaskNoticeBus.hasPending(t2.id))
        assertTrue(AgentNoticeBus.hasPending(sessionId))
        val notice = AgentNoticeBus.drain(sessionId).single()
        assertTrue(notice.contains("后台子代理任务已结束"))
        assertTrue(notice.contains("结果由你接手处理"))
        assertTrue(notice.contains("失败原因：超时"))

        SubagentTaskStore.clearSession(sessionId)
    }

    @Test
    fun undeliveredNotices_forwardUpWhileAncestorActive() {
        val sessionId = "nested-route-3"
        SubagentTaskStore.clearSession(sessionId)
        AgentNoticeBus.clear(sessionId)
        val t1 = registerTask(sessionId, depth = 1, parentTaskId = null, description = "主任务")
        val t2 = registerTask(sessionId, depth = 2, parentTaskId = t1.id, description = "子任务")
        val t3 = registerTask(sessionId, depth = 3, parentTaskId = t2.id, description = "孙任务")

        // T2 运行中收到 T3 的完成通知，但没来得及消费就结束了；T1 仍在运行。
        SubagentTaskStore.update(t3.id, status = SubagentTaskStatus.SUCCEEDED, result = "r3")
        routeSubagentCompletionNotice(sessionId, SubagentTaskStore.get(t3.id)!!)
        assertTrue(SubagentTaskNoticeBus.hasPending(t2.id))

        SubagentTaskStore.update(t2.id, status = SubagentTaskStatus.SUCCEEDED, result = "r2")
        forwardUndeliveredSubagentTaskNotices(SubagentTaskStore.get(t2.id)!!)

        // 通知从 T2 的队列上移到 T1 的队列。
        assertFalse(SubagentTaskNoticeBus.hasPending(t2.id))
        assertTrue(SubagentTaskNoticeBus.hasPending(t1.id))

        // T1 也结束且无人消费：最终回落到会话级，保证结果总能到达主会话。
        SubagentTaskStore.update(t1.id, status = SubagentTaskStatus.SUCCEEDED, result = "r1")
        forwardUndeliveredSubagentTaskNotices(SubagentTaskStore.get(t1.id)!!)
        assertFalse(SubagentTaskNoticeBus.hasPending(t1.id))
        assertTrue(AgentNoticeBus.hasPending(sessionId))

        SubagentTaskStore.clearSession(sessionId)
    }

    @Test
    fun taskNoticeBus_drainConsumesAndClearSessionCleansUp() {
        val sessionId = "nested-route-4"
        SubagentTaskStore.clearSession(sessionId)
        val task = registerTask(sessionId, depth = 1, parentTaskId = null, description = "清理测试")
        SubagentTaskNoticeBus.publish(task.id, "通知1")
        SubagentTaskNoticeBus.publish(task.id, "通知2")
        assertEquals(listOf("通知1", "通知2"), SubagentTaskNoticeBus.drain(task.id))
        assertFalse(SubagentTaskNoticeBus.hasPending(task.id))

        SubagentTaskNoticeBus.publish(task.id, "通知3")
        SubagentTaskStore.clearSession(sessionId)
        assertFalse(SubagentTaskNoticeBus.hasPending(task.id))
    }

    @Test
    fun completionNoticeText_reportsResultAndFailure() {
        val sessionId = "nested-route-5"
        SubagentTaskStore.clearSession(sessionId)
        val task = registerTask(sessionId, depth = 1, parentTaskId = null, description = "文本测试")

        SubagentTaskStore.update(task.id, status = SubagentTaskStatus.SUCCEEDED, result = "结论正文")
        val success = buildSubagentCompletionNotice(SubagentTaskStore.get(task.id)!!, addressedToParentTask = false)
        assertTrue(success.contains("状态=succeeded"))
        assertTrue(success.contains("结论正文"))

        SubagentTaskStore.update(task.id, status = SubagentTaskStatus.FAILED, error = "模型不可用")
        val failure = buildSubagentCompletionNotice(SubagentTaskStore.get(task.id)!!, addressedToParentTask = false)
        assertTrue(failure.contains("状态=failed"))
        assertTrue(failure.contains("失败原因：模型不可用"))

        SubagentTaskStore.clearSession(sessionId)
    }
}
