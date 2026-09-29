package com.nekobot.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionWorkspaceFsTest {

    @Test
    fun scanListsNonEmptySessionAndLegacyWorkspacesButSkipsSharedAndEmpty() {
        val filesDir = Files.createTempDirectory("nekobot-ws-scan").toFile()
        try {
            filesDir.resolve("workspace/session-a").apply {
                mkdirs()
                resolve("note.txt").writeText("hello")
                resolve("sub/deep.bin").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(2048))
            }
            // 空工作区不列出
            filesDir.resolve("workspace/session-empty").mkdirs()
            // 共享工作区不列出
            filesDir.resolve("workspace/shared").apply {
                mkdirs()
                resolve("common.txt").writeText("shared")
            }
            filesDir.resolve("agent_workspaces/legacy-a").apply {
                mkdirs()
                resolve("old.txt").writeText("legacy")
            }

            val scanned = SessionWorkspaceFs.scan(filesDir)

            assertEquals(2, scanned.size)
            val sessionA = scanned.single { it.sessionId == "session-a" }
            assertEquals(false, sessionA.legacy)
            assertEquals(2, sessionA.fileCount)
            assertEquals("hello".length + 2048L, sessionA.sizeBytes)

            val legacy = scanned.single { it.sessionId == "legacy-a" }
            assertEquals(true, legacy.legacy)
            assertEquals(1, legacy.fileCount)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun resolveWorkspaceRejectsTraversalAndMissingDirectories() {
        val filesDir = Files.createTempDirectory("nekobot-ws-resolve").toFile()
        try {
            filesDir.resolve("workspace/session-a").apply {
                mkdirs()
                resolve("keep.txt").writeText("keep")
            }

            assertNull(SessionWorkspaceFs.resolveWorkspace(filesDir, "../outside", legacy = false))
            assertNull(SessionWorkspaceFs.resolveWorkspace(filesDir, "missing", legacy = false))

            val resolved = SessionWorkspaceFs.resolveWorkspace(filesDir, "session-a", legacy = false)
            assertEquals(filesDir.resolve("workspace/session-a").canonicalFile, resolved)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun itemsListNestedPathsAndDeleteItemStaysInsideWorkspace() {
        val filesDir = Files.createTempDirectory("nekobot-ws-items").toFile()
        try {
            val root = filesDir.resolve("workspace/session-a").apply {
                mkdirs()
                resolve("a.txt").writeText("a")
                resolve("docs").mkdirs()
                resolve("docs/b.txt").writeText("b")
                resolve("docs/nested").mkdirs()
                resolve("docs/nested/c.txt").writeText("c")
            }

            val items = SessionWorkspaceFs.items(root)
            assertEquals(
                listOf("a.txt", "docs", "docs/b.txt", "docs/nested", "docs/nested/c.txt"),
                items.map { it.relativePath }
            )
            assertTrue(items.first { it.relativePath == "docs" }.isDirectory)
            assertEquals("b".length.toLong(), items.first { it.relativePath == "docs/b.txt" }.sizeBytes)

            // 越界路径不允许删除工作区外的文件
            val outside = filesDir.resolve("outside.txt").apply { writeText("safe") }
            assertFalse(SessionWorkspaceFs.deleteItem(root, "../outside.txt"))
            assertTrue(outside.exists())

            // 目录与文件都可删，删除后工作区其余内容保留
            assertTrue(SessionWorkspaceFs.deleteItem(root, "docs/nested"))
            assertTrue(SessionWorkspaceFs.deleteItem(root, "a.txt"))
            assertFalse(root.resolve("a.txt").exists())
            assertFalse(root.resolve("docs/nested").exists())
            assertTrue(root.resolve("docs/b.txt").exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun deleteWorkspaceRemovesWholeDirectory() {
        val filesDir = Files.createTempDirectory("nekobot-ws-delete").toFile()
        try {
            val root = filesDir.resolve("workspace/session-a").apply {
                mkdirs()
                resolve("plugins/p1/data.json").apply { parentFile?.mkdirs() }.writeText("{}")
                resolve("browser/page.html").apply { parentFile?.mkdirs() }.writeText("<html/>")
            }

            assertTrue(SessionWorkspaceFs.deleteWorkspace(filesDir, "session-a", legacy = false))
            assertFalse(root.exists())
            // 兄弟目录与共享目录不受影响
            assertTrue(filesDir.resolve("workspace").isDirectory)
            assertFalse(SessionWorkspaceFs.deleteWorkspace(filesDir, "session-a", legacy = false))
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
