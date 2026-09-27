package com.nekobot.app.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NekobotDatabaseLatestMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun cleanDatabase() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migration30To31_preservesRowsAndAddsRoutingSchema() {
        open(version = 30, onCreate = { db ->
            db.execSQL(
                "CREATE TABLE local_knowledge_chunks " +
                    "(id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE local_messages " +
                    "(id TEXT NOT NULL PRIMARY KEY, content TEXT)"
            )
            db.execSQL(
                "INSERT INTO local_knowledge_chunks(id, content) VALUES ('chunk-1', '正文')"
            )
            db.execSQL(
                "INSERT INTO local_messages(id, content) VALUES ('message-1', '回复')"
            )
        }).close()

        val migrated = open(
            version = 31,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(30, oldVersion)
                assertEquals(31, newVersion)
                NekobotDatabase.MIGRATION_30_31.migrate(db)
            }
        )
        val db = migrated.writableDatabase

        assertTrue(columnNames(db, "local_knowledge_chunks").containsAll(listOf("char_offset", "char_end")))
        assertTrue(columnNames(db, "local_messages").containsAll(listOf("knowledge_citations", "routing_decision_id")))
        assertTrue(tableExists(db, "routing_decision_logs"))
        db.query("SELECT content FROM local_messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("回复", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun migration32To33_createsMessageImageStorage() {
        open(version = 32).close()

        val migrated = open(
            version = 33,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(32, oldVersion)
                assertEquals(33, newVersion)
                NekobotDatabase.MIGRATION_32_33.migrate(db)
            }
        )
        val db = migrated.writableDatabase

        assertTrue(tableExists(db, "local_message_images"))
        assertTrue(
            columnNames(db, "local_message_images").containsAll(
                listOf("session_id", "message_id", "prompt", "status", "file_path", "updated_at")
            )
        )
        migrated.close()
    }

    @Test
    fun migration33To34_addsReferenceImageColumns() {
        open(version = 33, onCreate = { db ->
            db.execSQL(
                "CREATE TABLE local_message_images " +
                    "(id TEXT NOT NULL PRIMARY KEY, session_id TEXT NOT NULL, message_id TEXT NOT NULL, " +
                    "prompt TEXT NOT NULL, status TEXT NOT NULL, file_name TEXT, file_path TEXT, " +
                    "mime_type TEXT, model_id TEXT, model_name TEXT, error_message TEXT, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)"
            )
        }).close()

        val migrated = open(
            version = 34,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(33, oldVersion)
                assertEquals(34, newVersion)
                NekobotDatabase.MIGRATION_33_34.migrate(db)
            }
        )
        val db = migrated.writableDatabase
        assertTrue(
            columnNames(db, "local_message_images").containsAll(
                listOf("reference_image_path", "reference_image_mime_type")
            )
        )
        migrated.close()
    }

    @Test
    fun migration34To35_addsMessageAudioUpdateTime() {
        open(version = 34, onCreate = { db ->
            db.execSQL(
                "CREATE TABLE local_messages " +
                    "(id TEXT NOT NULL PRIMARY KEY, audio_url TEXT, created_at TEXT NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO local_messages(id, audio_url, created_at) " +
                    "VALUES ('message-1', 'file:///data/user/0/test/files/tts/reply.mp3', '2026-08-18T00:00:00Z')"
            )
        }).close()

        val migrated = open(
            version = 35,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(34, oldVersion)
                assertEquals(35, newVersion)
                NekobotDatabase.MIGRATION_34_35.migrate(db)
            }
        )
        val db = migrated.writableDatabase
        assertTrue(columnNames(db, "local_messages").contains("audio_updated_at"))
        db.query("SELECT audio_url, audio_updated_at FROM local_messages WHERE id = 'message-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("file:///data/user/0/test/files/tts/reply.mp3", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.close()
    }

    @Test
    fun migration42To43_addsSessionAutoNameInterval() {
        open(version = 42, onCreate = { db ->
            db.execSQL(
                "CREATE TABLE local_sessions " +
                    "(id TEXT NOT NULL PRIMARY KEY, name TEXT, auto_state_interval INTEGER NOT NULL DEFAULT 2)"
            )
            db.execSQL(
                "INSERT INTO local_sessions(id, name, auto_state_interval) VALUES ('session-1', '新会话', 2)"
            )
        }).close()

        val migrated = open(
            version = 43,
            onUpgrade = { db, oldVersion, newVersion ->
                assertEquals(42, oldVersion)
                assertEquals(43, newVersion)
                NekobotDatabase.MIGRATION_42_43.migrate(db)
            }
        )
        val db = migrated.writableDatabase

        assertTrue(columnNames(db, "local_sessions").contains("auto_name_interval"))
        db.query("SELECT name, auto_name_interval FROM local_sessions WHERE id = 'session-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("新会话", cursor.getString(0))
            // 旧数据升级后沿用原先硬编码的 10 条间隔
            assertEquals(10, cursor.getInt(1))
        }
        migrated.close()
    }

    @Test
    fun migration45To46_preservesMemoryAndInvalidatesEditedExperience() {
        open(version = 45, onCreate = { db ->
            db.execSQL("CREATE TABLE local_sessions (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE local_character_memories (id TEXT NOT NULL PRIMARY KEY, content TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE local_messages (id TEXT NOT NULL PRIMARY KEY, session_id TEXT NOT NULL, " +
                    "created_at TEXT NOT NULL, content TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("INSERT INTO local_sessions(id, name) VALUES ('s1', '旧会话')")
            db.execSQL("INSERT INTO local_character_memories(id, content) VALUES ('m1', '原来的角色记忆正文')")
            db.execSQL(
                "INSERT INTO local_messages(id, session_id, created_at, content) " +
                    "VALUES ('msg1', 's1', '2026-09-27T10:00:00Z', '中秋送了一本书')"
            )
        }).close()

        val migrated = open(version = 46, onUpgrade = { db, _, _ ->
            NekobotDatabase.MIGRATION_45_46.migrate(db)
        })
        val db = migrated.writableDatabase
        assertTrue(columnNames(db, "local_sessions").containsAll(listOf("long_conversation_enabled", "long_conversation_tail_until_id")))
        assertTrue(columnNames(db, "local_character_memories").containsAll(listOf("tags_json", "tags_edited", "source_session_id")))
        assertTrue(tableExists(db, "local_experience_archives"))
        assertTrue(tableExists(db, "local_experience_sources"))
        assertTrue(tableExists(db, "local_experience_archive_jobs"))
        db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM local_messages WHERE session_id = 's1' " +
                "AND created_at > '2026-09-27' ORDER BY created_at, rowid"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(3).contains("index_local_messages_session_id_created_at"))
        }
        db.query("SELECT content, tags_json FROM local_character_memories WHERE id = 'm1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("原来的角色记忆正文", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        db.query("SELECT long_conversation_enabled FROM local_sessions WHERE id = 's1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        db.execSQL(
            "INSERT INTO local_experience_archives(id, session_id, start_message_id, end_message_id, " +
                "source_started_at, source_ended_at, summary, source_fingerprint, created_at, updated_at) " +
                "VALUES ('a1', 's1', 'msg1', 'msg1', '2026-09-27', '2026-09-27', '中秋礼物', 'hash', 'now', 'now')"
        )
        db.execSQL("INSERT INTO local_experience_sources(archive_id, message_id) VALUES ('a1', 'msg1')")
        db.execSQL("UPDATE local_messages SET content = '中秋送了月饼' WHERE id = 'msg1'")
        db.query("SELECT status FROM local_experience_archives WHERE id = 'a1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("stale", cursor.getString(0))
        }
        db.execSQL("UPDATE local_experience_archives SET status = 'ready' WHERE id = 'a1'")
        db.execSQL("UPDATE local_messages SET deleted = 1 WHERE id = 'msg1'")
        db.query("SELECT status FROM local_experience_archives WHERE id = 'a1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("stale", cursor.getString(0))
        }
        db.execSQL("UPDATE local_experience_archives SET status = 'ready' WHERE id = 'a1'")
        db.execSQL("DELETE FROM local_messages WHERE id = 'msg1'")
        db.query("SELECT status FROM local_experience_archives WHERE id = 'a1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("stale", cursor.getString(0))
        }
        migrated.close()
    }

    private fun open(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit = {},
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    onUpgrade(db, oldVersion, newVersion)
            })
            .build()
    ).also { it.writableDatabase }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private companion object {
        const val TEST_DB = "migration-30-31-test.db"
    }
}
