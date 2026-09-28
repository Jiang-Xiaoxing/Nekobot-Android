package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ThinkingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeProgressCardsTest {

    private fun card(id: String, isComplete: Boolean) = ThinkingCard(
        id = id,
        content = "card-$id",
        steps = emptyList(),
        isComplete = isComplete,
        isAgent = true,
        timestamp = "2026-01-01T00:00:00Z",
        parentMessageId = "parent-1"
    )

    @Test
    fun merge_replacesSameIdAndAppendsNewId() {
        val existing = listOf(card("main", isComplete = false), card("sub_1", isComplete = false))
        val merged = mergeProgressCards(existing, card("main", isComplete = true))

        assertEquals(2, merged.size)
        assertTrue(merged.first { it.id == "main" }.isComplete)
        // 子代理卡不受主卡更新影响
        assertEquals("card-sub_1", merged.first { it.id == "sub_1" }.content)

        val appended = mergeProgressCards(existing, card("sub_2", isComplete = false))
        assertEquals(3, appended.size)
        assertEquals("sub_2", appended.last().id)
    }

    @Test
    fun merge_overCap_dropsOldestCompletedFirst() {
        val existing = (1..MAX_PERSISTED_PROGRESS_CARDS).map { index ->
            card("card_$index", isComplete = true)
        }.toMutableList()
        // 第 2 张标记为运行中：溢出时它必须被保留，最旧的已完成卡先被丢弃。
        existing[1] = card("card_2", isComplete = false)

        val merged = mergeProgressCards(existing, card("new", isComplete = false))

        assertEquals(MAX_PERSISTED_PROGRESS_CARDS, merged.size)
        assertTrue("运行中的卡不能被丢弃", merged.any { it.id == "card_2" })
        assertTrue("新卡必须保留", merged.any { it.id == "new" })
        assertFalse("最旧的已完成卡先被丢弃", merged.any { it.id == "card_1" })
    }

    @Test
    fun merge_extremeOverflow_truncatesTail() {
        val existing = (1..MAX_PERSISTED_PROGRESS_CARDS + 5).map { index ->
            card("card_$index", isComplete = false)
        }
        val merged = mergeProgressCards(existing, card("new", isComplete = false), maxCards = 8)

        assertEquals(8, merged.size)
        // 全部未完成无法按完成态丢弃：整体截尾，保留最新的卡片。
        assertEquals("new", merged.last().id)
    }
}
