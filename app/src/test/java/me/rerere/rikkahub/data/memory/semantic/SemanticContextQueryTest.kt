package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SemanticContextQueryTest {
    @Test fun pureContinuationsUseOnlyTheOriginalClosedListAndNormalization() {
        for (query in listOf("嗯。", "然后呢？", " OK! ")) {
            assertEquals("continuation", semanticRecallQueries(query, "上一条回复").assistantStrategy)
        }
        for (query in listOf("去码头。", "那两枚什么时候能取？")) {
            assertEquals("previous_assistant", semanticRecallQueries(query, "上一条回复").assistantStrategy)
        }
        assertEquals("disabled", semanticRecallQueries("嗯。", "上一条回复", false).assistantStrategy)
        assertEquals("no_context", semanticRecallQueries("然后呢？").assistantStrategy)
    }

    @Test fun pureContinuationUsesAuxiliaryCandidatesAloneAndErrorsStillReturnPrimary() {
        val primary = listOf(SemanticRankHit("uninformative", null, 1.0, 1.0))
        val auxiliary = listOf(SemanticRankHit("scene", 1.0, 0.9, 0.8))
        for (query in listOf("嗯。", "然后呢？")) {
            val queries = semanticRecallQueries(query, "上一条完整场景")
            val fused = fuseSemanticContext(primary, auxiliary, preferAssistant = queries.preferAssistant)
            assertEquals(1.0, fused.assistantWeight, 0.0)
            assertEquals(listOf("scene"), fused.hits.map { it.id })
            assertEquals(0.8, fused.hits.single().score, 0.0)
            assertEquals(primary, fuseSemanticContext(primary, auxiliary,
                technicalError = true, preferAssistant = queries.preferAssistant).hits)
            assertEquals(primary, fuseSemanticContext(primary, emptyList(), preferAssistant = queries.preferAssistant).hits)
        }
    }

    @Test fun oldSettingsEnableContextAndAnExplicitSwitchSurvivesNormalization() {
        assertTrue(Json.decodeFromString<SemanticMemorySettings>("{}").useAssistantContext)
        val off = Json.decodeFromString<SemanticMemorySettings>("""{"useAssistantContext":false}""")
        assertFalse(off.normalized().useAssistantContext)
        assertFalse(Json.decodeFromString<SemanticMemorySettings>(Json.encodeToString(off)).useAssistantContext)
    }

    @Test fun fullCurrentViewsAndOneExactAssistantQueryKeepTheirRoleBoundaries() {
        val narration = "仍在车站候车，没有提前离开，也没有改变原来的约定。"
        val current = "（$narration）还记得时间吗？"
        val previous = "先前约好了。对了，接着讲另一件事。"
        val queries = semanticRecallQueries(current, previous)
        assertEquals(listOf(current, narration), queries.mainVectorQuery.inputs)
        assertEquals("assistant: $previous\nuser: $current", queries.assistantQuery)
        assertEquals(listOf(current, narration, queries.assistantQuery), queries.vectorInputs)
        // 助手里的换题标记不作用于当前用户，辅助词法应保留整个角色文本。
        assertEquals("previous_assistant", queries.assistantStrategy)
        assertTrue(queries.assistantQuery!!.contains("先前约好了"))
    }

    @Test fun disabledMissingEmptyAndExplicitTopicSwitchNeverAddAnInput() {
        val previous = "这是上一条助手回复。"
        for (queries in listOf(
            semanticRecallQueries("继续", previous, false),
            semanticRecallQueries("继续", null),
            semanticRecallQueries("继续", " \n"),
            semanticRecallQueries("", previous),
            semanticRecallQueries("旧话题。对了，去哪里候车？", previous),
        )) {
            assertNull(queries.assistantQuery)
            assertEquals(queries.mainVectorQuery.inputs, queries.vectorInputs)
        }
        val current = "（仍在候车，没有提前离开，也没有改变约定。）继续讲吧"
        assertEquals(2, semanticRecallQueries(current, previous, false).vectorInputs.size)
    }

    @Test fun onlyThePreviousBodyIsBoundedAndUnicodePairsStayWhole() {
        val short = "正文".repeat(275)
        val current = "CURRENT_START" + "当前消息".repeat(1200) + "CURRENT_END"
        assertEquals("assistant: $short\nuser: $current", semanticRecallQueries(current, short).assistantQuery)
        val previous = "HEAD" + "😀".repeat(1100) + "TAIL"
        val query = semanticRecallQueries(current, previous).assistantQuery!!
        val bounded = query.removePrefix("assistant: ").substringBefore("\nuser: ")
        assertTrue(bounded.length <= 1200)
        assertTrue(bounded.startsWith("HEAD") && bounded.endsWith("TAIL"))
        assertTrue(query.endsWith("\nuser: $current"))
        bounded.forEachIndexed { index, char ->
            if (char.isHighSurrogate()) assertTrue(index + 1 < bounded.length && bounded[index + 1].isLowSurrogate())
            if (char.isLowSurrogate()) assertTrue(index > 0 && bounded[index - 1].isHighSurrogate())
        }
    }

    @Test fun lateFusionKeepsCurrentDominantAndMissingQueryContributesZero() {
        val main = listOf(SemanticRankHit("current", 1.0, 0.9, 1.0, lexicalRank = 2, vectorRank = 1))
        val auxiliary = listOf(SemanticRankHit("old", null, 1.0, 1.0, vectorRank = 1))
        val fused = fuseSemanticContext(main, auxiliary)
        assertEquals(0.4, fused.assistantWeight, 0.0)
        assertEquals("current", fused.hits.first().id)
        assertEquals(0.6, fused.hits.first().score, 1e-9)
        val old = fused.hits.last()
        assertEquals(0.4, old.score, 1e-9)
        assertNull(old.mainQueryScore)
        assertNull(old.vectorScore)
        assertFalse(old.vectorRanked)
        assertEquals(1.0, old.assistantEvidence!!.vectorScore!!, 0.0)
    }

    @Test fun onlyPrimaryFinalAndBothRouteLeadersReduceContextWeight() {
        val primary = SemanticRankHit("current", 1.0, 1.0, 1.0, lexicalRank = 1, vectorRank = 1)
        val auxiliary = listOf(SemanticRankHit("current", null, 0.9, 0.7),
            SemanticRankHit("old", null, 1.0, 1.0))
        val fused = fuseSemanticContext(listOf(primary), auxiliary)
        assertEquals(0.2, fused.assistantWeight, 0.0)
        assertEquals(0.94, fused.hits.first().score, 1e-9)
        // 路线第一名不是最终第一名时仍用 0.4，不恢复旧共识奖励。
        val other = SemanticRankHit("other", null, 0.7, 1.1, vectorRank = 2)
        assertEquals(0.4, fuseSemanticContext(listOf(other, primary), auxiliary).assistantWeight, 0.0)
    }

    @Test fun importanceIsWeightedInsideEachQueryOnceAndErrorsReturnPrimaryUnchanged() {
        val importance = mapOf("both" to 0.9, "tail" to 0.2)
        val primary = fuseSemanticRanks(listOf("both" to -2.0, "tail" to -1.0),
            listOf("both" to 1.0, "tail" to 0.6), 0.35, 6, 60, importance)
        val auxiliary = primary.reversed().map { it.copy(lexicalRank = null) }
        val fused = fuseSemanticContext(primary, auxiliary)
        primary.forEach { p ->
            assertEquals(p.score, fused.hits.single { it.id == p.id }.score, 1e-9)
        }
        assertEquals(primary, fuseSemanticContext(primary, auxiliary, technicalError = true).hits)
        assertEquals(primary, fuseSemanticContext(primary, emptyList()).hits)
        assertEquals(0.0, fuseSemanticContext(primary, emptyList()).assistantWeight, 0.0)
    }
}
