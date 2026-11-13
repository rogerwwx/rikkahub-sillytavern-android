package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.Json

object SemanticDreamSettingsSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val defaults = SemanticDreamSettings()
        check(!defaults.automatic && !defaults.jevEnabled && !defaults.jevRecall && !defaults.jevDreamReview)
        val config = defaults.copy(minCompletedTurns = -1, idleMinutes = 0, manualChunkTurns = -5,
            injectionRole = "invalid", injectionDepth = -1, temperature = Double.NaN,
            jevRecallYes = 0.1, jevRecallNo = 0.9, reasoningLevel = "invalid").normalized()
        check(config.minCompletedTurns == 1 && config.idleMinutes == 1)
        check(config.manualChunkTurns == 0 && config.injectionRole == "system" && config.injectionDepth == 0)
        check(config.temperature.isFinite() && config.jevRecallNo < config.jevRecallYes)
        check(config.reasoningLevel == "off" && defaults.copy(reasoningLevel = "high").normalized().reasoningLevel == "high")
        check(Json.decodeFromString<SemanticDreamSettings>(Json.encodeToString(config)) == config)
        check(defaults.ttlDays("episodic") == 7 && defaults.ttlDays("planned") == 2)
        check(defaults.ttlDays("factual") == 180 && defaults.ttlDays("preference") == 60)
        val legacy = Json.decodeFromString<SemanticDreamSettings>("""{"automatic":true}""")
        check(legacy.reflectionPrompt.isEmpty() && legacy.consolidationPrompt.isEmpty())
        check(legacy.reflectionSystemPrompt().contains(DEFAULT_SEMANTIC_REFLECTION_PROMPT))
        check(legacy.consolidationSystemPrompt().contains(DEFAULT_SEMANTIC_CONSOLIDATION_PROMPT))
        val custom = defaults.copy(reflectionPrompt = "只记录旅行计划。\n保留目的地。", consolidationPrompt = "按目的地归纳行程。")
        val restored = Json.decodeFromString<SemanticDreamSettings>(Json.encodeToString(custom)).normalized()
        check(restored == custom)
        check(restored.reflectionSystemPrompt().startsWith(custom.reflectionPrompt))
        check(restored.reflectionSystemPrompt().contains("\"changes\"") && restored.reflectionSystemPrompt().contains("\"evidence\""))
        check(restored.consolidationSystemPrompt().startsWith(custom.consolidationPrompt))
        check(restored.consolidationSystemPrompt().contains("\"groups\"") && restored.consolidationSystemPrompt().contains("\"ids\""))
        check(defaults.copy(reflectionPrompt = " \n", consolidationPrompt = "\t").reflectionSystemPrompt() == defaults.reflectionSystemPrompt())
        check(defaults.copy(consolidationPrompt = "\t").consolidationSystemPrompt() == defaults.consolidationSystemPrompt())
        println("SemanticDreamSettingsSmoke: PASS")
    }
}