package com.nekobot.app.data.local

import android.content.Context
import android.net.Uri
import com.google.gson.JsonObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal data class TransferAsset(
    val source: String, val hash: String, val size: Long, val name: String,
    val references: List<String>
)
internal data class TransferFiles(val assets: List<TransferAsset>, val missing: List<String>, val remote: Int) {
    val bytes get() = assets.sumOf { it.size }
}

/** 仅复制显式附件与媒体路径。聊天中的任意绝对路径不能成为读取私密文件的入口。 */
internal class CompanionTransferFiles(private val context: Context) {
    private val fields = setOf("avatar", "portrait", "character_avatar", "sender_avatar", "cover_url", "audio_url", "reference_image_path", "file_path")
    private val fileTag = Regex("\\[File: ([^\\]\\r\\n]+)]")
    private val link = Regex("(?:file:/+|content://)[^\\s<>\\\"'()]+")
    private val remoteLink = Regex("https?://[^\\s<>\\\"'()]+")

    fun inspect(directory: File, snapshot: TransferSnapshot): TransferFiles {
        val refs = linkedMapOf<String, MutableSet<String>>()
        val missing = linkedSetOf<String>()
        val remote = mutableSetOf<String>()
        fun add(reference: String, source: String) {
            if (source.startsWith("http://") || source.startsWith("https://")) { remote += source; return }
            if (source.startsWith("fas ") || source.startsWith("fa-") || source.isBlank()) return
            val allowed = source.startsWith("content://") || safeFile(source, snapshot.sessionIds) != null
            if (!allowed) { missing += "无法独立复制：$reference"; return }
            refs.getOrPut(source) { linkedSetOf() }.add(reference)
        }
        for (table in snapshot.counts.keys) for (row in transferRows(directory, table)) {
            val sessionId = row.text("session_id") ?: snapshot.sourceId
            for (field in fields) row.text(field)?.let { path ->
                if (path.startsWith("/") || path.startsWith("file:") || path.startsWith("content:") || path.startsWith("http")) add(path, path)
            }
            // 工具执行历史、系统提示与后台配置只是原文；不据此抓取文件或执行任何动作。
            for (field in listOf("content", "first_message", "greeting")) row.text(field)?.let { content ->
                fileTag.findAll(content).forEach { match ->
                    val name = match.groupValues[1]
                    val workspace = File(context.filesDir, "workspace/$sessionId")
                    val primary = File(workspace, name)
                    val legacy = File(context.filesDir, "agent_workspaces/$sessionId/$name")
                    add("$sessionId\u0000${match.value}", (if (primary.isFile) primary else legacy).path)
                }
                link.findAll(content).forEach { add(it.value, it.value) }
                remoteLink.findAll(content).forEach { remote += it.value }
            }
        }
        val assets = refs.mapNotNull { (source, references) ->
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                open(source).buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count); size += count }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val original = Uri.parse(source).lastPathSegment.orEmpty().substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]"), "_").takeLast(70).ifBlank { "attachment" }
                TransferAsset(source, hash, size, "${hash.take(16)}-$original", references.toList())
            } catch (_: Exception) { missing += "附件缺失或无法读取：${references.first().substringAfter('\u0000')}"; null }
        }
        return TransferFiles(assets, missing.toList(), remote.size)
    }

    fun backup(files: TransferFiles, directory: File, target: File) {
        require(files.missing.isEmpty()) { "有附件无法读取，请先修复后重新预览" }
        val backup = File(directory, "attachments").apply { check(mkdirs() || isDirectory) }
        check(target.mkdirs() || target.isDirectory) { "无法准备新会话的附件目录" }
        for (asset in files.assets) {
            val saved = File(backup, asset.name)
            open(asset.source).use { input -> saved.outputStream().use { input.copyTo(it) } }
            check(saved.length() == asset.size && transferHash(saved) == asset.hash) { "附件已变化，请重新预览" }
            val output = File(target, asset.name)
            saved.copyTo(output, overwrite = true)
            check(transferHash(output) == asset.hash) { "附件副本校验失败" }
        }
    }

    fun rewrite(row: JsonObject, sourceSession: String, files: TransferFiles, target: File): JsonObject = row.deepCopy().also { result ->
        val replacements = files.assets.flatMap { asset -> asset.references.map { ref ->
            if (ref.contains('\u0000')) ref to "[File: ${asset.name}]" else ref to File(target, asset.name).toURI().toString()
        } }.toMap()
        // 只重写实际可见的媒体引用；保留历史工具轨迹，不让它成为可执行任务。
        for (field in fields + listOf("content", "first_message", "greeting", "quote")) result.text(field)?.let { value ->
            var changed = value
            for ((ref, replacement) in replacements.entries.sortedByDescending { it.key.length }) {
                if (ref.contains('\u0000')) {
                    if (ref.substringBefore('\u0000') == sourceSession) changed = changed.replace(ref.substringAfter('\u0000'), replacement)
                } else changed = changed.replace(ref, replacement)
            }
            if (changed != value) result.addProperty(field, changed)
        }
    }

    private fun open(source: String): InputStream = if (source.startsWith("content://")) {
        requireNotNull(context.contentResolver.openInputStream(Uri.parse(source)))
    } else File(if (source.startsWith("file:")) Uri.parse(source).path!! else source).inputStream()

    private fun safeFile(path: String, sessions: List<String>): File? = runCatching {
        val file = File(if (path.startsWith("file:")) Uri.parse(path).path!! else path).canonicalFile
        val roots = listOf("portraits", "tts", "worldbook_covers") .map { File(context.filesDir, it) } +
            File(context.cacheDir, "portraits") + sessions.flatMap { listOf(File(context.filesDir, "workspace/$it"), File(context.filesDir, "agent_workspaces/$it")) }
        file.takeIf { candidate -> roots.any { candidate.path.startsWith(it.canonicalPath + File.separator) } }
    }.getOrNull()
}
