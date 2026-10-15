package com.nekobot.app.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.Cursor
import com.google.gson.Gson
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamCompanionMigrationTest {
    @Test
    fun newAndUpgradedDatabases_exportOpenTriggersForSqliteVerification() {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            when (method.name) {
                "execSQL" -> { statements += args!![0] as String; null }
                "query" -> {
                    var advanced = false
                    Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, operation, _ ->
                        when (operation.name) {
                            "moveToFirst" -> true
                            "moveToNext" -> (!advanced).also { advanced = true }
                            "getString" -> "companion_mode"
                            "close" -> null
                            else -> error("Unexpected cursor operation: ${operation.name}")
                        }
                    }
                }
                else -> error("Unexpected database operation: ${method.name}")
            }
        } as SupportSQLiteDatabase
        CompanionSchema.installTriggers(db)
        FusedCompanionSchema.installTriggers(db)
        assertTrue(statements.any { it.contains("fused_session_guard_insert") })
        println("NEKOBOT_COMPANION_TRIGGERS=" + Gson().toJson(statements))
    }

    @Test
    fun publishedMigrationsAndNewUpgrade_exportExecutedSqlForSqliteVerification() {
        val executed = linkedMapOf<Int, List<String>>()
        NekobotDatabase.ALL_MIGRATIONS.filter { it.startVersion >= 48 }.forEach { migration ->
            val statements = mutableListOf<String>()
            val db = Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java)
            ) { _, method, args ->
                check(method.name == "execSQL") { "Unexpected database operation: ${method.name}" }
                statements += args!![0] as String
                null
            } as SupportSQLiteDatabase
            migration.migrate(db)
            assertTrue(statements.isNotEmpty())
            executed[migration.startVersion] = statements
        }
        assertEquals(listOf(48, 49, 50, 51, 52), executed.keys.toList())
        assertEquals(UpstreamCompanionSchema.statements, executed[52])
        assertTrue(executed.getValue(48).first().contains("local_subagent_tasks"))
        assertTrue(executed.getValue(51).single().contains("source_task_id"))
        // The standard-library Python verifier executes these actual migration calls in SQLite.
        println("NEKOBOT_MIGRATION_SQL=" + Gson().toJson(executed))
    }
}
