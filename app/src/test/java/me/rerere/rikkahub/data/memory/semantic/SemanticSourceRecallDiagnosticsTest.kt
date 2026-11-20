package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSourceRecallDiagnosticsTest {
    private val settingsJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun oldSettingsDefaultSourceExpansionToDisabled() {
        val settings = settingsJson.decodeFromString<SemanticMemorySettings>(
            """{"enabled":true,"topK":9,"useAssistantContext":false}"""
        )
        val encoded = settingsJson.parseToJsonElement(settingsJson.encodeToString(settings)).jsonObject
        assertEquals(false, encoded["sourceExpansionEnabled"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(9, settings.topK)
        assertFalse(settings.useAssistantContext)
    }

    @Test
    fun explicitSourceExpansionSurvivesSerializationAndNormalization() {
        for (enabled in listOf(false, true)) {
            val settings = settingsJson.decodeFromString<SemanticMemorySettings>(
                """{"sourceExpansionEnabled":$enabled,"topK":9,"minSimilarity":0.4}"""
            ).normalized()
            val encoded = settingsJson.parseToJsonElement(settingsJson.encodeToString(settings)).jsonObject
            assertEquals(enabled, encoded["sourceExpansionEnabled"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(9, settings.topK)
            assertEquals(0.4, settings.minSimilarity, 0.0)
        }
    }

    @Test
    fun diagnosticsAggregateInjectionAndSourceFilters() {
        val keys = listOf("injection_budget", "source_invalid", "source_missing", "source_overlap",
            "source_visible", "source_budget", "source_expanded", "source_no_anchor",
            "source_request_stale", "source_request_boundary_missing", "source_request_boundary_changed",
            "semantic_request_stale")
        val first = keys.mapIndexed { index, key -> """"$key":${index + 1}""" }.joinToString(",")
        val second = keys.joinToString(",") { """"$it":2""" }
        val report = semanticDiagnostics(listOf(event("{$first}"), event("{$second}"), event("{}")))
        keys.forEachIndexed { index, key ->
            assertEquals(key, (index + 3).toLong(), report.filters[key])
        }
    }

    @Test
    fun malformedSourceFilterDoesNotBreakDiagnosticAggregation() {
        val report = semanticDiagnostics(listOf(
            event("""{"source_missing":{},"source_invalid":[],"source_budget":null,"injection_budget":2}""")
        ))
        assertEquals(0L, report.filters["source_missing"])
        assertEquals(0L, report.filters["source_invalid"])
        assertEquals(0L, report.filters["source_budget"])
        assertEquals(2L, report.filters["injection_budget"])
    }

    @Test
    fun legacyEventsDoNotClaimTheirInjectedCountWasConfirmed() {
        val lines = semanticInjectionSummary(event("""{"recalled":4,"injected":2}"""))
        assertEquals("记忆：选中 未知 → 预算接受 未知 → 逐字确认存在 未知", lines[0])
        assertEquals("来源：展开 未知 → 逐字确认存在 未知", lines[1])
        assertEquals("预算跳过：摘要 未知 · 来源 未知", lines[2])
        assertEquals("转换或缺失：未知", lines[3])
        assertEquals("注入估算：未知", lines[4])
    }

    @Test
    fun explicitZeroCountsAreDifferentFromMissingFields() {
        val lines = semanticInjectionSummary(event("""
            {"selectedIds":[],"budgetAcceptedIds":[],"confirmedInjectedIds":[],"injected":0,
             "injectionEstimatedTokens":0,"sourceExpanded":0,"sourceConfirmed":0,
             "injection_budget":0,"source_budget":0,"injectionTransformedOrMissing":0}
        """.trimIndent()))
        assertEquals("记忆：选中 0 条 → 预算接受 0 条 → 逐字确认存在 0 条", lines[0])
        assertEquals("来源：展开 0 片 → 逐字确认存在 0 片", lines[1])
        assertEquals("预算跳过：摘要 0 条 · 来源 0 片", lines[2])
        assertEquals("转换或缺失：0 项", lines[3])
        assertEquals("注入估算：0 token", lines[4])
        assertEquals("来源跳过：无效 未知 · 缺失 未知 · 重叠 未知 · 已可见 未知 · 未定位 未知", lines[5])
    }

    @Test
    fun summaryFormatsEachInjectionStageAndSourceCount() {
        val lines = semanticInjectionSummary(event("""
            {"selectedIds":["a","b","c"],"budgetAcceptedIds":["a","b"],
             "confirmedInjectedIds":["b"],"injected":1,"injectionEstimatedTokens":1234,
             "sourceExpanded":2,"sourceConfirmed":1,"injection_budget":1,"source_budget":3,
             "injectionTransformedOrMissing":2,"source_invalid":4,"source_missing":5,
             "source_overlap":6,"source_visible":7,"source_no_anchor":8}
        """.trimIndent()))
        assertEquals("记忆：选中 3 条 → 预算接受 2 条 → 逐字确认存在 1 条", lines[0])
        assertEquals("来源：展开 2 片 → 逐字确认存在 1 片", lines[1])
        assertEquals("预算跳过：摘要 1 条 · 来源 3 片", lines[2])
        assertEquals("转换或缺失：2 项", lines[3])
        assertEquals("注入估算：1234 token", lines[4])
        assertEquals("来源跳过：无效 4 · 缺失 5 · 重叠 6 · 已可见 7 · 未定位 8", lines[5])
    }

    @Test
    fun sourceWithoutAnchorIsSeparateFromSourceBudget() {
        val lines = semanticInjectionSummary(event("""{"source_no_anchor":2,"source_budget":0}"""))
        assertEquals("预算跳过：摘要 未知 · 来源 0 片", lines[2])
        assertEquals("来源跳过：无效 未知 · 缺失 未知 · 重叠 未知 · 已可见 未知 · 未定位 2", lines[5])
        val zero = semanticInjectionSummary(event("""{"source_no_anchor":0}"""))
        assertTrue(zero[5].endsWith("未定位 0"))
    }

    @Test
    fun changedRequestAndBoundaryFailuresAreShown() {
        val lines = semanticInjectionSummary(event("""
            {"source_request_stale":1,"source_request_boundary_missing":2,"source_request_boundary_changed":3}
        """.trimIndent()))
        assertEquals("请求校验：来源过时 1 · 记忆/设置过时 未知 · 无有效边界 2 · 边界已变 3", lines[6])
        assertTrue(lines.contains("来源过时：已回退摘要。"))
    }

    @Test
    fun partialRequestDiagnosticsKeepMissingFieldsUnknown() {
        val partial = semanticInjectionSummary(event("""{"source_request_stale":1}"""))
        assertEquals("请求校验：来源过时 1 · 记忆/设置过时 未知 · 无有效边界 未知 · 边界已变 未知", partial[6])
        assertTrue(partial.contains("来源过时：已回退摘要。"))
        val zero = semanticInjectionSummary(event("""{"source_request_boundary_changed":0}"""))
        assertEquals("请求校验：来源过时 未知 · 记忆/设置过时 未知 · 无有效边界 未知 · 边界已变 0", zero.last())
        assertEquals(6, semanticInjectionSummary(event("{}")).size)
    }

    @Test
    fun semanticRequestStaleExplainsRemovalInsteadOfSourceOnlyFallback() {
        val lines = semanticInjectionSummary(event("""{"semantic_request_stale":1}"""))
        assertEquals("请求校验：来源过时 未知 · 记忆/设置过时 1 · 无有效边界 未知 · 边界已变 未知", lines[6])
        assertTrue(lines.contains("记忆/设置过时：已撤下全部记忆块。"))
        assertFalse(lines.any { it.contains("已回退摘要") })
        val sourceOnly = semanticInjectionSummary(event("""{"source_request_stale":1}"""))
        assertTrue(sourceOnly.contains("来源过时：已回退摘要。"))
        assertFalse(sourceOnly.any { it.contains("已撤下全部记忆块") })
    }

    @Test
    fun zeroOrMalformedStaleFieldsDoNotClaimFallbackOrRemoval() {
        val zero = semanticInjectionSummary(event("""{"source_request_stale":0,"semantic_request_stale":0}"""))
        assertEquals("请求校验：来源过时 0 · 记忆/设置过时 0 · 无有效边界 未知 · 边界已变 未知", zero.last())
        assertFalse(zero.any { it.contains("已回退摘要") || it.contains("已撤下全部记忆块") })
        for (json in listOf("""{"semantic_request_stale":-1}""", """{"semantic_request_stale":{}}""")) {
            assertEquals(semanticInjectionSummary(event("{}")), semanticInjectionSummary(event(json)))
        }
    }

    @Test
    fun partialEventKeepsKnownFieldsWithoutFillingMissingCountsWithZero() {
        val lines = semanticInjectionSummary(event("""
            {"selectedIds":["a"],"sourceExpanded":0,"source_budget":2,"injectionEstimatedTokens":90}
        """.trimIndent()))
        assertEquals("记忆：选中 1 条 → 预算接受 未知 → 逐字确认存在 未知", lines[0])
        assertEquals("来源：展开 0 片 → 逐字确认存在 未知", lines[1])
        assertEquals("预算跳过：摘要 未知 · 来源 2 片", lines[2])
        assertEquals("注入估算：90 token", lines[4])
    }

    @Test
    fun malformedFieldsDoNotHideOtherKnownStagesOrBecomeZero() {
        val lines = semanticInjectionSummary(event("""
            {"selectedIds":{},"budgetAcceptedIds":[],"confirmedInjectedIds":null,
             "sourceExpanded":-1,"sourceConfirmed":{},"injectionEstimatedTokens":[],
             "injection_budget":0,"source_budget":null,"injectionTransformedOrMissing":1}
        """.trimIndent()))
        assertEquals("记忆：选中 未知 → 预算接受 0 条 → 逐字确认存在 未知", lines[0])
        assertEquals("来源：展开 未知 → 逐字确认存在 未知", lines[1])
        assertEquals("预算跳过：摘要 0 条 · 来源 未知", lines[2])
        assertEquals("转换或缺失：1 项", lines[3])
        assertEquals("注入估算：未知", lines[4])
        for (json in listOf("not json", "[]", "null")) {
            assertEquals(semanticInjectionSummary(event("{}")), semanticInjectionSummary(event(json)))
        }
    }

    @Test
    fun summaryContainsCountsWithoutEchoingEventContent() {
        val lines = semanticInjectionSummary(event("""
            {"selectedIds":["private-memory-id"],"budgetAcceptedIds":[null],
             "confirmedInjectedIds":[1],"query":"private-query","prompt":"private-source"}
        """.trimIndent())).joinToString("\n")
        assertTrue(lines.contains("选中 1 条"))
        assertTrue(lines.contains("预算接受 未知"))
        assertTrue(lines.contains("逐字确认存在 未知"))
        assertFalse(lines.contains("private-"))
    }

    private fun event(detailJson: String) = SemanticDiagnosticEvent(
        assistantId = "assistant", conversationId = "conversation", operation = "recall",
        purpose = "chat", status = "success", detailJson = detailJson,
    )
}
