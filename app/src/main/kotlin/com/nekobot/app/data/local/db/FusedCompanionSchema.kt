package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Single-session ownership guards; existing sessions are never converted automatically. */
internal object FusedCompanionSchema {
    fun installTriggers(db: SupportSQLiteDatabase) {
        // Only install ownership guards when the companion column exists.
        if (!db.query("PRAGMA table_info(local_sessions)").use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "companion_mode") found = true
            found
        }) return
        val checks = """
            SELECT CASE WHEN NEW.companion_mode = 1 AND
              (NEW.character_id IS NULL OR NEW.character_id = '' OR lower(NEW.session_mode) != 'agent'
               OR NEW.inherit_character != 1 OR NEW.long_conversation_enabled != 1)
              THEN RAISE(ABORT, '长久陪伴必须绑定角色并保留 Agent 角色模式') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM companion_owners o WHERE
              (o.characterId = NEW.character_id OR instr(COALESCE(NEW.character_ids,''), '"' || o.characterId || '"') > 0)
              AND (o.sessionId != NEW.id OR NEW.companion_mode != 1))
              THEN RAISE(ABORT, '该角色已有长久陪伴主窗口，不能创建第二个窗口或直接转换模式') END;
            SELECT CASE WHEN NEW.companion_mode = 1 AND EXISTS(SELECT 1 FROM local_sessions s WHERE s.id != NEW.id
              AND (s.character_id = NEW.character_id OR instr(COALESCE(s.character_ids,''), '"' || NEW.character_id || '"') > 0))
              THEN RAISE(ABORT, '长久陪伴只接受全新角色；已有角色须备份后通过迁入流程转换') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM local_sessions s WHERE s.id = NEW.id AND
              (s.companion_mode != NEW.companion_mode OR
               (s.companion_mode = 1 AND s.character_id IS NOT NEW.character_id)))
              THEN RAISE(ABORT, '不能直接切换长久陪伴模式或更换绑定角色') END;
        """.trimIndent()
        for (action in listOf("INSERT", "UPDATE")) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS fused_session_guard_${action.lowercase()} BEFORE $action ON local_sessions BEGIN $checks END")
            // An outer UPDATE OR ABORT (portable merge) overrides OR IGNORE inside triggers.
            // Avoid attempting the duplicate insert at all; also refresh pre-fix installations.
            db.execSQL("DROP TRIGGER IF EXISTS fused_session_owner_${action.lowercase()}")
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS fused_session_owner_${action.lowercase()} AFTER $action ON local_sessions
              WHEN NEW.companion_mode = 1 BEGIN
                INSERT INTO companion_owners(characterId,sessionId)
                  SELECT NEW.character_id,NEW.id WHERE NOT EXISTS
                    (SELECT 1 FROM companion_owners WHERE characterId=NEW.character_id AND sessionId=NEW.id);
              END""")
        }
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS fused_owner_guard_insert BEFORE INSERT ON companion_owners
            WHEN EXISTS(SELECT 1 FROM companion_owners WHERE
              (characterId = NEW.characterId AND sessionId != NEW.sessionId) OR
              (sessionId = NEW.sessionId AND characterId != NEW.characterId))
            BEGIN SELECT RAISE(ABORT, '导入的长久陪伴归属与本机已有窗口冲突'); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS fused_material_scope_deleted AFTER DELETE ON companion_scopes
            BEGIN DELETE FROM companion_context_materials WHERE scopeId = OLD.id; END""")
    }
}
