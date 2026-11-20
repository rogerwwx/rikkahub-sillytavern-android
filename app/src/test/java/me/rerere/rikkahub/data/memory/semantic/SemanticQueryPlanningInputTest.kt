package me.rerere.rikkahub.data.memory.semantic

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class SemanticQueryPlanningInputTest {
    @Test fun `请求已有摘要提供有界规划线索而默认缓存仍保持原行为`() {
        val summary = "周五在码头见面".repeat(1000)
        val input = buildSemanticRecallInput(listOf(
            UIMessage.system("旧摘要").copy(isContextCheckpoint = true),
            UIMessage.system(summary).copy(isContextCheckpoint = true),
            UIMessage.user("继续"),
        ), 0)
        val field = input.javaClass.declaredFields.firstOrNull { it.name == "historySummary" }
        assertNotNull("缺少请求已有摘要的规划输入", field)
        field!!.isAccessible = true
        val history = field.get(input) as String
        assertTrue(history.isNotBlank() && history.length <= 4000)
        assertFalse(history.contains("旧摘要"))
        val withoutSummary = buildSemanticRecallInput(listOf(UIMessage.user("继续")), 0)
        assertEquals(withoutSummary.cacheKey("memory", "jev"), input.cacheKey("memory", "jev"))
        val key = input.cacheKey("memory", "jev", queryPlanningEnabled = true,
            plannerRevision = "planner", stateRevision = "state")
        assertNotEquals(key, withoutSummary.cacheKey("memory", "jev", queryPlanningEnabled = true,
            plannerRevision = "planner", stateRevision = "state"))
        assertNotEquals(key, input.cacheKey("memory", "jev", queryPlanningEnabled = true,
            plannerRevision = "changed", stateRevision = "state"))
        assertNotEquals(key, input.cacheKey("memory", "jev", queryPlanningEnabled = true,
            plannerRevision = "planner", stateRevision = "changed"))
        assertEquals(input.cacheKey("memory", "jev"), input.cacheKey("memory", "jev",
            plannerRevision = "ignored", stateRevision = "ignored"))
    }
}
