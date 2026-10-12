package com.nekobot.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SessionListTitleMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun upgradeKeepsOldNamesMessagesAndSettingsWithoutGuessingManualTitles() {
        val databaseName = "session-list-title-59-60"
        helper.createDatabase(databaseName, 59).use { db ->
            db.execSQL("INSERT INTO local_sessions(id,name,character_id,character_name,favorite,pinned,archived,created_at,updated_at,message_count,plot_mode,plot_realtime_sync,auto_state_interval,is_public,companion_mode) VALUES ('s','原会话名称','c','林小满',1,1,0,'old','old',1,0,0,5,0,0)")
            db.execSQL("INSERT INTO local_messages(id,session_id,role,content,timestamp,created_at) VALUES ('m','s','user','原有聊天','old','old')")
        }
        helper.runMigrationsAndValidate(databaseName, 60, true, *NekobotDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT name,character_id,character_name,favorite,pinned,auto_state_interval,list_title_override FROM local_sessions WHERE id='s'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("原会话名称", c.getString(0)); assertEquals("c", c.getString(1))
                assertEquals("林小满", c.getString(2)); assertEquals(1, c.getInt(3)); assertEquals(1, c.getInt(4))
                assertEquals(5, c.getInt(5)); assertTrue(c.isNull(6))
            }
            db.query("SELECT content FROM local_messages WHERE id='m'").use { c -> assertTrue(c.moveToFirst()); assertEquals("原有聊天", c.getString(0)) }
        }
    }
}
