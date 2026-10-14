package com.nekobot.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RemoteMessagePageTest {
    @Test fun readsServerPaginationWithoutDependingOnGsonNamingPolicy() {
        val page = parseRemoteMessagePage(JsonParser.parseString("""{"messages":[{"id":"m","content":"hello"}],"has_more":true}"""), Gson())
        assertTrue(page.hasMore)
        assertEquals("m", page.messages.single().id)
    }
}
