package com.nekobot.app.ui.screens.settings

internal enum class MemoryDiffKind(val marker: String) {
    CONTEXT(" "), REMOVED("−"), ADDED("+")
}

internal data class MemoryDiffLine(
    val kind: MemoryDiffKind,
    val content: String,
    val oldNumber: Int?,
    val newNumber: Int?,
    val highlightStart: Int = 0,
    val highlightEnd: Int = content.length
)

internal sealed interface MemoryDiffRow {
    data class Line(val value: MemoryDiffLine) : MemoryDiffRow
    data class Omitted(val count: Int) : MemoryDiffRow
}

internal data class MemoryHistoryDiff(
    val lines: List<MemoryDiffLine>,
    val lineEndingsOnly: Boolean
) {
    val added: Int = lines.count { it.kind == MemoryDiffKind.ADDED }
    val removed: Int = lines.count { it.kind == MemoryDiffKind.REMOVED }
    val hasChanges: Boolean get() = added + removed > 0

    /** 默认定位到变更块，避免长记忆的未改正文把差异挤到首屏之外。 */
    fun rows(showUnchanged: Boolean, context: Int = 2): List<MemoryDiffRow> {
        if (showUnchanged) return lines.map { MemoryDiffRow.Line(it) }
        if (!hasChanges) return emptyList()
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { index, line ->
            if (line.kind != MemoryDiffKind.CONTEXT) {
                for (nearby in maxOf(0, index - context)..minOf(lines.lastIndex, index + context)) {
                    keep[nearby] = true
                }
            }
        }
        return buildList {
            var omitted = 0
            lines.forEachIndexed { index, line ->
                if (keep[index]) {
                    if (omitted > 0) add(MemoryDiffRow.Omitted(omitted))
                    omitted = 0
                    add(MemoryDiffRow.Line(line))
                } else omitted++
            }
            if (omitted > 0) add(MemoryDiffRow.Omitted(omitted))
        }
    }
}

/** 先剥离相同的首尾，再对变更区域做 LCS；换行格式单独提示，不伪造全文替换。 */
internal fun buildMemoryHistoryDiff(before: String, after: String): MemoryHistoryDiff {
    fun split(value: String) = if (value.isEmpty()) emptyList()
        else value.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    val old = split(before)
    val new = split(after)
    var prefix = 0
    while (prefix < minOf(old.size, new.size) && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(old.size, new.size) - prefix &&
        old[old.lastIndex - suffix] == new[new.lastIndex - suffix]) suffix++

    val kinds = ArrayList<Pair<MemoryDiffKind, String>>(old.size + new.size)
    fun emit(kind: MemoryDiffKind, text: String) { kinds += kind to text }
    repeat(prefix) { emit(MemoryDiffKind.CONTEXT, old[it]) }
    val oldEnd = old.size - suffix
    val newEnd = new.size - suffix
    val oldCount = oldEnd - prefix
    val newCount = newEnd - prefix
    if (oldCount.toLong() * newCount > 500_000L) {
        for (index in prefix until oldEnd) emit(MemoryDiffKind.REMOVED, old[index])
        for (index in prefix until newEnd) emit(MemoryDiffKind.ADDED, new[index])
    } else {
        val lcs = Array(oldCount + 1) { IntArray(newCount + 1) }
        for (i in oldCount - 1 downTo 0) for (j in newCount - 1 downTo 0) {
            lcs[i][j] = if (old[prefix + i] == new[prefix + j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < oldCount || j < newCount) {
            when {
                i < oldCount && j < newCount && old[prefix + i] == new[prefix + j] -> {
                    emit(MemoryDiffKind.CONTEXT, old[prefix + i]); i++; j++
                }
                i < oldCount && (j == newCount || lcs[i + 1][j] >= lcs[i][j + 1]) -> {
                    emit(MemoryDiffKind.REMOVED, old[prefix + i++])
                }
                else -> emit(MemoryDiffKind.ADDED, new[prefix + j++])
            }
        }
    }
    repeat(suffix) { emit(MemoryDiffKind.CONTEXT, old[oldEnd + it]) }

    var oldNumber = 0
    var newNumber = 0
    val lines = kinds.map { (kind, text) ->
        MemoryDiffLine(kind, text,
            if (kind != MemoryDiffKind.ADDED) ++oldNumber else null,
            if (kind != MemoryDiffKind.REMOVED) ++newNumber else null)
    }.toMutableList()

    // 同一变更块中的替换行再突出共同前后缀之间的具体修改。
    var start = 0
    while (start < lines.size) {
        if (lines[start].kind == MemoryDiffKind.CONTEXT) { start++; continue }
        var end = start
        while (end < lines.size && lines[end].kind != MemoryDiffKind.CONTEXT) end++
        val removed = (start until end).filter { lines[it].kind == MemoryDiffKind.REMOVED }
        val added = (start until end).filter { lines[it].kind == MemoryDiffKind.ADDED }
        removed.zip(added).forEach { (oldIndex, newIndex) ->
            val oldLine = lines[oldIndex].content
            val newLine = lines[newIndex].content
            val sharedStart = oldLine.commonPrefixWith(newLine).length
            val sharedEnd = oldLine.drop(sharedStart).commonSuffixWith(newLine.drop(sharedStart)).length
            lines[oldIndex] = lines[oldIndex].copy(highlightStart = sharedStart, highlightEnd = oldLine.length - sharedEnd)
            lines[newIndex] = lines[newIndex].copy(highlightStart = sharedStart, highlightEnd = newLine.length - sharedEnd)
        }
        start = end
    }
    return MemoryHistoryDiff(lines, before != after && old == new)
}
