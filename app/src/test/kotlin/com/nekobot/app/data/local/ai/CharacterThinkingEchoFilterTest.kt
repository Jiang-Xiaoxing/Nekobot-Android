package com.nekobot.app.data.local.ai

import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingEchoFilterTest {
    private val echo = "按thinking_style放在思考和回复里。" +
        "底层控制提示：接口没有原生思考字段时，在正文开头用「」包住第一人称内心想法。" +
        "我应该输出思考标签包裹的想法。"

    @Test
    fun reportedEchoIsRemovedAtEveryPossibleSplit() {
        for (split in 0..echo.length) {
            val out = StringBuilder()
            val filter = CharacterThinkingEchoFilter { out.append(it) }
            filter.append(echo.take(split))
            filter.append(echo.drop(split))
            assertEquals("", out.toString())
            filter.append("我有点担心他。")
            assertEquals("我有点担心他。", out.toString())
            filter.finish()
            assertEquals("我有点担心他。", out.toString())
        }
    }

    @Test
    fun echoCannotLeakByArrivingOneCharacterAtATime() {
        val out = StringBuilder()
        val filter = CharacterThinkingEchoFilter { out.append(it) }
        echo.forEach {
            filter.append(it.toString())
            assertEquals("", out.toString())
        }
        filter.append("我想他了。")
        assertEquals("我想他了。", out.toString())
    }

    @Test
    fun normalThoughtsRemainIncrementalAndWhitespaceAndAmbiguousEndingsArePreserved() {
        val out = StringBuilder()
        val filter = CharacterThinkingEchoFilter { out.append(it) }
        filter.append("我先")
        assertEquals("我先", out.toString())
        filter.append("想想")
        assertEquals("我先想想", out.toString())
        filter.append("。\n ")
        assertEquals("我先想想。\n ", out.toString())
        filter.append("他心里也有我")
        filter.finish()
        assertEquals("我先想想。\n 他心里也有我", out.toString())
    }

    @Test
    fun harmlessNearMatchesSurviveEverySplitWithoutBecomingPromptEcho() {
        val text = "我应该抱抱他。按他的意思走，在街上等我。思考一下从前的事。\nI miss him.\n"
        for (split in 0..text.length) {
            val out = StringBuilder()
            val filter = CharacterThinkingEchoFilter { out.append(it) }
            filter.append(text.take(split)); filter.append(text.drop(split)); filter.finish()
            assertEquals(text, out.toString())
        }
    }

    @Test
    fun unclosedEchoAtEndIsDroppedButNextRequestDoesNotInheritSuppression() {
        val first = StringBuilder()
        CharacterThinkingEchoFilter { first.append(it) }.apply {
            append("底层控制提示："); append("禁止复述的规则"); finish()
        }
        assertEquals("", first.toString())
        val second = StringBuilder()
        CharacterThinkingEchoFilter { second.append(it) }.apply { append("我在等他。"); finish() }
        assertEquals("我在等他。", second.toString())
    }
}
