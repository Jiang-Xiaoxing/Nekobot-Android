package com.nekobot.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CompanionConversionProcessTest {
    @Test fun resumesModelBatchesAfterActualAppStop() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("conversion_recovery_phase").orEmpty()
        assumeTrue(phase in setOf("prepare", "verify"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = context.getExternalFilesDir("validation")!!
        val marker = File(output, "conversion-recovery-marker.txt")
        val control = File(output, "conversion-recovery-job.txt")
        val completed = File(output, "conversion-recovery-batch.txt")
        val databaseName = "isolated-conversion-recovery.db"
        if (phase == "prepare") { context.deleteDatabase(databaseName); marker.delete(); completed.delete() }
        val db = Room.databaseBuilder(context, NekobotDatabase::class.java, databaseName).build()
        try {
            val service = CompanionTransferService(context, db)
            if (phase == "prepare") {
                db.characterDao().upsert(LocalCharacterEntity("c", "进程测试", createdAt = "old", updatedAt = "old"))
                db.sessionDao().upsert(LocalSessionEntity("s", "原会话", characterId = "c", createdAt = "old", updatedAt = "old"))
                repeat(4) { i ->
                    db.messageDao().upsert(LocalMessageEntity("u$i", "s", "user", "第${i}件事" + "原话".repeat(1100), timestamp = "2026-01-01", createdAt = "2026-01-01"))
                    db.messageDao().upsert(LocalMessageEntity("a$i", "s", "assistant", "知道了", timestamp = "2026-01-01", createdAt = "2026-01-01"))
                }
                control.writeText(service.preview("s").jobId)
            }
            assertEquals(1, db.sessionDao().count())
            val skipped = if (phase == "verify") completed.readLines().toSet() else emptySet()
            var calls = 0
            val id = service.execute(control.readText(), "完整副本", CompanionConversionRequest("test", "恢复模型", 14000, { _, raw ->
                calls++
                val ids = JsonParser.parseString(raw).asJsonObject["target"].asJsonArray.map { it.asJsonObject["id"].asString }
                assertTrue(ids.none { it in skipped })
                if (phase == "prepare" && calls == 1) completed.writeText(ids.joinToString("\n"))
                "{\"changes\":[]}"
            })) { progress ->
                if (phase == "prepare" && progress.phase == "正在整理记忆" && progress.processed > 0) {
                    marker.writeText("batch_committed")
                    Thread.sleep(45000)
                    error("主机没有在标记阶段强停 App")
                }
            }
            assertEquals("verify", phase); assertTrue(calls > 0)
            assertEquals(2, db.sessionDao().count()); assertEquals(8, db.messageDao().listBySession(id).size)
            assertEquals("complete", db.companionTransferDao().get(control.readText())!!.phase)
            service.deleteBackup(control.readText()); marker.delete(); control.delete(); completed.delete()
        } finally { db.close(); if (phase == "verify") context.deleteDatabase(databaseName) }
    }
}
