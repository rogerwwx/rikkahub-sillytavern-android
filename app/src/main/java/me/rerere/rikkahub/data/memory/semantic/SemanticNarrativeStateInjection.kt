package me.rerere.rikkahub.data.memory.semantic

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.context.estimateTextTokens

/** 状态独立于来源计划，来源剪枝重建摘要时不会丢失状态。 */
internal data class SemanticNarrativeRequestPlan(
    val state: SemanticStateInjection?,
    val source: SemanticSourceRecallPlan,
) {
    val prompt: String get() = state?.prompt.orEmpty() + source.prompt
    val estimatedTokens: Int get() = estimateTextTokens(prompt)
}

/** 实际扣除状态后继续原来源规划；小于64的余量也按真实预算验收。 */
internal fun planSemanticNarrativeRequest(
    state: SemanticStateInjection?,
    recalled: SemanticRecallResult,
    settings: SemanticMemorySettings,
    assistantId: String,
    conversationId: String,
    messages: List<DreamMessage> = emptyList(),
    query: String = "",
): SemanticNarrativeRequestPlan {
    val total = settings.dream.normalized().injectionTokenBudget
    val acceptedState = state?.takeIf {
        settings.enabled && settings.narrativeStateEnabled && it.prompt.isNotBlank() &&
            estimateTextTokens(it.prompt) <= total * 40 / 100
    }?.let { it.copy(estimatedTokens = estimateTextTokens(it.prompt)) }
    val remaining = total - (acceptedState?.estimatedTokens ?: 0)
    val config = if (acceptedState == null) settings else settings.copy(
        dream = settings.dream.copy(injectionTokenBudget = remaining.coerceAtLeast(64)),
    )
    var candidates = recalled
    var source = planSemanticSourceRecall(candidates, config, assistantId, conversationId,
        messages = messages, query = query)
    if (acceptedState == null) return SemanticNarrativeRequestPlan(null, source)
    // 旧planner会把38..63重新归一成64；这里逐条预算回退，不把其下限当作真实余量。
    while (estimateTextTokens(acceptedState?.prompt.orEmpty() + source.prompt) > total) {
        if (source.excerpts.isNotEmpty()) {
            source = planSemanticSourceRecall(candidates, config.copy(sourceExpansionEnabled = false),
                assistantId, conversationId)
        } else {
            candidates = candidates.copy(hits = source.result.hits.dropLast(1))
            source = planSemanticSourceRecall(candidates, config, assistantId, conversationId,
                messages = messages, query = query)
        }
    }
    source = source.copy(filters = source.filters + ("injection_budget" to
        ((recalled.filters["injection_budget"] ?: 0) + recalled.hits.size - source.result.hits.size)))
    return SemanticNarrativeRequestPlan(acceptedState, source)
}

/** 每条尾部真实原消息需完整可见；重复身份、隐藏正文和注入自身都不能充当证据。 */
internal fun semanticStateTailVisible(
    view: SemanticStateView,
    request: List<UIMessage>,
    injectedPrompts: List<String> = emptyList(),
    escapeXml: Boolean = false,
): Boolean {
    val covered = view.checkpoint.coveredCount
    if (covered !in 1..view.messages.size ||
        view.messages.map { it.messageId }.distinct().size != view.messages.size) return false
    val visible = request.filterNot { it.isSynthetic || it.isContextCheckpoint || it.isContextHidden }
        .groupBy { it.id.toString() }
    fun escaped(text: String) = if (escapeXml) text.replace("<", "&lt;").replace(">", "&gt;") else text
    var lastIndex = -1
    return view.messages.drop(covered).all { original ->
        val final = visible[original.messageId]?.singleOrNull() ?: return@all false
        val position = request.indexOf(final)
        if (position <= lastIndex || final.role.name.lowercase() != original.role) return@all false
        lastIndex = position
        val text = injectedPrompts.filter { it.isNotEmpty() }.fold(final.toText()) { result, prompt ->
            result.replace(escaped(prompt), "")
        }
        original.text.isNotBlank() && text.contains(escaped(original.text))
    }
}

/** 规划器只用真正收到的current/recent完整原文覆盖缺口，摘要和状态本身不作证明。 */
internal fun semanticStateQueryTailVisible(view: SemanticStateView, current: String, recent: String): Boolean {
    val covered = view.checkpoint.coveredCount
    if (covered !in 1..view.messages.size ||
        view.messages.map { it.messageId }.distinct().size != view.messages.size) return false
    val inputs = semanticQueryPlanningInputs(current, recent, "", "")
    val segments = listOf(inputs.current, inputs.recent).flatMap { it.split("\n…（中间省略）…\n") }
    return view.messages.drop(covered).all { original ->
        original.text.isNotBlank() && segments.any { it.contains(original.text) }
    }
}

