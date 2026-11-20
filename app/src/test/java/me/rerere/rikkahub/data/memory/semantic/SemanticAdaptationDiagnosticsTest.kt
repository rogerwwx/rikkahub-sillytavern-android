package me.rerere.rikkahub.data.memory.semantic

import org.junit.Assert.*
import org.junit.Test

class SemanticAdaptationDiagnosticsTest {
    private fun event(detail: String) = SemanticDiagnosticEvent(assistantId = "a", conversationId = "c",
        operation = "recall", purpose = "query", detailJson = detail)

    @Test fun `状态计划与最终确认分开显示`() {
        val lines = semanticInjectionSummary(event("""{"state_entries_planned":3,"state_entries_confirmed":0,"state_estimated_tokens":180,"state_covered_count":24}"""))
        assertTrue(lines.any { it.contains("状态：预算接受 3 格") && it.contains("逐字确认存在 0 格") })
        assertTrue(lines.any { it.contains("覆盖前 24 条原文") && it.contains("180 token") })
    }

    @Test fun `规划仅列候选数不能暗示正确率`() {
        val text = semanticInjectionSummary(event("""{"plannedQueryCount":2,"plannedCandidateIds":["private-id-a","private-id-b"],"plannedSelectedIds":["private-id-a"]}""")).joinToString("\n")
        assertTrue(text.contains("扩展查询 2 条"))
        assertTrue(text.contains("新增候选 2 条"))
        assertFalse(text.contains("private-id"))
        assertFalse(text.contains("正确率"))
    }

    @Test fun `旧日志不伪造状态成功或零值`() {
        val lines = semanticInjectionSummary(event("{}"))
        assertFalse(lines.any { it.startsWith("状态：") || it.startsWith("查询规划：") })
    }
}
