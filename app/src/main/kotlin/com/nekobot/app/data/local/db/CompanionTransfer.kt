package com.nekobot.app.data.local.db

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

/** 只在本机恢复，备份导入不重放此操作；正文仍在独立 companion_memories 中。 */
@Entity(tableName = "companion_transfer_jobs", indices = [Index(value = ["profile", "sourceSessionId", "fingerprint"], unique = true)])
data class CompanionTransferJobEntity(
    @PrimaryKey val id: String,
    val profile: String,
    val sourceSessionId: String,
    val fingerprint: String,
    val targetCharacterId: String,
    val targetSessionId: String,
    val targetName: String,
    val phase: String,
    val directory: String,
    val reportJson: String,
    val createdAt: String,
    val updatedAt: String
)

@Dao
interface CompanionTransferDao {
    @Query("SELECT * FROM companion_transfer_jobs WHERE id=:id")
    suspend fun get(id: String): CompanionTransferJobEntity?
    @Query("SELECT * FROM companion_transfer_jobs WHERE profile=:profile AND sourceSessionId=:source AND fingerprint=:fingerprint")
    suspend fun find(profile: String, source: String, fingerprint: String): CompanionTransferJobEntity?
    @Query("SELECT * FROM companion_transfer_jobs WHERE profile=:profile ORDER BY createdAt DESC")
    suspend fun list(profile: String): List<CompanionTransferJobEntity>
    @Upsert suspend fun save(job: CompanionTransferJobEntity)
    @Query("DELETE FROM companion_transfer_jobs WHERE id=:id AND profile=:profile")
    suspend fun delete(id: String, profile: String)
}

internal object CompanionTransferSchema {
    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE companion_memories ADD COLUMN provenance TEXT NOT NULL DEFAULT 'native'")
        db.execSQL("ALTER TABLE companion_memories ADD COLUMN expiresAt TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_transfer_jobs (id TEXT NOT NULL PRIMARY KEY, profile TEXT NOT NULL, sourceSessionId TEXT NOT NULL, fingerprint TEXT NOT NULL, targetCharacterId TEXT NOT NULL, targetSessionId TEXT NOT NULL, targetName TEXT NOT NULL, phase TEXT NOT NULL, directory TEXT NOT NULL, reportJson TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_companion_transfer_jobs_profile_sourceSessionId_fingerprint ON companion_transfer_jobs(profile,sourceSessionId,fingerprint)")
    }
}
