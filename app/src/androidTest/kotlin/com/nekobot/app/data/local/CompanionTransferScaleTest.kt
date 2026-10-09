package com.nekobot.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CompanionTransferScaleTest {
    @Test fun copies62000OriginalsInOrderWithoutSchedulingHistoricalExtraction() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        try {
            val sid = UUID.randomUUID().toString()
            db.characterDao().upsert(LocalCharacterEntity("c-$sid", "大型测试", createdAt = "old", updatedAt = "old"))
            val source = LocalSessionEntity(sid, "62000条合成原话", characterId = "c-$sid", createdAt = "old", updatedAt = "old")
            db.sessionDao().upsert(source)
            db.withTransaction { for (n in 0 until 62000) db.messageDao().upsert(LocalMessageEntity("raw-$sid-$n", sid,
                if (n % 2 == 0) "user" else "assistant", "第${n}条合成原话：正文和时间必须保留。", timestamp = "2026-01-01", createdAt = "2026-01-01")) }
            val started = System.currentTimeMillis()
            val service = CompanionTransferService(context, db)
            val preview = service.preview(sid); assertEquals(62000L, preview.messages)
            val targetId = service.execute(preview.jobId, "大型副本")
            assertEquals(62000, db.messageDao().countBySession(sid)); assertEquals(62000, db.messageDao().countBySession(targetId))
            db.openHelper.readableDatabase.query("SELECT content FROM local_messages WHERE session_id=? ORDER BY created_at,rowid", arrayOf(targetId)).use { cursor ->
                var n = 0; while(cursor.moveToNext()) { assertEquals("第${n}条合成原话：正文和时间必须保留。", cursor.getString(0)); n++ }; assertEquals(62000, n)
            }
            val target = db.sessionDao().getById(targetId)!!
            val scope = CompanionMemoryStore(db).ensureScope(target)
            assertTrue(db.companionMemoryDao().pending(scope.id).isEmpty())
            Log.i("CompanionTransferScale", "62000 originals copied and verified in ${System.currentTimeMillis()-started} ms; heap=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()}")
        } finally { db.close() }
    }
}
