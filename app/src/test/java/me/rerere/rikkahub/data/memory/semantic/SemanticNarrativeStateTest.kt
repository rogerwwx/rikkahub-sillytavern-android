package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import org.junit.Assert.*
import org.junit.Test

class SemanticNarrativeStateTest {
    private val assistant = "assistant"
    private val conversation = "conversation"
    private fun message(index: Int, role: String, text: String = "原文$index", finished: Boolean = true) =
        DreamMessage("node$index", "message$index", role, text, index.toLong(), finished)
    private val history = listOf(message(0, "assistant"), message(1, "user"), message(2, "assistant"),
        message(3, "user"), message(4, "assistant"))
    private fun empty() = emptySemanticState(assistant, conversation)
    private fun window(messages: List<DreamMessage> = history, covered: Int = 0, turns: Int = 0) =
        requireNotNull(semanticStateWindow(conversation, messages, covered, turns))
    private fun add(window: DreamWindow, subject: String = "林夏", facet: String = "左腕伤势",
        value: String = "仍需固定", kind: String = "character") =
        SemanticStateChange("set", listOf(window.evidence.last().evidenceId),
            kind = kind, subject = subject, facet = facet, value = value)
    private fun apply(before: SemanticStateCheckpoint, window: DreamWindow, vararg changes: SemanticStateChange,
        messages: List<DreamMessage> = history) =
        planSemanticStateChanges(before, SemanticStateDraft(before.revision, changes.toList()), window, messages)
    private fun reject(block: () -> Unit) {
        try { block(); fail("应拒绝无效状态操作") } catch (error: IllegalArgumentException) {
            assertFalse(error.message.isNullOrBlank())
        }
    }
    private fun first() = window(turns = 1).let { apply(empty(), it, add(it)) }
    private fun change(before: SemanticStateCheckpoint, window: DreamWindow, operation: String = "set",
        value: String? = "可以活动", outcome: String? = null, reason: String? = null) =
        SemanticStateChange(operation, listOf(window.evidence.last().evidenceId), before.entries.single().id,
            before.entries.single().version, value = value, outcome = outcome, reason = reason)

