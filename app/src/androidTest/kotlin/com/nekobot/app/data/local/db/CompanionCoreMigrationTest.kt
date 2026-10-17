package com.nekobot.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompanionCoreMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun upgradePreservesExistingCoreAndManualPin() {
        val name = "companion-core-53-54"
        helper.createDatabase(name, 53).use {
            it.execSQL("INSERT INTO companion_memories(id,scopeId,factKey,version,kind,subject,content,status,tagsJson,current,valid,locked,alwaysProvide,createdAt,updatedAt) VALUES ('m','s','name',1,'core','用户','叫我小舟','active','[]',1,1,0,1,'old','old')")
        }
        helper.runMigrationsAndValidate(name, 54, true, *NekobotDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT content,kind,alwaysProvide,coreExcluded FROM companion_memories WHERE id='m'").use {
                assertTrue(it.moveToFirst())
                assertEquals("叫我小舟", it.getString(0)); assertEquals("core", it.getString(1))
                assertEquals(1, it.getInt(2)); assertEquals(0, it.getInt(3))
            }
        }
    }
}
