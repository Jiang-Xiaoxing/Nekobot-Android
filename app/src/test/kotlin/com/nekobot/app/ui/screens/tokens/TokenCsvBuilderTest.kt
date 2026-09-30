package com.nekobot.app.ui.screens.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCsvBuilderTest {

    private fun record(
        id: String = "r1",
        input: Long = 1000,
        cachedInput: Long = 0,
        cacheWrite: Long = 0,
        sessionName: String = "会话A"
    ) = TokenRecordUi(
        id = id,
        timestamp = "2026-09-30 10:00:00",
        date = "2026-09-30",
        model = "配置模型",
        actualModel = "gpt-4o",
        purpose = "chat",
        source = "local",
        sessionId = "sess-1",
        sessionName = sessionName,
        input = input,
        output = 500,
        total = input + 500,
        cachedInput = cachedInput,
        cacheWrite = cacheWrite,
        cost = null,
        estimatedCostUsd = 0.001,
        durationMs = null,
        ttftMs = null,
        estimated = false
    )

    @Test
    fun csvHeaderContainsCacheColumns() {
        val header = buildTokenCsv(emptyList()).lineSequence().first()
        val columns = header.trimStart('\ufeff').split(",")
        assertEquals("缓存命中Token", columns[9])
        assertEquals("缓存写入Token", columns[10])
        assertEquals("输出Token", columns[11])
        assertEquals(18, columns.size)
    }

    @Test
    fun csvRowsCarryCacheValuesInMatchingColumns() {
        val csv = buildTokenCsv(
            listOf(
                record(cachedInput = 800, cacheWrite = 100),
                record(id = "r2", input = 300)
            )
        )
        val lines = csv.trim().split("\r\n")
        assertEquals(3, lines.size)
        val withCache = lines[1].split(",")
        val withoutCache = lines[2].split(",")
        assertEquals("800", withCache[9])
        assertEquals("100", withCache[10])
        assertEquals("0", withoutCache[9])
        assertEquals("0", withoutCache[10])
        // 行列数与表头一致
        assertEquals(18, withCache.size)
        assertEquals(18, withoutCache.size)
    }

    @Test
    fun csvFieldsWithCommasAreQuoted() {
        val csv = buildTokenCsv(listOf(record(sessionName = "会话,带逗号")))
        val dataLine = csv.trim().split("\r\n")[1]
        assertTrue(dataLine.contains("\"会话,带逗号\""))
    }
}
