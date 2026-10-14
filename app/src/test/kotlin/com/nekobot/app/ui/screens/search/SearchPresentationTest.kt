package com.nekobot.app.ui.screens.search

import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.*
import org.junit.Test

class SearchPresentationTest {
    @Test fun snippetIncludesLateMatches() {
        val snippet = searchSnippet("很长的内容".repeat(100) + "命中的消息" + "后文".repeat(100), "命中的消息")
        assertTrue(snippet.contains("命中的消息"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.length <= 102)
    }
    @Test fun highlightingTreatsQueryAsLiteralAndKeepsOriginalText() {
        val text = "API [a] api [a]"
        val highlighted = highlightedSearchText(text, "[a]", SpanStyle())
        assertEquals(text, highlighted.text)
        assertEquals(2, highlighted.spanStyles.size)
        assertEquals(2, highlightedSearchText(text, "api", SpanStyle()).spanStyles.size)
    }
}
