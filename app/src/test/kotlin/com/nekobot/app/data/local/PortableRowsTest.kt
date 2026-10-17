package com.nekobot.app.data.local

import org.junit.Assert.*
import org.junit.Test

class PortableRowsTest {
    @Test fun readsOnlyRequestedTableRegardlessOfJsonOrderAndPreservesUnicode() {
        val raw = """{"tables":{"child":[{"id":"c","text":"原话\\n中文"}],"parent":[{"id":"p"}]},"extra":true}""".toByteArray()
        val ids = mutableListOf<String>()
        forEachPortableRow(raw, "parent") { ids += it["id"].asString }
        forEachPortableRow(raw, "child") { ids += it["id"].asString; assertEquals("原话\\n中文", it["text"].asString) }
        assertEquals(listOf("p", "c"), ids)
        forEachPortableRow(raw, "missing") { fail("Absent table") }
    }
    @Test fun visitsEveryRowOfLargeArchiveWithoutCollectingTree() {
        val raw = buildString { append("{\"tables\":{\"messages\":["); repeat(62000) { if(it>0)append(','); append("{\"id\":$it,\"text\":\"原话\"}") }; append("]}}") }.toByteArray()
        var count = 0
        forEachPortableRow(raw, "messages") { assertEquals(count++, it["id"].asInt) }
        assertEquals(62000, count)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingTables() {
        forEachPortableRow("{}".toByteArray(), "messages") { fail() }
    }
}
