package me.rerere.rikkahub.data.memory.semantic

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 在 APK 的实际 Room/Requery/simple 中文扩展上执行；本地 JVM 不能替代。 */
@RunWith(AndroidJUnit4::class)
class SemanticMemoryDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "semantic-test-${UUID.randomUUID()}.db"
    private lateinit var database: SemanticMemoryDatabase

    @Before fun open() {
        database = SemanticMemoryDatabase.create(context, name)
    }

    @After fun close() {
        database.close()
        check(name.startsWith("semantic-test-"))
        context.deleteDatabase(name)
    }

    private suspend fun search(assistantId: String, query: String): List<SemanticLexicalHit> =
        database.dao().lexical(SimpleSQLiteQuery("""SELECT m.id, bm25(semantic_fts) AS score
            FROM semantic_fts JOIN semantic_memory m ON m.id=semantic_fts.id
            WHERE semantic_fts MATCH jieba_query(?) AND m.assistantId=?
            AND m.status='active' AND (m.isCore=1 OR m.expiresAt IS NULL OR m.expiresAt>?)
            ORDER BY score LIMIT 2""", arrayOf(query, assistantId, System.currentTimeMillis())))

    @Test fun restartIsolationAndChineseIndexFollowEditsAndDeletion() = runBlocking {
        val dao = database.dao()
        val first = SemanticMemoryEntity(assistantId = "a", content = "小明喜欢杭州的龙井茶")
        val other = SemanticMemoryEntity(assistantId = "b", content = "小明喜欢杭州的龙井茶")
        dao.insert(first)
        dao.insert(other)
        assertEquals(first.id, dao.revisions("a").single().id)
        assertEquals(listOf(first.id), search("a", "龙井茶").map { it.id })
        database.close()
        database = SemanticMemoryDatabase.create(context, name)
        assertEquals(first.content, database.dao().get("a", first.id)?.content)
        assertNull(database.dao().get("b", first.id))
        database.dao().put(first.copy(content = "小明喜欢咖啡", contentHash = semanticContentHash("小明喜欢咖啡"), contentVersion = 2))
        assertTrue(search("a", "龙井茶").isEmpty())
        assertEquals(listOf(first.id), search("a", "咖啡").map { it.id })
        database.dao().delete("a", listOf(first.id))
        assertTrue(search("a", "咖啡").isEmpty())
        assertEquals(1, database.dao().list("b").size)
    }

    @Test fun lateVectorsCannotOverwriteAnEditOrResurrectDeletion() = runBlocking {
        val dao = database.dao()
        val first = SemanticMemoryEntity(assistantId = "a", content = "原事实")
        dao.insert(first)
        val vector = SemanticVectors.encode(listOf(1f, 0f))
        assertEquals(1, dao.storeVector("a", first.id, 1, first.contentHash, vector, "model:2", 2, 1L))
        val edited = first.copy(content = "新事实", contentHash = semanticContentHash("新事实"), contentVersion = 2)
        dao.put(edited)
        assertEquals(0, dao.storeVector("a", first.id, 1, first.contentHash, vector, "model:2", 2, 2L))
        assertNull(dao.get("a", first.id)?.embedding)
        dao.delete("a", listOf(first.id))
        assertEquals(0, dao.storeVector("a", first.id, 2, edited.contentHash, vector, "model:2", 2, 3L))
        assertNull(dao.get("a", first.id))
    }

    @Test fun lifecycleFilterPrecedesTopKAndCoreDoesNotCrossAssistantBoundary() = runBlocking {
        val dao = database.dao()
        repeat(4) { index ->
            dao.insert(SemanticMemoryEntity(assistantId = "a", content = "杭州 杭州 杭州 杭州 $index", status = "archived"))
        }
        dao.insert(SemanticMemoryEntity(assistantId = "a", content = "杭州", expiresAt = 1L))
        val live = SemanticMemoryEntity(assistantId = "a", content = "杭州")
        val core = SemanticMemoryEntity(assistantId = "a", content = "杭州", isCore = true, expiresAt = 1L)
        dao.insert(live)
        dao.insert(core)
        assertEquals(setOf(live.id, core.id), search("a", "杭州").map { it.id }.toSet())
    }

    @Test fun largePersistedJobsReadExactUnicodeAfterRestart() = runBlocking {
        val payload = "{\"archive\":\"" + "中文😀".repeat(400_000) + "\"}"
        val job = SemanticJobEntity("large-import", "a", "content_import", payload, 17L)
        database.dao().putJob(job)
        assertNull(database.dao().jobHeader(job.id)?.inlinePayload)
        assertEquals(job, database.dao().getJob(job.id))
        assertEquals(listOf(job), database.dao().jobs("a"))
        assertTrue(database.dao().jobs("b").isEmpty())
        database.close()
        database = SemanticMemoryDatabase.create(context, name)
        assertEquals(job, database.dao().getJob(job.id))
    }
}
