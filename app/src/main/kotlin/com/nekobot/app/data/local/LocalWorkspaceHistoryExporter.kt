package com.nekobot.app.data.local

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.MessageDao
import com.nekobot.app.data.local.db.SessionDao
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 数据库原话的工作区只读副本，不修改消息、不触发模型，也不在每轮对话自动运行。 */
internal class LocalWorkspaceHistoryExporter(
    private val filesDir: File,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao
) {
    companion object {
        private const val PAGE_SIZE = 64
        // 同进程的手动重建串行化，避免两个重建同时替换索引并误清理彼此的分块。
        private val rebuildMutex = Mutex()
    }

    suspend fun rebuild(
        sessionId: String,
        onProgress: ((Long) -> Unit)? = null
    ): HistoryExportResult = rebuildMutex.withLock {
        withContext(Dispatchers.IO) {
            val expectedWorkspace = checkedWorkspacePath(sessionId)
            require(sessionDao.getById(sessionId) != null) { "会话不存在" }
            val workspace = LocalWorkspaceStorage.resolve(filesDir, sessionId)
                ?: error("无法解析当前会话工作区")
            require(workspace.canonicalFile == expectedWorkspace) { "会话工作区路径越界" }
            require(!Files.isSymbolicLink(File(workspace, "history").toPath())) { "历史副本不能是链接" }
            val history = File(workspace, "history").canonicalFile
            require(history.parentFile == workspace.canonicalFile) { "历史副本路径越界" }

            val writer = WorkspaceHistoryChunkWriter(history, sessionId)
            try {
                var page = messageDao.firstVisibleRow(sessionId)?.let { listOf(it) } ?: emptyList()
                var processed = 0L
                while (page.isNotEmpty()) {
                    for (row in page) {
                        val nextMessage = row.message
                        writer.append(
                            HistoryCopyMessage(
                                sessionId = nextMessage.sessionId,
                                id = nextMessage.id,
                                role = nextMessage.role,
                                content = nextMessage.content,
                                timestamp = nextMessage.timestamp,
                                createdAt = nextMessage.createdAt
                            )
                        )
                        processed++
                    }
                    onProgress?.invoke(processed)
                    val last = page.last()
                    page = messageDao.listVisibleRowsAfter(
                        sessionId, last.message.createdAt, last.rowId, PAGE_SIZE
                    )
                }
                writer.publish()
            } finally {
                writer.discardIfUnpublished()
            }
        }
    }

    /** 原话被编辑、删除、清空或会话已删除时调用；只清当前会话的 history 子目录。 */
    suspend fun invalidate(sessionId: String): Boolean = rebuildMutex.withLock {
        withContext(Dispatchers.IO) {
            val workspace = checkedWorkspacePath(sessionId)
            val rawHistory = File(workspace, "history")
            require(!Files.isSymbolicLink(rawHistory.toPath())) { "历史副本不能是链接" }
            val history = rawHistory.canonicalFile
            require(history.parentFile == workspace && history.name == "history") { "历史副本路径越界" }
            if (!history.exists()) false else {
                deleteTreeWithoutFollowingLinks(history)
                true
            }
        }
    }

    private fun checkedWorkspacePath(sessionId: String): File {
        require(isSingleSessionName(sessionId)) { "无效的会话 ID" }
        val rawBase = File(filesDir, "workspace")
        require(!Files.isSymbolicLink(rawBase.toPath())) { "工作区根不能是链接" }
        val base = rawBase.canonicalFile
        require(base.parentFile == filesDir.canonicalFile) { "工作区根路径越界" }
        val rawWorkspace = File(base, sessionId)
        require(!Files.isSymbolicLink(rawWorkspace.toPath())) { "会话工作区不能是链接" }
        val workspace = rawWorkspace.canonicalFile
        require(workspace.parentFile == base && workspace.name == sessionId) { "会话工作区路径越界" }
        return workspace
    }

    private fun isSingleSessionName(value: String): Boolean =
        value.isNotBlank() && value == value.trim() && value != "." && value != ".." &&
            value != "shared" && '/' !in value && '\\' !in value
}

internal data class HistoryCopyMessage(
    val sessionId: String,
    val id: String,
    val role: String,
    val content: String,
    val timestamp: String,
    val createdAt: String
)

internal data class HistoryExportResult(
    val indexFile: File,
    val generation: String,
    val messageCount: Long,
    val partCount: Long,
    val chunkCount: Int,
    val cleanupWarning: String? = null
)

/**
 * 独立于 Room 的受限大小写入器：chunk 与索引页都最多 128 KiB。
 * index.json 是唯一已发布指针；构建时先写临时代，最后原子替换指针。
 */
