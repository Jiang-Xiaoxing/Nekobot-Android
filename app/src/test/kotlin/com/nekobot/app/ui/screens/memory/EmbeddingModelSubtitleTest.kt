package com.nekobot.app.ui.screens.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingModelSubtitleTest {
    @Test
    fun identicalNameAndModelShowOneLine() {
        assertEquals("Qwen/Qwen3-Embedding-8B", embeddingModelSubtitle("Qwen/Qwen3-Embedding-8B", "Qwen/Qwen3-Embedding-8B"))
        assertEquals("Qwen/Qwen3-Embedding-8B", embeddingModelSubtitle(" Qwen/Qwen3-Embedding-8B ", "Qwen/Qwen3-Embedding-8B"))
    }

    @Test
    fun differentNameAndModelShowBoth() {
        assertEquals("我的向量\nQwen/Qwen3-Embedding-8B", embeddingModelSubtitle("我的向量", "Qwen/Qwen3-Embedding-8B"))
        assertEquals("m", embeddingModelSubtitle("", "m"))
    }
}