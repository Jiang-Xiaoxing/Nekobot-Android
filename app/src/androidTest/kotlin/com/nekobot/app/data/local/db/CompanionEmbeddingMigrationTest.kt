package com.nekobot.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompanionEmbeddingMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun upgradeKeepsScopeAndDoesNotEnableOrDisableExistingEmbedding() {
        val name = "companion-embedding-54-55"
        helper.createDatabase(name, 54).use {
            it.execSQL("INSERT INTO companion_scopes(id,profile,sessionId,characterId,baselineRowId,archiveRowId,historyAccess,revision,updatedAt) VALUES ('scope','profile','s','c',0,0,1,7,'old')")
        }
        helper.runMigrationsAndValidate(name, 55, true, *NekobotDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT sessionId,characterId,revision,embeddingModelId FROM companion_scopes WHERE id='scope'").use {
                assertTrue(it.moveToFirst()); assertEquals("s", it.getString(0)); assertEquals("c", it.getString(1))
                assertEquals(7, it.getInt(2)); assertTrue(it.isNull(3))
            }
        }
    }
}
