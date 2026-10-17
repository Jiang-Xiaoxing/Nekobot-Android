package com.nekobot.app.data.local.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompanionRelationshipNarrativeMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NekobotDatabase::class.java)

    @Test fun upgradePreservesOldNumbersEvidenceCursorAndSettingsWithoutInventingText() {
        val name = "companion-relationship-text-58-59"
        helper.createDatabase(name, 58).use { db ->
            db.execSQL("INSERT INTO local_sessions(id,name,character_id,favorite,pinned,archived,created_at,updated_at,message_count,plot_mode,plot_realtime_sync,auto_state_interval,is_public,companion_mode) VALUES ('s','旧单会话','c',0,0,0,'old','old',2,0,0,5,0,1)")
            db.execSQL("INSERT INTO companion_relationship_state(sessionId,characterId,version,enabled,automatic,interval,modelId,awaitingHistory,cursorId,baselineJson,legacy,mood,energy,feelingReason,feelingSources,updatedAt,moodIntensity,historyScopeVersion,historyReviewRequired) VALUES ('s','c',7,1,1,5,'chosen-model',0,'reply-old','{\"trust\":50}',1,'好奇','有些疲惫','原有依据','[]',123,0.35,1,0)")
            db.execSQL("INSERT INTO companion_relationship_events(id,sessionId,eventKey,revision,summary,reason,kind,deltasJson,sourcesJson,createdAt,corrected) VALUES ('e','s','promise',1,'兑现约定','原话依据','reliability','{\"trust\":3}','[]',123,0)")
        }
        helper.runMigrationsAndValidate(name, 59, true, *NekobotDatabase.ALL_MIGRATIONS).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            db.query("SELECT mood,energy,moodIntensity,cursorId,version,baselineJson,enabled,automatic,modelId,narrativeJson,legacySnapshotJson FROM companion_relationship_state WHERE sessionId='s'").use {
                assertTrue(it.moveToFirst()); assertEquals("好奇", it.getString(0)); assertEquals("有些疲惫", it.getString(1))
                assertEquals(0.35, it.getDouble(2), 0.0001); assertEquals("reply-old", it.getString(3)); assertEquals(7, it.getInt(4))
                assertEquals("{\"trust\":50}", it.getString(5)); assertEquals(1, it.getInt(6)); assertEquals(1, it.getInt(7))
                assertEquals("chosen-model", it.getString(8)); assertTrue(it.isNull(9)); assertTrue(it.isNull(10))
            }
            db.query("SELECT deltasJson,impactsJson FROM companion_relationship_events WHERE id='e'").use {
                assertTrue(it.moveToFirst()); assertEquals("{\"trust\":3}", it.getString(0)); assertTrue(it.isNull(1))
            }
            db.execSQL("INSERT INTO companion_relationship_assessments(id,sessionId,revision,narrativeJson,createdAt) VALUES ('a','s',8,'{}',456)")
            db.execSQL("DELETE FROM companion_relationship_state WHERE sessionId='s'")
            db.query("SELECT COUNT(*) FROM companion_relationship_assessments WHERE sessionId='s'").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
