package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort
import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingParserTest {
    private class Output {
        val content = StringBuilder()
        val thinking = StringBuilder()
        val contentChunks = mutableListOf<String>()
        val parser = CharacterThinkingParser({ content.append(it); contentChunks += it }, { thinking.append(it) })
        fun check(content: String, thinking: String) {
            assertEquals(content, this.content.toString())
            assertEquals(thinking, this.thinking.toString())
        }
    }

    @Test
    fun everyPossibleSplitKeepsThinkingOutOfContent() {
        val text = "前言<think>我想起他了。</think>你好。"
        for (first in 0..text.length) for (second in first..text.length) {
            val output = Output()
            listOf(text.substring(0, first), text.substring(first, second), text.substring(second)).forEach {
                output.parser.append(it)
                assertFalse(output.content.toString().contains("我"))
                assertFalse(output.content.toString().contains('<'))
            }
            output.parser.finish()
            output.check("前言你好。", "我想起他了。")
        }
    }

    @Test
    fun characterByCharacterTagsAreNeverRelayedAsContent() {
        val output = Output()
        "<ThInK>我先想想。</tHiNk>回答".forEach { output.parser.append(it.toString()) }
        output.parser.finish()
        output.check("回答", "我先想想。")
        assertTrue(output.contentChunks.all { it in listOf("回", "答") })
    }

    @Test
    fun unclosedThinkingRemainsThinkingAndCanBeSavedWithoutContent() {
        val output = Output()
        output.parser.append("<think>我还没有想完")
        output.parser.finish()
        output.check("", "我还没有想完")
        val result = characterThinkingResult(LocalAiResult("<think>我还没有想完"), true)
        assertEquals("", result.content)
        assertEquals("我还没有想完", result.thinkingContent)
        assertTrue(hasCharacterThinking(mapOf("thinking_content" to result.thinkingContent)))
        assertTrue(hasCharacterThinking(mapOf("reasoning_content" to result.thinkingContent)))
        assertFalse(hasCharacterThinking(mapOf("thinking_content" to " ")))
    }

    @Test
    fun incompleteTagFragmentsAreHeldAndDroppedAtFinish() {
        for (fragment in listOf("<thi", "<think", "</thi", "</think")) {
            val output = Output()
            output.parser.append("正文$fragment")
            output.check("正文", "")
            output.parser.finish()
            output.parser.finish()
            output.check("正文", "")
        }
        val output = Output()
        output.parser.append("<think>我想完了</thi")
        output.parser.finish()
        output.check("", "我想完了")
    }

    @Test
    fun noTagsKeepTextWhitespaceAndUnrelatedMarkupIntact() {
        val text = "  直接回答\n3 < 5；<b>文字</b>；<thinker>其他标签</thinker>。  "
        val output = Output()
        text.forEach { output.parser.append(it.toString()) }
        output.parser.finish()
        output.check(text, "")
    }

    @Test
    fun thinkingAliasAndTagNewlinesWorkAtEverySplit() {
        val text = "<ThInKiNg>\r\n\n我先想想\n再想一步</tHiNkInG>\r\n\n  回答\n第二行"
        for (split in 0..text.length) {
            val output = Output()
            output.parser.append(text.take(split))
            output.parser.append(text.drop(split))
            output.parser.finish()
            output.check("  回答\n第二行", "我先想想\n再想一步")
        }
    }

    @Test
    fun aliasTagsAndRepeatedOpeningCanArriveCharacterByCharacter() {
        val output = Output()
        "<think>\n<THINKING>\n我想到他了</THINKING>\n回答".forEach {
            output.parser.append(it.toString())
            assertFalse(output.content.toString().contains("我"))
            assertFalse(output.content.toString().contains('<'))
        }
        output.parser.finish()
        output.check("回答", "我想到他了")
    }

    @Test
    fun openingTagWithoutClosingTagStaysInThinking() {
        val output = Output()
        output.parser.append("<think>\n")
        output.parser.append("我还没想完")
        output.parser.finish()
        output.check("", "我还没想完")
    }

    @Test
    fun severalThinkingBlocksAndEmptyBlocksAreSeparated() {
        val output = Output()
        output.parser.append("<think></think>甲<think>我想到乙</think>乙<think>我想到丙</think>丙")
        output.parser.finish()
        output.check("甲乙丙", "我想到乙我想到丙")
    }

    @Test
    fun enabledStreamResetsNativeAndTagStateForNextAttempt() {
        val content = StringBuilder()
        val thinking = StringBuilder()
        var starts = 0
        var observed = 0
        val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onStart = { starts++; content.setLength(0); thinking.setLength(0) },
            onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) },
            onStreamObserved = { observed++ }
        ))
        stream.callbacks.onStart()
        stream.callbacks.onThinkingChunk("Native English")
        stream.callbacks.onContentChunk("<think>失败尝试<thi")
        stream.callbacks.onStart()
        stream.callbacks.onStreamObserved()
        stream.callbacks.onContentChunk("备用正文")
        stream.finish()
        assertEquals(2, starts)
        assertEquals(1, observed)
        assertEquals("备用正文", content.toString())
        assertEquals("", thinking.toString())
        // 新一轮工具调用同样创建独立标签状态。
        stream.callbacks.onStart()
        stream.callbacks.onContentChunk("<think>我再想想</think>下一轮")
        stream.finish()
        assertEquals("下一轮", content.toString())
        assertEquals("我再想想", thinking.toString())
    }

    @Test
    fun disabledStreamAndResultPreserveExistingNativeBehavior() {
        val content = StringBuilder()
        val thinking = StringBuilder()
        val stream = CharacterThinkingStream(false, LocalAiStreamCallbacks(
            onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }
        ))
        stream.callbacks.onStart()
        stream.callbacks.onContentChunk("<think>原始正文</think>")
        stream.callbacks.onThinkingChunk("原生思考")
        stream.finish()
        assertEquals("<think>原始正文</think>", content.toString())
        assertEquals("原生思考", thinking.toString())
        val original = LocalAiResult("正文", thinkingContent = "原生思考", thinkingSignature = "mock-signature")
        assertSame(original, characterThinkingResult(original, false))
    }

    @Test
    fun finalParsingPrefersTaggedThinkingAndRetainsNativeWhenTagsAreAbsent() {
        val original = LocalAiResult("<think>我先查一下。</think>",
            thinkingContent = "Native English", thinkingSignature = "mock-signature",
            toolCalls = listOf(mapOf("id" to "call-1", "function" to mapOf("name" to "lookup", "arguments" to "{}"))),
            finishReason = "tool_calls", usage = mapOf("prompt" to 100, "completion" to 30),
            usedModelId = "model", error = "mock-error")
        val parsed = characterThinkingResult(original, true)
        assertEquals(original.copy(content = "", thinkingContent = "我先查一下。", thinkingSignature = ""), parsed)
        val noTags = characterThinkingResult(original.copy(content = "直接回答"), true)
        assertEquals("直接回答", noTags.content)
        assertEquals("Native English", noTags.thinkingContent)
        assertEquals("mock-signature", noTags.thinkingSignature)
    }

    @Test
    fun historicalThinkingIsRemovedButCurrentToolThinkingAndSignatureArePreserved() {
        val user = mapOf<String, Any>("role" to "user", "content" to "哈哈")
        val calls = listOf(mapOf("id" to "call-1", "function" to mapOf("name" to "lookup", "arguments" to "{}")))
        val assistant = mapOf<String, Any>("role" to "assistant", "content" to "", "thinking_content" to "我查一下",
            "thinking_signature" to "mock-signature", "tool_calls" to calls)
        val tool = mapOf<String, Any>("role" to "tool", "content" to "<think>工具原文</think>", "tool_call_id" to "call-1")
        val messages = listOf(assistant, user, assistant, tool)
        val cleaned = characterThinkingMessages(messages, true)
        assertSame(user, cleaned[1]); assertSame(tool, cleaned[3]); assertSame(calls, cleaned[0]["tool_calls"])
        assertFalse(cleaned[0].containsKey("thinking_content"))
        assertFalse(cleaned[0].containsKey("thinking_signature"))
        assertSame(assistant, cleaned[2])
        assertEquals("我查一下", assistant["thinking_content"])
        assertSame(messages, characterThinkingMessages(messages, false))
    }

    @Test
    fun characterRequestKeepsCustomParameterAndEffortWithoutMutatingPreference() {
        val custom = CustomReasoningParam("thinking.type", "enabled")
        val extra = mutableMapOf<String, Any?>(CustomReasoningParam.EXTRA_KEY to custom)
        putReasoningParams(extra, ReasoningEffort.CUSTOM, true) { custom }
        assertEquals("custom", extra["reasoning_effort"])
        assertEquals(true, extra[CHARACTER_THINKING_EXTRA])
        assertSame(custom, extra[CustomReasoningParam.EXTRA_KEY])
        val native = mutableMapOf<String, Any?>()
        putReasoningParams(native, ReasoningEffort.CUSTOM) { custom }
        assertSame(custom, native[CustomReasoningParam.EXTRA_KEY])
        assertEquals("custom", native["reasoning_effort"])
        assertFalse(native.containsKey(CHARACTER_THINKING_EXTRA))
    }

    @Test
    fun characterModeTracksOnlyTheEnabledNonblankSystemStyleSection() {
        val ctx = PipelineContext(ChatRequest.forLocal("fake-mode", "哈哈"))
        assertFalse(ctx.usesCharacterThinking())
        ctx.promptStack.add(CHARACTER_THINKING_STYLE_KEY, "规则", 21)
        assertTrue(ctx.usesCharacterThinking())
        ctx.promptStack.disable(CHARACTER_THINKING_STYLE_KEY)
        assertFalse(ctx.usesCharacterThinking())
    }

    @Test
    fun nativeAndTaggedStreamSelectionMatchesFinalParsingRegardlessOfArrivalOrder() {
        for (tagged in listOf("", "<think></think>", "<think>  </think>", "<think>我想起小星。</think>")) {
            for (nativeFirst in listOf(false, true)) {
                val content = StringBuilder()
                val thinking = StringBuilder()
                val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks(
                    onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }))
                stream.callbacks.onStart()
                if (nativeFirst) stream.callbacks.onThinkingChunk("我先想想。")
                (tagged + "回答").forEach { stream.callbacks.onContentChunk(it.toString()) }
                if (!nativeFirst) stream.callbacks.onThinkingChunk("我先想想。")
                stream.finish()
                stream.finish()
                val final = characterThinkingResult(LocalAiResult(tagged + "回答", thinkingContent = "我先想想。"), true)
                assertEquals(final.content, content.toString())
                assertEquals(final.thinkingContent, thinking.toString())
            }
        }
    }

    @Test
    fun nativeThinkingOnlyResponseIsReleasedOnceAndCanBePersisted() {
        val thinking = StringBuilder()
        val stream = CharacterThinkingStream(true, LocalAiStreamCallbacks(onThinkingChunk = { thinking.append(it) }))
        stream.callbacks.onStart()
        stream.callbacks.onThinkingChunk("我先")
        stream.callbacks.onThinkingChunk("想想。")
        stream.finish()
        stream.finish()
        assertEquals("我先想想。", thinking.toString())
        assertTrue(hasCharacterThinking(mapOf("reasoning_content" to thinking.toString())))
    }
}
