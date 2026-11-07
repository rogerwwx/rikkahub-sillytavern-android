package me.rerere.rikkahub.data.sync

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.memory.semantic.SemanticMemoryDatabase
import me.rerere.rikkahub.data.memory.semantic.SemanticMemoryEntity
import me.rerere.rikkahub.data.memory.semantic.semanticContentHash
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** 实际Room/Requery/simple扩展验证；只使用临时合成数据库，不访问应用设置。 */
@RunWith(AndroidJUnit4::class)
class SemanticDatabaseBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "semantic-backup-test-").toFile()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun snapshot(): Pair<File, SemanticMemoryEntity> = runBlocking {
        val source = File(directory, "source")
        val snapshot = File(directory, "snapshot")
        val memory = SemanticMemoryEntity(assistantId = "fixture", content = "小明喜欢杭州龙井茶")
        val room = SemanticMemoryDatabase.create(context, source.absolutePath)
        try {
            room.dao().insert(memory)
            DatabaseBackup.createSnapshot(room.openHelper.writableDatabase, snapshot)
        } finally {
            room.close()
        }
        snapshot to memory
    }

    @Test fun restoresMissingFtsAndTriggersAndChineseIndexFollowsEdits() = runBlocking {
        val (file, memory) = snapshot()
        DatabaseBackup.open(context, file, SQLiteDatabase.OPEN_READWRITE) { database ->
            for (trigger in listOf("semantic_fts_insert", "semantic_fts_delete", "semantic_fts_update")) {
                database.execSQL("DROP TRIGGER $trigger")
            }
            database.execSQL("DROP TABLE semantic_fts")
        }
        SemanticDatabaseBackup.validate(context, file)
        assertFalse(File(file.path + "-wal").exists())
        assertFalse(File(file.path + "-shm").exists())
        val room = SemanticMemoryDatabase.create(context, file.absolutePath)
        try {
            suspend fun search(text: String) = room.dao().lexical(SimpleSQLiteQuery(
                """SELECT id, bm25(semantic_fts) AS score FROM semantic_fts
                    WHERE semantic_fts MATCH jieba_query(?)""",
                arrayOf(text),
            )).map { it.id }
            assertEquals(listOf(memory.id), search("龙井茶"))
            room.dao().put(memory.copy(
                content = "小明喜欢咖啡", contentHash = semanticContentHash("小明喜欢咖啡"), contentVersion = 2,
            ))
            assertTrue(search("龙井茶").isEmpty())
            assertEquals(listOf(memory.id), search("咖啡"))
            room.dao().delete("fixture", listOf(memory.id))
            assertTrue(search("咖啡").isEmpty())
        } finally {
            room.close()
        }
    }

    @Test fun matchingRoomHashDoesNotHideMissingSemanticTable() {
        val (file, _) = snapshot()
        DatabaseBackup.open(context, file, SQLiteDatabase.OPEN_READWRITE) { database ->
            database.execSQL("DROP TABLE semantic_job")
        }
        assertInvalid(file)
    }

    @Test fun selfConsistentButStaleFtsContentIsRebuiltFromSemanticRows() = runBlocking {
        val (file, memory) = snapshot()
        DatabaseBackup.open(context, file, SQLiteDatabase.OPEN_READWRITE) { database ->
            database.execSQL(
                "UPDATE semantic_fts SET content=? WHERE id=?", arrayOf("小明喜欢旧正文", memory.id),
            )
            database.execSQL("INSERT INTO semantic_fts(semantic_fts) VALUES('integrity-check')")
        }
        SemanticDatabaseBackup.validate(context, file)
        val room = SemanticMemoryDatabase.create(context, file.absolutePath)
        try {
            assertEquals(memory.content, room.dao().get("fixture", memory.id)?.content)
            assertEquals(listOf(memory.id), room.dao().lexical(SimpleSQLiteQuery(
                "SELECT id, bm25(semantic_fts) AS score FROM semantic_fts WHERE semantic_fts MATCH jieba_query(?)",
                arrayOf("龙井茶"),
            )).map { it.id })
            assertTrue(room.dao().lexical(SimpleSQLiteQuery(
                "SELECT id, bm25(semantic_fts) AS score FROM semantic_fts WHERE semantic_fts MATCH jieba_query(?)",
                arrayOf("旧正文"),
            )).isEmpty())
        } finally {
            room.close()
        }
    }

    @Test fun malformedFtsTableIsRejected() {
        val (file, _) = snapshot()
        DatabaseBackup.open(context, file, SQLiteDatabase.OPEN_READWRITE) { database ->
            database.execSQL("DROP TABLE semantic_fts")
            database.execSQL("CREATE TABLE semantic_fts (broken TEXT)")
        }
        assertInvalid(file)
    }

    @Test fun futureVersionIsRejectedWithoutDowngrade() {
        val (file, _) = snapshot()
        DatabaseBackup.open(context, file, SQLiteDatabase.OPEN_READWRITE) { it.version = 9999 }
        assertInvalid(file)
        DatabaseBackup.open(context, file, SQLiteDatabase.OPEN_READWRITE) { assertEquals(9999, it.version) }
    }

    private fun assertInvalid(file: File) {
        var failed = false
        try { SemanticDatabaseBackup.validate(context, file) } catch (_: Exception) { failed = true }
        assertTrue(failed)
    }
}
