package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.InjectionPosition
import kotlin.uuid.Uuid

/** 按助手持久保存；旧 Markdown 做梦继续使用原设置。 */
@Serializable
data class SemanticDreamSettings(
    val automatic: Boolean = false,
    val minCompletedTurns: Int = 10,
    val idleMinutes: Int = 2,
    val reviewIntervalHours: Int = 24,
    val modelId: Uuid? = null,
    val reflectionPrompt: String = "",
    val consolidationPrompt: String = "",
    val temperature: Double = 0.2,
    val reasoningLevel: String = "off",
    val maxOutputTokens: Int = 4096,
    val maxInputTokens: Int = 12000,
    val maxSegmentsPerRun: Int = 10,
    val segmentGapMinutes: Int = 180,
    val splitByDay: Boolean = true,
    val overlapTurns: Int = 2,
    val injectionPosition: InjectionPosition = InjectionPosition.AT_DEPTH,
    val injectionRole: String = "system",
    val injectionDepth: Int = 0,
    val injectionTokenBudget: Int = 1000,
    val consolidationEveryRuns: Int = 5,
    val consolidationMinCandidates: Int = 10,
    val episodicTtlDays: Int = 7,
    val factualTtlDays: Int = 180,
    val relationalTtlDays: Int = 90,
    val preferenceTtlDays: Int = 60,
    val plannedTtlDays: Int = 2,
    val unknownTtlDays: Int = 30,
    val protectCore: Boolean = true,
    val retryLimit: Int = 3,
    val retryBackoffSeconds: Int = 30,
    val jevEnabled: Boolean = false,
    val jevRecall: Boolean = false,
    val jevDreamReview: Boolean = false,
    val jevTimeoutMillis: Int = 2500,
    val jevRecallYes: Double = 0.80,
    val jevRecallNo: Double = 0.20,
    val jevRetentionYes: Double = 0.85,
    val jevRetentionNo: Double = 0.15,
    val jevEvidenceYes: Double = 0.90,
    val jevEvidenceNo: Double = 0.10,
) {
    fun normalized(): SemanticDreamSettings {
        val recall = probabilityPair(jevRecallYes, jevRecallNo, 0.8, 0.2)
        val retention = probabilityPair(jevRetentionYes, jevRetentionNo, 0.85, 0.15)
        val evidence = probabilityPair(jevEvidenceYes, jevEvidenceNo, 0.9, 0.1)
        return copy(
            minCompletedTurns = minCompletedTurns.coerceIn(1, 1000),
            idleMinutes = idleMinutes.coerceIn(1, 1440),
            reviewIntervalHours = reviewIntervalHours.coerceIn(1, 720),
            temperature = if (temperature.isFinite()) temperature.coerceIn(0.0, 2.0) else 0.2,
            reasoningLevel = reasoningLevel.takeIf { it in setOf("off", "auto", "low", "medium", "high", "xhigh", "max") } ?: "off",
            maxInputTokens = maxInputTokens.coerceIn(1024, 128000),
            maxOutputTokens = maxOutputTokens.coerceIn(256, 32768),
            maxSegmentsPerRun = maxSegmentsPerRun.coerceIn(1, 50),
            segmentGapMinutes = segmentGapMinutes.coerceIn(1, 10080),
            overlapTurns = overlapTurns.coerceIn(0, 10),
            injectionRole = injectionRole.takeIf { it in setOf("system", "user", "assistant") } ?: "system",
            injectionDepth = injectionDepth.coerceIn(0, 1000),
            injectionTokenBudget = injectionTokenBudget.coerceIn(64, 16000),
            consolidationEveryRuns = consolidationEveryRuns.coerceIn(1, 100),
            consolidationMinCandidates = consolidationMinCandidates.coerceIn(2, 1000),
            episodicTtlDays = episodicTtlDays.coerceIn(1, 3650),
            factualTtlDays = factualTtlDays.coerceIn(1, 3650),
            relationalTtlDays = relationalTtlDays.coerceIn(1, 3650),
            preferenceTtlDays = preferenceTtlDays.coerceIn(1, 3650),
            plannedTtlDays = plannedTtlDays.coerceIn(1, 3650),
            unknownTtlDays = unknownTtlDays.coerceIn(1, 3650),
            retryLimit = retryLimit.coerceIn(0, 5),
            retryBackoffSeconds = retryBackoffSeconds.coerceIn(10, 3600),
            jevTimeoutMillis = jevTimeoutMillis.coerceIn(250, 10000),
            jevRecallYes = recall.first, jevRecallNo = recall.second,
            jevRetentionYes = retention.first, jevRetentionNo = retention.second,
            jevEvidenceYes = evidence.first, jevEvidenceNo = evidence.second,
        )
    }

    fun ttlDays(type: String): Int = when (type) {
        "episodic" -> episodicTtlDays
        "factual" -> factualTtlDays
        "relational" -> relationalTtlDays
        "preference" -> preferenceTtlDays
        "planned" -> plannedTtlDays
        else -> unknownTtlDays
    }
}

private fun probabilityPair(yes: Double, no: Double, defaultYes: Double, defaultNo: Double): Pair<Double, Double> =
    if (yes.isFinite() && no.isFinite() && yes in 0.5..1.0 && no in 0.0..0.5 && no < yes) yes to no
    else defaultYes to defaultNo