internal class WorkspaceHistoryChunkWriter(
    historyDirectory: File,
    private val sessionId: String,
    private val beforePublish: () -> Unit = {}
) {
    private companion object {
        const val MAX_FILE_BYTES = 128 * 1024
        const val MAX_CONTENT_CHARS = 8 * 1024
        val UTF8 = StandardCharsets.UTF_8
        val GENERATION_PATTERN = Regex("g-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }

    private val history = historyDirectory.canonicalFile
    private val chunks = File(history, "chunks").canonicalFile
    private val generation = "g-${UUID.randomUUID()}"
    private val staging = File(chunks, ".building-${UUID.randomUUID()}").canonicalFile
    private val publishedGeneration = File(chunks, generation).canonicalFile
    private val index = File(history, "index.json").canonicalFile
    private val tempIndex = File(history, ".index-${UUID.randomUUID()}.tmp").canonicalFile
    private val chunkData = ByteArrayOutputStream()
    private val chunkIndex = ByteArrayOutputStream()
    private val indexPage = ByteArrayOutputStream()
    private val pages = JsonArray()

    private var chunkCount = 0
    private var pageCount = 0
    private var chunkPartCount = 0
    private var firstMessageId: String? = null
    private var lastMessageId: String? = null
    private var firstTimestamp: String? = null
    private var lastTimestamp: String? = null
    private var committed = false
    private var messageCount = 0L
    private var partCount = 0L

    init {
        require(sessionId.isNotBlank())
        require(!Files.isSymbolicLink(File(history, "chunks").toPath())) { "分块目录不能是链接" }
        require(!Files.isSymbolicLink(File(history, "index.json").toPath())) { "根索引不能是链接" }
        require(chunks.parentFile == history) { "分块目录越界" }
        require(staging.parentFile == chunks && publishedGeneration.parentFile == chunks) { "分块路径越界" }
        require(index.parentFile == history && tempIndex.parentFile == history) { "索引路径越界" }
        check(history.isDirectory || history.mkdirs()) { "无法建立历史副本目录" }
        check(chunks.isDirectory || chunks.mkdirs()) { "无法建立分块目录" }
        check(staging.mkdir()) { "无法建立副本临时目录" }
    }

    fun append(message: HistoryCopyMessage) {
        check(!committed) { "历史副本已经发布" }
        require(message.sessionId == sessionId) { "禁止混入其他会话消息" }
        require(message.id.isNotBlank() && message.role in setOf("user", "assistant")) { "非可见原话消息" }

        val total = countParts(message.content)
        var start = 0
        for (part in 0 until total) {
            val end = if (message.content.isEmpty()) 0 else nextPartEnd(message.content, start)
            val content = message.content.substring(start, end)
            val record = JsonObject().apply {
                addProperty("message_id", message.id)
                addProperty("role", message.role)
                addProperty("timestamp", message.timestamp)
                addProperty("created_at", message.createdAt)
                addProperty("part_index", part)
                addProperty("part_count", total)
                addProperty("content", content)
            }
            val dataLine = jsonLine(record)
            require(dataLine.size <= MAX_FILE_BYTES) { "原话分片元信息过长" }
            var offset = chunkData.size()
            var indexLine = indexLine(message, part, total, offset, dataLine.size)
            require(indexLine.size <= MAX_FILE_BYTES) { "原话索引元信息过长" }
            if (offset + dataLine.size > MAX_FILE_BYTES || chunkIndex.size() + indexLine.size > MAX_FILE_BYTES) {
                flushChunk()
                offset = 0
                indexLine = indexLine(message, part, total, offset, dataLine.size)
            }
            if (chunkPartCount == 0) {
                firstMessageId = message.id
                firstTimestamp = message.timestamp
            }
            chunkData.write(dataLine)
            chunkIndex.write(indexLine)
            chunkPartCount++
            lastMessageId = message.id
            lastTimestamp = message.timestamp
            partCount++
            start = end
        }
        messageCount++
    }

    fun publish(): HistoryExportResult {
        check(!committed) { "历史副本已经发布" }
        flushChunk()
        flushIndexPage()
        val oldGeneration = readActiveGeneration()
        val manifest = JsonObject().apply {
            addProperty("format", "nekobot-history-copy-v1")
            addProperty("source", "local_messages")
            addProperty("session_id", sessionId)
            addProperty("generation", generation)
            addProperty("generated_at", Instant.now().toString())
            addProperty("message_count", messageCount)
            addProperty("part_count", partCount)
            addProperty("chunk_count", chunkCount)
            addProperty("max_file_bytes", MAX_FILE_BYTES)
            add("pages", pages)
        }
        val manifestBytes = manifest.toString().toByteArray(UTF8)
        require(manifestBytes.size <= MAX_FILE_BYTES) { "根索引超过 128 KiB" }

        beforePublish()
        check(!publishedGeneration.exists()) { "目标副本代已存在" }
        moveUnpublishedDirectory(staging, publishedGeneration)
        FileOutputStream(tempIndex).use { stream ->
            stream.write(manifestBytes)
            stream.fd.sync()
        }
        // 同一目录中原子替换唯一公开入口。失败时旧索引及其所有旧块保留。
        Files.move(
            tempIndex.toPath(), index.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
        )
        committed = true
        val warning = cleanupOldGeneration(oldGeneration)
        return HistoryExportResult(index, generation, messageCount, partCount, chunkCount, warning)
    }

    fun discardIfUnpublished() {
        if (committed) return
        tempIndex.takeIf { it.exists() }?.delete()
        deleteOwnGeneration(staging)
        deleteOwnGeneration(publishedGeneration)
    }

    private fun indexLine(message: HistoryCopyMessage, part: Int, total: Int, offset: Int, length: Int): ByteArray =
        jsonLine(JsonObject().apply {
            addProperty("message_id", message.id)
            addProperty("timestamp", message.timestamp)
            addProperty("created_at", message.createdAt)
            addProperty("part_index", part)
            addProperty("part_count", total)
            addProperty("byte_offset", offset)
            addProperty("byte_length", length)
        })

    private fun flushChunk() {
        if (chunkPartCount == 0) return
        chunkCount++
        val stem = "chunk-${chunkCount.toString().padStart(6, '0')}"
        val dataName = "$stem.jsonl"
        val indexName = "$stem.idx.jsonl"
        val dataBytes = chunkData.toByteArray()
        val indexBytes = chunkIndex.toByteArray()
        writeBounded(File(staging, dataName), dataBytes)
        writeBounded(File(staging, indexName), indexBytes)
        val descriptor = JsonObject().apply {
            addProperty("data_file", dataName)
            addProperty("index_file", indexName)
            addProperty("first_message_id", firstMessageId)
            addProperty("last_message_id", lastMessageId)
            addProperty("first_timestamp", firstTimestamp)
            addProperty("last_timestamp", lastTimestamp)
            addProperty("part_count", chunkPartCount)
            addProperty("data_bytes", dataBytes.size)
            addProperty("index_bytes", indexBytes.size)
        }
        val descriptorLine = jsonLine(descriptor)
        require(descriptorLine.size <= MAX_FILE_BYTES) { "分块索引元信息过长" }
        if (indexPage.size() + descriptorLine.size > MAX_FILE_BYTES) flushIndexPage()
        indexPage.write(descriptorLine)

        chunkData.reset()
        chunkIndex.reset()
        chunkPartCount = 0
        firstMessageId = null
        lastMessageId = null
        firstTimestamp = null
        lastTimestamp = null
    }

    private fun flushIndexPage() {
        if (indexPage.size() == 0) return
        pageCount++
        val pageName = "page-${pageCount.toString().padStart(6, '0')}.idx.jsonl"
        writeBounded(File(staging, pageName), indexPage.toByteArray())
        pages.add(pageName)
        indexPage.reset()
    }

    private fun readActiveGeneration(): String? = runCatching {
        if (!index.isFile || index.length() > MAX_FILE_BYTES) return@runCatching null
        val old = JsonParser.parseString(index.readText(UTF8)).asJsonObject
        if (old.get("session_id")?.asString != sessionId) return@runCatching null
        old.get("generation")?.asString?.takeIf { GENERATION_PATTERN.matches(it) }
    }.getOrNull()

    private fun cleanupOldGeneration(oldGeneration: String?): String? {
        if (oldGeneration == null || oldGeneration == generation) return null
        val rawDirectory = File(chunks, oldGeneration)
        if (Files.isSymbolicLink(rawDirectory.toPath())) return "旧副本代是链接，未清理：$oldGeneration"
        val oldDirectory = rawDirectory.canonicalFile
        if (oldDirectory.parentFile != chunks || !oldDirectory.exists()) return null
        return runCatching { deleteTreeWithoutFollowingLinks(oldDirectory) }
            .fold(onSuccess = { null }, onFailure = { "旧历史副本未能清理：$oldGeneration" })
    }

    private fun deleteOwnGeneration(directory: File) {
        if (directory.parentFile == chunks && directory.exists()) {
            runCatching { deleteTreeWithoutFollowingLinks(directory) }
        }
    }

    private fun moveUnpublishedDirectory(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // 未发布的代可以普通移动；公开索引仍要求原子替换。
            Files.move(from.toPath(), to.toPath())
        }
    }

    private fun writeBounded(file: File, bytes: ByteArray) {
        require(bytes.size <= MAX_FILE_BYTES) { "历史副本文件超过 128 KiB" }
        require(!Files.isSymbolicLink(file.toPath())) { "历史副本文件不能是链接" }
        require(file.canonicalFile.parentFile == staging) { "历史副本文件路径越界" }
        FileOutputStream(file).use { it.write(bytes) }
    }

    private fun jsonLine(value: JsonObject): ByteArray = (value.toString() + "\n").toByteArray(UTF8)

    private fun countParts(content: String): Int {
        if (content.isEmpty()) return 1
        var count = 0
        var start = 0
        while (start < content.length) {
            start = nextPartEnd(content, start)
            count++
        }
        return count
    }

    private fun nextPartEnd(content: String, start: Int): Int {
        var end = minOf(start + MAX_CONTENT_CHARS, content.length)
        if (end < content.length && Character.isHighSurrogate(content[end - 1]) && Character.isLowSurrogate(content[end])) {
            end--
        }
        return end
    }
}

/** Files.walkFileTree 默认不跟随符号链接，只删除经过校验的目标树本身。 */
internal fun deleteTreeWithoutFollowingLinks(root: File) {
    Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
