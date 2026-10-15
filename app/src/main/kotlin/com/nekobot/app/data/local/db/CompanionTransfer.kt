package com.nekobot.app.data.local.db

import androidx.room.*

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