    @Test fun `opening assistant is a completed state turn`() {
        val selected = window(turns = 1)
        assertEquals(listOf(0), selected.evidence.map { it.position })
        assertEquals(1, selected.completeTurns)
    }
    @Test fun `zero turns covers whole complete prefix without splitting`() {
        assertEquals((0..4).toList(), window().evidence.map { it.position })
        assertEquals(3, window().completeTurns)
    }
    @Test fun `unfinished middle turn blocks all later turns`() {
        val messages = history.toMutableList().apply { this[2] = this[2].copy(finished = false) }
        assertEquals(listOf(0), window(messages).evidence.map { it.position })
        assertNull(semanticStateWindow(conversation, messages, 1))
    }
    @Test fun `unfinished opening cannot be skipped`() {
        assertNull(semanticStateWindow(conversation, history.mapIndexed { i, m -> if (i == 0) m.copy(finished = false) else m }, 0))
    }
    @Test fun `last user waits for assistant and frozen bound never splits a turn`() {
        assertEquals((0..4).toList(), window(history + message(5, "user")).evidence.map { it.position })
        assertNull(semanticStateWindow(conversation, history, 1, lastPosition = 1))
        assertNull(semanticStateWindow(conversation, listOf(message(0, "user")), 0))
    }
    @Test fun `coverage must match a complete boundary`() {
        reject { semanticStateWindow(conversation, history, 2) }
        reject { semanticStateWindow(conversation, history, -1) }
        reject { semanticStatePrefixHash(history, 6) }
    }
    @Test fun `earlier frozen boundary needs no state backfill`() {
        assertNull(semanticStateWindow(conversation, history, 3, lastPosition = 1))
        assertNull(semanticStateWindow(conversation, history, 0, lastPosition = -1))
    }
    @Test fun `consecutive opening assistants form a single whole turn`() {
        val messages = listOf(message(0, "assistant"), message(1, "assistant"),
            message(2, "user"), message(3, "assistant"))
        assertEquals(listOf(0, 1), window(messages, turns = 1).evidence.map { it.position })
        assertNull(semanticStateWindow(conversation, messages, 0, lastPosition = 0))
        assertNull(semanticStateWindow(conversation, messages.mapIndexed { i, m ->
            if (i == 1) m.copy(finished = false) else m
        }, 0))
    }
    @Test fun `prefix hashes include identity role text and completion`() {
        val hash = semanticStatePrefixHash(history, 5)
        for (changed in listOf(history[0].copy(nodeId = "branch"), history[0].copy(messageId = "swipe"),
            history[0].copy(role = "user"), history[0].copy(text = "编辑"), history[0].copy(finished = false))) {
            assertNotEquals(hash, semanticStatePrefixHash(listOf(changed) + history.drop(1), 5))
        }
        assertEquals(hash, semanticStatePrefixHash(history + message(5, "user"), 5))
    }
    @Test fun `empty revision is deterministic and scoped`() {
        assertEquals(empty(), empty())
        assertNotEquals(empty().revision, emptySemanticState("other", conversation).revision)
        assertNotEquals(empty().revision, emptySemanticState(assistant, "other").revision)
    }
    @Test fun `longest matching checkpoint survives branch round trip`() {
        val first = first()
        val second = apply(first, window(covered = 1), change(first, window(covered = 1)))
        val checkpoints = listOf(second, first)
        assertEquals(second, selectSemanticStateCheckpoint(assistant, conversation, checkpoints, history))
        val branch = history.toMutableList().apply { this[2] = this[2].copy(text = "另一分支") }
        assertEquals(first, selectSemanticStateCheckpoint(assistant, conversation, checkpoints, branch))
        assertEquals(second, selectSemanticStateCheckpoint(assistant, conversation, checkpoints, history))
        val edited = history.toMutableList().apply { this[0] = this[0].copy(text = "改开场") }
        assertEquals(empty(), selectSemanticStateCheckpoint(assistant, conversation, checkpoints, edited))
    }
    @Test fun `schema scope row and revision mismatches are rejected`() {
        val state = first()
        for (bad in listOf(state.copy(schemaVersion = 2), state.copy(conversationId = "other"),
            state.copy(revision = "broken"), state.copy(coveredCount = 2),
            state.copy(entries = listOf(state.entries.single().copy(status = "arbitrary"))))) {
            assertEquals(empty(), selectSemanticStateCheckpoint(assistant, conversation, listOf(bad), history))
        }
    }
    @Test fun `one facet update cannot overwrite another injury`() {
        val opening = window(turns = 1)
        val state = apply(empty(), opening, add(opening), add(opening, facet = "右腿伤势", value = "不能负重"))
        val next = window(covered = 1)
        val wrist = state.entries.single { it.facet == "左腕伤势" }
        val updated = apply(state, next, SemanticStateChange("set", listOf(next.evidence.last().evidenceId),
            wrist.id, wrist.version, value = "已痊愈"))
        assertEquals("不能负重", updated.entries.single { it.facet == "右腿伤势" }.value)
        assertEquals(2, updated.entries.single { it.facet == "左腕伤势" }.version)
    }
    @Test fun `clear keeps old value and new evidence as tombstone`() {
        val state = first()
        val next = window(covered = 1)
        reject { apply(state, next, change(state, next, "clear", value = null)) }
        val cleared = apply(state, next, change(state, next, "clear", value = null, reason = "医生确认伤势已经解除"))
        assertEquals("cleared", cleared.entries.single().status)
        assertEquals(state.entries.single().value, cleared.entries.single().value)
        assertTrue(cleared.entries.single().sources.any { it.evidenceId == next.evidence.last().evidenceId })
        assertTrue(renderSemanticStateForExtraction(cleared).contains("cleared"))
        val injection = planSemanticStateInjection(SemanticStateView(cleared, history, 0), "", 1000)
        assertTrue(injection.prompt.contains("已解除/撤销"))
        assertTrue(injection.prompt.contains("已失效旧值：${state.entries.single().value}"))
        assertFalse(injection.prompt.contains("\"status\":\"active\""))
        assertEquals(listOf(cleared.entries.single().id), injection.entryIds)
    }
    @Test fun `only four most recent closures are eligible while active cells have priority`() {
        val opening = window(turns = 1)
        val state = apply(empty(), opening, *Array(7) { add(opening, facet = "伤势$it") })
        val next = window(covered = 1)
        val changes = state.entries.take(6).map { old ->
            SemanticStateChange("clear", listOf(next.evidence.last().evidenceId), old.id, old.version, reason = "确认已经痊愈")
        }
        val cleared = apply(state, next, *changes.toTypedArray())
        val injection = planSemanticStateInjection(SemanticStateView(cleared, history, 0), "", 3000)
        assertEquals(5, injection.entryIds.size)
        assertEquals(2, injection.skipped)
        assertEquals(cleared.entries.single { it.status == "active" }.id, injection.entryIds.first())
        assertEquals(4, Regex("已解除/撤销").findAll(injection.prompt).count())
    }
    @Test fun `promise entry defaults to open`() {
        val state = first()
        val row = state.entries.single()
        assertEquals("open", SemanticStateEntry(row.id, "promise", row.subject, row.facet, row.value,
            sources = row.sources, lastPosition = row.lastPosition).status)
    }
    @Test fun `promise resolution requires new reason and explicit reopen`() {
        val opening = window(turns = 1)
        val state = apply(empty(), opening, add(opening, facet = "码头见面", value = "周六三点", kind = "promise"))
        assertEquals("open", state.entries.single().status)
        val next = window(covered = 1, turns = 1)
        reject { apply(state, next, change(state, next, "resolve", null, "done")) }
        val done = apply(state, next, change(state, next, "resolve", null, "done", "双方已经赴约"))
        assertEquals("done", done.entries.single().status)
        val final = window(covered = 3)
        reject { apply(done, final, change(done, final)) }
        reject { apply(done, final, change(done, final, "reopen", null)) }
        val reopened = apply(done, final, change(done, final, "reopen", null, reason = "双方明确重新约定"))
        assertEquals("open", reopened.entries.single().status)
        assertEquals(state.entries.single().id, reopened.entries.single().id)
    }
    @Test fun `all promise outcomes remain closed without wall clock expiry`() {
        val opening = window(turns = 1)
        val state = apply(empty(), opening, add(opening, kind = "promise"))
        val next = window(covered = 1)
        for (outcome in listOf("done", "cancelled", "failed")) {
            val closed = apply(state, next, change(state, next, "resolve", null, outcome, "剧情确认结案"))
            assertEquals(closed, selectSemanticStateCheckpoint(assistant, conversation, listOf(closed), history))
            assertTrue(planSemanticStateInjection(SemanticStateView(closed, history, 0), "", 2000).prompt.contains(outcome))
        }
    }
    @Test fun `stale base and version cannot overwrite this round`() {
        val state = first()
        val next = window(covered = 1)
        reject { planSemanticStateChanges(state, SemanticStateDraft(empty().revision, emptyList()), next, history) }
        reject { apply(state, next, change(state, next).copy(expectedVersion = 0)) }
    }
    @Test fun `unknown operations fields missing evidence and repeated cells fail atomically`() {
        val state = first()
        val next = window(covered = 1)
        for (bad in listOf(change(state, next).copy(operation = "overwrite"),
            change(state, next).copy(evidence = emptyList()), change(state, next).copy(evidence = listOf("unknown")),
            change(state, next).copy(evidence = listOf(next.evidence.last().evidenceId, next.evidence.last().evidenceId)),
            change(state, next).copy(kind = "sql"), change(state, next).copy(outcome = "done"))) {
            reject { apply(state, next, bad) }
        }
        reject { apply(state, next, change(state, next), change(state, next)) }
        assertEquals(1, state.entries.single().version)
    }
    @Test fun `identical normalized key cannot bypass version as new`() {
        val state = first()
        val next = window(covered = 1)
        reject { apply(state, next, add(next, subject = " 林夏 ")) }
        val opening = window(turns = 1)
        reject { apply(empty(), opening, add(opening), add(opening, subject = " 林夏 ")) }
    }
    @Test fun `length framed keys cannot collide through delimiters`() {
        val opening = window(turns = 1)
        val state = apply(empty(), opening, add(opening, subject = "甲:乙", facet = "丙"),
            add(opening, subject = "甲", facet = "乙:丙"))
        assertEquals(2, state.entries.map { it.id }.distinct().size)
    }
    @Test fun `limits reject whole result without dropping old cells`() {
        val opening = window(turns = 1)
        for (bad in listOf(add(opening, subject = "人".repeat(81)), add(opening, facet = "伤".repeat(81)),
            add(opening, value = "值".repeat(501)), add(opening).copy(reason = "因".repeat(301)),
            add(opening, kind = "unknown"))) {
            reject { apply(empty(), opening, bad) }
        }
        reject { apply(empty(), opening, *Array(201) { add(opening, facet = "字段$it") }) }
        reject { apply(empty(), opening, *Array(120) { add(opening, facet = "字段$it", value = "值".repeat(500)) }) }
    }
    @Test fun `empty delta still advances checkpoint and round trips serialization`() {
        val state = apply(empty(), window())
        assertEquals(5, state.coveredCount)
        assertTrue(state.entries.isEmpty())
        assertNotEquals(empty().revision, state.revision)
        assertEquals(state, Json.decodeFromString<SemanticStateCheckpoint>(Json.encodeToString(state)))
    }
    @Test fun `noncontiguous partial wrong scope and late windows cannot advance state`() {
        val valid = window()
        for (bad in listOf(valid.copy(evidence = valid.evidence.drop(1)),
            valid.copy(evidence = valid.evidence.mapIndexed { i, s -> if (i == 0) s.copy(start = 1) else s }),
            valid.copy(evidence = valid.evidence.map { it.copy(conversationId = "other") }),
            valid.copy(evidence = valid.evidence.map { it.copy(role = "tool") }),
            valid.copy(evidence = valid.evidence.map { it.copy(valid = false) }),
            valid.copy(evidence = valid.evidence.map { it.copy(isIncomplete = true) }))) {
            reject { apply(empty(), bad) }
        }
        val changed = history.toMutableList().apply { this[4] = this[4].copy(text = "晚到编辑") }
        reject { apply(empty(), valid, messages = changed) }
    }
    @Test fun `old evidence cannot be reused as this round evidence`() {
        val state = first()
        val next = window(covered = 1)
        reject { apply(state, next, change(state, next).copy(evidence = listOf(state.entries.single().sources.single().evidenceId))) }
    }
    @Test fun `extraction table keeps update identity without exposing stored source metadata`() {
        val state = first()
        val rendered = renderSemanticStateForExtraction(state)
        val table = Json.parseToJsonElement(rendered.substringAfter('\n')).jsonObject
        assertEquals(setOf("baseRevision", "coveredCount", "entries"), table.keys)
        assertEquals(state.revision, table.getValue("baseRevision").jsonPrimitive.content)
        assertEquals(state.coveredCount, table.getValue("coveredCount").jsonPrimitive.int)
        val row = table.getValue("entries").jsonArray.single().jsonObject
        val entry = state.entries.single()
        assertEquals(setOf("id", "version", "kind", "subject", "facet", "value", "status", "reason"), row.keys)
        assertEquals(entry.id, row.getValue("id").jsonPrimitive.content)
        assertEquals(entry.version, row.getValue("version").jsonPrimitive.int)
        assertEquals(entry.value, row.getValue("value").jsonPrimitive.content)
        for (source in entry.sources) {
            assertFalse(rendered.contains(source.contentHash))
            assertFalse(rendered.contains(source.messageId))
            assertFalse(rendered.contains(source.nodeId))
        }
        assertFalse(rendered.contains(state.prefixHash))
        assertFalse(rendered.contains("\"sources\""))
        assertFalse(rendered.contains("\"createdAt\""))
        assertFalse(rendered.contains("\"lastPosition\""))
        reject { renderSemanticStateForExtraction(state.copy(revision = "broken")) }
    }
    @Test fun `relationship is injected without matching query`() {
        val opening = window()
        val state = apply(empty(), opening, add(opening, facet = "与阿青关系", value = "兄妹", kind = "relationship"))
        val plan = planSemanticStateInjection(SemanticStateView(state, history, 0), "今天天气如何", 1000)
        assertTrue(plan.prompt.contains("兄妹"))
        assertEquals(listOf(state.entries.single().id), plan.entryIds)
        assertEquals(state.coveredCount, plan.coveredCount)
    }
    @Test fun `old relationship survives twenty recent items for acknowledgement query`() {
        val opening = window(turns = 1)
        val state = apply(empty(), opening, add(opening, facet = "与阿青关系", value = "兄妹", kind = "relationship"))
        val next = window(covered = 1)
        val updated = apply(state, next, *Array(20) { add(next, subject = "物品$it", facet = "归属",
            value = "林夏", kind = "item") })
        for (query in listOf("嗯", "走吧")) {
            val plan = planSemanticStateInjection(SemanticStateView(updated, history, 0), query, 600)
            assertEquals(state.entries.single().id, plan.entryIds.first())
            assertTrue(plan.prompt.contains("兄妹"))
        }
        val item = updated.entries.single { it.subject == "物品19" }
        val asked = planSemanticStateInjection(SemanticStateView(updated, history, 0), "物品19现在在哪里", 600)
        assertEquals(item.id, asked.entryIds.first())
    }
    @Test fun `current query matches exact subjects or facets without guessing aliases`() {
        val opening = window()
        val state = apply(empty(), opening,
            add(opening, subject = "林夏", facet = "身份", value = "医生", kind = "character"),
            add(opening, subject = "阿青与林夏", facet = "关系", value = "兄妹", kind = "relationship"),
            add(opening, subject = "银钥匙", facet = "保管人", value = "阿青", kind = "item"))
        assertEquals("林夏", state.entries.single {
            it.id == planSemanticStateInjection(SemanticStateView(state, history, 0), "林夏是谁", 1000).entryIds.first()
        }.subject)
        assertEquals("银钥匙", state.entries.single {
            it.id == planSemanticStateInjection(SemanticStateView(state, history, 0), "保管人变了吗", 1000).entryIds.first()
        }.subject)
        assertEquals("relationship", state.entries.single {
            it.id == planSemanticStateInjection(SemanticStateView(state, history, 0), "小夏是谁", 1000).entryIds.first()
        }.kind)
    }
    @Test fun `current version keeps only latest evidence while prior snapshot preserves chain`() {
        val before = first()
        val next = window(covered = 1)
        val after = apply(before, next, change(before, next))
        assertEquals(listOf(next.evidence.last()), after.entries.single().sources)
        assertEquals(listOf(window(turns = 1).evidence.single()), before.entries.single().sources)
    }
    @Test fun `injection uses at most forty percent including framing and no partial cells`() {
        val opening = window()
        val state = apply(empty(), opening, *Array(12) { add(opening, facet = "字段$it", value = "值".repeat(80)) })
        for (budget in listOf(0, 1, 20, 200, 1000, 3000)) {
            val plan = planSemanticStateInjection(SemanticStateView(state, history, 0), "", budget)
            assertTrue(plan.estimatedTokens <= minOf(budget * 2 / 5, (budget - 64).coerceAtLeast(0)))
            assertEquals(estimateTextTokens(plan.prompt), plan.estimatedTokens)
            assertEquals(state.entries.size, plan.entryIds.size + plan.skipped)
        }
        assertTrue(planSemanticStateInjection(SemanticStateView(state, history, 0), "", 64).prompt.isEmpty())
    }
    @Test fun `invalid view cannot inject an old state after early edit`() {
        val state = first()
        val changed = history.toMutableList().apply { this[0] = this[0].copy(text = "伤势已撤回") }
        reject { planSemanticStateInjection(SemanticStateView(state, changed, 4), "", 1000) }
    }
}
