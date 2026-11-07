package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.StreamCompletion
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.memory.semantic.SemanticDiagnosticEvent
import me.rerere.rikkahub.data.memory.semantic.SemanticJobEntity
import me.rerere.rikkahub.data.memory.semantic.SemanticMemoryDatabase
import me.rerere.rikkahub.data.memory.semantic.SemanticMemoryEntity
import me.rerere.rikkahub.data.memory.semantic.SemanticVectors
import me.rerere.rikkahub.data.memory.semantic.DreamSource
import me.rerere.rikkahub.data.memory.semantic.dreamMessages
import me.rerere.rikkahub.data.memory.semantic.validDreamSources
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var liveDatabase: AppDatabase
    private lateinit var semanticDatabase: SemanticMemoryDatabase
    private lateinit var settingsScope: CoroutineScope
    private lateinit var filesScope: AppScope
    private lateinit var settingsStore: SettingsStore
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var manager: BackupManager

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "backup-manager-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
                else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        liveDatabase = AppDatabaseFactory.create(context)
        liveDatabase.openHelper.writableDatabase.execSQL("CREATE TABLE backup_probe (text TEXT)")
        liveDatabase.openHelper.writableDatabase.execSQL("INSERT INTO backup_probe VALUES ('live')")
        semanticDatabase = SemanticMemoryDatabase.create(context)
        settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        filesScope = AppScope()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = settingsScope) { File(directory, "settings.preferences_pb") },
            settingsScope,
        )
        conversationRepository = ConversationRepository(
            liveDatabase.conversationDao(), liveDatabase.messageNodeDao(), liveDatabase.favoriteDao(), liveDatabase,
            FilesManager(
                context, FilesRepository(liveDatabase.managedFileDao()),
                GenMediaRepository(liveDatabase.genMediaDao()), filesScope,
            ),
            MessageFtsManager(liveDatabase),
        )
        manager = BackupManager(context, liveDatabase, settingsStore, JsonInstant, semanticDatabase, conversationRepository)
    }

    @After fun tearDown() {
        runBlocking { settingsScope.coroutineContext.job.cancelAndJoin() }
        filesScope.cancel()
        semanticDatabase.close()
        liveDatabase.close()
        directory.deleteRecursively()
    }

    @Test fun oldArchiveIgnoresShmAndLeavesLiveDatabaseUntouchedUntilStartup() = runBlocking {
        val source = File(directory, "source")
        val archive = File(directory, "legacy.zip")
        withRoom(source) { room ->
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE backup_probe (text TEXT)")
            DatabaseBackup.checkpoint(db)
            db.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
            db.execSQL("INSERT INTO backup_probe VALUES ('archived')")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addFile(zip, DatabaseBackup.ARCHIVE_DATABASE, source)
                addFile(zip, DatabaseBackup.WAL, File(source.path + "-wal"))
                zip.putNextEntry(ZipEntry(DatabaseBackup.SHM))
                zip.write("deliberately invalid SHM".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        assertTrue(staged.isFile)
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        liveDatabase.close()
        BackupManager.applyPendingRestore(context, JsonInstant)
        liveDatabase = AppDatabaseFactory.create(context)
        assertEquals("archived", probe(liveDatabase))
        ZipFile(archive).use { assertTrue(it.getEntry(DatabaseBackup.SHM) != null) }
    }

    @Test fun newArchiveContainsStandaloneDatabaseAndNoWalOrShm() = runBlocking {
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("settings.json") != null)
            assertTrue(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            assertTrue(zip.getEntry(SemanticDatabaseBackup.ARCHIVE_DATABASE) != null)
            assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
            assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
            assertEquals(null, zip.getEntry(SemanticDatabaseBackup.WAL))
            assertEquals(null, zip.getEntry(SemanticDatabaseBackup.SHM))
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun launchCountStaysInSettingsJsonThroughBackupAndStaging() = runBlocking {
        val archive = manager.createBackup(includeDatabase = false, includeFiles = false)
        val archived = ZipFile(archive).use { zip ->
            launchCountOf(zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString())
        }
        assertTrue(archived != null)
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText(Charsets.UTF_8)
        assertEquals(archived, launchCountOf(staged))
    }

    @Test fun archiveWithoutLaunchCountRestoresItAsZero() = runBlocking {
        val current = manager.createBackup(includeDatabase = false, includeFiles = false)
        val settingsJson = ZipFile(current).use { zip ->
            zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString()
        }
        val archive = File(directory, "no-launch-count.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(JsonObject(JsonInstant.parseToJsonElement(settingsJson).jsonObject - "launchCount").toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText(Charsets.UTF_8)
        assertEquals(0, launchCountOf(staged))
    }

    private fun launchCountOf(settingsJson: String): Int? =
        JsonInstant.parseToJsonElement(settingsJson).jsonObject["launchCount"]?.jsonPrimitive?.int

    @Test fun mediaCreationFilesKeepTheirDirectoriesAndSkipUnfinishedDownloads() = runBlocking {
        val session = File(context.filesDir, "media_creation/session")
        File(session, "record").mkdirs()
        File(session, "draft").mkdirs()
        File(session, "record/out_0.png").writeText("image", Charsets.UTF_8)
        File(session, "record/out_1.mp4.part").writeText("half a video", Charsets.UTF_8)
        File(session, "draft/asset.jpg").writeText("asset", Charsets.UTF_8)
        val archive = manager.createBackup(includeDatabase = false, includeFiles = true)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("media_creation/session/record/out_0.png") != null)
            assertTrue(zip.getEntry("media_creation/session/draft/asset.jpg") != null)
            assertEquals(null, zip.getEntry("media_creation/session/record/out_1.mp4.part"))
        }
        session.deleteRecursively()
        manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
        assertFalse(session.exists())
        // Applying the staged settings would write to the real settings store of the app under test.
        assertTrue(File(context.noBackupFilesDir, "backup-restore/pending/settings.json").delete())
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertEquals("image", File(session, "record/out_0.png").readText(Charsets.UTF_8))
        assertEquals("asset", File(session, "draft/asset.jpg").readText(Charsets.UTF_8))
        assertFalse(File(session, "record/out_1.mp4.part").exists())
    }

    @Test fun unsupportedSchemaIsRejectedBeforePublishing() = runBlocking {
        val future = File(directory, "future")
        withRoom(future) {
            it.openHelper.writableDatabase.version = 9999
        }
        val archive = File(directory, "future.zip")
        ZipOutputStream(archive.outputStream()).use { addFile(it, DatabaseBackup.ARCHIVE_DATABASE, future) }
        var failed = false
        try {
            manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun fullBackupPreservesSemanticVectorsJobsAndSelectedHiddenSources() = runBlocking {
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000005")
        settingsStore.update { it.copy(
            assistantId = assistantId, assistants = it.assistants + Assistant(id = assistantId, name = "fixture"),
        ) }
        val user = UIMessage.user("小明喜欢杭州龙井茶").copy(isContextHidden = true)
        val reply = UIMessage.assistant("已记住").copy(
            finishedAt = user.createdAt, generationCompletion = StreamCompletion.NORMAL,
        )
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = listOf(MessageNode.of(user), MessageNode.of(reply)),
        )
        conversationRepository.insertConversation(conversation)
        val sourceMessage = conversation.dreamMessages().first()
        val source = DreamSource(
            conversation.id.toString(), sourceMessage.nodeId, sourceMessage.messageId, 0,
            sourceMessage.snapshotHash, sourceMessage.role, sourceMessage.createdAt, 0, sourceMessage.text.length,
            createdAtText = sourceMessage.createdAtText,
        )
        val vector = SemanticVectors.encode(listOf(1f, 0f))
        val memory = SemanticMemoryEntity(
            id = "fixture-memory", assistantId = assistantId.toString(), content = "小明喜欢杭州龙井茶",
            embedding = vector, embeddingDimension = 2, embeddingFingerprint = "fixture:2",
            embeddedVersion = 1, indexedAt = 123L, sourceJson = JsonInstant.encodeToString(listOf(source)),
            metadataJson = """{"origin":"dream","scope":"user"}""",
        )
        val cursorId = "dream_cursor:$assistantId:${conversation.id}"
        val jobs = listOf(
            SemanticJobEntity(cursorId, assistantId.toString(), "dream_cursor",
                """{"schemaVersion":1,"chunks":1,"continuity":"fixture","successfulRuns":2}"""),
            SemanticJobEntity("$cursorId:sources:0", assistantId.toString(), "dream_cursor_sources", memory.sourceJson),
            SemanticJobEntity("fixture-embedding", assistantId.toString(), "dream_embedding",
                """{"versions":{"fixture-memory":1},"hashes":{},"errors":{}}"""),
        )
        semanticDatabase.dao().insert(memory)
        semanticDatabase.dao().insert(memory.copy(id = "other-memory", assistantId = "other"))
        val event = SemanticDiagnosticEvent(
            id = "fixture-event", assistantId = assistantId.toString(), operation = "embedding",
            purpose = "save", status = "success",
        )
        semanticDatabase.dao().putEvent(event)
        jobs.forEach { semanticDatabase.dao().putJob(it) }
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
        ZipFile(archive).use { zip ->
            val settings = JsonInstant.parseToJsonElement(
                zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString(),
            ).jsonObject
            assertEquals(assistantId.toString(), settings.getValue("assistantId").jsonPrimitive.content)
            assertTrue(settings.getValue("assistants").jsonArray.any {
                it.jsonObject.getValue("id").jsonPrimitive.content == assistantId.toString()
            })
        }
        semanticDatabase.dao().delete(assistantId.toString(), listOf(memory.id))
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals(null, semanticDatabase.dao().get(assistantId.toString(), memory.id))
        applyStagedDatabases()
        val restored = requireNotNull(semanticDatabase.dao().get(assistantId.toString(), memory.id))
        assertTrue(vector.contentEquals(restored.embedding))
        assertEquals(memory.sourceJson, restored.sourceJson)
        assertEquals(memory.metadataJson, restored.metadataJson)
        assertEquals(memory.embeddingFingerprint, restored.embeddingFingerprint)
        assertEquals(memory.indexedAt, restored.indexedAt)
        assertEquals(event, semanticDatabase.dao().getEvent(event.id))
        assertEquals(jobs.toSet(), semanticDatabase.dao().jobs(assistantId.toString()).toSet())
        assertEquals(null, semanticDatabase.dao().get("other", memory.id))
        val restoredConversation = requireNotNull(conversationRepository.getConversationById(conversation.id))
        assertEquals(assistantId, restoredConversation.assistantId)
        assertEquals(user.id, restoredConversation.messageNodes.first().currentMessage.id)
        assertTrue(restoredConversation.messageNodes.first().currentMessage.isContextHidden)
        assertEquals(StreamCompletion.NORMAL, restoredConversation.messageNodes.last().currentMessage.generationCompletion)
        assertTrue(validDreamSources(listOf(source), restoredConversation.dreamMessages()))
        assertEquals(listOf(memory.id), semanticDatabase.dao().lexical(SimpleSQLiteQuery(
            """SELECT m.id, bm25(semantic_fts) AS score FROM semantic_fts
                JOIN semantic_memory m ON m.id=semantic_fts.id
                WHERE semantic_fts MATCH jieba_query(?) AND m.assistantId=?""",
            arrayOf("龙井茶", assistantId.toString()),
        )).map { it.id })
    }

    @Test fun databaseExcludedAndUpstreamArchivesOmitSemanticDatabase() = runBlocking {
        for ((database, upstream) in listOf(false to false, false to true, true to true)) {
            val archive = manager.createBackup(database, includeFiles = false, upstreamCompatible = upstream)
            ZipFile(archive).use { zip ->
                assertEquals(null, zip.getEntry(SemanticDatabaseBackup.ARCHIVE_DATABASE))
                assertEquals(null, zip.getEntry(SemanticDatabaseBackup.WAL))
                assertEquals(database, zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            }
        }
    }

    @Test fun oldMainDatabaseBackupClearsAllOldSemanticTables() = runBlocking {
        semanticDatabase.dao().insert(SemanticMemoryEntity(assistantId = "fixture", content = "old semantic"))
        semanticDatabase.dao().putJob(SemanticJobEntity("old-job", "fixture", "dream_cursor", "{}"))
        semanticDatabase.dao().putEvent(SemanticDiagnosticEvent(assistantId = "fixture", operation = "recall", purpose = "chat"))
        val archive = archiveWithDatabases()
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals(1, semanticDatabase.dao().list("fixture").size)
        applyStagedDatabases()
        assertTrue(semanticDatabase.dao().list("fixture").isEmpty())
        assertTrue(semanticDatabase.dao().jobs("fixture").isEmpty())
        assertTrue(semanticDatabase.dao().events().isEmpty())
    }

    @Test fun settingsOnlyRestoreDoesNotClearSemanticEvenWhenDatabaseOptionIsSelected() = runBlocking {
        val memory = SemanticMemoryEntity(assistantId = "fixture", content = "keep semantic")
        semanticDatabase.dao().insert(memory)
        val archive = manager.createBackup(includeDatabase = false, includeFiles = false)
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        applyStagedDatabases()
        assertEquals(memory.content, semanticDatabase.dao().get("fixture", memory.id)?.content)
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun excludedDatabaseIgnoresCorruptSemanticEntryAndKeepsBothLiveDatabases() = runBlocking {
        val memory = SemanticMemoryEntity(assistantId = "fixture", content = "keep semantic")
        semanticDatabase.dao().insert(memory)
        val source = manager.createBackup(includeDatabase = false, includeFiles = false)
        val archive = File(directory, "excluded-corrupt.zip")
        ZipFile(source).use { input ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(input.getInputStream(input.getEntry("settings.json")).readBytes())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(SemanticDatabaseBackup.ARCHIVE_DATABASE))
                zip.write("corrupt semantic fixture".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        applyStagedDatabases()
        assertEquals(memory.content, semanticDatabase.dao().get("fixture", memory.id)?.content)
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun semanticWithoutMainAndOrphanSemanticWalAreRejectedBeforePublish() = runBlocking {
        val semantic = File(directory, "semantic-snapshot")
        DatabaseBackup.createSnapshot(semanticDatabase.openHelper.writableDatabase, semantic)
        assertRestoreRejected(archiveWithDatabases(semantic, includeMain = false))
        val archive = File(directory, "orphan-wal.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(SemanticDatabaseBackup.WAL))
            zip.write("orphan WAL fixture".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        assertRestoreRejected(archive)
    }

    @Test fun futureSemanticSchemaAndCorruptSemanticAreRejectedBeforePublish() = runBlocking {
        val future = File(directory, "future-semantic")
        val room = SemanticMemoryDatabase.create(context, future.path)
        try { room.openHelper.writableDatabase.version = 9999 } finally { room.close() }
        assertRestoreRejected(archiveWithDatabases(future))
        val corrupt = File(directory, "corrupt-semantic")
        corrupt.writeText("not SQLite", Charsets.UTF_8)
        assertRestoreRejected(archiveWithDatabases(corrupt))
    }

    private fun archiveWithDatabases(semantic: File? = null, includeMain: Boolean = true): File {
        val archive = File(directory, "fixture-${UUID.randomUUID()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            if (includeMain) {
                val snapshot = File(directory, "main-${UUID.randomUUID()}")
                DatabaseBackup.createSnapshot(liveDatabase.openHelper.writableDatabase, snapshot)
                addFile(zip, DatabaseBackup.ARCHIVE_DATABASE, snapshot)
            }
            if (semantic != null) addFile(zip, SemanticDatabaseBackup.ARCHIVE_DATABASE, semantic)
        }
        return archive
    }

    private suspend fun assertRestoreRejected(archive: File) {
        var failed = false
        try { manager.stageRestore(archive, includeDatabase = true, includeFiles = false) }
        catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    private suspend fun applyStagedDatabases() {
        semanticDatabase.close()
        liveDatabase.close()
        // 记录型回调验证实际journal安装，绝不调用应用全局Settings DataStore。
        PendingRestore(
            File(context.noBackupFilesDir, "backup-restore"), context.getDatabasePath("rikka_hub"),
            context.filesDir, context.getDatabasePath(SemanticMemoryDatabase.NAME),
        ).apply { assertTrue(it.isNotBlank()) }
        liveDatabase = AppDatabaseFactory.create(context)
        semanticDatabase = SemanticMemoryDatabase.create(context)
        conversationRepository = ConversationRepository(
            liveDatabase.conversationDao(), liveDatabase.messageNodeDao(), liveDatabase.favoriteDao(), liveDatabase,
            FilesManager(
                context, FilesRepository(liveDatabase.managedFileDao()),
                GenMediaRepository(liveDatabase.genMediaDao()), filesScope,
            ),
            MessageFtsManager(liveDatabase),
        )
    }

    private fun probe(database: AppDatabase): String = database.openHelper.readableDatabase
        .query("SELECT text FROM backup_probe").use { check(it.moveToFirst()); it.getString(0) }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun <T> withRoom(file: File, block: (AppDatabase) -> T): T {
        val room = AppDatabaseFactory.create(context, file.path)
        return try {
            block(room)
        } finally {
            room.close()
        }
    }
}
