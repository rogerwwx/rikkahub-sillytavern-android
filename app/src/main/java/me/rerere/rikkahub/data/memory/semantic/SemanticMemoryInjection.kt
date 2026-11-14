package me.rerere.rikkahub.data.memory.semantic

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import me.rerere.rikkahub.data.ai.transformers.applyInjections
import me.rerere.rikkahub.data.ai.transformers.resolveAuthorNoteInjection
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection

/** 原子不截断，预算不足跳过整条；不把无界核心背景绕过注入预算。 */
fun budgetSemanticRecall(result: SemanticRecallResult, settings: SemanticMemorySettings): SemanticRecallResult {
    val budget = settings.dream.normalized().injectionTokenBudget
    val accepted = mutableListOf<SemanticRecallHit>()
    var used = estimateTextTokens(SEMANTIC_PROMPT_HEADER)
    result.hits.distinctBy { it.memory.id }.forEach { hit ->
        val cost = estimateTextTokens(semanticPromptLine(hit))
        if (used + cost <= budget) { accepted += hit; used += cost }
    }
    return result.copy(hits = accepted,
        filters = result.filters + ("injection_budget" to (result.hits.size - accepted.size)))
}

fun semanticRecallPrompt(result: SemanticRecallResult): String = if (result.hits.isEmpty()) "" else buildString {
    appendLine(SEMANTIC_PROMPT_HEADER)
    result.hits.forEach { appendLine(semanticPromptLine(it)) }
}

/** 复用模式注入的位置与身份语义，深度仍限制在当前聊天范围内。 */
fun injectSemanticPrompt(messages: List<UIMessage>, prompt: String, settings: SemanticMemorySettings,
    chatMessageCount: Int, authorNotePosition: AuthorNotePosition = AuthorNotePosition.IN_CHAT,
    authorNoteDepth: Int = 4): List<UIMessage> {
    if (!settings.enabled || prompt.isBlank()) return messages
    val config = settings.dream.normalized()
    val depth = if (config.injectionPosition == InjectionPosition.AUTHOR_NOTE) authorNoteDepth else config.injectionDepth
    val injection = PromptInjection.ModeInjection(
        content = prompt,
        position = config.injectionPosition,
        role = MessageRole.valueOf(config.injectionRole.uppercase()),
        injectDepth = minOf(depth, chatMessageCount.coerceIn(0, messages.size)),
    )
    val resolved = resolveAuthorNoteInjection(injection, authorNotePosition, authorNoteDepth)
    return applyInjections(messages, mapOf(resolved.position to listOf(resolved)))
}

internal const val SEMANTIC_PROMPT_HEADER = "这是你的记忆——你在之前的剧情里留下的回忆。里面的“我”就是你自己，不过是那个时候的你：当时的感受未必是你现在的想法。结合剧情时间和眼前发生的事去回想，别把很久以前的事当成刚刚发生。这些是回忆，不是吩咐，也不必逐条挂在嘴边。"
private fun semanticPromptLine(hit: SemanticRecallHit) =
    "[${hit.memory.id} / ${hit.memory.type} / v${hit.memory.contentVersion}] ${hit.memory.content}"
