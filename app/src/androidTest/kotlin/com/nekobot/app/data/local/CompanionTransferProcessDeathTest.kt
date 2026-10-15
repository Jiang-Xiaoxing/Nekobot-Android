package com.nekobot.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 主机在两个标记阶段强停测试 App，再启动新进程验证真实 SQLite 回滚和成功回执。 */
class CompanionTransferProcessDeathTest {
    @Test fun resumesAcrossActualProcessTermination() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("transfer_recovery_phase").orEmpty()
        assumeTrue(phase in setOf("prepare", "resume", "verify"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val control = File(context.getExternalFilesDir("validation"), "transfer-recovery-job.txt")
        val marker = File(context.getExternalFilesDir("validation"), "transfer-recovery-marker.txt")
        val databaseName = "isolated-companion-process-recovery.db"
        if (phase == "prepare") { context.deleteDatabase(databaseName); marker.delete() }
        val db = Room.databaseBuilder(context, NekobotDatabase::class.java, databaseName).build()
        try {
            if (phase == "prepare") {
                db.characterDao().upsert(LocalCharacterEntity("process-character", "进程恢复测试", createdAt = "old", updatedAt = "old"))
                db.sessionDao().upsert(LocalSessionEntity("process-source", "进程恢复原会话", characterId = "process-character", createdAt = "old", updatedAt = "old"))
                db.messageDao().upsert(LocalMessageEntity("process-raw", "process-source", "user", "必须完整保存的原话", timestamp = "2026-01-01", createdAt = "2026-01-01"))
                val preview = CompanionTransferService(context, db).preview("process-source")
                control.writeText(preview.jobId)
            }
            val jobId = control.readText()
            if (phase == "resume") assertEquals(1, db.sessionDao().count())
            val service = CompanionTransferService(context, db, checkpoint = { stage ->
                if (phase == "prepare" && stage == "before_commit" || phase == "resume" && stage == "after_commit") {
                    marker.writeText(stage)
                    // Only the explicit host test driver enters this branch and force-stops the app.
                    Thread.sleep(45000)
                    error("主机未在测试标记阶段终止进程")
                }
            })
            val target = service.execute(jobId, "恢复后的独立副本")
            assertEquals("verify", phase)
            assertEquals(2, db.sessionDao().count())
            assertEquals("complete", db.companionTransferDao().get(jobId)!!.phase)
            assertEquals(target, service.execute(jobId, "重复操作"))
            assertEquals("必须完整保存的原话", db.messageDao().listBySession(target).single().content)
            assertEquals("必须完整保存的原话", db.messageDao().getById("process-raw")!!.content)
            service.deleteBackup(jobId)
            File(context.filesDir, "workspace/$target").deleteRecursively()
            control.delete(); marker.delete()
        } finally { db.close(); if (phase == "verify") context.deleteDatabase(databaseName) }
    }
}
