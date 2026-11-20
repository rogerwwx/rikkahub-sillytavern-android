package me.rerere.rikkahub.data.memory.semantic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SemanticQueryPlanningTest {
    private val inputs = SemanticQueryPlanningInputs("当前在码头", "她提起周五的约定", "旧日曾到灯塔", "左腕受伤")
    private fun parse(text: String) = parseSemanticQueryPlan(text, inputs)
    private fun hit(id: String, score: Double = 0.8) = SemanticRankHit(id, null, 0.7, score)

    @Test fun `严格逐字锚点只接受实际发送的来源`() {
        assertEquals(listOf("周五约定"), parse(
            """{"queries":[{"text":"周五约定","anchors":[{"source":"recent","quote":"周五的约定"}]}]}""").queries)
        for (json in listOf(
            """{"queries":[{"text":"查询","anchors":[{"source":"recent","quote":"周六"}]}]}""",
            """{"queries":[{"text":"查询","anchors":[]}]}""",
            """{"queries":[{"text":"查询","anchors":[{"source":"other","quote":"码头"}]}]}""",
            """{"queries":[{"text":"查询","anchors":[{"source":"current","quote":3}]}]}""",
            """{"queries":[],"extra":true}""",
            """```json {"queries":[]} ```""",
        )) assertTrue(parse(json).queries.isEmpty())
        assertEquals("empty", parse("""{"queries":[]}""").status)
    }

    @Test fun `重复原query和超限输出不会扩展`() {
        for (text in listOf("当前在码头", "x".repeat(241))) {
            assertTrue(parse("""{"queries":[{"text":"$text","anchors":[{"source":"current","quote":"码头"}]}]}""")
                .queries.isEmpty())
        }
        val query = """{"text":"码头约定","anchors":[{"source":"current","quote":"码头"}]}"""
        assertTrue(parse("""{"queries":[$query,$query]}""").queries.isEmpty())
        assertTrue(parse("""{"queries":[$query,$query,$query]}""").queries.isEmpty())
    }

    @Test fun `受限输入保留各源首尾并仅在发送范围校验`() {
        val bounded = semanticQueryPlanningInputs("开头"+"中".repeat(20000)+"结尾", "近期", "摘要", "状态")
        assertTrue(bounded.current.startsWith("开头") && bounded.current.endsWith("结尾"))
        assertTrue(bounded.current.length <= 6000)
        assertEquals("近期", bounded.recent)
        assertEquals("摘要", bounded.history)
        assertEquals("状态", bounded.state)
        assertTrue(parseSemanticQueryPlan(
            """{"queries":[{"text":"中段查询","anchors":[{"source":"current","quote":"${"中".repeat(6001)}"}]}]}""",
            bounded).queries.isEmpty())
    }

    @Test fun `省略拼接标记和空泛锚点不算原文证据`() {
        val bounded = semanticQueryPlanningInputs("开头" + "中".repeat(10000) + "结尾", "这里，……", "", "")
        for (quote in listOf("…（中间省略）…", "中间省略", "……", "这里")) {
            val source = if (quote in listOf("……", "这里")) "recent" else "current"
            assertEquals("invalid", parseSemanticQueryPlan(
                """{"queries":[{"text":"查找背景","anchors":[{"source":"$source","quote":"$quote"}]}]}""",
                bounded).status)
        }
    }

    @Test fun `候选按原二新一轮转且扩展间公平并保留原序`() {
        val baseline = (1..6).map { hit("b$it", 100.0-it) }
        val merged = mergeSemanticPlannedCandidates(baseline,
            listOf(listOf(hit("b1"), hit("p1"), hit("p2"), hit("p3")),
                listOf(hit("q1"), hit("q2"))))
        assertEquals(listOf("b1","b2","p1","b3","b4","q1","b5","b6","p2","q2","p3"),
            merged.hits.map { it.id })
        assertEquals(baseline.map { it.id }, merged.baselineCandidateIds)
        assertEquals(listOf("p1","q1","p2","q2","p3"), merged.plannedCandidateIds)
        assertTrue(merged.hits.zipWithNext().all { (a,b) -> a.score > b.score })
        assertTrue(merged.hits.all { it.score in 0.0..1.0 })
        assertEquals(merged.hits.take(6),
            applySemanticMmr(merged.hits, 6) { it })
        assertEquals(baseline, mergeSemanticPlannedCandidates(baseline, emptyList()).hits)
    }

    @Test fun `错误扩展能够挤掉部分原TopK必须可见`() {
        val baseline = (1..6).map { hit("b$it") }
        val merged = mergeSemanticPlannedCandidates(baseline, listOf(listOf(hit("wrong"))))
        val selected = applySemanticMmr(merged.hits, 3) { it }.map { it.id }
        assertEquals(listOf("b1","b2","wrong"), selected)
        assertFalse("b3" in selected)
        assertTrue("b3" in merged.baselineCandidateIds)
    }

    @Test fun `embedding扩展失败只重试原batch且取消重抛`() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val result = requestSemanticPlannedBatch(listOf("original", "assistant"), listOf("planned")) {
            calls += it
            if ("planned" in it) error("含敏感正文的错误")
            it.map { listOf(1f, 0f) }
        }
        assertEquals(listOf(listOf("original","assistant","planned"), listOf("original","assistant")), calls)
        assertTrue(result.plannedFailed)
        assertEquals(2, result.value.size)
        calls.clear()
        try {
            requestSemanticPlannedBatch(listOf("original"), listOf("planned")) {
                calls += it
                throw CancellationException("外部取消")
            }
            fail("取消必须重抛")
        } catch (_: CancellationException) { assertEquals(1, calls.size) }
    }

    @Test fun `空扩展batch零额外请求`() = runBlocking {
        var calls = 0
        val result = requestSemanticPlannedBatch(listOf("original"), emptyList()) {
            calls++
            assertEquals(listOf("original"), it)
            "ok"
        }
        assertEquals("ok", result.value)
        assertFalse(result.plannedFailed)
        assertEquals(1, calls)
    }
}