/** 最后先做所有挂起检查，再同步复查来源；来源改变不能躲进无节选的状态请求。 */
internal suspend fun inspectSemanticNarrativeAttempt(
    frozenSourceRevision: Long,
    baselineCurrent: suspend () -> Boolean,
    dependenciesCurrent: suspend () -> Boolean,
    currentSourceRevision: () -> Long,
): Boolean {
    val baseline = baselineCurrent()
    val dependencies = dependenciesCurrent()
    val source = currentSourceRevision() == frozenSourceRevision
    return baseline && dependencies && source
}

internal data class SemanticPreparedRecall(val result: SemanticRecallResult?, val revision: String)

/** 结果只绑定开始时的版本；等待召回期间变化就撤回，不给旧结果重盖新版本。 */
internal suspend fun prepareSemanticBaselineRecall(
    revision: suspend () -> String,
    recall: suspend () -> SemanticRecallResult?,
): SemanticPreparedRecall {
    val before = revision()
    val result = recall()
    val after = revision()
    return SemanticPreparedRecall(result.takeIf { before == after }, before)
}

internal data class SemanticNarrativeFinalRequest(
    val plan: SemanticNarrativeRequestPlan,
    val messages: List<UIMessage>,
    val requiresRebuild: Boolean,
)

/** 状态被改写或缺失时要求调用方从requestBase重新转换，不能只替换旧字符串。 */
internal fun finalizeSemanticNarrativeRequest(
    plan: SemanticNarrativeRequestPlan,
    view: SemanticStateView?,
    messages: List<UIMessage>,
    historyIds: Set<String>,
): SemanticNarrativeFinalRequest {
    val state = plan.state
    if (state != null && (view == null ||
        !semanticStatePromptConfirmed(state.prompt, messages.flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
            .map { it.text }) ||
        !semanticStateTailVisible(view, messages, listOf(state.prompt, plan.source.prompt)))) {
        return SemanticNarrativeFinalRequest(plan, messages, requiresRebuild = true)
    }
    val stripped = if (state == null) messages else replaceNarrativeText(messages, state.prompt, "")
    val finalSource = finalizeSemanticSourceRecall(plan.source, stripped, historyIds).plan
    val finalMessages = if (finalSource.prompt == plan.source.prompt) messages
        else replaceNarrativeText(messages, plan.source.prompt, finalSource.prompt)
    return SemanticNarrativeFinalRequest(plan.copy(source = finalSource), finalMessages, requiresRebuild = false)
}

/** 计数只证明实际发送文本完整存在，不证明Provider接受或模型遵守。 */
internal fun auditSemanticNarrativeState(
    plan: SemanticNarrativeRequestPlan,
    requestTexts: List<String>,
    escapeXml: Boolean = false,
): Map<String, Int> {
    val state = plan.state
    val prompt = state?.prompt.orEmpty().let {
        if (escapeXml) it.replace("<", "&lt;").replace(">", "&gt;") else it
    }
    val confirmed = state != null && semanticStatePromptConfirmed(prompt, requestTexts)
    return mapOf(
        "state_planned" to if (state == null) 0 else 1,
        "state_confirmed" to if (confirmed) 1 else 0,
        "state_entries_planned" to (state?.entryIds?.distinct()?.size ?: 0),
        "state_entries_confirmed" to if (confirmed) state?.entryIds?.distinct()?.size ?: 0 else 0,
        "state_estimated_tokens" to (state?.let { estimateTextTokens(it.prompt) } ?: 0),
        "state_covered_count" to (state?.coveredCount ?: 0),
        "state_budget_skipped" to (state?.skipped ?: 0),
    )
}

private fun semanticStatePromptConfirmed(prompt: String, texts: List<String>): Boolean {
    if (prompt.isBlank()) return false
    return texts.sumOf { text ->
        var count = 0
        var offset = 0
        while (true) {
            val next = text.indexOf(prompt, offset)
            if (next < 0) break
            count++
            offset = next + prompt.length
        }
        count
    } == 1
}

private fun replaceNarrativeText(messages: List<UIMessage>, before: String, after: String): List<UIMessage> =
    if (before.isEmpty()) messages else messages.map { message ->
        message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Text) part.copy(text = part.text.replace(before, after)) else part
        })
    }
