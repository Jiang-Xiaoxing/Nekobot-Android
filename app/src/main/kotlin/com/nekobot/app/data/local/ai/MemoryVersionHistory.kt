package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class MemoryVersion(
    val id: String,
    val content: String,
    val previousContent: String,
    val createdAt: String,
    val source: String,
    val sourceSessionId: String? = null,
    val sourceMessageId: String? = null,
    val restoredFrom: String? = null
)

class MemoryConflictException : IllegalStateException("Memory changed since it was read")

/** 待提交记录与记忆文件配合恢复，避免崩溃后丢失最近一次可撤销的修改。 */
internal class MemoryVersionHistory(private val directory: File) {
    private val gson = Gson()
    private fun file(id: String, suffix: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        return File(directory, "$id.$suffix")
    }

    fun prepare(version: MemoryVersion) {
        check(directory.isDirectory || directory.mkdirs())
        FileOutputStream(file(version.id, "pending")).use {
            it.write(gson.toJson(version).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    }

    fun commit(id: String) {
        val source = file(id, "pending")
        if (source.isFile) move(source, file(id, "json"))
        prune()
    }

    fun discard(id: String) { file(id, "pending").delete() }

    fun list(currentContent: String): List<MemoryVersion> {
        directory.listFiles().orEmpty().filter { it.extension == "pending" }.forEach { pending ->
            val value = read(pending)
            if (value != null && value.content == currentContent) commit(value.id) else pending.delete()
        }
        return directory.listFiles().orEmpty().filter { it.extension == "json" }
            .mapNotNull(::read).sortedWith(compareByDescending<MemoryVersion> { Instant.parse(it.createdAt) }.thenByDescending { it.id })
    }

    private fun read(file: File): MemoryVersion? = runCatching {
        gson.fromJson(file.readText(Charsets.UTF_8), MemoryVersion::class.java).takeIf {
            Instant.parse(it.createdAt)
            it.id == file.nameWithoutExtension && it.createdAt.isNotBlank() &&
                it.content.length <= GlobalAgentMemoryStore.MAX_CONTENT_CHARS &&
                it.previousContent.length <= GlobalAgentMemoryStore.MAX_CONTENT_CHARS
        }
    }.getOrNull()

    private fun prune() {
        directory.listFiles().orEmpty().filter { it.extension == "json" }
            .mapNotNull { file -> read(file)?.let { file to it.createdAt } }
            .sortedByDescending { Instant.parse(it.second) }.drop(50).forEach { it.first.delete() }
    }

    private fun move(source: File, target: File) {
        runCatching { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    fun newVersion(content: String, previous: String, source: String,
                   sessionId: String?, messageId: String?, restoredFrom: String?) =
        MemoryVersion(UUID.randomUUID().toString(), content, previous, Instant.now().toString(),
            source, sessionId, messageId, restoredFrom)
}

/** 不重叠的编辑可合并；冲突保留用户草稿，让用户明确处理。 */
internal fun mergeMemoryEdits(base: String, draft: String, latest: String): String? {
    if (draft == base || draft == latest) return latest
    if (latest == base) return draft
    val lines = base.split('\n')
    data class Edit(val start: Int, val end: Int, val replacement: List<String>)
    fun edit(value: String): Edit {
        val next = value.split('\n')
        var start = 0
        while (start < minOf(lines.size, next.size) && lines[start] == next[start]) start++
        var suffix = 0
        while (suffix < minOf(lines.size, next.size) - start && lines[lines.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        return Edit(start, lines.size - suffix, next.subList(start, next.size - suffix))
    }
    val left = edit(draft)
    val right = edit(latest)
    fun overlaps(a: Edit, b: Edit): Boolean = if (a.start == a.end)
        a.start >= b.start && a.start < b.end || a.start == b.start
    else a.start < b.end && b.start < a.end
    if (overlaps(left, right) || overlaps(right, left)) return null
    return lines.toMutableList().apply {
        listOf(left, right).sortedByDescending { it.start }.forEach {
            subList(it.start, it.end).clear()
            addAll(it.start, it.replacement)
        }
    }.joinToString("\n")
}
