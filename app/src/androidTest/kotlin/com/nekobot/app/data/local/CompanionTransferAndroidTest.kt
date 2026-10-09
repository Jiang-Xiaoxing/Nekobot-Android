package com.nekobot.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class CompanionTransferAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun database() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
    private suspend fun setup(db: NekobotDatabase, legacy: Boolean = false): LocalSessionEntity {
        val prefix = UUID.randomUUID().toString()
        db.characterDao().upsert(LocalCharacterEntity(prefix, "测试角色", firstMessage = "不要重复插入的开场白", createdAt = "old", updatedAt = "old"))
        return LocalSessionEntity("s-$prefix", "旧会话", characterId = prefix, sessionMode = if (legacy) "agent" else "character",
            inheritCharacter = legacy, longConversationEnabled = legacy, createdAt = "old", updatedAt = "old").also { db.sessionDao().upsert(it) }
    }
    private fun message(session: LocalSessionEntity, id: String, text: String) = LocalMessageEntity(id, session.id, "user", text,
        timestamp = "2026-01-01T00:00:00Z", createdAt = "2026-01-01T00:00:00Z")
    private suspend fun failure(block: suspend () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }

    @Test fun copiesOrderedRawVariantsFilesAndIndependentMemoryWithoutChangingSource() = runBlocking {
        val db = database()
        try {
            val source = setup(db)
            val sourceFile = File(context.filesDir, "workspace/${source.id}/note.txt").apply { parentFile!!.mkdirs(); writeText("附件原文") }
            val one = message(source, "m1", "我不喜欢辣椒 [File: note.txt]")
            db.messageDao().upsert(one)
            db.messageDao().upsert(message(source, "m2", "第二条同时间戳消息").copy(deleted = true))
            db.messageVariantDao().upsert(LocalMessageVariantEntity("v", "m1", source.id, 0, "候选原文", createdAt = "old"))
            db.memoryDao().upsert(LocalCharacterMemoryEntity("old-memory", source.characterId!!, "local-user", "long", "user_persona", "饮食", "", "用户不喜欢辣椒", 5, createdAt = "old"))
            db.memoryDao().upsert(LocalCharacterMemoryEntity("foreign", source.characterId, "other-user", "long", "user_persona", "不应导入", "", "其他人的秘密", 5, createdAt = "old"))
            val service = CompanionTransferService(context, db)
            val preview = service.preview(source.id)
            assertEquals(2L, preview.messages); assertEquals(1L, preview.variants); assertEquals(1, preview.attachments)
            assertEquals(1L, preview.skippedMemories)
            val targetId = service.execute(preview.jobId, "新角色")
            val target = db.sessionDao().getById(targetId)!!
            assertNotEquals(source.characterId, target.characterId)
            assertEquals(source, db.sessionDao().getById(source.id))
            assertEquals(one, db.messageDao().getById("m1"))
            val all = db.openHelper.readableDatabase.query("SELECT content,deleted,id FROM local_messages WHERE session_id=? ORDER BY created_at,rowid", arrayOf(targetId)).use { c ->
                buildList { while(c.moveToNext()) add(Triple(c.getString(0), c.getInt(1), c.getString(2))) }
            }
            assertEquals(2, all.size); assertEquals(1, all[1].second)
            assertTrue(all.first().first.startsWith("我不喜欢辣椒")); assertFalse(all.first().first.contains("[File: note.txt]"))
            val newName = all.first().first.substringAfter("[File: ").substringBefore(']')
            val copiedFile = File(context.filesDir, "workspace/$targetId/$newName")
            assertEquals(sourceFile.readText(), copiedFile.readText())
            val store = CompanionMemoryStore(db); val scope = store.ensureScope(target)
            val imported = store.validatedMemories(scope).single()
            assertEquals("用户不喜欢辣椒", imported.content); assertFalse(imported.manual)
            assertEquals(CompanionImportPolicy.IMPORTED_UNVERIFIED, imported.provenance)
            assertTrue(db.companionMemoryDao().pending(scope.id).isEmpty())
            assertEquals(0, store.process(target, { _, _ -> error("Historical migration must not call a model") }, 24000))
            db.sessionDao().deleteById(source.id); db.characterDao().deleteById(source.characterId); assertTrue(sourceFile.delete())
            assertEquals("附件原文", copiedFile.readText()); assertEquals(imported, store.validatedMemories(scope).single())
            db.messageDao().upsert(message(target, "new-message", "新聊天"))
            assertEquals(listOf("new-message"), db.companionMemoryDao().pending(scope.id).map { it.id })
        } finally { db.close() }
    }

    @Test fun verifiedLegacySourcesGetNewFingerprintsAndForgottenHistoryStaysForgotten() = runBlocking {
        val db = database()
        try {
            val source = setup(db, true)
            val row = message(source, "m", "我怕辣")
            val oldStore = CompanionMemoryStore(db); val oldScope = oldStore.ensureScope(source)
            db.messageDao().upsert(row)
            db.companionMemoryDao().saveMemory(CompanionMemoryEntity("fact", oldScope.id, "diet", 1, "fact", "玩家", "用户怕辣", "active", createdAt = "old", updatedAt = "old"))
            db.companionMemoryDao().saveSources(listOf(CompanionSourceEntity("fact", "m", CompanionMemoryPolicy.fingerprint(row), "我怕辣", "2026-01-01", "user")))
            db.messageDao().upsert(message(source, "forgotten", "不应从原话找回的事"))
            db.companionMemoryDao().saveForgotten(listOf(CompanionForgottenSourceEntity(oldScope.id, "forgotten")))
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            val target = db.sessionDao().getById(service.execute(preview.jobId, "副本"))!!
            val scope = oldStore.ensureScope(target)
            val memory = oldStore.validatedMemories(scope).single()
            val copied = db.companionMemoryDao().sources(memory.id).single()
            assertNotEquals("m", copied.messageId); assertEquals(CompanionMemoryPolicy.fingerprint(db.messageDao().getById(copied.messageId)!!), copied.fingerprint)
            assertNotEquals(CompanionMemoryPolicy.fingerprint(row), copied.fingerprint)
            assertEquals(1, db.companionMemoryDao().forgotten(scope.id).size)
            db.messageDao().upsert(row.copy(content = "原角色改了说法"))
            assertEquals("用户怕辣", oldStore.validatedMemories(scope).single().content)
        } finally { db.close() }
    }

    @Test fun changedSourceOrMissingFileCannotCommitAndForeignArchivesAreRejected() = runBlocking {
        val db = database()
        try {
            val source = setup(db)
            db.messageDao().upsert(message(source, "m", "原话"))
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            db.messageDao().upsert(message(source, "m", "预览后变化"))
            failure { service.execute(preview.jobId, "副本") }
            assertEquals(1, db.sessionDao().count())
            db.messageDao().upsert(message(source, "m", "[File: missing.txt]"))
            val missing = service.preview(source.id); assertEquals(1, missing.missingAttachments.size)
            failure { service.execute(missing.jobId, "副本") }; assertEquals(1, db.sessionDao().count())
            val other = setup(db)
            db.sessionDao().update(source.copy(archiveSessionId = other.id))
            failure { service.preview(source.id) }
        } finally { db.close() }
    }

    @Test fun rollbackAndRestartReuseOneJobAndCommittedTarget() = runBlocking {
        val db = database()
        try {
            val source = setup(db); db.messageDao().upsert(message(source, "m", "原话"))
            val before = CompanionTransferService(context, db, checkpoint = { if(it == "before_commit") throw IllegalArgumentException("Injected rollback") })
            val preview = before.preview(source.id)
            failure { before.execute(preview.jobId, "副本") }; assertEquals(1, db.sessionDao().count())
            val after = CompanionTransferService(context, db, checkpoint = { if(it == "after_commit") throw IllegalArgumentException("Injected lost callback") })
            failure { after.execute(preview.jobId, "副本") }; assertEquals(2, db.sessionDao().count())
            val restarted = CompanionTransferService(context, db)
            val retry = restarted.preview(source.id)
            assertEquals(preview.jobId, retry.jobId)
            assertEquals(retry.completedSessionId, restarted.execute(preview.jobId, "副本"))
            assertEquals(2, db.sessionDao().count())
        } finally { db.close() }
    }

    @Test fun archiveRawAndSelectedSessionStateSurviveWithNoOldSummaryInjection() = runBlocking {
        val db = database()
        try {
            val source = setup(db)
            val archive = source.copy(id = "archive-${source.id}", archived = true)
            db.sessionDao().upsert(archive); db.sessionDao().update(source.copy(archiveSessionId = archive.id))
            db.messageDao().upsert(message(archive, "archived", "较早的原话").copy(createdAt = "2025-01-01"))
            db.messageDao().upsert(message(source, "recent", "最新原话"))
            val selected = LocalStateSnapshotEntity("state", source.id, source.characterId!!, "session:${source.id}", "2026-01-01", "开心", 0.5f, 60, 80, 70, 60, 50, 40, 30, triggerType = "chat")
            db.stateSnapshotDao().insert(selected)
            db.memoryDao().upsert(LocalCharacterMemoryEntity("summary", source.characterId, "local-user", "short", "recent_digest", "摘要", "", "不得当事实注入的摘要", 1, createdAt = "old"))
            val service = CompanionTransferService(context, db); val preview = service.preview(source.id)
            assertEquals(1L, preview.skippedMemories)
            val target = db.sessionDao().getById(service.execute(preview.jobId, "新角色"))!!
            assertEquals(listOf("较早的原话", "最新原话"), db.messageDao().listBySession(target.id).map { it.content })
            val state = db.relationshipDao().get(target.characterId!!, "session:${target.id}")!!
            assertEquals(80, RelationshipState.fromJson(state.dataJson).affection)
            assertTrue(CompanionMemoryStore(db).validatedMemories(CompanionMemoryStore(db).ensureScope(target)).isEmpty())
        } finally { db.close() }
    }

    @Test fun databaseSwitchAndBusySourceBlockBeforeCreatingTarget() = runBlocking {
        val db = database()
        try {
            val source = setup(db); db.messageDao().upsert(message(source, "m", "原话"))
            var allowed = true
            val service = CompanionTransferService(context, db, guard = { require(allowed) { "资料库已切换或会话忙碌" } })
            val preview = service.preview(source.id); allowed = false
            failure { service.execute(preview.jobId, "副本") }
            assertEquals(1, db.sessionDao().count()); assertEquals("preview", service.records().single().phase)
        } finally { db.close() }
    }
}
