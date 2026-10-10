package com.nekobot.app.data.local.db

import androidx.room.*

@Entity(tableName = "companion_relationship_state", foreignKeys = [ForeignKey(
    entity = LocalSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class CompanionRelationshipEntity(
    @PrimaryKey val sessionId: String,
    val characterId: String,
    val version: Long = 0,
    val enabled: Boolean = false,
    val automatic: Boolean = false,
    val interval: Int = 5,
    val modelId: String? = null,
    val awaitingHistory: Boolean = false,
    val cursorId: String? = null,
    val baselineJson: String = "{}",
    val legacy: Boolean = false,
    val mood: String = "尚未评估",
    val energy: String = "尚未评估",
    val feelingReason: String = "",
    val feelingSources: String = "[]",
    val updatedAt: Long = 0,
    val notice: String? = null,
    val correctionResult: String? = null
)

/** 仅记录事件对关系的影响及来源引用，不另建事实记忆库；修正追加版本，保留核对轨迹。 */
@Entity(tableName = "companion_relationship_events",
    foreignKeys = [ForeignKey(entity = CompanionRelationshipEntity::class, parentColumns = ["sessionId"],
        childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "eventKey", "revision"], unique = true)])
data class CompanionRelationshipEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val eventKey: String,
    val revision: Long,
    val summary: String,
    val reason: String,
    val kind: String,
    val deltasJson: String,
    val sourcesJson: String,
    val createdAt: Long,
    val corrected: Boolean = false
)

@Dao
interface CompanionRelationshipDao {
    @Query("SELECT * FROM local_messages WHERE session_id = :id AND id IN (:ids) AND deleted = 0 ORDER BY created_at, rowid")
    suspend fun sources(id: String, ids: List<String>): List<LocalMessageEntity>
    @Query("SELECT * FROM companion_relationship_state WHERE sessionId = :id")
    fun observe(id: String): kotlinx.coroutines.flow.Flow<CompanionRelationshipEntity?>
    @Query("SELECT * FROM companion_relationship_state WHERE sessionId = :id")
    suspend fun state(id: String): CompanionRelationshipEntity?
    @Upsert suspend fun save(state: CompanionRelationshipEntity)
    @Insert suspend fun append(events: List<CompanionRelationshipEventEntity>)
    @Query("SELECT e.* FROM companion_relationship_events e WHERE sessionId = :id AND NOT EXISTS (SELECT 1 FROM companion_relationship_events n WHERE n.sessionId = e.sessionId AND n.eventKey = e.eventKey AND n.revision > e.revision) ORDER BY createdAt DESC")
    suspend fun currentEvents(id: String): List<CompanionRelationshipEventEntity>
    @Query("SELECT * FROM companion_relationship_events WHERE sessionId = :id AND eventKey = :key ORDER BY revision DESC")
    suspend fun history(id: String, key: String): List<CompanionRelationshipEventEntity>
}
