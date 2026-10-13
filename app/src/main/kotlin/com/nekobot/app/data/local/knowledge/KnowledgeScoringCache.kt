package com.nekobot.app.data.local.knowledge

import com.google.gson.Gson
import com.nekobot.app.data.model.KnowledgeSearchResult
import java.util.PriorityQueue

/** 有界缓存只保存切片的计算结果；原文变化时重新计算。 */
internal class KnowledgeScoringCache(private val capacity: Int = 256) {
    private data class Terms(val text: String, val counts: Map<String, Int>)
    private data class Vector(val raw: String, val values: FloatArray?)
    private fun <T> cache() = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > capacity
    }
    private val terms = cache<Terms>()
    private val vectors = cache<Vector>()
    private val gson = Gson()

    @Synchronized
    fun terms(id: String, text: String): Map<String, Int> {
        terms[id]?.takeIf { it.text == text }?.let { return it.counts }
        return LocalKnowledgeSearch.termCounts(text).also { terms[id] = Terms(text, it) }
    }

    @Synchronized
    fun vector(id: String, raw: String?): FloatArray? {
        if (raw.isNullOrBlank()) return null
        vectors[id]?.takeIf { it.raw == raw }?.let { return it.values }
        val value = runCatching { gson.fromJson(raw, FloatArray::class.java) }
            .getOrNull()?.takeIf { values -> values.all(Float::isFinite) }
        vectors[id] = Vector(raw, value)
        return value
    }
}

/** 扫描所有有权限的切片，但只在内存里保留最佳候选，避免提前截断语义召回。 */
internal class KnowledgeCandidates(private val capacity: Int) {
    private val order = compareByDescending<KnowledgeSearchResult> { it.score ?: 0f }
        .thenBy { it.id }.thenBy { it.chunkIndex ?: 0 }
    private val queue = PriorityQueue(order.reversed())
    fun offer(value: KnowledgeSearchResult) {
        val duplicate = queue.firstOrNull { it.id == value.id && it.content == value.content }
        if (duplicate != null) {
            if (order.compare(value, duplicate) >= 0) return
            queue.remove(duplicate)
        }
        if (queue.size >= capacity.coerceAtLeast(1)) {
            if (order.compare(value, queue.peek()) >= 0) return
            queue.poll()
        }
        queue.add(value)
    }
    fun results(): List<KnowledgeSearchResult> = queue.sortedWith(order)
}
