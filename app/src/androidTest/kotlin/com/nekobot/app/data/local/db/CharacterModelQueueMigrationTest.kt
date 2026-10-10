package com.nekobot.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CharacterModelQueueMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun upgradePreservesCharactersAndLeavesExistingRolesOnDefaultQueue() {
        val name = "character-model-queue-56-57"
        helper.createDatabase(name, 56).use {
            it.execSQL("INSERT INTO local_characters(id,name,personality,created_at,updated_at) VALUES ('c','测试角色','原设定','old','old')")
        }
        helper.runMigrationsAndValidate(name, 57, true, *NekobotDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT name,personality,chat_model_queue FROM local_characters WHERE id='c'").use {
                assertTrue(it.moveToFirst())
                assertEquals("测试角色", it.getString(0))
                assertEquals("原设定", it.getString(1))
                assertTrue(it.isNull(2))
            }
        }
    }
}
