package com.nekobot.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompanionTransferMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun upgrade52CreatesEmptyCompanionStorageAndJobLedger() {
        val name = "companion-transfer-52-53"
        helper.createDatabase(name, 52).close()
        helper.runMigrationsAndValidate(name, 53, true, *NekobotDatabase.ALL_MIGRATIONS).use { db ->
            for (table in listOf("companion_memories", "companion_owners", "companion_transfer_jobs")) {
                db.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        }
    }

    @Test fun upgrade53PreservesEvidenceAndOwnershipAndKeepsEmptyJobLedger() {
        val name = "companion-transfer-53-60"
        helper.createDatabase(name, 53).use { old ->
            old.execSQL("INSERT INTO companion_memories(id,scopeId,factKey,version,kind,subject,content,status,tagsJson,current,valid,locked,createdAt,updatedAt) VALUES ('m','s','diet',1,'fact','玩家','怕辣','active','[]',1,1,1,'old','old')")
            old.execSQL("INSERT INTO companion_owners VALUES ('c','s')")
        }
        helper.runMigrationsAndValidate(name, 60, true, *NekobotDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT content,provenance,expiresAt,locked FROM companion_memories WHERE id='m'").use {
                assertTrue(it.moveToFirst()); assertEquals("怕辣", it.getString(0))
                assertEquals("native", it.getString(1)); assertTrue(it.isNull(2)); assertEquals(1, it.getInt(3))
            }
            db.query("SELECT sessionId FROM companion_owners WHERE characterId='c'").use {
                assertTrue(it.moveToFirst()); assertEquals("s", it.getString(0))
            }
            db.query("SELECT COUNT(*) FROM companion_transfer_jobs").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
