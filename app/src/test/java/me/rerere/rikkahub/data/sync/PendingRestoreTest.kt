package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PendingRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun restore() = PendingRestore(
        File(temporary.root, "restore"), database(), File(temporary.root, "files"), semanticDatabase()
    )

    private fun database() = File(temporary.root, "databases/rikka_hub")

    private fun write(file: File, contents: String): File {
        file.parentFile!!.mkdirs()
        file.writeText(contents, Charsets.UTF_8)
        return file
    }

    private fun stage(restore: PendingRestore): File = restore.createStagingDirectory().also {
        write(File(it, "payload/database/rikka_hub"), "new database")
        write(File(it, "payload/files/upload/photo"), "new photo")
        write(File(it, "settings.json"), "new settings")
        restore.publish(it)
    }

    @Test fun publishingDoesNotTouchLiveFiles() {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        stage(restore())
        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertEquals("old wal", File(database().path + "-wal").readText(Charsets.UTF_8))
        assertFalse(File(temporary.root, "files/upload/photo").exists())
    }

    @Test fun installsStandaloneDatabaseAndDoesNotReplayOnLaterLaunches() = runBlocking {
        write(database(), "old database")
        listOf("-wal", "-shm", "-journal").forEach { write(File(database().path + it), "old") }
        stage(restore())
        val restored = restore().apply {
            assertEquals("new settings", it)
            assertEquals("new database", database().readText(Charsets.UTF_8))
            assertEquals("new photo", File(temporary.root, "files/upload/photo").readText(Charsets.UTF_8))
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                assertFalse(File(database().path + suffix).exists())
            }
        }
        assertTrue(restored)
        database().writeText("subsequent user changes", Charsets.UTF_8)
        assertFalse(restore().apply { error("Settings must not be applied again") })
        assertEquals("subsequent user changes", database().readText(Charsets.UTF_8))
    }

    @Test fun failedSettingsWriteRestoresOriginalDatabaseAndSidecarsAndCanRetry() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        write(File(temporary.root, "files/upload/photo"), "old photo")
        stage(restore())
        try {
            restore().apply { throw IOException("Disk full") }
            error("Expected failure")
        } catch (_: RestoreFailedException) {
            assertEquals("old database", database().readText(Charsets.UTF_8))
            assertEquals("old wal", File(database().path + "-wal").readText(Charsets.UTF_8))
            assertEquals("old photo", File(temporary.root, "files/upload/photo").readText(Charsets.UTF_8))
        }
        stage(restore())
        restore().apply { }
        assertEquals("new database", database().readText(Charsets.UTF_8))
        assertFalse(File(database().path + "-wal").exists())
    }

    @Test fun failedInitialJournalWriteKeepsOriginalDataAndDoesNotBlockLaterLaunches() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        write(File(temporary.root, "files/upload/photo"), "old photo")
        stage(restore())
        // Force a real I/O failure without relying on disk capacity or filesystem permissions.
        assertTrue(File(temporary.root, "restore/pending/journal.json.tmp").mkdir())

        try {
            restore().apply { error("Settings must not be applied without a journal") }
            error("Expected failure")
        } catch (e: RestoreFailedException) {
            assertTrue(e.cause is IOException)
        }

        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertEquals("old wal", File(database().path + "-wal").readText(Charsets.UTF_8))
        assertEquals("old photo", File(temporary.root, "files/upload/photo").readText(Charsets.UTF_8))
        assertFalse(File(temporary.root, "restore/pending").exists())
        val failed = File(temporary.root, "restore").listFiles()!!.single { it.name.startsWith("failed-") }
        assertEquals("new database", File(failed, "payload/database/rikka_hub").readText(Charsets.UTF_8))
        assertFalse(restore().apply { error("Failed restore must not be replayed") })

        stage(restore())
        assertTrue(restore().apply { })
        assertEquals("new database", database().readText(Charsets.UTF_8))
    }

    @Test fun invalidJournalAfterInterruptionKeepsOriginalsForRecovery() = runBlocking {
        write(database(), "old database")
        stage(restore())
        try {
            restore().apply { throw SimulatedProcessDeath() }
        } catch (_: SimulatedProcessDeath) {
            // The live database has been replaced, but the original is still needed for recovery.
        }
        val pending = File(temporary.root, "restore/pending")
        File(pending, "journal.json").writeText("invalid journal", Charsets.UTF_8)

        try {
            restore().apply { error("Settings must not be applied with an invalid journal") }
            error("Expected failure")
        } catch (_: SerializationException) {
            // Startup must stop because the live files may already have been replaced.
        }

        assertTrue(pending.isDirectory)
        assertEquals("old database", File(pending, "originals/database/rikka_hub").readText(Charsets.UTF_8))
        assertEquals("new database", database().readText(Charsets.UTF_8))
    }

    @Test fun restartResumesInstallationInterruptedBeforeCommit() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        stage(restore())
        try {
            // Error bypasses ordinary exception rollback, simulating process loss after file moves.
            restore().apply { throw SimulatedProcessDeath() }
        } catch (_: SimulatedProcessDeath) {
            assertTrue(File(temporary.root, "restore/pending").exists())
        }
        restore().apply { assertEquals("new settings", it) }
        assertEquals("new database", database().readText(Charsets.UTF_8))
        assertFalse(File(database().path + "-wal").exists())
        assertFalse(File(temporary.root, "restore/pending").exists())
    }

    @Test fun filesOnlyRestoreLeavesDatabaseAndWalUntouched() = runBlocking {
        write(database(), "old database")
        write(File(database().path + "-wal"), "old wal")
        val restore = restore()
        val staging = restore.createStagingDirectory()
        write(File(staging, "payload/files/fonts/custom.ttf"), "font")
        restore.publish(staging)
        restore.apply { error("No settings in backup") }
        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertEquals("old wal", File(database().path + "-wal").readText(Charsets.UTF_8))
        assertEquals("font", File(temporary.root, "files/fonts/custom.ttf").readText(Charsets.UTF_8))
    }

    @Test fun cannotReplaceAlreadyPendingRestore() {
        stage(restore())
        assertThrows(IllegalStateException::class.java) { restore().createStagingDirectory() }
    }

    @Test fun rejectsArchiveTraversalPaths() {
        for (path in listOf("../database", "/absolute", "skills/../../database", "skills/..\\database")) {
            assertThrows(IllegalArgumentException::class.java) {
                PendingRestore.resolveInside(temporary.root, path)
            }
        }
    }

    private fun semanticDatabase() = File(temporary.root, "databases/semantic_memory.db")

    private fun stageDatabases(includeSemantic: Boolean): File {
        val restore = restore()
        return restore.createStagingDirectory().also {
            write(File(it, "payload/database/rikka_hub"), "new database")
            if (includeSemantic) write(File(it, "payload/database/semantic_memory.db"), "new semantic")
            write(File(it, "settings.json"), "new settings")
            restore.publish(it)
        }
    }

    private fun seedSemantic() {
        write(semanticDatabase(), "old semantic")
        listOf("-wal", "-shm", "-journal").forEach { write(File(semanticDatabase().path + it), "old semantic$it") }
    }

    @Test fun legacyDatabaseRestoreClearsSemanticStateOnlyAtStartup() = runBlocking {
        write(database(), "old database")
        seedSemantic()
        stageDatabases(includeSemantic = false)
        assertEquals("old semantic", semanticDatabase().readText(Charsets.UTF_8))
        restore().apply { }
        assertEquals("new database", database().readText(Charsets.UTF_8))
        assertFalse(semanticDatabase().exists())
        listOf("-wal", "-shm", "-journal").forEach { assertFalse(File(semanticDatabase().path + it).exists()) }
    }

    @Test fun installsBothDatabasesWithNoOldSidecars() = runBlocking {
        write(database(), "old database")
        seedSemantic()
        stageDatabases(includeSemantic = true)
        restore().apply { assertEquals("new semantic", semanticDatabase().readText(Charsets.UTF_8)) }
        assertEquals("new database", database().readText(Charsets.UTF_8))
        assertEquals("new semantic", semanticDatabase().readText(Charsets.UTF_8))
        listOf("-wal", "-shm", "-journal").forEach { assertFalse(File(semanticDatabase().path + it).exists()) }
    }

    @Test fun failedLegacyRestoreRollsBackClearedSemanticDatabaseAndAllSidecars() = runBlocking {
        write(database(), "old database")
        seedSemantic()
        stageDatabases(includeSemantic = false)
        assertThrows(RestoreFailedException::class.java) {
            runBlocking { restore().apply { throw IOException("fixture settings write failed") } }
        }
        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertEquals("old semantic", semanticDatabase().readText(Charsets.UTF_8))
        listOf("-wal", "-shm", "-journal").forEach {
            assertEquals("old semantic$it", File(semanticDatabase().path + it).readText(Charsets.UTF_8))
        }
    }

    @Test fun failedDualRestoreRollsBackBothDatabasesAndSemanticSidecars() = runBlocking {
        write(database(), "old database")
        seedSemantic()
        stageDatabases(includeSemantic = true)
        assertThrows(RestoreFailedException::class.java) {
            runBlocking { restore().apply { throw IOException("fixture settings write failed") } }
        }
        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertEquals("old semantic", semanticDatabase().readText(Charsets.UTF_8))
        listOf("-wal", "-shm", "-journal").forEach {
            assertEquals("old semantic$it", File(semanticDatabase().path + it).readText(Charsets.UTF_8))
        }
    }

    @Test fun failedDualRestoreDoesNotLeaveNewSemanticDatabaseWhenNoneExisted() = runBlocking {
        write(database(), "old database")
        stageDatabases(includeSemantic = true)
        assertThrows(RestoreFailedException::class.java) {
            runBlocking { restore().apply { throw IOException("fixture settings write failed") } }
        }
        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertFalse(semanticDatabase().exists())
    }

    @Test fun restartResumesLegacySemanticClearAndDoesNotReplay() = runBlocking {
        write(database(), "old database")
        seedSemantic()
        stageDatabases(includeSemantic = false)
        try { restore().apply { throw SimulatedProcessDeath() } } catch (_: SimulatedProcessDeath) { }
        assertTrue(restore().apply { })
        assertFalse(semanticDatabase().exists())
        write(semanticDatabase(), "subsequent semantic")
        assertFalse(restore().apply { error("completed restore replayed") })
        assertEquals("subsequent semantic", semanticDatabase().readText(Charsets.UTF_8))
    }

    @Test fun restartResumesDualDatabaseInstallation() = runBlocking {
        write(database(), "old database")
        seedSemantic()
        stageDatabases(includeSemantic = true)
        try { restore().apply { throw SimulatedProcessDeath() } } catch (_: SimulatedProcessDeath) { }
        assertTrue(restore().apply { })
        assertEquals("new database", database().readText(Charsets.UTF_8))
        assertEquals("new semantic", semanticDatabase().readText(Charsets.UTF_8))
    }

    @Test fun settingsOnlyRestoreKeepsBothDatabasesAndSemanticSidecars() = runBlocking {
        write(database(), "old database")
        seedSemantic()
        val restore = restore()
        val staging = restore.createStagingDirectory()
        write(File(staging, "settings.json"), "new settings")
        restore.publish(staging)
        restore.apply { assertEquals("new settings", it) }
        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertEquals("old semantic", semanticDatabase().readText(Charsets.UTF_8))
        listOf("-wal", "-shm", "-journal").forEach {
            assertEquals("old semantic$it", File(semanticDatabase().path + it).readText(Charsets.UTF_8))
        }
    }

    private fun interruptedLegacyJournal(): File {
        write(database(), "new database")
        seedSemantic()
        val restore = restore()
        val staging = restore.createStagingDirectory()
        write(File(staging, "settings.json"), "new settings")
        write(File(staging, "originals/database/rikka_hub"), "old database")
        write(File(staging, "originals/database/rikka_hub-wal"), "old wal")
        // 精确模拟旧版已安装主库、尚未commit的journal；主库payload已被移走。
        write(File(staging, "journal.json"), """[
            {"path":"database/rikka_hub-journal","install":false,"hadOriginal":false},
            {"path":"database/rikka_hub-shm","install":false,"hadOriginal":false},
            {"path":"database/rikka_hub-wal","install":false,"hadOriginal":true},
            {"path":"database/rikka_hub","install":true,"hadOriginal":true}
        ]""")
        restore.publish(staging)
        return File(temporary.root, "restore/pending")
    }

    private fun assertSemanticClearWasJournaled(pending: File) {
        val entries = Json.parseToJsonElement(File(pending, "journal.json").readText(Charsets.UTF_8)).jsonArray
            .map { it.jsonObject }
        val semantic = entries.filter { it.getValue("path").jsonPrimitive.content.startsWith("database/semantic_memory.db") }
        assertEquals(4, semantic.size)
        assertTrue(semantic.all { it.getValue("install").jsonPrimitive.content == "false" })
    }

    @Test fun oldInterruptedMainJournalClearsSemanticStateAndDoesNotReplay() = runBlocking {
        val pending = interruptedLegacyJournal()
        assertFalse(File(pending, "payload/database/rikka_hub").exists())
        restore().apply {
            assertSemanticClearWasJournaled(pending)
            assertFalse(semanticDatabase().exists())
        }
        assertEquals("new database", database().readText(Charsets.UTF_8))
        listOf("-wal", "-shm", "-journal").forEach { assertFalse(File(semanticDatabase().path + it).exists()) }
        write(semanticDatabase(), "subsequent semantic")
        assertFalse(restore().apply { error("old journal replayed") })
        assertEquals("subsequent semantic", semanticDatabase().readText(Charsets.UTF_8))
    }

    @Test fun failedSettingsAfterOldJournalUpgradeRollsBackBothDatabaseStates() = runBlocking {
        interruptedLegacyJournal()
        assertThrows(RestoreFailedException::class.java) {
            runBlocking { restore().apply {
                assertFalse(semanticDatabase().exists())
                throw IOException("fixture settings write failed")
            } }
        }
        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertEquals("old wal", File(database().path + "-wal").readText(Charsets.UTF_8))
        assertEquals("old semantic", semanticDatabase().readText(Charsets.UTF_8))
        listOf("-wal", "-shm", "-journal").forEach {
            assertEquals("old semantic$it", File(semanticDatabase().path + it).readText(Charsets.UTF_8))
        }
    }

    @Test fun interruptedOldJournalUpgradeIsDurableAndResumesWithoutDuplicateEntries() = runBlocking {
        val pending = interruptedLegacyJournal()
        try { restore().apply {
            assertSemanticClearWasJournaled(pending)
            assertFalse(semanticDatabase().exists())
            throw SimulatedProcessDeath()
        } } catch (_: SimulatedProcessDeath) { }
        assertEquals("old semantic", File(pending, "originals/database/semantic_memory.db").readText(Charsets.UTF_8))
        val journal = File(pending, "journal.json").readText(Charsets.UTF_8)
        restore().apply {
            assertSemanticClearWasJournaled(pending)
            assertEquals(journal, File(pending, "journal.json").readText(Charsets.UTF_8))
        }
        assertFalse(semanticDatabase().exists())
        assertEquals("new database", database().readText(Charsets.UTF_8))
    }

    @Test fun failedOldJournalUpgradeWriteKeepsPendingAndOriginalsUntilRetry() = runBlocking {
        val pending = interruptedLegacyJournal()
        val journal = File(pending, "journal.json").readText(Charsets.UTF_8)
        val blocker = File(pending, "journal.json.tmp")
        assertTrue(blocker.mkdir())
        assertThrows(IOException::class.java) {
            runBlocking { restore().apply { error("settings applied before durable old journal upgrade") } }
        }
        assertTrue(pending.isDirectory)
        assertEquals(journal, File(pending, "journal.json").readText(Charsets.UTF_8))
        assertEquals("new database", database().readText(Charsets.UTF_8))
        assertEquals("old database", File(pending, "originals/database/rikka_hub").readText(Charsets.UTF_8))
        assertEquals("old semantic", semanticDatabase().readText(Charsets.UTF_8))
        assertTrue(blocker.delete())
        assertTrue(restore().apply { })
        assertFalse(semanticDatabase().exists())
    }

    @Test fun unknownPathInOldJournalKeepsJournalAndDatabaseOriginalsUntouched() = runBlocking {
        val pending = interruptedLegacyJournal()
        val file = File(pending, "journal.json")
        val journal = file.readText(Charsets.UTF_8).trim().dropLast(1) +
            """,{"path":"database/unrelated.db","install":true,"hadOriginal":false}]"""
        file.writeText(journal, Charsets.UTF_8)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { restore().apply { error("unknown journal path accepted") } }
        }
        assertTrue(pending.isDirectory)
        assertEquals(journal, file.readText(Charsets.UTF_8))
        assertEquals("new database", database().readText(Charsets.UTF_8))
        assertEquals("old database", File(pending, "originals/database/rikka_hub").readText(Charsets.UTF_8))
        assertEquals("old semantic", semanticDatabase().readText(Charsets.UTF_8))
    }

    @Test fun existingSettingsOnlyJournalDoesNotClearSemanticState() = runBlocking {
        write(database(), "old database")
        seedSemantic()
        val restore = restore()
        val staging = restore.createStagingDirectory()
        write(File(staging, "settings.json"), "new settings")
        write(File(staging, "journal.json"), "[]")
        restore.publish(staging)
        restore.apply { assertEquals("new settings", it) }
        assertEquals("old database", database().readText(Charsets.UTF_8))
        assertEquals("old semantic", semanticDatabase().readText(Charsets.UTF_8))
        listOf("-wal", "-shm", "-journal").forEach {
            assertEquals("old semantic$it", File(semanticDatabase().path + it).readText(Charsets.UTF_8))
        }
    }

    private class SimulatedProcessDeath : Error()
}
