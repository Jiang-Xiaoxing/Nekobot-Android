package com.nekobot.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentPresentationTest {

    @Test
    fun flattenedPreviewBecomesReadableRows() {
        val rows = toolArgumentRows(mapOf("preview" to "{path=/sdcard/a.txt, limit=10}"))

        assertEquals(2, rows.size)
        assertEquals(ToolArgumentRow("path", "/sdcard/a.txt"), rows[0])
        assertEquals(ToolArgumentRow("limit", "10"), rows[1])
    }

    @Test
    fun commaInsideValueIsKeptWhenItIsNotANewParameter() {
        val rows = toolArgumentRows(
            mapOf("preview" to "{content=hello, world, path=/a.txt}")
        )

        assertEquals(2, rows.size)
        assertEquals("hello, world", rows[0].value)
        assertEquals(ToolArgumentRow("path", "/a.txt"), rows[1])
    }

    @Test
    fun nestedValuesStayIntact() {
        val rows = toolArgumentRows(
            mapOf("preview" to "{filter={status=done, size=3}, tags=[a, b], dry_run=true}")
        )

        assertEquals(3, rows.size)
        assertEquals("{status=done, size=3}", rows[0].value)
        assertEquals("[a, b]", rows[1].value)
        assertEquals("true", rows[2].value)
    }

    @Test
    fun jsonPreviewIsParsedFieldByField() {
        val rows = toolArgumentRows(
            mapOf("preview" to """{"path": "a.txt", "start_line": 1, "ok": true}""")
        )

        assertEquals(3, rows.size)
        assertEquals(ToolArgumentRow("path", "a.txt"), rows[0])
        assertEquals(ToolArgumentRow("start_line", "1"), rows[1])
        assertEquals(ToolArgumentRow("ok", "true"), rows[2])
    }

    @Test
    fun realArgumentsFromServerAreListed() {
        val rows = toolArgumentRows(
            mapOf("command" to "ls -la", "timeout" to 30, "nested" to mapOf("a" to 1))
        )

        assertEquals(3, rows.size)
        assertEquals(ToolArgumentRow("command", "ls -la"), rows[0])
        assertEquals(ToolArgumentRow("timeout", "30"), rows[1])
        assertEquals("nested", rows[2].name)
        assertTrue(rows[2].value.contains("\"a\""))
    }

    @Test
    fun nestedPreviewWrappingFromHistoryIsUnwrapped() {
        // 历史进度卡：参数被落库逻辑包了两层 preview，展示时仍要还原成参数列表
        val rows = toolArgumentRows(mapOf("preview" to "{preview={path=/sdcard/a.txt, limit=10}}"))

        assertEquals(2, rows.size)
        assertEquals(ToolArgumentRow("path", "/sdcard/a.txt"), rows[0])
        assertEquals(ToolArgumentRow("limit", "10"), rows[1])
    }

    @Test
    fun plainTextPreviewFallsBackToSingleValueRow() {
        val rows = toolArgumentRows(mapOf("preview" to "adb shell input keyevent 3"))

        assertEquals(1, rows.size)
        assertEquals("", rows[0].name)
        assertEquals("adb shell input keyevent 3", rows[0].value)
    }

    @Test
    fun emptyPayloadYieldsNoRowsSoCallerCanFallBackToJson() {
        assertTrue(toolArgumentRows(null).isEmpty())
        assertTrue(toolArgumentRows(emptyMap()).isEmpty())
        assertTrue(toolArgumentRows(mapOf("preview" to "")).isEmpty())
        assertTrue(toolArgumentRows(mapOf("preview" to "{}")).isEmpty())
    }

    @Test
    fun shortValuesFitInlineWhileLongOrMultilineValuesStack() {
        assertTrue(ToolArgumentRow("path", "/a.txt").fitsInline())
        assertTrue(!ToolArgumentRow("content", "x".repeat(200)).fitsInline())
        assertTrue(!ToolArgumentRow("content", "line1\nline2").fitsInline())
        // 没有参数名的裸文本只能堆叠展示
        assertTrue(!ToolArgumentRow("", "adb shell").fitsInline())
    }

    @Test
    fun strictJsonResultBecomesObjectNode() {
        val node = parseStepResultNode("""{"success": true, "count": 3}""")

        assertTrue(node is StepResultNode.Obj)
        node as StepResultNode.Obj
        assertEquals(2, node.entries.size)
        assertEquals("success" to StepResultNode.Text("true"), node.entries[0])
        assertEquals("count" to StepResultNode.Text("3"), node.entries[1])
    }

    @Test
    fun jsonArrayResultBecomesArrayNode() {
        val node = parseStepResultNode("""["a", "b"]""")

        assertTrue(node is StepResultNode.Arr)
        node as StepResultNode.Arr
        assertEquals(listOf("a", "b"), node.items.map { (it as StepResultNode.Text).text })
    }

    @Test
    fun flattenedPreviewResultIsParsedRecursively() {
        val node = parseStepResultNode("{success=true, data={status=done, size=3}, tags=[a, b]}")

        assertTrue(node is StepResultNode.Obj)
        node as StepResultNode.Obj
        assertEquals(3, node.entries.size)
        assertEquals("success" to StepResultNode.Text("true"), node.entries[0])
        // 嵌套的扁平化对象继续下钻成对象节点，而不是整段文本
        val data = node.entries[1].second
        assertTrue(data is StepResultNode.Obj)
        assertEquals(2, (data as StepResultNode.Obj).entries.size)
        // 嵌套数组下钻成数组节点
        val tags = node.entries[2].second
        assertTrue(tags is StepResultNode.Arr)
        assertEquals(2, (tags as StepResultNode.Arr).items.size)
    }

    @Test
    fun jsonStringValuesContainingJsonAreDrilledDown() {
        val node = parseStepResultNode("""{"data": "{\"a\": 1}"}""")

        assertTrue(node is StepResultNode.Obj)
        val data = (node as StepResultNode.Obj).entries.single().second
        assertTrue(data is StepResultNode.Obj)
        assertEquals("a" to StepResultNode.Text("1"), (data as StepResultNode.Obj).entries.single())
    }

    @Test
    fun plainTextResultIsKeptVerbatim() {
        val text = "{这不是键值格式的内容}"
        assertEquals(StepResultNode.Text(text), parseStepResultNode(text))
        assertEquals(
            StepResultNode.Text("adb shell input keyevent 3"),
            parseStepResultNode("adb shell input keyevent 3")
        )
    }

    @Test
    fun truncatedFlattenedResultKeepsLeadingBraceOutOfKeyNames() {
        // 预览超限被截断时右括号缺失，左括号不能混进第一个键名
        val node = parseStepResultNode("{success=true, data=被截断的长文本…")

        assertTrue(node is StepResultNode.Obj)
        val entries = (node as StepResultNode.Obj).entries
        assertEquals("success", entries[0].first)
        assertEquals("true", (entries[0].second as StepResultNode.Text).text)
        assertEquals("data", entries[1].first)
    }

    @Test
    fun truncatedJsonResultFallsBackToRawText() {
        val text = """{"success": true, "data": "被截断的内容"""
        val node = parseStepResultNode(text)

        // 残缺 JSON 不强行拆分，整段原文展示
        assertEquals(StepResultNode.Text(text), node)
    }

    @Test
    fun deepNestingIsCappedByDepthLimit() {
        var text = "底层"
        repeat(10) { text = "{\"n\": $text}" }
        var node: StepResultNode = parseStepResultNode(text)
        var levels = 0
        while (node is StepResultNode.Obj) {
            node = (node as StepResultNode.Obj).entries.single().second
            levels++
        }
        // 深度上限 6：超过后按纯文本展示，不再无限展开
        assertTrue(levels <= STEP_RESULT_MAX_DEPTH)
        assertTrue(node is StepResultNode.Text)
    }
}
