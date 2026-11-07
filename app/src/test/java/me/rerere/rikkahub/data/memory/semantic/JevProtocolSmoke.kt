package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.jev.*

object JevProtocolSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val noul = JevQuestion("noul", JsonPrimitive("是否有用"))
        check(JevProtocol.verdict(noul, JevAnswer(type = "noul", noul = 0.81)) != null)
        check(JevProbability.classify(0.81, 0.8, 0.2) == JevDecision.YES)
        check(JevProbability.classify(0.19, 0.8, 0.2) == JevDecision.NO)
        check(JevProbability.classify(0.5, 0.8, 0.2) == JevDecision.UNCERTAIN)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1).forEach {
            check(JevProtocol.verdict(noul, JevAnswer(type = "noul", noul = it)) == null)
        }
        check(JevProtocol.verdict(noul, JevAnswer(type = "choice", choice = "yes")) == null)
        check(JevProtocol.verdict(noul, JevAnswer(type = "noul")) == null)
        val choice = JevQuestion("choice", criteria = buildJsonObject { put("old", "旧事实"); put("unknown", "无法判断") })
        val choiceAnswer = JevAnswer("choice", choice = "old", confidence = 0.8,
            probabilities = mapOf("old" to 0.9, "unknown" to 0.1))
        check(JevProtocol.verdict(choice, choiceAnswer) != null)
        check(JevProtocol.verdict(choice, choiceAnswer.copy(choice = "foreign")) == null)
        check(JevProtocol.verdict(choice, choiceAnswer.copy(probabilities = mapOf("old" to 0.4))) == null)
        check(JevProtocol.verdict(choice, choiceAnswer.copy(confidence = Double.NaN)) == null)
        val score = JevQuestion("score", criteria = buildJsonArray { add("低"); add("高") })
        val scoreAnswer = JevAnswer("score", score = 0.8, confidence = 0.6,
            probabilities = mapOf("0" to 0.2, "1" to 0.8), legend = mapOf("0" to "低", "1" to "高"))
        check(JevProtocol.verdict(score, scoreAnswer) != null)
        check(JevProtocol.verdict(score, scoreAnswer.copy(score = 2.0)) == null)
        check(JevProtocol.verdict(score, scoreAnswer.copy(score = 0.2)) == null)
        check(JevProtocol.verdict(score, scoreAnswer.copy(legend = mapOf("0" to "低"))) == null)
        check(resolveJevEndpoint("https://api.typesafe.ai") == "https://api.typesafe.ai/v1/systemone")
        check(resolveJevEndpoint("https://example.invalid/v1") == "https://example.invalid/v1/systemone")
        check(resolveJevEndpoint("https://example.invalid/v1/chat/completions") == "https://example.invalid/v1/chat/completions")
        println("JevProtocolSmoke: PASS（概率、缺答、类型、有限数值、选项与等级分布）")
    }
}
