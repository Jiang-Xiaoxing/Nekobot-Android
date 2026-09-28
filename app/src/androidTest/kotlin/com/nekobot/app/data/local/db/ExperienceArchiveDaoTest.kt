package com.nekobot.app.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperienceArchiveDaoTest {
    @Test
    fun generatedArchiveIsIdempotentAndKeepsManualEdits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NekobotDatabase::class.java
        ).build()
        try {
            db.sessionDao().upsert(
                LocalSessionEntity(id = "session-1", name = "长期对话", createdAt = "now", updatedAt = "now")
            )
            val dao = db.experienceArchiveDao()
            val generated = LocalExperienceArchiveEntity(
                id = "archive-1",
                sessionId = "session-1",
                startMessageId = "u1",
                endMessageId = "a1",
                sourceStartedAt = "2026-09-27T10:00:00Z",
                sourceEndedAt = "2026-09-27T10:01:00Z",
                summary = "模型摘要",
                tagsJson = "[\"中秋\"]",
                sourceFingerprint = "fingerprint-1",
                createdAt = "now",
                updatedAt = "now"
            )
            dao.saveGenerated(generated, listOf("u1", "a1"))
            dao.updateUserEdits("archive-1", "用户修正后的摘要", "[\"礼物\"]", "later")
            val repeated = dao.saveGenerated(
                generated.copy(id = "different-id", summary = "另一次模型摘要", tagsJson = "[\"月饼\"]"),
                listOf("u1", "a1")
            )
            assertEquals("archive-1", repeated.id)
            assertEquals("用户修正后的摘要", repeated.summary)
            assertEquals("[\"礼物\"]", repeated.tagsJson)
            assertEquals(1, dao.listBySession("session-1", 10).size)

            val changedSource = dao.saveGenerated(
                generated.copy(sourceFingerprint = "fingerprint-2"),
                listOf("u1", "a1")
            )
            assertEquals("stale", changedSource.status)
            assertTrue(changedSource.summaryEdited)
            assertEquals("用户修正后的摘要", changedSource.summary)
            assertEquals(setOf("u1", "a1"), dao.sourceMessageIds("archive-1").toSet())

            val jobs = db.experienceArchiveJobDao()
            jobs.upsert(LocalExperienceArchiveJobEntity(
                sessionId = "session-1",
                checkpointMessageId = "a1",
                status = "running",
                processedCount = 2,
                totalCount = 100,
                generatedCount = 1,
                updatedAt = "now"
            ))
            assertEquals(1, jobs.recoverInterruptedRuns("later"))
            assertEquals("paused", jobs.getBySession("session-1")?.status)
            assertEquals("a1", jobs.getBySession("session-1")?.checkpointMessageId)

            db.sessionDao().deleteById("session-1")
            assertTrue(dao.listBySession("session-1", 10).isEmpty())
            assertEquals(null, jobs.getBySession("session-1"))
        } finally {
            db.close()
        }
    }
}
