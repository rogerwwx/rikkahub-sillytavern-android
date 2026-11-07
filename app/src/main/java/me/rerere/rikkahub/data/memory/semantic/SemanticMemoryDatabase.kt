package me.rerere.rikkahub.data.memory.semantic

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import java.util.UUID

val SEMANTIC_MEMORY_TYPES = setOf("episodic", "factual", "relational", "preference", "planned", "unknown")
val SEMANTIC_MEMORY_STATUSES = setOf("active", "expired", "dormant", "archived", "superseded", "forgotten")

@Entity(tableName = "semantic_memory", indices = [Index("assistantId")])
data class SemanticMemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val assistantId: String,
    val content: String,
    val type: String = "unknown",
    val importance: Double = 0.5,
    val confidence: Double = 1.0,
    val isCore: Boolean = false,
    val status: String = "active",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val expiresAt: Long? = null,
    val contentVersion: Int = 1,
    val contentHash: String = semanticContentHash(content),
    val metadataJson: String = "{}",
    val sourceJson: String = "[]",
    val embedding: ByteArray? = null,
    val embeddingFingerprint: String? = null,
    val embeddingDimension: Int? = null,
    val embeddedVersion: Int? = null,
    val indexedAt: Long? = null,
    val accessCount: Long = 0,
    val lastAccessAt: Long? = null,
)

@Entity(tableName = "semantic_event", indices = [Index("assistantId")])
data class SemanticDiagnosticEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val assistantId: String,
    val operation: String,
    val purpose: String,
    val status: String = "running",
    val startedAt: Long = System.currentTimeMillis(),
    val durationNanos: Long = 0,
    val detailJson: String = "{}",
    val error: String? = null,
)

