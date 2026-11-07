package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.Json

object SemanticDreamSettingsSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val defaults = SemanticDreamSettings()
        check(!defaults.automatic && !defaults.jevEnabled && !defaults.jevRecall && !defaults.jevDreamReview)
        val config = defaults.copy(minCompletedTurns = -1, idleMinutes = 0, maxInputTokens = -10,
            injectionRole = "invalid", injectionDepth = -1, temperature = Double.NaN,
            jevRecallYes = 0.1, jevRecallNo = 0.9, retryLimit = 100, reasoningLevel = "invalid").normalized()
        check(config.minCompletedTurns == 1 && config.idleMinutes == 1)
        check(config.maxInputTokens >= 1024 && config.injectionRole == "system" && config.injectionDepth == 0)
        check(config.temperature.isFinite() && config.jevRecallNo < config.jevRecallYes && config.retryLimit <= 5)
        check(config.reasoningLevel == "off" && defaults.copy(reasoningLevel = "high").normalized().reasoningLevel == "high")
        check(Json.decodeFromString<SemanticDreamSettings>(Json.encodeToString(config)) == config)
        check(defaults.ttlDays("episodic") == 7 && defaults.ttlDays("planned") == 2)
        check(defaults.ttlDays("factual") == 180 && defaults.ttlDays("preference") == 60)
        println("SemanticDreamSettingsSmoke: PASS")
    }
}
