package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** 63→64只新增结构和标记作废，不清除任何记忆或来源。 */
internal object CompanionMemoryV2Schema {
    fun migrate(db: SupportSQLiteDatabase) {
        for (column in listOf("voided", "pendingConfirm", "uncertainInjectedTurns")) {
            db.execSQL("ALTER TABLE companion_memories ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
        }
        for (column in listOf("voidedAt", "voidedReason", "supersededById", "uncertainTargetId",
            "timeText", "modelDate", "dateType", "dateSource")) {
            db.execSQL("ALTER TABLE companion_memories ADD COLUMN $column TEXT")
        }
        db.execSQL("ALTER TABLE companion_sources ADD COLUMN messageSnapshot TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_applied_ops (scopeId TEXT NOT NULL, opKey TEXT NOT NULL, memoryId TEXT, createdAt TEXT NOT NULL, PRIMARY KEY(scopeId,opKey))")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_locked_reviews (id TEXT NOT NULL PRIMARY KEY, scopeId TEXT NOT NULL, sessionId TEXT NOT NULL, lockedMemoryId TEXT NOT NULL, confirmedMemoryId TEXT NOT NULL, createdAt TEXT NOT NULL, resolvedAt TEXT, resolution TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_companion_locked_reviews_pending ON companion_locked_reviews(sessionId,resolvedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_companion_memories_scope_pending ON companion_memories(scopeId,pendingConfirm,voided)")
        db.execSQL("UPDATE companion_memories SET voided=1,voidedReason='retract',voidedAt=updatedAt WHERE status='retracted' AND current=0")
        installTriggers(db)
        CompanionSchema.installTriggers(db)
    }

    fun installTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_v2_scope_deleted AFTER DELETE ON companion_scopes
            BEGIN DELETE FROM companion_applied_ops WHERE scopeId=OLD.id;
              DELETE FROM companion_locked_reviews WHERE scopeId=OLD.id; END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS companion_v2_session_deleted AFTER DELETE ON local_sessions
            BEGIN DELETE FROM companion_locked_reviews WHERE sessionId=OLD.id; END""")
    }
}
