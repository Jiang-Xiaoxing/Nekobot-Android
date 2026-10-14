package com.nekobot.app.data.local

import org.junit.Assert.*
import org.junit.Test

class MessageSearchTermsTest {
    @Test fun substringTokensCoverChineseLatinEmojiAndLiteralPunctuation() {
        val text = "前缀甲乙🙂你好SomeAPIKey 100% 'quoted' 后缀"
        val document = MessageSearchTerms.document(text).split(' ').toSet()
        for (query in listOf("你好", "🙂你好", "API", "APIKey", "100%", "'quoted'")) {
            val tokens = MessageSearchTerms.query(query)!!.split(" ")
            assertTrue(query, document.containsAll(tokens))
            assertTrue(tokens.all { it.matches(Regex("[a-z0-9]+")) })
        }
        assertNull(MessageSearchTerms.query("甲"))
        assertEquals("", MessageSearchTerms.document("甲"))
    }
}
