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
    val injectionPosition: InjectionPosition = InjectionPosition.AT_DEPTH,
    val injectionRole: String = "system",
    val injectionDepth: Int = 0,
    val injectionTokenBudget: Int = 1000,
    /** 手动总结的分块轮数（用户消息＋模型回复算一轮）；0 表示不分块，整段一次请求。 */
    val manualChunkTurns: Int = 0,
    val consolidationEveryRuns: Int = 5,
    val consolidationMinCandidates: Int = 10,
    val episodicTtlDays: Int = 7,
    val factualTtlDays: Int = 180,
    val relationalTtlDays: Int = 90,
    val preferenceTtlDays: Int = 60,
    val plannedTtlDays: Int = 2,
    val unknownTtlDays: Int = 30,
    val protectCore: Boolean = true,
    val jevEnabled: Boolean = false,
    val jevRecall: Boolean = false,
    val jevTimeoutMillis: Int = 2500,
    /** 粗召回后送进 Jev 重排的候选条数；再多也只取融合顺序前 N 条。 */
    val jevCandidateCount: Int = 20,
) {
    fun normalized(): SemanticDreamSettings {
        return copy(
            minCompletedTurns = minCompletedTurns.coerceIn(1, 1000),
            idleMinutes = idleMinutes.coerceIn(1, 1440),
            reviewIntervalHours = reviewIntervalHours.coerceIn(1, 720),
            temperature = if (temperature.isFinite()) temperature.coerceIn(0.0, 2.0) else 0.2,
            reasoningLevel = reasoningLevel.takeIf { it in setOf("off", "auto", "low", "medium", "high", "xhigh", "max") } ?: "off",
            injectionRole = injectionRole.takeIf { it in setOf("system", "user", "assistant") } ?: "system",
            injectionDepth = injectionDepth.coerceIn(0, 1000),
            injectionTokenBudget = injectionTokenBudget.coerceIn(64, 16000),
            manualChunkTurns = manualChunkTurns.coerceIn(0, 500),
            consolidationEveryRuns = consolidationEveryRuns.coerceIn(1, 100),
            consolidationMinCandidates = consolidationMinCandidates.coerceIn(2, 1000),
            episodicTtlDays = episodicTtlDays.coerceIn(1, 3650),
            factualTtlDays = factualTtlDays.coerceIn(1, 3650),
            relationalTtlDays = relationalTtlDays.coerceIn(1, 3650),
            preferenceTtlDays = preferenceTtlDays.coerceIn(1, 3650),
            plannedTtlDays = plannedTtlDays.coerceIn(1, 3650),
            unknownTtlDays = unknownTtlDays.coerceIn(1, 3650),
            jevTimeoutMillis = jevTimeoutMillis.coerceIn(250, 10000),
            jevCandidateCount = jevCandidateCount.coerceIn(1, 100),
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
