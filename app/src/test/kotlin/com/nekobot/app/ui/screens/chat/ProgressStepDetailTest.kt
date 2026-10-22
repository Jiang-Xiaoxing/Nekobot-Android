package com.nekobot.app.ui.screens.chat

import com.nekobot.app.data.model.ThinkingStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度卡片步骤「能否点开详情」的判定。
 *
 * 回归点：子代理运行期间新出现的工具步骤（尤其 subagent_list 这类无参数工具）既没有参数
 * 也没有结果，过去被判成「没有详情」，点击完全没有反应。
 */
class ProgressStepDetailTest {

    @Test
    fun runningToolStepWithoutArgumentsOrResultIsStillOpenable() {
        val step = ThinkingStep(
            type = "tool",
            name = "subagent_list",
            status = "running"
        )

        assertTrue(progressStepHasDetail(step))
    }

    @Test
    fun anyToolStepIsOpenableEvenWithoutDescription() {
        val step = ThinkingStep(type = "tool", name = "mcp_some_dynamic_tool", status = "running")

        assertTrue(progressStepHasDetail(step))
    }

    @Test
    fun toolDoneStepIsOpenable() {
        val step = ThinkingStep(type = "tool_done", name = "subagent_get", status = "done")

        assertTrue(progressStepHasDetail(step))
    }

    @Test
    fun thinkingStepWithContentIsOpenable() {
        val step = ThinkingStep(type = "thinking", status = "active", thinkingContent = "思考中")

        assertTrue(progressStepHasDetail(step))
    }

    @Test
    fun plainStepWithoutAnythingIsNotOpenable() {
        // 非工具步骤且没有任何内容：不显示可点击样式，避免点开一个空弹窗
        assertFalse(progressStepHasDetail(ThinkingStep(type = "done", name = "子代理完成")))
    }
}
