package me.rerere.rikkahub.data.memory.semantic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SemanticNarrativeStateStoreTest {
    private val assistant = "assistant"
    private val conversation = "conversation"
    private val messages = (0..5).map { i -> DreamMessage("n$i", "m$i",
        if (i % 2 == 0) "user" else "assistant", "原文$i", i.toLong(), true) }
    private class Jobs {
        val rows = linkedMapOf<String, SemanticJobEntity>()
        val readIds = mutableListOf<String>()
        var headers: List<SemanticJobHeader>? = null
        val store = SemanticNarrativeStateStore(
            readHeaders = { assistant ->
                headers ?: rows.values.filter { it.assistantId == assistant }.map {
                    SemanticJobHeader(it.id, it.assistantId, it.kind, it.updatedAt,
                        it.payloadJson.codePointCount(0, it.payloadJson.length), null, it.conversationId)
                }
            },
            readJob = { id -> readIds += id; rows[id] },
            writeJob = { rows[it.id] = it },
        )
    }
    private fun checkpoint(count: Int): SemanticStateCheckpoint {
        val before = emptySemanticState(assistant, conversation)
        val window = requireNotNull(semanticStateWindow(conversation, messages, 0, lastPosition = count - 1))
        return planSemanticStateChanges(before, SemanticStateDraft(before.revision, listOf(
            SemanticStateChange("set", listOf(window.evidence.last().evidenceId),
                kind = "relationship", subject = "林夏", facet = "与阿青关系", value = "兄妹")
        )), window, messages)
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("损坏状态必须明确报错") } catch (error: IllegalArgumentException) {
            assertFalse(error.message.isNullOrBlank())
        }
    }

    @Test fun `empty store returns scoped empty and complete pending count`() = runBlocking {
        val jobs = Jobs()
        val view = jobs.store.read(assistant, conversation, messages)
        assertEquals(emptySemanticState(assistant, conversation), view.checkpoint)
        assertEquals(6, view.pendingMessages)
        assertEquals(messages, view.messages)
        assertTrue(jobs.readIds.isEmpty())
    }
    @Test fun `put persists existing job kind without modifying other tasks`() = runBlocking {
        val jobs = Jobs()
        val unrelated = SemanticJobEntity("dream", assistant, "dream", "legacy", conversationId = conversation)
        jobs.rows[unrelated.id] = unrelated
        val state = checkpoint(2)
        jobs.store.put(state)
        assertEquals(unrelated, jobs.rows["dream"])
        val row = jobs.rows.values.single { it.kind == "narrative_state" }
        assertEquals(assistant, row.assistantId)
        assertEquals(conversation, row.conversationId)
        assertEquals(state, semanticStateJson.decodeFromString<SemanticStateCheckpoint>(row.payloadJson))
    }
    @Test fun `headers filter scope and kind before materializing payload`() = runBlocking {
        val jobs = Jobs()
        jobs.store.put(checkpoint(2))
        val valid = jobs.rows.values.single()
        jobs.rows["other-chat"] = valid.copy(id = "other-chat", conversationId = "other", payloadJson = "{broken")
        jobs.rows["other-assistant"] = valid.copy(id = "other-assistant", assistantId = "other", payloadJson = "{broken")
        jobs.rows["dream"] = valid.copy(id = "dream", kind = "dream", payloadJson = "{broken")
        assertEquals(2, jobs.store.read(assistant, conversation, messages).checkpoint.coveredCount)
        assertEquals(listOf(valid.id), jobs.readIds)
    }
    @Test fun `read prefers coverage over update time and stops before old payloads`() = runBlocking {
        val jobs = Jobs()
        for (count in listOf(2, 6, 4)) jobs.store.put(checkpoint(count))
        jobs.rows.replaceAll { _, row ->
            val state = semanticStateJson.decodeFromString<SemanticStateCheckpoint>(row.payloadJson)
            row.copy(updatedAt = (100 - state.coveredCount).toLong())
        }
        val view = jobs.store.read(assistant, conversation, messages + messages.last().copy(nodeId = "new", messageId = "new", role = "user"))
        assertEquals(6, view.checkpoint.coveredCount)
        assertEquals(1, view.pendingMessages)
        assertEquals(1, jobs.readIds.size)
    }
    @Test fun `frozen earlier bound skips future checkpoint payload reads`() = runBlocking {
        val jobs = Jobs()
        val current = checkpoint(2)
        jobs.store.put(current)
        val currentId = jobs.rows.values.single().id
        jobs.store.put(checkpoint(4))
        jobs.store.put(checkpoint(6))
        jobs.rows.replaceAll { id, row ->
            if (id == currentId) row else row.copy(payloadJson = "{unread future payload")
        }
        val view = jobs.store.read(assistant, conversation, messages.take(2))
        assertEquals(current, view.checkpoint)
        assertEquals(0, view.pendingMessages)
        assertEquals(listOf(currentId), jobs.readIds)
    }
    @Test fun `edited branch falls back then restores historical checkpoint`() = runBlocking {
        val jobs = Jobs()
        for (count in listOf(2, 4, 6)) jobs.store.put(checkpoint(count))
        val branch = messages.toMutableList().apply { this[2] = this[2].copy(text = "切换分支") }
        assertEquals(2, jobs.store.read(assistant, conversation, branch).checkpoint.coveredCount)
        assertEquals(3, jobs.readIds.size)
        jobs.readIds.clear()
        assertEquals(6, jobs.store.read(assistant, conversation, messages).checkpoint.coveredCount)
        assertEquals(1, jobs.readIds.size)
        assertEquals(3, jobs.rows.size)
    }
    @Test fun `repeated identical put does not create duplicate snapshots`() = runBlocking {
        val jobs = Jobs()
        jobs.store.put(checkpoint(2))
        jobs.store.put(checkpoint(2))
        assertEquals(1, jobs.rows.size)
    }
    @Test fun `schema corruption is explicit instead of empty state`() = runBlocking {
        val jobs = Jobs()
        jobs.store.put(checkpoint(2))
        jobs.rows.replaceAll { _, row ->
            row.copy(payloadJson = semanticStateJson.encodeToString(checkpoint(2).copy(schemaVersion = 99)))
        }
        try {
            jobs.store.read(assistant, conversation, messages)
            fail("未知 schema 必须报错")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("schemaVersion"))
        }
    }
    @Test fun `truncated and unknown fields cannot masquerade as empty state`() = runBlocking {
        for (payload in listOf("{broken", "{}",
            semanticStateJson.encodeToString(checkpoint(2)).dropLast(1) + ",\"override\":true}")) {
            val jobs = Jobs()
            jobs.store.put(checkpoint(2))
            jobs.rows.replaceAll { _, row -> row.copy(payloadJson = payload) }
            reject { jobs.store.read(assistant, conversation, messages) }
        }
    }
    @Test fun `payload scope revision row and job identity are validated`() = runBlocking {
        val state = checkpoint(2)
        for (bad in listOf(state.copy(conversationId = "other"), state.copy(revision = "broken"),
            state.copy(entries = listOf(state.entries.single().copy(status = "unknown"))), state.copy(coveredCount = 4))) {
            val jobs = Jobs()
            jobs.store.put(state)
            jobs.rows.replaceAll { _, row -> row.copy(payloadJson = semanticStateJson.encodeToString(bad)) }
            reject { jobs.store.read(assistant, conversation, messages) }
        }
        val jobs = Jobs()
        jobs.store.put(state)
        val row = jobs.rows.values.single()
        jobs.headers = listOf(SemanticJobHeader(row.id, assistant, "narrative_state", 0, 0, null, conversation))
        jobs.rows[row.id] = row.copy(kind = "dream")
        reject { jobs.store.read(assistant, conversation, messages) }
    }
    @Test fun `missing payload and malformed state job id report damage`() = runBlocking {
        val jobs = Jobs()
        jobs.headers = listOf(SemanticJobHeader("malformed", assistant, "narrative_state", 0, 0, null, conversation))
        reject { jobs.store.read(assistant, conversation, messages) }
        jobs.store.put(checkpoint(2))
        val row = jobs.rows.values.single()
        jobs.headers = listOf(SemanticJobHeader(row.id, assistant, "narrative_state", 0, 0, null, conversation))
        jobs.rows.clear()
        reject { jobs.store.read(assistant, conversation, messages) }
    }
    @Test fun `put validates before writing and retains earlier snapshots`() = runBlocking {
        val jobs = Jobs()
        val state = checkpoint(2)
        jobs.store.put(state)
        reject { jobs.store.put(state.copy(revision = "manual-overwrite")) }
        assertEquals(1, jobs.rows.size)
        jobs.store.put(checkpoint(6))
        assertEquals(2, jobs.rows.size)
    }
    @Test fun `completed flag mutation invalidates dependent stored snapshots`() = runBlocking {
        val jobs = Jobs()
        jobs.store.put(checkpoint(2))
        jobs.store.put(checkpoint(6))
        val changed = messages.toMutableList().apply { this[2] = this[2].copy(finished = false) }
        assertEquals(2, jobs.store.read(assistant, conversation, changed).checkpoint.coveredCount)
    }
}
