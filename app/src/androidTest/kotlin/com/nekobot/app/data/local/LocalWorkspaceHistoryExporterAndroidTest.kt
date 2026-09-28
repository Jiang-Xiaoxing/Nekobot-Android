package com.nekobot.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalWorkspaceHistoryExporterAndroidTest {
    @Test
    fun rebuildExportsOnlyVisibleOriginalsAndInvalidateDeletesOnlyCurrentHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val files = File(context.cacheDir, "history-export-${UUID.randomUUID()}")
        assertTrue(files.mkdirs())
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        try {
            db.sessionDao().upsert(LocalSessionEntity(id = "s1", name = "一", createdAt = "now", updatedAt = "now"))
            db.sessionDao().upsert(LocalSessionEntity(id = "s2", name = "二", createdAt = "now", updatedAt = "now"))
            db.messageDao().upsert(message("u1", "s1", "user", "应保留原话"))
            db.messageDao().upsert(message("hidden", "s1", "assistant", "已删除原话").copy(deleted = true))
            db.messageDao().upsert(message("summary", "s1", "system", "系统摘要"))
            db.messageDao().upsert(message("u2", "s2", "user", "另一个会话"))

            val exporter = LocalWorkspaceHistoryExporter(files, db.sessionDao(), db.messageDao())
            val first = exporter.rebuild("s1")
            val second = exporter.rebuild("s2")
            assertEquals(1L, first.messageCount)
            assertEquals(1L, second.messageCount)
            val s1History = File(files, "workspace/s1/history")
            val s2History = File(files, "workspace/s2/history")
            val ownNote = File(files, "workspace/s1/notes.txt")
            ownNote.writeText("工作区其他资料")
            assertTrue(s1History.isDirectory)
            assertTrue(s2History.isDirectory)

            db.sessionDao().deleteById("s1")
            assertTrue(exporter.invalidate("s1"))
            assertFalse(s1History.exists())
            assertEquals("工作区其他资料", ownNote.readText())
            assertTrue(s2History.isDirectory)
            assertFalse(exporter.invalidate("s1"))
            try {
                exporter.invalidate("../s2")
                throw AssertionError("路径穿越必须拒绝")
            } catch (_: IllegalArgumentException) {
                assertTrue(s2History.isDirectory)
            }
        } finally {
            db.close()
            files.deleteRecursively()
        }
    }

    private fun message(id: String, sessionId: String, role: String, content: String) =
        LocalMessageEntity(
            id = id, sessionId = sessionId, role = role, content = content,
            timestamp = "2026-09-27T10:00:00Z", createdAt = "2026-09-27T10:00:00Z"
        )
}
