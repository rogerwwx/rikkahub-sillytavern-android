package me.rerere.rikkahub.data.memory.semantic

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

object SemanticMemoryInjectionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val config = SemanticMemorySettings(enabled = true, dream = SemanticDreamSettings(injectionTokenBudget = 128))
        val large = SemanticMemoryEntity(id = "large", assistantId = "assistant", content = "过长正文".repeat(200), isCore = true)
        val small = SemanticMemoryEntity(id = "small", assistantId = "assistant", content = "用户喜欢茶")
        val result = SemanticRecallResult(listOf(large, small).map { SemanticRecallHit(it, SemanticRankHit(it.id, null, null, 1.0)) },
            2, 0, emptyMap(), emptyList(), 0, "fixture")
        val bounded = budgetSemanticRecall(result, config)
        check(bounded.hits.map { it.memory.id } == listOf("small"))
        check(bounded.filters["injection_budget"] == 1 && !semanticRecallPrompt(bounded).contains(large.content))
        val messages = listOf(UIMessage.system("系统"), UIMessage.user("问题"), UIMessage.assistant("答案"))
        for ((role, expected) in listOf("system" to MessageRole.SYSTEM, "user" to MessageRole.USER, "assistant" to MessageRole.ASSISTANT)) {
            val injected = injectSemanticPrompt(messages, "长期记忆", config.copy(dream = config.dream.copy(injectionRole = role, injectionDepth = 1)), 2)
            check(injected[2].toText() == "长期记忆" && injected[2].role == expected)
        }
        val deep = injectSemanticPrompt(messages, "记忆", config.copy(dream = config.dream.copy(injectionDepth = 1000)), 2)
        check(deep[0] == messages[0] && deep[1].toText() == "记忆")
        check(injectSemanticPrompt(messages, "记忆", config.copy(enabled = false), 2) == messages)
        println("SemanticMemoryInjectionSmoke: PASS（原子预算、真实注入角色与深度）")
    }
}
