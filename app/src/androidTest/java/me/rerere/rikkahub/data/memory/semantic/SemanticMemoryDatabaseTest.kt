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

    private suspend fun search(assistantId: String, query: String, conversationId: String = "window"): List<SemanticLexicalHit> =
        database.dao().lexical(SimpleSQLiteQuery("""SELECT m.id, bm25(semantic_fts) AS score
            FROM semantic_fts JOIN semantic_memory m ON m.id=semantic_fts.id
            WHERE semantic_fts MATCH jieba_query(?) AND m.assistantId=? AND m.conversationId=?
            AND m.status='active' AND (m.isCore=1 OR m.expiresAt IS NULL OR m.expiresAt>?)
            ORDER BY score LIMIT 2""", arrayOf(query, assistantId, conversationId, System.currentTimeMillis())))

    @Test fun restartIsolationAndChineseIndexFollowEditsAndDeletion() = runBlocking {
        val dao = database.dao()
        val first = SemanticMemoryEntity(assistantId = "a", conversationId = "window", content = "小明喜欢杭州的龙井茶")
        val other = SemanticMemoryEntity(assistantId = "b", conversationId = "window", content = "小明喜欢杭州的龙井茶")
        val sibling = first.copy(id = UUID.randomUUID().toString(), conversationId = "other-window", isCore = true)
        dao.insert(first)
        dao.insert(other)
        dao.insert(sibling)
        assertEquals(first.id, dao.revisions("a", "window").single().id)
        assertEquals(listOf(first.id), search("a", "龙井茶").map { it.id })
        database.close()
        database = SemanticMemoryDatabase.create(context, name)
        assertEquals(first.content, database.dao().get("a", "window", first.id)?.content)
        assertNull(database.dao().get("b", "window", first.id))
        assertNull(database.dao().get("a", "other-window", first.id))
        assertEquals(0, database.dao().delete("a", "window", listOf(sibling.id)))
        assertEquals(listOf(sibling.id), search("a", "龙井茶", "other-window").map { it.id })
        database.dao().put(first.copy(content = "小明喜欢咖啡", contentHash = semanticContentHash("小明喜欢咖啡"), contentVersion = 2))
        assertTrue(search("a", "龙井茶").isEmpty())
        assertEquals(listOf(first.id), search("a", "咖啡").map { it.id })
        database.dao().delete("a", "window", listOf(first.id))
        assertTrue(search("a", "咖啡").isEmpty())
        assertEquals(1, database.dao().list("b", "window").size)
        assertEquals(1, database.dao().list("a", "other-window").size)
    }

    @Test fun lateVectorsCannotOverwriteAnEditOrResurrectDeletion() = runBlocking {
        val dao = database.dao()
        val first = SemanticMemoryEntity(assistantId = "a", conversationId = "window", content = "原事实")
        dao.insert(first)
        val vector = SemanticVectors.encode(listOf(1f, 0f))
        assertEquals(0, dao.storeVector("a", "other-window", first.id, 1, first.contentHash, vector, "model:2", 2, 1L))
        assertEquals(1, dao.storeVector("a", "window", first.id, 1, first.contentHash, vector, "model:2", 2, 1L))
        val edited = first.copy(content = "新事实", contentHash = semanticContentHash("新事实"), contentVersion = 2)
        dao.put(edited)
        assertEquals(0, dao.storeVector("a", "window", first.id, 1, first.contentHash, vector, "model:2", 2, 2L))
        assertNull(dao.get("a", "window", first.id)?.embedding)
        dao.delete("a", "window", listOf(first.id))
        assertEquals(0, dao.storeVector("a", "window", first.id, 2, edited.contentHash, vector, "model:2", 2, 3L))
        assertNull(dao.get("a", "window", first.id))
    }

    @Test fun lifecycleFilterPrecedesTopKAndCoreDoesNotCrossAssistantBoundary() = runBlocking {
        val dao = database.dao()
        repeat(4) { index ->
            dao.insert(SemanticMemoryEntity(assistantId = "a", conversationId = "window", content = "杭州 杭州 杭州 杭州 $index", status = "archived"))
            dao.insert(SemanticMemoryEntity(assistantId = "a", conversationId = "other-window",
                content = "杭州 杭州 杭州 杭州 $index", isCore = true))
        }
        dao.insert(SemanticMemoryEntity(assistantId = "a", conversationId = "window", content = "杭州", expiresAt = 1L))
        val live = SemanticMemoryEntity(assistantId = "a", conversationId = "window", content = "杭州")
        val core = SemanticMemoryEntity(assistantId = "a", conversationId = "window", content = "杭州", isCore = true, expiresAt = 1L)
        dao.insert(live)
        dao.insert(core)
        assertEquals(setOf(live.id, core.id), search("a", "杭州").map { it.id }.toSet())
    }

    @Test fun largePersistedJobsReadExactUnicodeAfterRestart() = runBlocking {
        val payload = "{\"archive\":\"" + "中文😀".repeat(400_000) + "\"}"
        val job = SemanticJobEntity("large-import", "a", "content_import", payload, 17L, "window")
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
