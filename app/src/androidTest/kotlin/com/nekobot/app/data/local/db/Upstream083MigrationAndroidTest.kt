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

    @Test fun main52ToLatestPreservesConversation() = migrate(52)
    @Test fun allExportedMainVersionsToLatestValidateRoomSchema() {
        (31..51).forEach(::migrate)
    }

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
            inheritCharacter = true, longConversationEnabled = true, companionMode = false,
            createdAt = "2026-10-01", updatedAt = "2026-10-01"))
        insert("local_messages", LocalMessageEntity("m", "s", "user", "ORIGINAL QUOTE",
            timestamp = "2026-10-01", createdAt = "2026-10-01"))
        old.close()
        val db = helper.runMigrationsAndValidate(name, 60, true, *NekobotDatabase.ALL_MIGRATIONS)
        db.query("SELECT content FROM local_messages WHERE id='m'").use {
            assertTrue(it.moveToFirst()); assertEquals("ORIGINAL QUOTE", it.getString(0))
        }
        for (table in listOf("companion_memories", "companion_owners", "companion_relationship_state")) {
            db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
        db.query("SELECT companion_mode FROM local_sessions WHERE id='s'").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        for (table in listOf("local_subagent_tasks", "local_subagent_messages", "local_subagent_tool_calls", "local_agent_notices")) {
            db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        db.close()
    }
}
