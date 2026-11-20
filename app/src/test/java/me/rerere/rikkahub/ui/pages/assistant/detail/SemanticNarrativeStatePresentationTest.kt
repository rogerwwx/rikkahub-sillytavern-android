package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.memory.semantic.*
import org.junit.Assert.*
import org.junit.Test

class SemanticNarrativeStatePresentationTest {
    private val messages = listOf(
        DreamMessage("node", "message", "assistant", "林夏归还了钥匙，约定已经完成。", 1, true),
    )
    private val source = DreamSource("conversation", "node", "message", 0,
        messages[0].snapshotHash, "assistant", 1, 0, messages[0].text.length)
    private fun entry(kind: String = "promise", status: String = "done", reason: String = "钥匙已归还") =
        SemanticStateEntry("entry", kind, "林夏", "归还钥匙", "已归还", status, reason, 1, listOf(source), 0)
    private fun view(entries: List<SemanticStateEntry> = listOf(entry()), pending: Int = 0) =
        SemanticStateView(emptySemanticState("assistant", "conversation").copy(
            coveredCount = 1, prefixHash = semanticStatePrefixHash(messages, 1), entries = entries),
            messages, pending)

    @Test
    fun allFiveGroupsRemainVisibleIncludingClosedPromises() {
        val entries = listOf("scene", "promise", "item", "relationship", "character").mapIndexed { index, kind ->
            entry(kind).copy(id = "entry-$index")
        }
        val groups = semanticNarrativeGroups(entries)
        assertEquals(listOf("人物", "关系", "物品", "约定", "场景"), groups.map { it.label })
        assertEquals(5, groups.sumOf { it.entries.size })
        assertEquals("done", groups.single { it.kind == "promise" }.entries.single().status)
        assertEquals(5, semanticNarrativeGroups(emptyList()).size)
    }

    @Test
    fun closedOutcomeAndReasonAreShownWithoutRevivingThePromise() {
        for ((status, label) in listOf("done" to "已完成", "cancelled" to "已取消", "failed" to "失败")) {
            assertEquals("已结案 · $label", semanticNarrativeStatusLabel(status))
        }
        assertEquals("在进行", semanticNarrativeStatusLabel("open"))
        assertEquals("已清除", semanticNarrativeStatusLabel("cleared"))
        val preview = semanticNarrativeSourcePreview(view(), entry())
        assertTrue(preview.contains("钥匙已归还"))
        assertTrue(preview.contains("已结案"))
        assertTrue(preview.contains("版本 1"))
    }

    @Test
    fun coverageAndGapAreIndependentFromNumberOfEntries() {
        val empty = view(emptyList(), pending = 2)
        val line = semanticNarrativeCoverageLine(empty)
        assertTrue(line.contains("已覆盖 1 条"))
        assertTrue(line.contains("缺口 2 条"))
        assertTrue(line.contains("0 格"))
    }

    @Test
    fun plannerGetsNoStateWhenCoverageHasGapOrFeatureIsDisabled() {
        assertTrue(semanticNarrativePreviewSafe(view(), true, "assistant", "conversation"))
        // 旧库和不完整覆盖均不能把状态交给查询规划。
        assertFalse(semanticNarrativePreviewSafe(view(pending = 1), true, "assistant", "conversation"))
        assertFalse(semanticNarrativePreviewSafe(view(), false, "assistant", "conversation"))
    }

    @Test
    fun wrongScopeAndEditedOrIncompleteMessagesCannotBecomePlannerState() {
        assertFalse(semanticNarrativePreviewSafe(view(), true, "another-assistant", "conversation"))
        assertFalse(semanticNarrativePreviewSafe(view(), true, "assistant", "another-conversation"))
        assertFalse(semanticNarrativePreviewSafe(view().copy(messages = listOf(messages[0].copy(text = "钥匙尚未归还"))),
            true, "assistant", "conversation"))
        assertFalse(semanticNarrativePreviewSafe(view().copy(messages = listOf(messages[0].copy(finished = false))),
            true, "assistant", "conversation"))
    }

    @Test
    fun sourcePreviewLocatesMessageAndVerifiedExcerpt() {
        val preview = semanticNarrativeSourcePreview(view(), entry())
        assertTrue(preview.contains("第 1 条原文"))
        assertTrue(preview.contains("message"))
        assertTrue(preview.contains("偏移 0—${messages[0].text.length}"))
        assertTrue(preview.contains(messages[0].text))
    }

    @Test
    fun staleSourceDoesNotDisplayChangedTextAsEvidence() {
        val edited = view().copy(messages = listOf(messages[0].copy(text = "私密的新正文")))
        val preview = semanticNarrativeSourcePreview(edited, entry())
        assertTrue(preview.contains("来源已变化"))
        assertFalse(preview.contains("私密的新正文"))
        assertFalse(preview.contains(messages[0].text))
    }

    @Test
    fun onlyExpansionExclusiveCandidatesAreCountedAndDuplicatesCountOnce() {
        assertEquals(2, semanticPlannedCandidateCount(listOf("a", "b"), listOf("b", "c", "c", "d")))
        assertEquals(0, semanticPlannedCandidateCount(listOf("a"), listOf("a", "a")))
    }

    @Test
    fun queryPlanningStatusesUseChineseAndExplainFallback() {
        assertEquals("已关闭", semanticQueryPlanningStatusLabel("disabled"))
        assertEquals("已生成辅助查询", semanticQueryPlanningStatusLabel("success"))
        for (status in listOf("empty", "timeout", "invalid", "failure")) {
            assertTrue(semanticQueryPlanningStatusLabel(status).contains("沿用普通召回"))
        }
    }

    @Test
    fun unknownInternalStatusNeverLeaksEnglishIntoTheStateLabel() {
        assertEquals("未知状态", semanticNarrativeStatusLabel("future_status"))
        assertFalse(semanticQueryPlanningStatusLabel("future_status").contains("future_status"))
    }
}
