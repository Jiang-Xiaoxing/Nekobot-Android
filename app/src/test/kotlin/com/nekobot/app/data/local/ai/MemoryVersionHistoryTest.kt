package com.nekobot.app.data.local.ai

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MemoryVersionHistoryTest {
    @Test fun historyTracksSourcesAndRestoresThroughCompareAndSwap() {
        val directory = Files.createTempDirectory("memory-history").toFile()
        try {
            val store = GlobalAgentMemoryStore(java.io.File(directory, "global-memory.md"))
            store.replace("original")
            val version = store.versions().first()
            store.replace("updated", "original", "agent", "session", "message")
            assertEquals("message", store.versions().first().sourceMessageId)
            try { store.replace("stale", "original"); fail("Must reject a stale draft") }
            catch (_: MemoryConflictException) { }
            assertEquals("original", store.restoreVersion(version.id, "updated").content)
            assertEquals("updated", store.undo("original").content)
        } finally { directory.deleteRecursively() }
    }
    @Test fun separateStoreInstancesCannotOverwriteTheSameSnapshotConcurrently() {
        val directory = Files.createTempDirectory("memory-cas").toFile()
        try {
            val file = java.io.File(directory, "global-memory.md")
            GlobalAgentMemoryStore(file).replace("base")
            val start = CountDownLatch(1)
            val success = AtomicInteger()
            val conflict = AtomicInteger()
            val workers = listOf("a", "b").map { content -> Thread {
                start.await()
                try { GlobalAgentMemoryStore(file).replace(content, "base"); success.incrementAndGet() }
                catch (_: MemoryConflictException) { conflict.incrementAndGet() }
            }.apply { start() } }
            start.countDown()
            workers.forEach { it.join() }
            assertEquals(1, success.get())
            assertEquals(1, conflict.get())
        } finally { directory.deleteRecursively() }
    }
    @Test fun committedMemoryRecoversAPendingHistoryRecord() {
        val directory = Files.createTempDirectory("memory-journal").toFile()
        try {
            val file = java.io.File(directory, "global-memory.md")
            val history = MemoryVersionHistory(java.io.File(directory, "global-memory-history"))
            val entry = history.newVersion("new", "old", "manual", null, null, null)
            history.prepare(entry)
            file.writeText("new")
            assertEquals(entry.id, GlobalAgentMemoryStore(file).versions().single().id)
        } finally { directory.deleteRecursively() }
    }
    @Test fun threeWayMergePreservesIndependentEditsAndRejectsOverlap() {
        assertEquals("ONE\ntwo\nTHREE", mergeMemoryEdits("one\ntwo\nthree", "ONE\ntwo\nthree", "one\ntwo\nTHREE"))
        assertNull(mergeMemoryEdits("one\ntwo", "ONE\ntwo", "OTHER\ntwo"))
        assertNull(mergeMemoryEdits("one\ntwo", "one\na\ntwo", "one\nb\ntwo"))
    }
}
