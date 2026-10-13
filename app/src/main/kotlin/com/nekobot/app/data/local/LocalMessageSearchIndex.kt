package com.nekobot.app.data.local

import androidx.room.withTransaction
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 可重建的派生索引。触发器登记变更，索引和原文始终在同一事务内更新。 */
internal class LocalMessageSearchIndex(private val db: NekobotDatabase) {
    private val mutex = Mutex()
    private var initialized = false

    suspend fun searchIds(query: String, limit: Int): List<String>? = mutex.withLock {
        val match = MessageSearchTerms.query(query) ?: return@withLock null
        if (!initialized) {
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                sql.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS local_message_search USING fts4(message_id, terms, notindexed=message_id, tokenize=unicode61)")
                sql.execSQL("CREATE TABLE IF NOT EXISTS local_message_search_dirty(message_id TEXT NOT NULL PRIMARY KEY)")
                for ((name, event) in listOf("insert" to "INSERT", "update" to "UPDATE OF content, reasoning_content, deleted")) {
                    sql.execSQL("CREATE TRIGGER IF NOT EXISTS message_search_$name AFTER $event ON local_messages BEGIN INSERT OR IGNORE INTO local_message_search_dirty VALUES (new.id); END")
                }
                sql.execSQL("CREATE TRIGGER IF NOT EXISTS message_search_delete AFTER DELETE ON local_messages BEGIN DELETE FROM local_message_search WHERE message_id = old.id; DELETE FROM local_message_search_dirty WHERE message_id = old.id; END")
                sql.execSQL("INSERT OR IGNORE INTO local_message_search_dirty SELECT id FROM local_messages WHERE deleted = 0 AND id NOT IN (SELECT message_id FROM local_message_search WHERE message_id IS NOT NULL)")
            }
            initialized = true
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = db.withTransaction {
                val sql = db.openHelper.writableDatabase
                val rows = sql.query("SELECT m.id, m.content, m.reasoning_content, m.deleted FROM local_messages m JOIN local_message_search_dirty d ON d.message_id = m.id LIMIT 16").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(Triple(cursor.getString(0),
                            if (cursor.getInt(3) == 0) "${cursor.getString(1).orEmpty()}\n${cursor.getString(2).orEmpty()}" else null,
                            cursor.getInt(3)))
                    }
                }
                for ((id, text, _) in rows) {
                    currentCoroutineContext().ensureActive()
                    sql.execSQL("DELETE FROM local_message_search WHERE message_id = ?", arrayOf(id))
                    if (text != null) sql.execSQL("INSERT INTO local_message_search(message_id, terms) VALUES (?, ?)", arrayOf(id, MessageSearchTerms.document(text)))
                    sql.execSQL("DELETE FROM local_message_search_dirty WHERE message_id = ?", arrayOf(id))
                }
                rows.size
            }
            if (count == 0) break
        }
        db.openHelper.readableDatabase.query(
            "SELECT m.id FROM local_message_search JOIN local_messages m ON m.id = local_message_search.message_id " +
                "WHERE local_message_search MATCH ? AND m.deleted = 0 " +
                "AND (instr(lower(COALESCE(m.content, '')), lower(?)) > 0 OR instr(lower(COALESCE(m.reasoning_content, '')), lower(?)) > 0) " +
                "ORDER BY m.created_at DESC, m.rowid DESC LIMIT ?",
            arrayOf<Any>(match, query, query, limit)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }
}
