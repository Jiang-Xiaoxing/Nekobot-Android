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

class CompanionMigrationAndroidTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun migration50To51PreservesOldModeAndMemoryAndCreatesNoNewOwnership() {
        val name="companion-fused-migration-test"
        val old=helper.createDatabase(name,50)
        val fields=InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.nekobot.app.data.local.db.NekobotDatabase/50.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject["database"].asJsonObject["entities"].asJsonArray
                    .single { e -> e.asJsonObject["tableName"].asString=="local_sessions" }.asJsonObject["fields"].asJsonArray
            }
        val source=Gson().toJsonTree(LocalSessionEntity("old","保留旧聊天",characterId="c",sessionMode="agent",
            inheritCharacter=true,longConversationEnabled=true,createdAt="old-day",updatedAt="old-day")).asJsonObject
        val values=ContentValues()
        for (entry in fields) {
            val f=entry.asJsonObject; val column=f["columnName"].asString; val value=source[f["fieldPath"].asString]
            when { value==null || value.isJsonNull -> values.putNull(column)
                value.asJsonPrimitive.isBoolean -> values.put(column,if(value.asBoolean) 1 else 0)
                value.asJsonPrimitive.isNumber -> values.put(column,value.asLong)
                else -> values.put(column,value.asString) }
        }
        old.insert("local_sessions",SQLiteDatabase.CONFLICT_ABORT,values)
        old.execSQL("INSERT INTO companion_scopes VALUES ('scope','p','old','c',0,0,NULL,1,2,NULL,'old-day')")
        old.execSQL("INSERT INTO companion_memories VALUES ('fact','scope','diet',1,'fact','用户','怕辣','active',NULL,NULL,'[]',1,1,1,'old-day','old-day')")
        old.close()
        val current=helper.runMigrationsAndValidate(name,51,true,NekobotDatabase.MIGRATION_50_51)
        current.query("SELECT companion_mode,long_conversation_enabled FROM local_sessions WHERE id='old'").use {
            assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)); assertEquals(1,it.getInt(1))
        }
        current.query("SELECT content,locked,alwaysProvide,manual FROM companion_memories").use {
            assertTrue(it.moveToFirst()); assertEquals("怕辣",it.getString(0)); assertEquals(1,it.getInt(1)); assertEquals(0,it.getInt(2)); assertEquals(0,it.getInt(3))
        }
        current.query("SELECT COUNT(*) FROM companion_owners").use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
        current.close()
    }

    @Test fun full49To50KeepsIndependentFactsSourcesLocksAndQueue() {
        val name="companion-search-migration-test"
        val old=helper.createDatabase(name,49)
        old.execSQL("INSERT INTO companion_scopes VALUES ('scope','original-profile','session','character',12,14,'anchor',1,3,NULL,'original-day')")
        old.execSQL("INSERT INTO companion_memories VALUES ('fact','scope','diet',1,'fact','用户','怕辣','active',NULL,NULL,'[]',1,1,1,'created-day','updated-day')")
        old.execSQL("INSERT INTO companion_sources VALUES ('fact','source','digest','我怕辣','2026-10-01','user')")
        old.execSQL("INSERT INTO companion_receipts VALUES ('scope','source','digest')")
        old.execSQL("INSERT INTO companion_pending VALUES ('scope','next-source')")
        old.close()
        val migrated=helper.runMigrationsAndValidate(name,50,true,NekobotDatabase.MIGRATION_49_50)
        migrated.query("SELECT content,locked,createdAt FROM companion_memories WHERE id='fact'").use {
            assertTrue(it.moveToFirst()); assertEquals("怕辣",it.getString(0)); assertEquals(1,it.getInt(1)); assertEquals("created-day",it.getString(2))
        }
        migrated.query("SELECT quote,spokenDate FROM companion_sources").use {
            assertTrue(it.moveToFirst()); assertEquals("我怕辣",it.getString(0)); assertEquals("2026-10-01",it.getString(1))
        }
        migrated.query("SELECT messageId FROM companion_pending").use { assertTrue(it.moveToFirst()); assertEquals("next-source",it.getString(0)) }
        migrated.query("SELECT COUNT(*) FROM companion_vectors").use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM companion_clarifications").use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
        migrated.close()
    }

    @Test fun full48To50MigrationKeepsAllOldDataAndValidatesLatestRoomSchema() {
        val name="companion-full-migration-test"
        val old=helper.createDatabase(name,48)
        val schema=InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.nekobot.app.data.local.db.NekobotDatabase/48.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject["database"].asJsonObject["entities"].asJsonArray
            }
        fun insert(table:String, entity:Any) {
            val fields=schema.single { it.asJsonObject["tableName"].asString==table }.asJsonObject["fields"].asJsonArray
            val json=Gson().toJsonTree(entity).asJsonObject
            val values=ContentValues()
            for(field in fields) {
                val f=field.asJsonObject; val key=f["columnName"].asString; val value=json[f["fieldPath"].asString]
                if(value==null || value.isJsonNull) values.putNull(key)
                else if(value.asJsonPrimitive.isBoolean) values.put(key,if(value.asBoolean) 1 else 0)
                else if(value.asJsonPrimitive.isNumber) values.put(key,value.asLong)
                else values.put(key,value.asString)
            }
            old.insert(table,SQLiteDatabase.CONFLICT_ABORT,values)
        }
        val s=LocalSessionEntity("s","old session",characterId="c",sessionMode="agent",inheritCharacter=true,
            longConversationEnabled=true,createdAt="2026-01-01",updatedAt="2026-10-05")
        insert("local_sessions",s)
        insert("local_messages",LocalMessageEntity("source","s","user","OLD ORIGINAL",timestamp="2026-01-02",createdAt="2026-01-02"))
        insert("local_character_memories",LocalCharacterMemoryEntity("shared","c","local-user","long",title="Original",summary="AUTHOR MEMORY",content="AUTHOR MEMORY",importance=8,createdAt="2026-01-01"))
        insert("local_experience_archives",LocalExperienceArchiveEntity("episode","s",startMessageId="source",endMessageId="source",
            sourceStartedAt="2026-01-02",sourceEndedAt="2026-01-02",summary="MANUAL SUMMARY",tagsJson="[\"MANUAL TAG\"]",
            sourceFingerprint="old",summaryEdited=true,tagsEdited=true,createdAt="2026-01-02",updatedAt="2026-10-05"))
        insert("local_experience_sources",LocalExperienceSourceEntity("episode","source"))
        old.close()
        val migrated=helper.runMigrationsAndValidate(name,50,true,*NekobotDatabase.ALL_MIGRATIONS)
        migrated.query("SELECT content FROM local_messages WHERE id='source'").use { assertTrue(it.moveToFirst()); assertEquals("OLD ORIGINAL",it.getString(0)) }
        migrated.query("SELECT content FROM local_character_memories WHERE id='shared'").use { assertTrue(it.moveToFirst()); assertEquals("AUTHOR MEMORY",it.getString(0)) }
        migrated.query("SELECT summary,tags_json,summary_edited,tags_edited,character_id FROM local_experience_archives WHERE id='episode'").use {
            assertTrue(it.moveToFirst()); assertEquals("MANUAL SUMMARY",it.getString(0)); assertEquals("[\"MANUAL TAG\"]",it.getString(1))
            assertEquals(1,it.getInt(2)); assertEquals(1,it.getInt(3)); assertEquals("c",it.getString(4))
        }
        migrated.query("SELECT COUNT(*) FROM local_experience_sources").use { it.moveToFirst(); assertEquals(1,it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM companion_memories").use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM companion_vectors").use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM companion_clarifications").use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
        migrated.close()
    }
}
