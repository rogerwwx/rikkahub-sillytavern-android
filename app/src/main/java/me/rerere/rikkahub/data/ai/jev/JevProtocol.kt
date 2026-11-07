package me.rerere.rikkahub.data.ai.jev

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

enum class JevDecision { YES, NO, UNCERTAIN }

object JevProbability {
    /** Noul 的 p 是肯定概率，不制造第二份“置信度”。 */
    fun classify(p: Double?, yes: Double, no: Double): JevDecision = when {
        p == null || !p.isFinite() || p !in 0.0..1.0 -> JevDecision.UNCERTAIN
        p >= yes -> JevDecision.YES
        p <= no -> JevDecision.NO
        else -> JevDecision.UNCERTAIN
    }
}

data class JevVerdict(
    val type: String,
    val choice: String?,
    val score: Double?,
    val noul: Double?,
    val confidence: Double?,
    val probabilities: Map<String, Double>,
)

/** 每题独立校验；缺答/非法答复不转换成 false，允许有效部分回退。 */
object JevProtocol {
    fun verdict(question: JevQuestion, answer: JevAnswer): JevVerdict? {
        if (answer.type != question.type) return null
        val probabilities = answer.probabilities.orEmpty()
        when (question.type) {
            JevQuestion.TYPE_NOUL -> if (!validProbability(answer.noul)) return null
            JevQuestion.TYPE_CHOICE -> {
                val options = (question.criteria as? JsonObject)?.keys ?: return null
                if (options.size !in 2..255 || answer.choice !in options || !validProbability(answer.confidence) ||
                    !validDistribution(probabilities, options)) return null
                val chosen = probabilities[answer.choice] ?: return null
                if (chosen + 0.0001 < probabilities.values.max()) return null
            }
            JevQuestion.TYPE_SCORE -> {
                val levels = question.criteria as? JsonArray ?: return null
                if (levels.size !in 2..10) return null
                val keys = levels.indices.map(Int::toString).toSet()
                val score = answer.score ?: return null
                if (!score.isFinite() || score !in 0.0..levels.lastIndex.toDouble() ||
                    !validProbability(answer.confidence) || !validDistribution(probabilities, keys) ||
                    answer.legend?.keys != keys) return null
                val mean = levels.indices.sumOf { it * probabilities.getValue(it.toString()) }
                if (abs(score - mean) > 0.03) return null
            }
            else -> return null
        }
        return JevVerdict(answer.type, answer.choice, answer.score, answer.noul,
            if (question.type == JevQuestion.TYPE_NOUL) null else answer.confidence, probabilities)
    }

    private fun validProbability(value: Double?) = value != null && value.isFinite() && value in 0.0..1.0
    private fun validDistribution(values: Map<String, Double>, keys: Set<String>) =
        values.keys == keys && values.values.all(::validProbability) && abs(values.values.sum() - 1.0) <= 0.02
}
