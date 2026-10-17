package com.nekobot.app.data.local.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** 缺少记录的老会话/转换/导入会话默认没有邀请资格。 */
@Entity(tableName = "companion_examples", foreignKeys = [ForeignKey(entity = LocalSessionEntity::class,
    parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class CompanionExamplesEntity(
    @PrimaryKey val sessionId: String,
    val state: String,
    val pairsJson: String = "[]",
    val noticeAfterId: String? = null,
    @ColumnInfo(defaultValue = "0") val optedIn: Boolean = false
)

@Dao
interface CompanionExamplesDao {
    @Query("SELECT * FROM companion_examples WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): CompanionExamplesEntity?
    @Query("SELECT * FROM companion_examples WHERE sessionId = :sessionId")
    fun observe(sessionId: String): Flow<CompanionExamplesEntity?>
    @Upsert suspend fun save(row: CompanionExamplesEntity)
    /** 先落库再调用；双击、重进页面或进程中断均不能再次挑选。 */
    @Query(CompanionExamplesSql.CLAIM)
    suspend fun claim(sessionId: String): Int
    @Query("SELECT * FROM local_messages WHERE session_id = :sessionId ORDER BY created_at,rowid LIMIT 100")
    suspend fun earlyMessages(sessionId: String): List<LocalMessageEntity>
}

internal object CompanionExamplesSql {
    const val CLAIM = "UPDATE companion_examples SET state = 'selecting', optedIn = 1 WHERE sessionId = :sessionId AND state = 'offered'"
}

internal object CompanionExamplesSchema {
    fun create(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS companion_examples (sessionId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, pairsJson TEXT NOT NULL, noticeAfterId TEXT, optedIn INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(sessionId) REFERENCES local_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
