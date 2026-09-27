package com.nekobot.app.data.local

import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalWorkspaceHistoryExporterTest {
    @Test
    fun oversizedOriginalMessageIsSplitWithoutLosingTextAndEveryFileIsBounded() {
        val root = Files.createTempDirectory("nekobot-history-copy-").toFile()
        try {
            val history = File(root, "history")
            val writer = WorkspaceHistoryChunkWriter(history, "session-1")
            val longText = "中秋🙂\n\\\"".repeat(35_000)
            writer.append(message("u1", "user", longText, "2026-09-27T10:00:00Z"))
            writer.append(message("a1", "assistant", "记得月饼", "2026-09-27T10:01:00Z"))
            val result = writer.publish()

            assertEquals(2L, result.messageCount)
            assertTrue(result.partCount > 2)
            assertTrue(result.chunkCount > 1)
            history.walkTopDown().filter { it.isFile }.forEach { file ->
                assertTrue("${file.name} exceeded 128 KiB", file.length() <= 128 * 1024)
            }

            val rootIndex = JsonParser.parseString(result.indexFile.readText()).asJsonObject
            assertEquals("session-1", rootIndex.get("session_id").asString)
            assertEquals("local_messages", rootIndex.get("source").asString)
            val rebuilt = linkedMapOf<String, StringBuilder>()
            val generationDir = File(history, "chunks/${result.generation}")
            rootIndex.getAsJsonArray("pages").forEach { page ->
                File(generationDir, page.asString).forEachLine { descriptorText ->
                    val descriptor = JsonParser.parseString(descriptorText).asJsonObject
                    val data = File(generationDir, descriptor.get("data_file").asString).readBytes()
                    val sidecar = File(generationDir, descriptor.get("index_file").asString)
                    sidecar.forEachLine { indexedText ->
                        val position = JsonParser.parseString(indexedText).asJsonObject
                        val offset = position.get("byte_offset").asInt
                        val length = position.get("byte_length").asInt
                        val record = JsonParser.parseString(
                            String(data.copyOfRange(offset, offset + length), StandardCharsets.UTF_8).trim()
                        ).asJsonObject
                        val id = record.get("message_id").asString
                        assertEquals(id, position.get("message_id").asString)
                        assertEquals(record.get("timestamp").asString, position.get("timestamp").asString)
                        rebuilt.getOrPut(id) { StringBuilder() }.append(record.get("content").asString)
                    }
                }
            }
            assertEquals(longText, rebuilt["u1"].toString())
            assertEquals("记得月饼", rebuilt["a1"].toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oldGenerationIsRemovedOnlyAfterNewIndexIsPublished() {
        val root = Files.createTempDirectory("nekobot-history-swap-").toFile()
        try {
            val history = File(root, "history")
            val first = WorkspaceHistoryChunkWriter(history, "session-1").apply {
                append(message("u1", "user", "旧原话", "t1"))
            }.publish()
            val oldDirectory = File(history, "chunks/${first.generation}")
            assertTrue(oldDirectory.isDirectory)
            val oldIndex = first.indexFile.readText()

            val interrupted = WorkspaceHistoryChunkWriter(history, "session-1") {
                error("simulated pre-commit failure")
            }
            interrupted.append(message("u2", "user", "不会发布", "t2"))
            try {
                interrupted.publish()
                fail("expected an interrupted rebuild")
            } catch (expected: IllegalStateException) {
                assertEquals("simulated pre-commit failure", expected.message)
            } finally {
                interrupted.discardIfUnpublished()
            }
            assertEquals(oldIndex, first.indexFile.readText())
            assertTrue(oldDirectory.isDirectory)

            val second = WorkspaceHistoryChunkWriter(history, "session-1").apply {
                append(message("u3", "user", "新的原话", "t3"))
            }.publish()
            assertFalse(oldDirectory.exists())
            assertTrue(File(history, "chunks/${second.generation}").isDirectory)
            assertEquals(second.generation, JsonParser.parseString(second.indexFile.readText())
                .asJsonObject.get("generation").asString)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writerRejectsCrossSessionRows() {
        val root = Files.createTempDirectory("nekobot-history-scope-").toFile()
        try {
            val writer = WorkspaceHistoryChunkWriter(File(root, "history"), "session-1")
            try {
                writer.append(message("u1", "user", "其他会话", "t1").copy(sessionId = "session-2"))
                fail("cross-session message must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("其他会话"))
            } finally {
                writer.discardIfUnpublished()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun message(id: String, role: String, content: String, time: String) = HistoryCopyMessage(
        sessionId = "session-1", id = id, role = role, content = content,
        timestamp = time, createdAt = time
    )
}
