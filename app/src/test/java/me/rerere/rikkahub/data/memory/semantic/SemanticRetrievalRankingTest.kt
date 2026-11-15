package me.rerere.rikkahub.data.memory.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticRetrievalRankingTest {
    @Test
    fun missingCandidateRouteDoesNotCancelItsConfiguredWeight() {
        val hits = fuseSemanticScores(
            listOf("lexical" to 1.0, "lexical-tail" to 0.0),
            listOf("vector" to 0.9, "vector-tail" to 0.4),
            bm25Weight = 0.1, agreementBonus = 0.0,
        ).associateBy { it.id }
        assertEquals(0.1, hits.getValue("lexical").score, 1e-12)
        assertEquals(0.9, hits.getValue("vector").score, 1e-12)
    }

    @Test
    fun losingWeakVectorEvidenceDoesNotPromoteTheLexicalCandidate() {
        val lexical = listOf("noise" to 1.0, "lexical-tail" to 0.0)
        val vector = listOf("target" to 0.82, "noise" to 0.36, "vector-tail" to 0.35)
        val before = fuseSemanticScores(lexical, vector).first { it.id == "noise" }
        val after = fuseSemanticScores(lexical, vector.filter { it.second >= 0.4 }).first { it.id == "noise" }
        assertTrue("移除向量证据不应让同一条词法候选升为满分", after.score <= before.score)
        assertEquals(0.5, after.score, 1e-12)
    }

    @Test
    fun emptyRouteAndFailedRouteKeepTheirDifferentFallbackSemantics() {
        val vector = listOf("best" to 0.9, "tail" to 0.4)
        assertEquals(1.0, fuseSemanticScores(emptyList(), vector).first().score, 1e-12)
        val healthy = fuseSemanticRanks(emptyList(), vector, 0.35, 6, 60)
        val failed = fuseSemanticRanks(emptyList(), vector, 0.35, 6, 60, lexicalError = true)
        assertEquals(1.0 / 6.0, healthy.last().score, 1e-12)
        assertEquals((2.0 / 3.0) * (61.0 / 62.0) + 1.0 / 6.0, failed.last().score, 1e-12)
        assertTrue(fuseSemanticRanks(emptyList(), vector, 0.35, 6, 60,
            lexicalError = true, vectorError = true).isEmpty())
    }

    @Test
    fun lexicalRescueDoesNotPretendToPassTheVectorThreshold() {
        val hits = fuseSemanticRanks(
            listOf("rescued" to -2.0), listOf("rescued" to 0.2, "vector" to 0.8),
            minSimilarity = 0.35, topK = 6, rrfK = 60,
        )
        assertFalse(hits.first { it.id == "rescued" }.vectorRanked)
        assertTrue(hits.first { it.id == "vector" }.vectorRanked)
        assertEquals(setOf("rescued", "vector"), hits.map { it.id }.toSet())
    }

    @Test
    fun invalidAndDuplicateRouteScoresCannotPoisonOtherCandidates() {
        val lexical = listOf("bad" to Double.NaN, "a" to Double.NaN, "a" to 1.0,
            "a" to 0.0, "b" to 0.0, "infinite" to Double.POSITIVE_INFINITY)
        val vector = listOf("a" to 0.8, "a" to 0.1, "c" to 0.4)
        assertEquals(
            fuseSemanticScores(listOf("a" to 1.0, "b" to 0.0), listOf("a" to 0.8, "c" to 0.4)),
            fuseSemanticScores(lexical, vector),
        )
        assertEquals(listOf("a" to 1.0, "b" to 0.0),
            normalizeSemanticBm25(listOf("a" to Double.NaN, "a" to -2.0, "a" to -1.5, "b" to -1.0)))
    }

    @Test
    fun chineseExactDuplicatesYieldToAnotherMemory() {
        val hits = listOf(
            SemanticRankHit("first", null, null, 1.0),
            SemanticRankHit("duplicate", null, null, 0.98),
            SemanticRankHit("other", null, null, 0.60),
        )
        val texts = mapOf("first" to "她答应在雨夜陪我回家。", "duplicate" to "她答应在雨夜陪我回家。",
            "other" to "她害怕独自乘船。")
        assertEquals(listOf("first", "other"), applySemanticMmr(hits, 2) { texts.getValue(it) }.map { it.id })
    }

    @Test
    fun chineseOpposingFactsMustNotBeDeclaredEquivalentByAWordOverlapProxy() {
        val hits = listOf(
            SemanticRankHit("promise", null, null, 1.0),
            SemanticRankHit("refusal", null, null, 0.98),
            SemanticRankHit("background", null, null, 0.60),
        )
        val texts = mapOf("promise" to "她答应在雨夜陪我回家。", "refusal" to "她没有答应在雨夜陪我回家。",
            "background" to "她喜欢青色。")
        assertEquals(listOf("promise", "refusal"),
            applySemanticMmr(hits, 2) { texts.getValue(it) }.map { it.id })
    }
}
