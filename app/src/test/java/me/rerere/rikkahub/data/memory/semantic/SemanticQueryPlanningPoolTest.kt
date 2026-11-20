package me.rerere.rikkahub.data.memory.semantic

import org.junit.Assert.*
import org.junit.Test

class SemanticQueryPlanningPoolTest {
    private fun hit(id: String, vector: Int? = null, lexical: Int? = null, auxiliary: Boolean = false) =
        SemanticRecallHit(SemanticMemoryEntity(id=id, assistantId="a", conversationId="c", content=id),
            SemanticRankHit(id, null, null, .8, vectorRanked = false,
                vectorRank = vector.takeUnless { auxiliary }, lexicalRank = lexical.takeUnless { auxiliary },
                assistantEvidence = if (auxiliary) SemanticQueryEvidence(.8,null,null,lexical,null,vector,vector!=null) else null))

    @Test fun `原池沿用路线头部并保护辅助扩展槽位`() {
        val baseline = listOf(hit("fused"),hit("v1",vector=1),hit("v2",vector=2),
            hit("l1",lexical=1),hit("l2",lexical=2),hit("aux",vector=1,auxiliary=true))
        val planned = listOf(hit("new1"),hit("new2"),hit("new3"))
        val ranked = baseline + planned
        val pool = SemanticJevAssist.selectPlannedRerankPool(ranked, 6,
            baseline.map { it.memory.id }, planned.map { it.memory.id }, .4)
        assertEquals(6,pool.size)
        assertEquals(setOf("v1","l1","aux","fused","new1","new2"),pool.map { it.memory.id }.toSet())
    }

    @Test fun `双方不足互补且无扩展逐项等于旧池`() {
        val baseline = (1..8).map { hit("b$it",vector=it) }
        val one = listOf(hit("new"))
        val pool = SemanticJevAssist.selectPlannedRerankPool(baseline+one,6,
            baseline.map { it.memory.id },listOf("new"))
        assertEquals(6,pool.size)
        assertEquals(5,pool.count { it.memory.id.startsWith("b") })
        assertEquals(SemanticJevAssist.selectRerankPool(baseline,6),
            SemanticJevAssist.selectPlannedRerankPool(baseline,6,emptyList(),emptyList()))
        val many = (1..9).map { hit("new$it") }
        assertEquals(6,SemanticJevAssist.selectPlannedRerankPool(baseline.take(1)+many,6,
            listOf("b1"),many.map { it.memory.id }).size)
    }
}
