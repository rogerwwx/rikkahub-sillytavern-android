package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class SemanticMemorySettings(
    val enabled: Boolean = false,
    val embeddingModelId: Uuid? = null,
    val dimensions: Int? = null,
    val topK: Int = 6,
    val minSimilarity: Double = 0.35,
    val candidateMultiplier: Int = 3,
    val rrfK: Int = 60,
    val coreBudget: Int = 2,
    val dream: SemanticDreamSettings = SemanticDreamSettings(),
) {
    fun normalized() = copy(
        dimensions = dimensions?.coerceIn(1, 65536),
        topK = topK.coerceIn(1, 50),
        minSimilarity = if (minSimilarity.isFinite()) minSimilarity.coerceIn(-1.0, 1.0) else 0.35,
        candidateMultiplier = candidateMultiplier.coerceIn(1, 10),
        rrfK = rrfK.coerceIn(1, 1000),
        coreBudget = coreBudget.coerceIn(0, topK.coerceIn(1, 50)),
        dream = dream.normalized(),
    )
}
