package com.nekobot.app.data.local.knowledge

import com.nekobot.app.data.model.KnowledgeSearchResult
import org.junit.Assert.*
import org.junit.Test

class KnowledgeScoringCacheTest {
    @Test fun cachedScoresMatchTheOriginalAlgorithmAndRefreshEditedChunks() {
        val cache = KnowledgeScoringCache(2)
        val query = LocalKnowledgeSearch.termCounts("中文 alpha")
        for (text in listOf("中文 beta", "alpha 中文内容", "unrelated")) {
            assertEquals(LocalKnowledgeSearch.lexicalScore("中文 alpha", text),
                LocalKnowledgeSearch.lexicalScore(query, cache.terms("same-id", text)), 0.00001f)
        }
        assertArrayEquals(floatArrayOf(1f, 0f), cache.vector("v", "[1,0]"), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), cache.vector("v", "[0,1]"), 0f)
        assertNull(cache.vector("v", "invalid"))
    }
    @Test fun lateCandidatesAreNotDroppedAndDuplicateChunksDoNotConsumeSlots() {
        val best = KnowledgeCandidates(2)
        for (index in 0..500) best.offer(KnowledgeSearchResult(
            id = "doc-$index", content = "content-$index", score = index / 1000f, chunkIndex = 0))
        best.offer(KnowledgeSearchResult(id = "winner", content = "answer", score = 1f, chunkIndex = 3))
        best.offer(KnowledgeSearchResult(id = "winner", content = "answer", score = 1f, chunkIndex = 1))
        assertEquals(listOf("winner", "doc-500"), best.results().map { it.id })
        assertEquals(1, best.results().first().chunkIndex)
    }
}
