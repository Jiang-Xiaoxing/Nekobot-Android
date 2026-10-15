package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** 合成资料和无效离线端点：检查真实配置分支，不向外部服务发送资料。 */
class CompanionSharedRetrievalTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun db() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build().apply {
        NekobotDatabase::class.java.getDeclaredField("dbName").apply { isAccessible = true }.set(this, "vector-test-${UUID.randomUUID()}")
    }
    private suspend fun setup(db: NekobotDatabase, id: String): CompanionScopeEntity {
        db.characterDao().upsert(LocalCharacterEntity("c-$id", id, createdAt = "now", updatedAt = "now"))
        val session = LocalSessionEntity(id, id, characterId = "c-$id", sessionMode = "agent", inheritCharacter = true,
            longConversationEnabled = true, companionMode = true, createdAt = "now", updatedAt = "now")
        db.sessionDao().upsert(session)
        return CompanionMemoryStore(db).ensureScope(session)
    }
    private suspend fun model(db: NekobotDatabase, id: String) = LocalAiModelEntity(id, id, protocol = "openai_chat",
        apiKey = "", baseUrl = "https://example.invalid", model = "vector-$id", purpose = "embedding", active = true,
        createdAt = "now").also { db.aiModelDao().upsert(it) }
    private fun save(db: NekobotDatabase, enabled: Boolean, id: String?) = ServiceContainer.prefs.saveCompanionMemorySettings(
        db.dbName, "a", CompanionRecallSettings(7, 4000), enabled, id)

    @Test fun firstUseIgnoresOldGlobalAndRoleBindingsAndLeavesIndexesIntact() = runBlocking {
        val db = db()
        try {
            val a = setup(db, "a"); val old = model(db, "old")
            db.companionMemoryDao().saveScope(a.copy(embeddingModelId = old.id))
            val item = CompanionSearchItem("fact", "f", "用户喝茶不加糖")
            db.companionMemoryDao().saveVectors(listOf(CompanionVectorEntity(a.id, "fact", "f", embeddingCacheIdentity(old), item.fingerprint, "[1,0]", "now")))
            val search = CompanionHybridSearch(db)
            withTimeout(1000) {
                search.index(a, listOf(item.copy(id = "new")))
                assertTrue(search.cachedScores(a, "茶", listOf(item)).isEmpty())
            }
            assertNull(CompanionHybridSearch.configuredBackend(db, a.copy(embeddingModelId = old.id)))
            assertEquals(1, db.companionMemoryDao().vectors(a.id, embeddingCacheIdentity(old)).size)
            assertEquals(old.id, db.companionMemoryDao().scope(a.id)?.embeddingModelId)
        } finally { db.close() }
    }
    @Test fun sharedModelPersistsAcrossCharactersButLimitsAndProfilesStaySeparate() = runBlocking {
        val db = db(); val other = db()
        try {
            val a = setup(db, "a"); val b = setup(db, "b"); val chosen = model(db, "chosen")
            save(db, false, chosen.id)
            assertNull(CompanionHybridSearch.configuredBackend(db, b))
            val setting = save(db, true, chosen.id)
            model(db, "unrelated-active")
            db.aiModelDao().setActiveForPurpose("unrelated-active", "embedding")
            for (s in listOf(a, b)) assertEquals(embeddingCacheIdentity(chosen), CompanionHybridSearch.configuredBackend(db, s)?.modelKey)
            val reopenedPrefs = PrefsManager(context)
            assertEquals(setting, reopenedPrefs.companionRetrievalSettings(db.dbName))
            assertEquals(CompanionRecallSettings(7,4000), reopenedPrefs.companionRecallSettings(db.dbName, "a"))
            assertEquals(CompanionRecallSettings(), reopenedPrefs.companionRecallSettings(db.dbName, "b"))
            assertFalse(reopenedPrefs.companionRetrievalSettings(other.dbName).enabled)
            assertNotEquals(a.id, b.id)
            assertEquals("a", db.companionMemoryDao().scope(a.id)?.sessionId)
            assertEquals("b", db.companionMemoryDao().scope(b.id)?.sessionId)
        } finally { db.close(); other.close() }
    }
    @Test fun turningOffOrChangingSelectionInvalidatesAnAlreadyAcquiredBackendBeforeHttp() = runBlocking {
        val db = db()
        try {
            val a = setup(db, "a"); val chosen = model(db, "chosen")
            save(db, true, chosen.id)
            val backend = requireNotNull(CompanionHybridSearch.configuredBackend(db, a))
            save(db, false, chosen.id)
            withTimeout(1000) {
                try { backend.embed(listOf("不能发出去")); fail("stale backend must stop") }
                catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("设置已改变")) }
            }
            save(db, true, chosen.id)
            // 即使重新开启同一模型，旧请求也不能复活。
            try { backend.embed(listOf("旧批次")); fail("generation must change") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("设置已改变")) }
        } finally { db.close() }
    }
    @Test fun deletedOrDisabledModelFallsBackWithoutUsingActiveModel() = runBlocking {
        val db = db()
        try {
            val a = setup(db, "a"); val chosen = model(db, "chosen"); model(db, "other")
            save(db, true, chosen.id)
            db.aiModelDao().upsert(chosen.copy(enabled = false))
            assertNull(CompanionHybridSearch.configuredBackend(db, a))
            db.aiModelDao().deleteById(chosen.id)
            assertNull(CompanionHybridSearch.configuredBackend(db, a))
            assertTrue(ServiceContainer.prefs.companionRetrievalSettings(db.dbName).enabled)
        } finally { db.close() }
    }
}
