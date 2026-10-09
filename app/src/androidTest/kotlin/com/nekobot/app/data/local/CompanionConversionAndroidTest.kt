package com.nekobot.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CompanionConversionAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun database() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
    private suspend fun source(db: NekobotDatabase): LocalSessionEntity {
        val id = UUID.randomUUID().toString()
        db.characterDao().upsert(LocalCharacterEntity(id, "转换测试", createdAt = "old", updatedAt = "old"))
        return LocalSessionEntity("s-$id", "旧窗口", characterId = id, createdAt = "old", updatedAt = "old").also { db.sessionDao().upsert(it) }
    }
    private suspend fun message(db: NekobotDatabase, source: LocalSessionEntity, id: String, text: String, role: String = "user") =
        db.messageDao().upsert(LocalMessageEntity(id, source.id, role, text, timestamp = "2026-10-01", createdAt = "2026-10-01"))
    private fun draft(id: String, text: String, category: String, key: String = category) = mapOf(
        "key" to key, "operation" to "add", "category" to category, "kind" to if (category == "episode") "episode" else "fact",
        "subject" to "玩家", "content" to text, "source_id" to id, "quote" to text)
    private suspend fun fails(block: suspend () -> Unit) { try { block(); fail("Expected conversion rejection") } catch (_: IllegalArgumentException) { } }

    @Test fun buildsMissingEpisodesAndProcessesLastUserTurnBeforeAtomicImport() = runBlocking {
        val db = database()
        try {
            val source = source(db)
            message(db, source, "u1", "我们一起做了生日蛋糕，最后烤成功了。")
            message(db, source, "a1", "一起做蛋糕很开心。", "assistant")
            message(db, source, "u2", "我喜欢茉莉茶。")
            db.memoryDao().upsert(LocalCharacterMemoryEntity("old", source.characterId!!, "local-user", "long", "user_persona", "旧错误", "", "用户喜欢开飞机", 1, createdAt = "old"))
            val service = CompanionTransferService(context, db)
            val preview = service.preview(source.id)
            var requests = 0
            val targetId = service.execute(preview.jobId, "新角色", CompanionConversionRequest("chosen", "我的普通模型", 16000, { rules, material ->
                requests++
                assertEquals(CompanionConversionRules.prompt, rules)
                assertEquals(1, db.sessionDao().count()) // 模型未完成时正式库没有半成品
                assertFalse(material.contains("apiKey"))
                val input = JsonParser.parseString(material).asJsonObject
                assertTrue(input["legacy_reference"].toString().contains("飞机"))
                val rows = input["target"].asJsonArray.map { it.asJsonObject }.filter { it["role"].asString == "user" }
                Gson().toJson(mapOf("changes" to rows.map { draft(it["id"].asString, it["text"].asString, if(it["text"].asString.contains("蛋糕")) "episode" else "user_profile") }))
            }))
            assertEquals(1, requests)
            val target = db.sessionDao().getById(targetId)!!
            val store = CompanionMemoryStore(db); val scope = store.ensureScope(target)
            val memories = store.validatedMemories(scope).filter { it.current }
            assertEquals(setOf("episode", "user_profile"), memories.map { it.category }.toSet())
            assertFalse(memories.any { it.content.contains("飞机") })
            assertTrue(memories.all { it.eventDate == null })
            assertTrue(db.companionMemoryDao().pending(scope.id).isEmpty())
            assertTrue(service.receipt(preview.jobId).contains("我的普通模型"))
            assertEquals(targetId, service.execute(preview.jobId, "重复", CompanionConversionRequest("chosen", "我的普通模型", 16000, { _, _ -> error("不得重调") })))
            db.sessionDao().deleteById(source.id); db.characterDao().deleteById(source.characterId)
            assertEquals(2, store.validatedMemories(scope).count { it.current })
            assertEquals(3, db.messageDao().listBySession(target.id).size)
        } finally { db.close() }
    }

    @Test fun resumesCompletedBatchesAndRejectsFakeQuoteWithoutPublishing() = runBlocking {
        val db = database()
        try {
            val source = source(db)
            repeat(4) { i ->
                message(db, source, "u$i", "第${i}件事" + "原话".repeat(1100))
                message(db, source, "a$i", "知道了", "assistant")
            }
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            var calls = 0
            var completedFirst: String? = null
            fails { service.execute(preview.jobId, "副本", CompanionConversionRequest("m", "测试模型", 14000, { _, raw ->
                calls++
                val row = JsonParser.parseString(raw).asJsonObject["target"].asJsonArray.first().asJsonObject
                if (calls == 1) { completedFirst = row["id"].asString; "{\"changes\":[]}" }
                else Gson().toJson(mapOf("changes" to listOf(draft(row["id"].asString, "捏造的引用", "episode"))))
            })) }
            assertEquals(4, calls); assertEquals(1, db.sessionDao().count()) // 首批完成；失败批最多三次，不无限重试。
            var resumed = 0
            val restarted = CompanionTransferService(context, db)
            val target = restarted.execute(preview.jobId, "副本", CompanionConversionRequest("m2", "继续用的模型", 14000, { _, raw ->
                resumed++
                assertFalse(JsonParser.parseString(raw).asJsonObject["target"].asJsonArray.any { it.asJsonObject["id"].asString == completedFirst })
                "{\"changes\":[]}"
            }))
            assertTrue(resumed > 0); assertNotNull(db.sessionDao().getById(target)); assertEquals(2, db.sessionDao().count())
            assertTrue(restarted.receipt(preview.jobId).contains("测试模型、继续用的模型"))
        } finally { db.close() }
    }

    @Test fun changedSourceAtFinalCommitPreservesOriginalAndDoesNotPublish() = runBlocking {
        val db = database()
        try {
            val source = source(db); message(db, source, "u", "我喜欢茶")
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            fails { service.execute(preview.jobId, "副本", CompanionConversionRequest("m", "模型", 14000, { _, _ ->
                message(db, source, "u", "我更改了原资料")
                "{\"changes\":[]}"
            })) }
            assertEquals(1, db.sessionDao().count())
            assertEquals("我更改了原资料", db.messageDao().getById("u")!!.content)
        } finally { db.close() }
    }

    @Test fun noRawEvidenceDoesNotTurnLegacyContentIntoVerifiedMemory() = runBlocking {
        val db = database()
        try {
            val source = source(db)
            db.memoryDao().upsert(LocalCharacterMemoryEntity("old", source.characterId!!, "local-user", "long", "important_event", "没有原话", "", "一起去了月球", 1, createdAt = "old"))
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            val target = db.sessionDao().getById(service.execute(preview.jobId, "副本", CompanionConversionRequest("m", "模型", 14000, { _, _ -> error("没有原话不调用") })))!!
            val store = CompanionMemoryStore(db)
            assertTrue(store.validatedMemories(store.ensureScope(target)).isEmpty())
            assertTrue(service.receipt(preview.jobId).contains("一起去了月球"))
        } finally { db.close() }
    }

    @Test fun completedPromiseCanBeImportedWithoutInventingAnEarlierMemory() = runBlocking {
        val db = database()
        try {
            val source = source(db); message(db, source, "m", "我们约好的照片已经整理完成了。")
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            val target = db.sessionDao().getById(service.execute(preview.jobId, "副本", CompanionConversionRequest("m", "模型", 14000, { _, raw ->
                val row = JsonParser.parseString(raw).asJsonObject["target"].asJsonArray.single().asJsonObject
                Gson().toJson(mapOf("changes" to listOf(draft(row["id"].asString, row["text"].asString, "promise") + ("operation" to "complete"))))
            })))!!
            val store = CompanionMemoryStore(db)
            assertEquals("completed", store.validatedMemories(store.ensureScope(target)).single { it.current }.status)
        } finally { db.close() }
    }

    @Test fun repairsInvalidFormatAndDateEvidenceBeforePublishingCompleteBatch() = runBlocking {
        val db = database()
        try {
            val source = source(db); message(db, source, "u", "我喜欢茶。我们约好的照片已经整理完成了。")
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            var calls = 0
            val target = db.sessionDao().getById(service.execute(preview.jobId, "副本", CompanionConversionRequest("m", "模型", 14000, { rules, raw ->
                calls++
                assertEquals(CompanionConversionRules.prompt, rules)
                assertEquals(1, db.sessionDao().count())
                val input = JsonParser.parseString(raw).asJsonObject
                val row = input["target"].asJsonArray.single().asJsonObject
                val first = draft(row["id"].asString, "我喜欢茶。", "user_profile")
                val second = draft(row["id"].asString, "我们约好的照片已经整理完成了。", "promise") + ("operation" to "complete")
                val changes = when(calls) {
                    1 -> listOf(first - "operation", second + ("event_evidence" to "10月20日"))
                    2 -> {
                        assertTrue(input["repair_feedback"].asString.contains("operation"))
                        assertTrue(input["repair_feedback"].asString.contains("event_evidence"))
                        listOf(first, second + ("quote" to "不存在的原话"))
                    }
                    else -> {
                        assertTrue(input["repair_feedback"].asString.contains("quote"))
                        listOf(first, second)
                    }
                }
                Gson().toJson(mapOf("changes" to changes))
            })))!!
            assertEquals(3, calls)
            val store = CompanionMemoryStore(db); val scope = store.ensureScope(target)
            val saved = store.validatedMemories(scope).filter { it.current }
            assertEquals(2, saved.size)
            assertTrue(saved.all { it.eventDate == null })
            assertEquals("completed", saved.single { it.category == "promise" }.status)
        } finally { db.close() }
    }

    @Test fun preservesManualProtectionAndDoesNotSendForgottenOrExpiredSources() = runBlocking {
        val db = database()
        try {
            val normal = source(db)
            val source = normal.copy(sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true)
            db.sessionDao().update(source)
            val store = CompanionMemoryStore(db); val old = store.ensureScope(source)
            for ((id, text) in listOf("forgotten" to "已经要求遗忘的往事", "expired" to "限时活动暗号", "current" to "我喜欢茶")) message(db, source, id, text)
            val expired = db.messageDao().getById("expired")!!
            db.companionMemoryDao().saveMemory(CompanionMemoryEntity("expired-memory", old.id, "expired-key", 1, "fact", "玩家", "限时活动暗号", "active", createdAt = "old", updatedAt = "old", expiresAt = "2020-01-01"))
            db.companionMemoryDao().saveSources(listOf(CompanionSourceEntity("expired-memory", expired.id, CompanionMemoryPolicy.fingerprint(expired), expired.content, "2026-10-01", "user")))
            db.companionMemoryDao().saveForgotten(listOf(CompanionForgottenSourceEntity(old.id, "forgotten")))
            db.companionMemoryDao().saveMemory(CompanionMemoryEntity("manual", old.id, "manual-key", 1, "fact", "玩家", "手动保护的记忆", "active", manual = true, locked = true, alwaysProvide = true, createdAt = "old", updatedAt = "old"))
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            val target = db.sessionDao().getById(service.execute(preview.jobId, "副本", CompanionConversionRequest("m", "模型", 14000, { _, raw ->
                val input = JsonParser.parseString(raw).asJsonObject
                assertFalse(input["target"].toString().contains("遗忘")); assertFalse(input["target"].toString().contains("限时活动"))
                assertTrue(input["existing"].toString().contains("手动保护"))
                "{\"changes\":[]}"
            })))!!
            val scope = store.ensureScope(target)
            val saved = store.validatedMemories(scope).filter { it.current }
            assertEquals(1, saved.size); assertTrue(saved.single().manual && saved.single().locked && saved.single().alwaysProvide)
            assertEquals(1, db.companionMemoryDao().forgotten(scope.id).size)
        } finally { db.close() }
    }
}
