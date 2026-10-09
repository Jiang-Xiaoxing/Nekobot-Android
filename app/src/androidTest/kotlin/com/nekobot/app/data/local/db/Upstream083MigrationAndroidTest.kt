package com.nekobot.app.data.local.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Upstream083MigrationAndroidTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun author48ToPersonal52PreservesConversation() = migrate(48)
    @Test fun personal49To52PreservesIndependentMemory() = migrate(49)
    @Test fun personal50To52PreservesIndependentMemory() = migrate(50)
    @Test fun personal51To52PreservesOwnershipAndMemory() = migrate(51)

    private fun migrate(version: Int) {
        val name = "upstream-083-migration-$version"
        val old = helper.createDatabase(name, version)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.nekobot.app.data.local.db.NekobotDatabase/$version.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject["database"].asJsonObject["entities"].asJsonArray
            }
        fun insert(table: String, entity: Any) {
            val fields = schema.single { it.asJsonObject["tableName"].asString == table }
                .asJsonObject["fields"].asJsonArray
            val source = Gson().toJsonTree(entity).asJsonObject
            val values = ContentValues()
            for (entry in fields) {
                val field = entry.asJsonObject
                val column = field["columnName"].asString
                val value = source[field["fieldPath"].asString]
                when {
                    value == null || value.isJsonNull -> values.putNull(column)
                    value.asJsonPrimitive.isBoolean -> values.put(column, if (value.asBoolean) 1 else 0)
                    value.asJsonPrimitive.isNumber -> values.put(column, value.asLong)
                    else -> values.put(column, value.asString)
                }
            }
            old.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
        }
        insert("local_sessions", LocalSessionEntity("s", "Preserved", characterId = "c", sessionMode = "agent",
            inheritCharacter = true, longConversationEnabled = true, companionMode = version >= 51,
            createdAt = "2026-10-01", updatedAt = "2026-10-01"))
        insert("local_messages", LocalMessageEntity("m", "s", "user", "ORIGINAL QUOTE",
            timestamp = "2026-10-01", createdAt = "2026-10-01"))
        if (version >= 49) {
            old.execSQL("INSERT INTO companion_scopes VALUES ('scope','original-profile','s','c',0,0,NULL,1,3,NULL,'original-day')")
            old.execSQL("INSERT INTO companion_memories(id,scopeId,factKey,version,kind,subject,content,status,tagsJson,current,valid,locked,createdAt,updatedAt) " +
                "VALUES ('fact','scope','diet',1,'fact','用户','怕辣','active','[]',1,1,1,'original-day','original-day')")
            old.execSQL("INSERT INTO companion_sources VALUES ('fact','m','digest','ORIGINAL QUOTE','2026-10-01','user')")
        }
        if (version >= 51) {
            old.execSQL("INSERT INTO companion_owners(characterId,sessionId) VALUES ('c','s')")
        }
        old.close()
        val db = helper.runMigrationsAndValidate(name, 52, true, *NekobotDatabase.ALL_MIGRATIONS)
        db.query("SELECT content FROM local_messages WHERE id='m'").use {
            assertTrue(it.moveToFirst()); assertEquals("ORIGINAL QUOTE", it.getString(0))
        }
        if (version >= 49) {
            db.query("SELECT content,locked,createdAt FROM companion_memories WHERE id='fact'").use {
                assertTrue(it.moveToFirst()); assertEquals("怕辣", it.getString(0))
                assertEquals(1, it.getInt(1)); assertEquals("original-day", it.getString(2))
            }
            db.query("SELECT quote FROM companion_sources WHERE memoryId='fact'").use {
                assertTrue(it.moveToFirst()); assertEquals("ORIGINAL QUOTE", it.getString(0))
            }
        }
        if (version >= 51) db.query("SELECT sessionId FROM companion_owners WHERE characterId='c'").use {
            assertTrue(it.moveToFirst()); assertEquals("s", it.getString(0))
        }
        for (table in listOf("local_subagent_tasks", "local_subagent_messages", "local_subagent_tool_calls", "local_agent_notices")) {
            db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        db.close()
    }
}
