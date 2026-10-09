package com.nekobot.app.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionCreationAndroidTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), NekobotDatabase::class.java
    ).build()
    private fun session(id: String, mode: String = "character") = LocalSessionEntity(
        id, id, characterId = "c", sessionMode = mode, createdAt = "now", updatedAt = "now"
    )

    @Test fun missingEmptyAndUsedCharactersHaveDistinctActions() = runBlocking {
        val db = database()
        try {
            val check = CompanionCreation(db)
            assertEquals(CompanionEligibility.MissingCharacter, check.eligibility("missing"))
            db.characterDao().upsert(LocalCharacterEntity("c", "角色", createdAt = "now", updatedAt = "now"))
            assertEquals(CompanionEligibility.Available, check.eligibility("c"))
            db.sessionDao().upsert(session("empty"))
            val used = check.eligibility("c") as CompanionEligibility.Used
            assertEquals(listOf("empty"), used.candidates.map { it.id })
            // 页面检查后出现新会话，提交事务仍会拒绝，且不暴露 SQLite 文本。
            try {
                db.withTransaction { check.requireNew("c"); fail("Must recheck at submission") }
            } catch (e: CompanionCreationException) {
                assertEquals(com.nekobot.app.R.string.companion_create_used, e.messageRes)
            }
            assertEquals(1, db.sessionDao().count())
        } finally { db.close() }
    }

    @Test fun groupUseBlocksCreationButCannotBeTransferred() = runBlocking {
        val db = database()
        try {
            db.characterDao().upsert(LocalCharacterEntity("c", "角色", createdAt = "now", updatedAt = "now"))
            db.sessionDao().upsert(session("group", "group").copy(characterId = null, characterIds = "[\"c\"]"))
            assertTrue((CompanionCreation(db).eligibility("c") as CompanionEligibility.Used).candidates.isEmpty())
            db.sessionDao().upsert(session("agent", "agent").copy(inheritCharacter = true))
            assertEquals(listOf("agent"), (CompanionCreation(db).eligibility("c") as CompanionEligibility.Used).candidates.map { it.id })
        } finally { db.close() }
    }

    @Test fun ownerRemainsReservedAfterSessionDeletion() = runBlocking {
        val db = database()
        try {
            FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
            db.characterDao().upsert(LocalCharacterEntity("c", "角色", createdAt = "now", updatedAt = "now"))
            db.sessionDao().upsert(session("main", "agent").copy(companionMode = true, inheritCharacter = true, longConversationEnabled = true))
            assertEquals(CompanionEligibility.Existing("main"), CompanionCreation(db).eligibility("c"))
            db.sessionDao().deleteById("main")
            assertEquals(CompanionEligibility.ReservedCharacter, CompanionCreation(db).eligibility("c"))
        } finally { db.close() }
    }

    @Test fun freshCompanionUsesCardStateAndInsertsOneGreetingWhileOrdinaryAgentStaysUnchanged() = runBlocking {
        val db = database()
        try {
            db.characterDao().upsert(LocalCharacterEntity("c", "角色", state = "{\"affection\":72}", firstMessage = "角色卡开场白", createdAt = "now", updatedAt = "now"))
            val repo = LocalRepository(db, appContext = ApplicationProvider.getApplicationContext())
            val created = repo.createSession(com.nekobot.app.data.model.CreateSessionRequest(characterId = "c", sessionMode = "agent", companionMode = true, firstMessage = "自定义开场白"))
            assertEquals(listOf("自定义开场白"), db.messageDao().listBySession(requireNotNull(created.id)).map { it.content })
            val relationship = db.relationshipDao().get("c", "session:${created.id}")!!
            assertEquals(72, com.nekobot.app.data.local.ai.RelationshipState.fromJson(relationship.dataJson).affection)
            db.characterDao().upsert(LocalCharacterEntity("ordinary", "普通", firstMessage = "普通开场白", createdAt = "now", updatedAt = "now"))
            val agent = repo.createSession(com.nekobot.app.data.model.CreateSessionRequest(characterId = "ordinary", sessionMode = "agent"))
            assertTrue(db.messageDao().listBySession(requireNotNull(agent.id)).isEmpty())
        } finally { db.close() }
    }
}
