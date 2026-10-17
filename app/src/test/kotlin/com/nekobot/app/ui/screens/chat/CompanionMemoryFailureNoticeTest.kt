package com.nekobot.app.ui.screens.chat

import com.nekobot.app.ui.screens.chat.CompanionMemoryFailureReducer.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CompanionMemoryFailureNoticeTest {
    private val empty = State(null, null)

    @Test
    fun failureShowsNoticeAnchoredToReply() {
        val s = CompanionMemoryFailureReducer.onDone(empty, failed = true, failureKey = "超时", anchor = "回复")
        assertEquals(CompanionMemoryFailureUiState("超时", "回复"), s.notice)
        assertEquals(false, s.notice!!.running)
    }

    @Test
    fun sameFailureRepeatedDoesNotSpam() {
        val first = CompanionMemoryFailureReducer.onDone(empty, true, "超时", "a")
        val again = CompanionMemoryFailureReducer.onDone(first, true, "超时", "b")
        assertSame(first, again)
    }

    @Test
    fun dismissedFailureStaysHiddenForSameReason() {
        val shown = CompanionMemoryFailureReducer.onDone(empty, true, "超时", "a")
        val dismissed = CompanionMemoryFailureReducer.dismiss(shown)
        assertNull(dismissed.notice)
        assertEquals("超时", dismissed.dismissedKey)
        assertNull(CompanionMemoryFailureReducer.onDone(dismissed, true, "超时", "b").notice)
        // 换了失败原因则重新提示
        assertNotNull(CompanionMemoryFailureReducer.onDone(dismissed, true, "额度不足", "b").notice)
    }

    @Test
    fun successClearsNoticeAndDismissMemory() {
        val shown = CompanionMemoryFailureReducer.onDone(empty, true, "超时", "a")
        val ok = CompanionMemoryFailureReducer.onDone(shown, false, "", "a")
        assertEquals(empty, ok)
        val dismissed = CompanionMemoryFailureReducer.dismiss(shown)
        val okAfterDismiss = CompanionMemoryFailureReducer.onDone(dismissed, false, "", "")
        assertNotNull(CompanionMemoryFailureReducer.onDone(okAfterDismiss, true, "超时", "c").notice)
    }

    @Test
    fun runtimeAppliesFailureAndDismiss() {
        val state = ChatSessionState("s1", loadMemoryNotice = { null }, loadMemoryAnchor = { "" })
        state.applyAutoMemoryNotice(com.nekobot.app.data.local.ai.AgentMemoryNotice(
            "s1", 0, com.nekobot.app.data.local.ai.AgentMemoryPhase.DONE, anchorContent = "她的回复",
            failed = true, failureKey = "超时"))
        assertNotNull(state.inlineNoticeOrNull<CompanionMemoryFailureUiState>())
        state.dismissCompanionMemoryFailure()
        assertNull(state.inlineNoticeOrNull<CompanionMemoryFailureUiState>())
        state.applyAutoMemoryNotice(com.nekobot.app.data.local.ai.AgentMemoryNotice(
            "s1", 0, com.nekobot.app.data.local.ai.AgentMemoryPhase.DONE, failed = true, failureKey = "超时"))
        assertNull(state.inlineNoticeOrNull<CompanionMemoryFailureUiState>())
    }
}