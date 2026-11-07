package me.rerere.rikkahub.data.sync

import android.content.Context
import io.requery.android.database.sqlite.SQLiteDatabase
import me.rerere.rikkahub.data.memory.semantic.SemanticMemoryDatabase
import java.io.File

/** 仅打开暂存语义库；主库的结构对齐和宽松identity路径不适用。 */
internal object SemanticDatabaseBackup {
    const val ARCHIVE_DATABASE = SemanticMemoryDatabase.NAME
    const val WAL = "$ARCHIVE_DATABASE-wal"
    const val SHM = "$ARCHIVE_DATABASE-shm"

    fun validate(context: Context, databaseFile: File) {
        DatabaseBackup.normalize(context, databaseFile)
        DatabaseBackup.open(context, databaseFile, SQLiteDatabase.OPEN_READWRITE) { database ->
            check(database.version == SemanticMemoryDatabase.VERSION) {
                "备份语义库版本为 ${database.version}，当前支持 ${SemanticMemoryDatabase.VERSION}"
            }
            // 暂存副本强制重验实际表结构，避免正确hash掩盖损坏schema。
            database.execSQL("DROP TABLE IF EXISTS room_master_table")
            DatabaseBackup.checkpoint(database)
        }
        DatabaseBackup.removeSidecars(databaseFile)
        val room = SemanticMemoryDatabase.create(context, databaseFile.absolutePath)
        try {
            val database = room.openHelper.writableDatabase
            // FTS自洽不等于正文一致；只在暂存副本重建派生索引，原子与任务保持原样。
            database.execSQL("DELETE FROM semantic_fts")
            database.execSQL("INSERT INTO semantic_fts(content,id,assistantId) SELECT content,id,assistantId FROM semantic_memory")
            database.query(
                "SELECT id FROM semantic_fts WHERE semantic_fts MATCH jieba_query(?) LIMIT 1",
                arrayOf("备份校验"),
            ).use { it.moveToFirst() }
            database.execSQL("INSERT INTO semantic_fts(semantic_fts) VALUES('integrity-check')")
            DatabaseBackup.checkpoint(database)
        } finally {
            room.close()
        }
        DatabaseBackup.removeSidecars(databaseFile)
    }
}
