package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.memory.semantic.*

internal data class SemanticNarrativeGroup(
    val kind: String, val label: String, val entries: List<SemanticStateEntry>,
)

internal fun semanticNarrativeGroups(entries: List<SemanticStateEntry>): List<SemanticNarrativeGroup> =
    listOf("character" to "人物", "relationship" to "关系", "item" to "物品",
        "promise" to "约定", "scene" to "场景").map { (kind, label) ->
        SemanticNarrativeGroup(kind, label, entries.filter { it.kind == kind }
            .sortedWith(compareBy<SemanticStateEntry> { it.subject }.thenBy { it.facet }))
    }

internal fun semanticNarrativeStatusLabel(status: String): String = when (status) {
    "active", "open" -> "在进行"
    "done" -> "已结案 · 已完成"
    "cancelled" -> "已结案 · 已取消"
    "failed" -> "已结案 · 失败"
    "cleared" -> "已清除"
    else -> "未知状态"
}

internal fun semanticNarrativeCoverageLine(view: SemanticStateView): String =
    "已覆盖 ${view.checkpoint.coveredCount} 条原文 · 缺口 ${view.pendingMessages} 条 · ${view.checkpoint.entries.size} 格状态"

/** 诊断页没有最终请求可见正文；只允许完整覆盖当前分支的状态作为规划输入。 */
internal fun semanticNarrativePreviewSafe(
    view: SemanticStateView?, enabled: Boolean, assistantId: String, conversationId: String,
): Boolean {
    if (!enabled || view == null) return false
    val checkpoint = view.checkpoint
    if (checkpoint.assistantId != assistantId || checkpoint.conversationId != conversationId ||
        checkpoint.coveredCount <= 0 || checkpoint.coveredCount != view.messages.size ||
        view.pendingMessages != 0 || view.messages.any { !it.finished }) return false
    return checkpoint.prefixHash == semanticStatePrefixHash(view.messages, checkpoint.coveredCount) &&
        checkpoint.entries.all { entry ->
            entry.sources.isNotEmpty() && entry.sources.all { semanticNarrativeSourceCurrent(view, it) }
        }
}

internal fun semanticNarrativeSourceCurrent(view: SemanticStateView, source: DreamSource): Boolean {
    val message = view.messages.getOrNull(source.position) ?: return false
    return source.conversationId == view.checkpoint.conversationId && source.valid && !source.isIncomplete &&
        source.nodeId == message.nodeId && source.messageId == message.messageId &&
        source.role == message.role && message.finished && source.start < source.end &&
        validDreamSources(listOf(source), view.messages)
}

internal fun semanticNarrativeSourcePreview(view: SemanticStateView, entry: SemanticStateEntry): String = buildString {
    appendLine("${entry.subject}／${entry.facet} · ${semanticNarrativeStatusLabel(entry.status)} · 版本 ${entry.version}")
    appendLine(entry.value)
    if (entry.reason.isNotBlank()) appendLine("理由：${entry.reason}")
    if (entry.sources.isEmpty()) appendLine("没有可定位的来源。")
    entry.sources.forEach { source ->
        appendLine()
        appendLine("第 ${source.position + 1} 条原文 · ${if (source.role == "user") "用户" else "助手"}")
        appendLine("消息 ${source.messageId} · 偏移 ${source.start}—${source.end}")
        if (semanticNarrativeSourceCurrent(view, source)) {
            appendLine(view.messages[source.position].text.substring(source.start, source.end))
        } else {
            appendLine("来源已变化，不能把当前正文当作这格状态的证据。")
        }
    }
}.trim()

internal fun semanticPlannedCandidateCount(baselineIds: List<String>, plannedIds: List<String>): Int =
    (plannedIds.toSet() - baselineIds.toSet()).size

internal fun semanticQueryPlanningStatusLabel(status: String): String = when (status) {
    "disabled" -> "已关闭"
    "success", "planned", "expanded" -> "已生成辅助查询"
    "empty", "no_queries", "no_expansion" -> "没有有效扩展，沿用普通召回"
    "timeout" -> "辅助调用超时，沿用普通召回"
    "failed", "failure", "invalid", "error" -> "辅助调用失败，沿用普通召回"
    "no_model", "unavailable", "skipped" -> "辅助调用不可用，沿用普通召回"
    else -> "未扩展，沿用普通召回"
}
