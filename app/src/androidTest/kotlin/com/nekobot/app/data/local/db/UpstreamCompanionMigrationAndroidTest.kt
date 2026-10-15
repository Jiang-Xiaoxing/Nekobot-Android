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

class UpstreamCompanionMigrationAndroidTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)
    @Test fun official48To53() = upgrade(48)
    @Test fun official49To53() = upgrade(49)
    @Test fun official50To53() = upgrade(50)
    @Test fun official51To53() = upgrade(51)
    @Test fun official52To53() = upgrade(52)

    private fun upgrade(version: Int) {
        val name = "upstream-companion-$version"
        val old = helper.createDatabase(name, version)
        val entities = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.nekobot.app.data.local.db.NekobotDatabase/$version.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject["database"].asJsonObject["entities"].asJsonArray
            }
        fun insert(table: String, value: Any) {
            val fields = entities.single { it.asJsonObject["tableName"].asString == table }.asJsonObject["fields"].asJsonArray
            val json = Gson().toJsonTree(value).asJsonObject
            val values = ContentValues()
            for (field in fields) {
                val f = field.asJsonObject; val column = f["columnName"].asString; val item = json[f["fieldPath"].asString]
                when {
                    item == null || item.isJsonNull -> values.putNull(column)
                    item.asJsonPrimitive.isBoolean -> values.put(column, if (item.asBoolean) 1 else 0)
                    item.asJsonPrimitive.isNumber -> values.put(column, item.asLong)
                    else -> values.put(column, item.asString)
                }
            }
            old.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
        }
        insert("local_sessions", LocalSessionEntity("old", "Original session", characterId = "c", sessionMode = "agent",
            inheritCharacter = true, longConversationEnabled = true, createdAt = "old-day", updatedAt = "old-day"))
        insert("local_messages", LocalMessageEntity("quote", "old", "user", "ORIGINAL", timestamp = "old-day", createdAt = "old-day"))
        old.close()
        val current = helper.runMigrationsAndValidate(name, 53, true, *NekobotDatabase.ALL_MIGRATIONS)
        current.query("SELECT content FROM local_messages WHERE id='quote'").use {
            assertTrue(it.moveToFirst()); assertEquals("ORIGINAL", it.getString(0))
        }
        current.query("SELECT companion_mode,list_title_override FROM local_sessions WHERE id='old'").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertTrue(it.isNull(1))
        }
        for (table in listOf("companion_memories", "companion_sources", "companion_owners", "companion_vectors",
            "companion_relationship_state", "companion_relationship_events", "companion_relationship_assessments", "companion_transfer_jobs")) {
            current.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
        current.close()
    }
}
