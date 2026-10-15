package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive migration only: old messages, archives, source maps and manual edits are untouched. */
internal object CompanionSchema {
    fun installTriggers(db: SupportSQLiteDatabase) {
        // Search tables exist after the upstream 52 to 53 migration.
        val hasSearch = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='companion_vectors'").use { it.moveToFirst() }
        if (hasSearch) {
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_fact_search_invalidated
                AFTER UPDATE OF valid, content, subject, kind, tagsJson, eventDate ON companion_memories
                BEGIN DELETE FROM companion_vectors WHERE itemType = 'fact' AND itemId = NEW.id;
                  DELETE FROM companion_clarifications WHERE memoryId = NEW.id AND NEW.valid = 0; END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_fact_search_deleted AFTER DELETE ON companion_memories
                BEGIN DELETE FROM companion_vectors WHERE itemType = 'fact' AND itemId = OLD.id;
                  DELETE FROM companion_clarifications WHERE memoryId = OLD.id; END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_archive_search_changed
                AFTER UPDATE OF summary, tags_json, status, character_id ON local_experience_archives
                BEGIN DELETE FROM companion_vectors WHERE itemType = 'archive' AND itemId = NEW.id; END""")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_archive_search_deleted AFTER DELETE ON local_experience_archives
                BEGIN DELETE FROM companion_vectors WHERE itemType = 'archive' AND itemId = OLD.id; END""")
        }
        fun invalidate(id: String) = """
            INSERT OR IGNORE INTO companion_pending(scopeId,messageId)
              SELECT scopeId, $id FROM companion_receipts WHERE messageId = $id;
            UPDATE companion_memories SET valid = 0, current = 0
              WHERE id IN (SELECT memoryId FROM companion_sources WHERE messageId = $id);
            UPDATE local_experience_archives SET status = 'stale'
              WHERE id IN (SELECT archive_id FROM local_experience_sources WHERE message_id = $id);
            DELETE FROM companion_receipts WHERE messageId = $id;
            UPDATE companion_scopes SET revision = revision + 1
              WHERE sessionId IN (SELECT session_id FROM local_messages WHERE id = $id)
                 OR id IN (SELECT m.scopeId FROM companion_memories m JOIN companion_sources x ON x.memoryId = m.id WHERE x.messageId = $id)
                 OR id IN (SELECT p.scopeId FROM companion_pending p WHERE p.messageId = $id);
        """.trimIndent()
        // Timestamp changes matter even when the original text is unchanged.
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_message_changed
            AFTER UPDATE OF content, deleted, created_at, timestamp, role, session_id ON local_messages
            WHEN OLD.content IS NOT NEW.content OR OLD.deleted != NEW.deleted OR OLD.created_at IS NOT NEW.created_at
              OR OLD.timestamp IS NOT NEW.timestamp OR OLD.role IS NOT NEW.role OR OLD.session_id IS NOT NEW.session_id
            BEGIN ${invalidate("NEW.id")}
              UPDATE companion_scopes SET revision = revision + 1
                WHERE sessionId = OLD.session_id AND OLD.session_id IS NOT NEW.session_id;
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_message_deleted AFTER DELETE ON local_messages
            BEGIN ${invalidate("OLD.id")}
              DELETE FROM companion_sources WHERE messageId = OLD.id;
              UPDATE companion_scopes SET revision = revision + 1 WHERE sessionId = OLD.session_id;
            END""")
        // Room's existing message DAO uses INSERT OR REPLACE; AFTER INSERT also covers replacement.
        // BEFORE INSERT can still see the old owner, even when SQLite's implicit REPLACE
        // deletion does not fire DELETE triggers. This also protects imported raw-only evidence.
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_message_owner_replacing BEFORE INSERT ON local_messages
            WHEN EXISTS(SELECT 1 FROM local_messages WHERE id = NEW.id)
            BEGIN UPDATE companion_scopes SET revision = revision + 1
              WHERE sessionId IN (SELECT session_id FROM local_messages WHERE id = NEW.id); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_message_replaced AFTER INSERT ON local_messages
            WHEN EXISTS(SELECT 1 FROM companion_sources WHERE messageId = NEW.id)
              OR EXISTS(SELECT 1 FROM companion_receipts WHERE messageId = NEW.id)
            BEGIN ${invalidate("NEW.id")} END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_new_message AFTER INSERT ON local_messages
            WHEN NEW.deleted = 0 AND NEW.role IN ('user','assistant')
            BEGIN INSERT OR IGNORE INTO companion_pending(scopeId,messageId)
              SELECT s.id, NEW.id FROM companion_scopes s JOIN local_sessions c ON c.id = s.sessionId
              WHERE s.sessionId = NEW.session_id AND s.characterId = c.character_id
                AND c.long_conversation_enabled = 1 AND c.inherit_character = 1 AND lower(c.session_mode) = 'agent';
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_session_changed
            AFTER UPDATE OF character_id, long_conversation_enabled, inherit_character, session_mode ON local_sessions
            WHEN OLD.character_id IS NOT NEW.character_id OR OLD.long_conversation_enabled != NEW.long_conversation_enabled
              OR OLD.inherit_character != NEW.inherit_character OR OLD.session_mode IS NOT NEW.session_mode
            BEGIN UPDATE companion_scopes SET revision = revision + 1 WHERE sessionId = NEW.id; END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_session_deleted AFTER DELETE ON local_sessions
            BEGIN
              ${if (hasSearch) "DELETE FROM companion_vectors WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id); DELETE FROM companion_clarifications WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);" else ""}
              DELETE FROM companion_sources WHERE memoryId IN (SELECT id FROM companion_memories WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id));
              DELETE FROM companion_memories WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);
              DELETE FROM companion_receipts WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);
              DELETE FROM companion_forgotten_sources WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);
              DELETE FROM companion_pending WHERE scopeId IN (SELECT id FROM companion_scopes WHERE sessionId = OLD.id);
              DELETE FROM companion_scopes WHERE sessionId = OLD.id;
            END""")
    }
}