@Entity(tableName = "semantic_job", indices = [Index("assistantId")])
data class SemanticJobEntity(
    @PrimaryKey val id: String,
    val assistantId: String,
    val kind: String,
    val payloadJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SemanticLexicalHit(val id: String, val score: Double)

data class SemanticMemoryRevision(
    val id: String,
    val contentVersion: Int,
    val contentHash: String,
    val updatedAt: Long,
    val status: String,
    val isCore: Boolean,
    val expiresAt: Long?,
    val embeddingFingerprint: String?,
    val indexedAt: Long?,
)

@Dao
interface SemanticMemoryDAO {
    @Query("""SELECT id, contentVersion, contentHash, updatedAt, status, isCore, expiresAt,
        embeddingFingerprint, indexedAt FROM semantic_memory WHERE assistantId = :assistantId ORDER BY id""")
    suspend fun revisions(assistantId: String): List<SemanticMemoryRevision>

    @Query("SELECT * FROM semantic_memory WHERE assistantId = :assistantId ORDER BY updatedAt DESC, id")
    suspend fun list(assistantId: String): List<SemanticMemoryEntity>

    @Query("SELECT * FROM semantic_memory WHERE assistantId = :assistantId ORDER BY updatedAt DESC, id")
    fun observe(assistantId: String): Flow<List<SemanticMemoryEntity>>

    @Query("SELECT * FROM semantic_memory WHERE assistantId = :assistantId AND id = :id")
    suspend fun get(assistantId: String, id: String): SemanticMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SemanticMemoryEntity)

    @Update
    suspend fun put(entity: SemanticMemoryEntity)

    @Query("DELETE FROM semantic_memory WHERE assistantId = :assistantId AND id IN (:ids)")
    suspend fun delete(assistantId: String, ids: List<String>): Int

    @Query("""UPDATE semantic_memory SET embedding = :bytes, embeddingFingerprint = :fingerprint,
        embeddingDimension = :dimensions, embeddedVersion = :version, indexedAt = :time
        WHERE assistantId = :assistantId AND id = :id AND contentVersion = :version AND contentHash = :hash""")
    suspend fun storeVector(assistantId: String, id: String, version: Int, hash: String,
        bytes: ByteArray, fingerprint: String, dimensions: Int, time: Long): Int

    @Query("UPDATE semantic_memory SET accessCount = accessCount + 1, lastAccessAt = :time WHERE assistantId = :assistantId AND id IN (:ids)")
    suspend fun recordAccess(assistantId: String, ids: List<String>, time: Long)

    @RawQuery
    suspend fun lexical(query: SupportSQLiteQuery): List<SemanticLexicalHit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEvent(event: SemanticDiagnosticEvent)

    @Query("SELECT * FROM semantic_event WHERE (:assistantId IS NULL OR assistantId = :assistantId) ORDER BY startedAt DESC")
    suspend fun events(assistantId: String? = null): List<SemanticDiagnosticEvent>

    @Query("SELECT * FROM semantic_event WHERE id = :id")
    suspend fun getEvent(id: String): SemanticDiagnosticEvent?

    @Query("UPDATE semantic_event SET status = 'interrupted', error = '上次进程中断，结果未知' WHERE status = 'running'")
    suspend fun interruptRunningEvents(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putJob(job: SemanticJobEntity)

    @Query("""SELECT id, assistantId, kind, updatedAt, length(payloadJson) AS payloadLength,
        CASE WHEN length(payloadJson) <= 16384 THEN payloadJson ELSE NULL END AS inlinePayload
        FROM semantic_job WHERE id = :id""")
    suspend fun jobHeader(id: String): SemanticJobHeader?

    @Query("""SELECT id, assistantId, kind, updatedAt, length(payloadJson) AS payloadLength,
        CASE WHEN length(payloadJson) <= 16384 THEN payloadJson ELSE NULL END AS inlinePayload
        FROM semantic_job WHERE assistantId = :assistantId ORDER BY updatedAt DESC""")
    suspend fun jobHeaders(assistantId: String): List<SemanticJobHeader>

    @Query("SELECT substr(payloadJson, :offset, :length) FROM semantic_job WHERE id = :id")
    suspend fun jobPayloadSlice(id: String, offset: Int, length: Int): String?

    // 同一读事务防止恢复大任务时拼接到不同修订的进度；既有单行存储无需迁移。
    @Transaction
    suspend fun getJob(id: String): SemanticJobEntity? = jobHeader(id)?.let { materializeJob(it) }

    @Transaction
    suspend fun jobs(assistantId: String): List<SemanticJobEntity> = jobHeaders(assistantId).map { materializeJob(it) }

    private suspend fun materializeJob(header: SemanticJobHeader): SemanticJobEntity = SemanticJobEntity(
        header.id, header.assistantId, header.kind,
        readSemanticJobPayload(header) { offset, length -> jobPayloadSlice(header.id, offset, length) },
        header.updatedAt,
    )

    @Query("DELETE FROM semantic_job WHERE id = :id")
    suspend fun deleteJob(id: String)
}

@Database(entities = [SemanticMemoryEntity::class, SemanticDiagnosticEvent::class, SemanticJobEntity::class],
    version = SemanticMemoryDatabase.VERSION, exportSchema = false)
abstract class SemanticMemoryDatabase : RoomDatabase() {
    abstract fun dao(): SemanticMemoryDAO

    companion object {
        const val NAME = "semantic_memory.db"
        const val VERSION = 1

        fun create(context: Context, name: String = NAME): SemanticMemoryDatabase =
            Room.databaseBuilder(context, SemanticMemoryDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .openHelperFactory(RequerySQLiteOpenHelperFactory(listOf(
                    RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                        options.customExtensions.add(SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple", null))
                        options
                    }
                )))
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        val dict = SimpleDictManager.extractDict(context)
                        db.query("SELECT jieba_dict(?)", arrayOf(dict.absolutePath)).use { cursor ->
                            check(cursor.moveToFirst() && cursor.getString(0)?.trimEnd('/') == dict.absolutePath.trimEnd('/')) {
                                "语义记忆中文词典初始化失败"
                            }
                        }
                        db.execSQL("""CREATE VIRTUAL TABLE IF NOT EXISTS semantic_fts USING fts5(
                            content, id UNINDEXED, assistantId UNINDEXED, tokenize = 'simple')""")
                        db.execSQL("""CREATE TRIGGER IF NOT EXISTS semantic_fts_insert AFTER INSERT ON semantic_memory
                            BEGIN INSERT INTO semantic_fts(content,id,assistantId) VALUES(new.content,new.id,new.assistantId); END""")
                        db.execSQL("""CREATE TRIGGER IF NOT EXISTS semantic_fts_delete AFTER DELETE ON semantic_memory
                            BEGIN DELETE FROM semantic_fts WHERE id=old.id; END""")
                        db.execSQL("""CREATE TRIGGER IF NOT EXISTS semantic_fts_update AFTER UPDATE OF content ON semantic_memory
                            BEGIN DELETE FROM semantic_fts WHERE id=old.id;
                            INSERT INTO semantic_fts(content,id,assistantId) VALUES(new.content,new.id,new.assistantId); END""")
                        // 覆盖首次建表及触发器安装前的恢复内容；避免每次打开全量重建。
                        db.execSQL("""DELETE FROM semantic_fts WHERE id NOT IN (SELECT id FROM semantic_memory)""")
                        db.execSQL("""INSERT INTO semantic_fts(content,id,assistantId)
                            SELECT content,id,assistantId FROM semantic_memory
                            WHERE id NOT IN (SELECT id FROM semantic_fts)""")
                    }
                }).build()
    }
}
