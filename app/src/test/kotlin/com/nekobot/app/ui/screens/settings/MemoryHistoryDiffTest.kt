package com.nekobot.app.ui.screens.settings

import org.junit.Assert.*
import org.junit.Test

class MemoryHistoryDiffTest {
    @Test fun changesNearTheEndAreVisibleWithoutScrollingThroughTheWholeMemory() {
        val before = (0 until 200).map { "line $it" }
        val after = before.toMutableList().apply { this[198] = "changed" }
        val diff = buildMemoryHistoryDiff(before.joinToString("\n"), after.joinToString("\n"))
        val rows = diff.rows(false)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(MemoryDiffRow.Omitted(196), rows.first())
        assertTrue(rows.size < 10)
        val visible = rows.filterIsInstance<MemoryDiffRow.Line>().map { it.value }
        assertTrue(visible.any { it.kind == MemoryDiffKind.REMOVED && it.content == "line 198" })
        assertTrue(visible.any { it.kind == MemoryDiffKind.ADDED && it.content == "changed" })
        assertEquals(201, diff.rows(true).size)
    }

    @Test fun repeatedAndBlankLinesCanReconstructBothVersions() {
        val before = "same\nrepeat\n\nrepeat\nold\n"
        val after = "same\n\nrepeat\nnew\nextra\n"
        val diff = buildMemoryHistoryDiff(before, after)
        assertEquals(before, diff.lines.filter { it.kind != MemoryDiffKind.ADDED }.joinToString("\n") { it.content })
        assertEquals(after, diff.lines.filter { it.kind != MemoryDiffKind.REMOVED }.joinToString("\n") { it.content })
        assertEquals((1..before.split('\n').size).toList(), diff.lines.mapNotNull { it.oldNumber })
        assertEquals((1..after.split('\n').size).toList(), diff.lines.mapNotNull { it.newNumber })
    }

    @Test fun replacementHighlightsOnlyTheChangedWords() {
        val diff = buildMemoryHistoryDiff("喜欢喝咖啡。", "喜欢喝茶。")
        val removed = diff.lines.single { it.kind == MemoryDiffKind.REMOVED }
        val added = diff.lines.single { it.kind == MemoryDiffKind.ADDED }
        assertEquals("咖啡", removed.content.substring(removed.highlightStart, removed.highlightEnd))
        assertEquals("茶", added.content.substring(added.highlightStart, added.highlightEnd))
    }

    @Test fun lineEndingOnlyChangesDoNotClaimEveryLineWasReplaced() {
        val diff = buildMemoryHistoryDiff("one\r\ntwo\r\n", "one\ntwo\n")
        assertTrue(diff.lineEndingsOnly)
        assertFalse(diff.hasChanges)
        assertEquals(0, diff.added)
        assertEquals(0, diff.removed)
        assertTrue(diff.rows(false).isEmpty())
        assertEquals(3, diff.rows(true).size)
    }

    @Test fun aSmallEditInALargeMemoryRemainsAnExactDiff() {
        val before = (0 until 2000).map { "entry $it" }
        val after = before.toMutableList().apply { this[1000] = "updated entry" }
        val diff = buildMemoryHistoryDiff(before.joinToString("\n"), after.joinToString("\n"))
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(1999, diff.lines.count { it.kind == MemoryDiffKind.CONTEXT })
    }

    @Test fun aCompleteRewriteKeepsEveryLineWithinTheMatrixLimit() {
        val before = (0 until 900).joinToString("\n") { "old $it" }
        val after = (0 until 900).joinToString("\n") { "new $it" }
        val diff = buildMemoryHistoryDiff(before, after)
        assertEquals(900, diff.added)
        assertEquals(900, diff.removed)
        assertEquals(before, diff.lines.filter { it.kind == MemoryDiffKind.REMOVED }.joinToString("\n") { it.content })
        assertEquals(after, diff.lines.filter { it.kind == MemoryDiffKind.ADDED }.joinToString("\n") { it.content })
    }

    @Test fun emptyMemorySupportsInitialCreationAndCompleteDeletion() {
        val creation = buildMemoryHistoryDiff("", "new")
        assertEquals(1, creation.added)
        assertEquals(0, creation.removed)
        assertEquals(1, creation.lines.single().newNumber)
        assertNull(creation.lines.single().oldNumber)
        val deletion = buildMemoryHistoryDiff("old", "")
        assertEquals(0, deletion.added)
        assertEquals(1, deletion.removed)
        assertFalse(buildMemoryHistoryDiff("", "").hasChanges)
    }
}
