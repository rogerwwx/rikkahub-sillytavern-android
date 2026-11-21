package me.rerere.rikkahub.data.memory.semantic

import org.junit.Assert.*
import org.junit.Test

class SemanticDreamReferencesTest {
    private val messages = listOf(
        DreamMessage("u", "u", "user", "林夏说：e1、m1、s1、r1 都是原文，左腕已经包扎", 1, true),
        DreamMessage("a", "a", "assistant", "我记住了林夏的伤势", 2, true),
    )
    private val window = dreamWindow("conversation", messages, DreamCursor())!!
    private val before = emptySemanticState("assistant", "conversation")
    private val state = planSemanticStateChanges(before, SemanticStateDraft(before.revision, listOf(
        SemanticStateChange("set", listOf(window.evidence.first().evidenceId), kind = "character",
            subject = "林夏", facet = "左腕伤势", value = "已包扎"),
    )), window, messages)

    @Test fun shortReferencesRestoreRealIdsAndLeaveContentUntouched() {
        val refs = SemanticDreamReferences(window.evidence, listOf("dream:long-memory-id"), state)
        val text = """{"changes":[{"operation":"reinforce","evidence":["e1"],"targetId":"m1","expectedVersion":7,"scope":"role"}],"continuity":"e1 m1 s1 r1 原样保留","state":{"baseRevision":"r1","changes":[{"operation":"set","evidence":["e2"],"targetId":"s1","expectedVersion":1,"value":"e1 m1 s1 r1 原样保留"}]}}"""
        val draft = refs.parseDraft(text)
        assertEquals(window.evidence.first().evidenceId, draft.changes.single().evidence.single())
        assertEquals("dream:long-memory-id", draft.changes.single().targetId)
        assertEquals(7, draft.changes.single().expectedVersion)
        assertNull(draft.changes.single().content)
        assertEquals(state.revision, draft.state!!.baseRevision)
        assertEquals(state.entries.single().id, draft.state.changes.single().targetId)
        assertEquals("e1 m1 s1 r1 原样保留", draft.state.changes.single().value)
        assertEquals("e1 m1 s1 r1 原样保留", draft.continuity)
    }

    @Test fun unknownWrongTypeAndDuplicateReferencesAreRejected() {
        val refs = SemanticDreamReferences(window.evidence, listOf("old"))
        fun add(id: String) = """{"changes":[{"operation":"add","evidence":["$id"],"content":"事实"}]}"""
        assertTrue(runCatching { refs.parseDraft(add("e99")) }.isFailure)
        assertTrue(runCatching { refs.parseDraft(add("m1")) }.isFailure)
        assertTrue(runCatching { refs.parseDraft(add(window.evidence.first().evidenceId)) }.isFailure)
        assertTrue(runCatching {
            refs.parseDraft("""{"changes":[{"operation":"reinforce","evidence":["e1"],"targetId":"s1","expectedVersion":1}]}""")
        }.isFailure)
        assertTrue(runCatching {
            refs.parseDraft("""{"changes":[{"operation":"add","evidence":["e1","e1"],"content":"事实"}]}""")
        }.isFailure)
        assertTrue(runCatching { refs.expandMemoryIds(listOf("s1")) }.isFailure)
    }

    @Test fun transcriptsAndStateTablesOnlyAliasIdentityFields() {
        val refs = SemanticDreamReferences(window.evidence, checkpoint = state)
        val transcript = renderDreamTranscript(window.evidence, messages, refs::evidenceId)
        assertTrue(transcript.startsWith("[新证据 id=e1 role=user"))
        assertTrue(transcript.contains(messages.first().text))
        assertFalse(transcript.contains(window.evidence.first().evidenceId))
        val table = renderSemanticStateForExtraction(state, refs::stateId, refs.baseRevision!!)
        assertTrue(table.contains("\"baseRevision\":\"r1\"") && table.contains("\"id\":\"s1\""))
        assertTrue(table.contains("\"subject\":\"林夏\"") && table.contains("\"value\":\"已包扎\""))
        assertFalse(table.contains(state.revision) || table.contains(state.entries.single().id))
    }

    @Test fun stateOnlyAndRevisionValidationRemainStrict() {
        val refs = SemanticDreamReferences(window.evidence, checkpoint = before)
        val empty = refs.parseDraft("""{"changes":[],"continuity":null,"state":{"baseRevision":"r1","changes":[]}}""", stateOnly = true)
        assertEquals(before.revision, empty.state!!.baseRevision)
        assertTrue(runCatching {
            refs.parseDraft("""{"changes":[],"state":{"baseRevision":"r2","changes":[]}}""")
        }.isFailure)
        assertTrue(runCatching {
            refs.parseDraft("""{"changes":[],"continuity":"多余回忆","state":{"baseRevision":"r1","changes":[]}}""", stateOnly = true)
        }.isFailure)
        assertTrue(runCatching {
            SemanticDreamReferences(window.evidence).parseDraft("""{"changes":[],"state":{"baseRevision":"r1","changes":[]}}""")
        }.isFailure)
    }

    @Test fun mappedDraftStillUsesSourceAndVersionValidationBeforeCommit() {
        val refs = SemanticDreamReferences(window.evidence, checkpoint = before)
        val draft = refs.parseDraft("""{"changes":[{"operation":"add","evidence":["e1"],"content":"[包扎之后] 林夏左腕伤势缓解，我安心了","scope":"role"}],"state":{"baseRevision":"r1","changes":[{"operation":"set","evidence":["e1"],"kind":"character","subject":"林夏","facet":"左腕伤势","value":"已包扎"}]}}""")
        val memory = planDreamChanges("assistant", "run", draft, window, emptyList(), SemanticDreamSettings(), 2).single().after
        assertEquals(window.evidence.first().evidenceId, dreamSources(memory).single().evidenceId)
        assertEquals(state, planSemanticStateChanges(before, draft.state!!, window, messages))
        val updateRefs = SemanticDreamReferences(window.evidence, listOf(memory.id), state)
        val stale = updateRefs.parseDraft("""{"changes":[{"operation":"reinforce","evidence":["e1"],"targetId":"m1","expectedVersion":99,"scope":"role"}],"state":{"baseRevision":"r1","changes":[{"operation":"set","evidence":["e1"],"targetId":"s1","expectedVersion":99,"value":"错误更新"}]}}""")
        assertTrue(runCatching {
            planDreamChanges("assistant", "next", stale, window, listOf(memory), SemanticDreamSettings(), 3)
        }.isFailure)
        assertTrue(runCatching { planSemanticStateChanges(state, stale.state!!, window, messages) }.isFailure)
        assertTrue(runCatching {
            planSemanticStateChanges(before, draft.state, window, messages.map { it.copy(text = "来源已编辑") })
        }.isFailure)
    }

    @Test fun eachRequestBindsItsOwnEvidenceAndConsolidationSelection() {
        val first = SemanticDreamReferences(window.evidence, listOf("old-a", "old-b"))
        val changed = window.evidence.map { it.copy(contentHash = dreamHash("另一份来源")) }
        val second = SemanticDreamReferences(changed, listOf("old-c"))
        val text = """{"changes":[{"operation":"add","evidence":["e1"],"content":"事实"}]}"""
        assertEquals(window.evidence.first().evidenceId, first.parseDraft(text).changes.single().evidence.single())
        assertEquals(changed.first().evidenceId, second.parseDraft(text).changes.single().evidence.single())
        assertEquals(listOf("old-b", "old-a"), first.expandMemoryIds(listOf("m2", "m1")))
        assertTrue(runCatching { second.expandMemoryIds(listOf("m2")) }.isFailure)
    }
}
