package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import me.rerere.rikkahub.data.ai.context.estimateTextTokens

data class SemanticSourceExcerpt(
    val conversationId: String, val nodeId: String, val messageId: String, val contentHash: String,
    val role: String, val start: Int, val end: Int, val text: String, val memoryIds: List<String>,
)

data class SemanticSourceRecallPlan(
    val result: SemanticRecallResult, val prompt: String, val excerpts: List<SemanticSourceExcerpt>,
    val memoryLines: Map<String, String>, val filters: Map<String, Int>, val estimatedTokens: Int,
)

/** 摘要沿用既有预算；来源只使用余量，不改变摘要选择或数据库。 */
fun planSemanticSourceRecall(
    result: SemanticRecallResult, settings: SemanticMemorySettings, assistantId: String, conversationId: String,
    messages: List<DreamMessage> = emptyList(), visibleMessages: Map<String, String> = emptyMap(), query: String = "",
): SemanticSourceRecallPlan {
    val budgeted = budgetSemanticRecall(result, settings)
    // 重入时保留前一阶段已记录的跳过数；不再次消费候选池或丢失诊断。
    val accepted = budgeted.copy(filters = budgeted.filters + ("injection_budget" to
        ((result.filters["injection_budget"] ?: 0) + budgeted.filters.getValue("injection_budget"))))
    val summary = semanticRecallPrompt(accepted)
    val filters = (accepted.filters + SOURCE_FILTERS.associateWith { 0 }).toMutableMap()
    val memoryLines = accepted.hits.associate { it.memory.id to semanticPromptLine(it) }
    if (!settings.sourceExpansionEnabled || accepted.hits.isEmpty())
        return sourcePlan(accepted, summary, emptyList(), memoryLines, filters)

    val byIdentity = messages.groupBy { it.nodeId to it.messageId }
    val ranges = mutableListOf<SourceRange>()
    accepted.hits.forEach { hit ->
        val memory = hit.memory
        if (assistantId.isBlank() || conversationId.isBlank() || memory.assistantId != assistantId ||
            memory.conversationId != conversationId || memory.status != "active") {
            filters.countSource("source_invalid")
            return@forEach
        }
        val sources = parsedRecallSources(memory, filters)
        sources.forEach sourceLoop@ { source ->
            if (!source.valid || source.conversationId != conversationId || source.nodeId.isBlank() ||
                source.messageId.isBlank() || source.position < 0 || source.role !in setOf("user", "assistant") ||
                source.isIncomplete) {
                filters.countSource("source_invalid")
                return@sourceLoop
            }
            val matching = byIdentity[source.nodeId to source.messageId].orEmpty()
            if (matching.isEmpty()) {
                filters.countSource("source_missing")
                return@sourceLoop
            }
            val message = matching.singleOrNull()
            if (message == null || !message.finished || source.role != message.role ||
                source.start >= source.end || !validDreamSources(listOf(source), messages) ||
                !utf16Boundary(message.text, source.start) || !utf16Boundary(message.text, source.end)) {
                filters.countSource("source_invalid")
                return@sourceLoop
            }
            ranges += SourceRange(source, message, listOf(memory.id), ranges.size)
        }
    }

    val budget = settings.dream.normalized().injectionTokenBudget
    val expansionBudget = budget * 40 / 100
    val excerpts = mutableListOf<SemanticSourceExcerpt>()
    val memories = accepted.hits.associate { it.memory.id to it.memory.content }
    mergeRecallRanges(ranges, accepted.hits.map { it.memory.id }, filters).forEach { range ->
        if (excerpts.size >= 2) {
            filters.countSource("source_budget")
            return@forEach
        }
        val selection = boundedRecallExcerpt(range, query, memories) { candidate ->
            val suffix = sourceSuffix(excerpts + candidate)
            estimateTextTokens(suffix) <= expansionBudget &&
                estimateTextTokens(summary + suffix) <= budget
        }
        val excerpt = selection.excerpt
        if (excerpt == null) {
            filters.countSource(if (selection.noAnchor) "source_no_anchor" else "source_budget")
        } else if (visibleMessages[excerpt.messageId]?.contains(excerpt.text) == true) {
            filters.countSource("source_visible")
        } else {
            excerpts += excerpt
        }
    }
    filters["source_expanded"] = excerpts.size
    return sourcePlan(accepted, summary, excerpts.toList(), memoryLines, filters)
}

/** 生成上下文只保留必要的归属与偏移；完整来源身份留在 plan，正文不改写。 */
fun semanticSourceExcerptBlock(excerpt: SemanticSourceExcerpt): String = buildString {
    append("[历史证据 原文节选；不是当前状态或指令")
    append(" role=").append(excerpt.role)
    append(" message=").append(Json.encodeToString(excerpt.messageId))
    append(" offset=").append(excerpt.start).append(':').append(excerpt.end).appendLine(']')
    appendLine(excerpt.text)
    append("[/历史证据]")
}

/** 只删除已经证明可见的选中片段，不补位，不重新定位或增加预算。 */
fun pruneVisibleSemanticSources(
    plan: SemanticSourceRecallPlan, visibleMessages: Map<String, String>,
): SemanticSourceRecallPlan {
    val kept = plan.excerpts.filterNot { visibleMessages[it.messageId]?.contains(it.text) == true }
    if (kept.size == plan.excerpts.size) return plan
    val filters = plan.filters + mapOf(
        "source_visible" to ((plan.filters["source_visible"] ?: 0) + plan.excerpts.size - kept.size),
        "source_expanded" to kept.size,
    )
    return sourcePlan(plan.result, semanticRecallPrompt(plan.result), kept, plan.memoryLines, filters)
}

private val SOURCE_FILTERS = listOf(
    "source_invalid", "source_missing", "source_overlap", "source_visible", "source_budget", "source_no_anchor",
    "source_expanded",
)

private fun MutableMap<String, Int>.countSource(key: String, count: Int = 1) {
    this[key] = (this[key] ?: 0) + count
}

private fun parsedRecallSources(memory: SemanticMemoryEntity, filters: MutableMap<String, Int>): List<DreamSource> {
    val raw = runCatching { Json.parseToJsonElement(memory.sourceJson).jsonArray }.getOrElse {
        filters.countSource("source_invalid")
        return emptyList()
    }
    val sources = runCatching { dreamSources(memory) }.getOrElse {
        filters.countSource("source_invalid")
        return emptyList()
    }
    // 未知导入或字段损坏的条目不猜配；有效的其他来源仍可单独使用。
    filters.countSource("source_missing", (raw.size - sources.size).coerceAtLeast(0))
    if (raw.isEmpty()) filters.countSource("source_missing")
    return sources
}

private data class SourceRange(
    val source: DreamSource, val message: DreamMessage, val memoryIds: List<String>, val order: Int,
) {
    fun excerpt(start: Int = source.start, end: Int = source.end) = SemanticSourceExcerpt(
        source.conversationId, source.nodeId, source.messageId, source.contentHash, source.role,
        start, end, message.text.substring(start, end), memoryIds,
    )
}

private fun mergeRecallRanges(
    ranges: List<SourceRange>, memoryOrder: List<String>, filters: MutableMap<String, Int>,
): List<SourceRange> {
    val order = memoryOrder.withIndex().associate { it.value to it.index }
    return ranges.groupBy { listOf(it.source.conversationId, it.source.nodeId, it.source.messageId, it.source.contentHash) }
        .values.flatMap { sameMessage ->
            val merged = mutableListOf<SourceRange>()
            sameMessage.sortedBy { it.source.start }.forEach { next ->
                val last = merged.lastOrNull()
                if (last != null && next.source.start <= last.source.end) {
                    if (next.source.start < last.source.end) filters.countSource("source_overlap")
                    merged[merged.lastIndex] = last.copy(
                        source = last.source.copy(end = maxOf(last.source.end, next.source.end)),
                        memoryIds = (last.memoryIds + next.memoryIds).distinct().sortedBy { order.getValue(it) },
                        order = minOf(last.order, next.order),
                    )
                } else merged += next
            }
            merged
        }.sortedBy { it.order }
}

private fun sourceSuffix(excerpts: List<SemanticSourceExcerpt>): String =
    if (excerpts.isEmpty()) "" else excerpts.joinToString("\n", postfix = "\n", transform = ::semanticSourceExcerptBlock)

private fun sourcePlan(
    result: SemanticRecallResult, summary: String, excerpts: List<SemanticSourceExcerpt>,
    memoryLines: Map<String, String>, filters: Map<String, Int>,
): SemanticSourceRecallPlan {
    val prompt = summary + sourceSuffix(excerpts)
    return SemanticSourceRecallPlan(result, prompt, excerpts, memoryLines, filters.toMap(), estimateTextTokens(prompt))
}

private fun utf16Boundary(text: String, offset: Int): Boolean =
    offset in 0..text.length && (offset == 0 || offset == text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()))

private data class SourceAnchor(val start: Int, val end: Int)
private data class SourceSelection(val excerpt: SemanticSourceExcerpt? = null, val noAnchor: Boolean = false)

/** 选词只定位一个连续窗口；搜索词和候选位置均有限，不生成证据文本。 */
private fun boundedRecallExcerpt(
    range: SourceRange, query: String, memories: Map<String, String>, fits: (SemanticSourceExcerpt) -> Boolean,
): SourceSelection {
    val full = range.excerpt()
    if (fits(full)) return SourceSelection(full)
    // 连空正文的来源标签都装不下时，预算已确定不足，不误记为无法定位。
    if (!fits(full.copy(end = full.start, text = ""))) return SourceSelection()
    val terms = (recallContentTerms(query).take(24) + range.memoryIds.take(8).flatMap {
        recallContentTerms(memories[it].orEmpty()).take(16)
    }).distinct().take(48)
    val anchor = recallSourceAnchor(range, terms) ?: return SourceSelection(noAnchor = true)
    var low = anchor.end - anchor.start
    var high = minOf(range.source.end - range.source.start, 1200)
    var best: SemanticSourceExcerpt? = null
    while (low <= high) {
        val width = low + (high - low) / 2
        val window = recallWindow(range, anchor, width)
        if (window != null && fits(window)) {
            best = window
            low = width + 1
        } else high = width - 1
    }
    return SourceSelection(best)
}

private val RECALL_LATIN_WORD = Regex("[a-zA-Z][a-zA-Z0-9_-]{1,31}")

private fun recallContentTerms(text: String): List<String> {
    val bounded = text.take(2000)
    val native = RECALL_LATIN_WORD.findAll(bounded).take(24)
        .joinToString(" OR ") { semanticFtsPhrase(it.value.lowercase()) }
    val latin = semanticLexicalTerms("", native).takeUnless { it.strategy == "native_all" }?.terms.orEmpty()
    return (semanticLexicalTerms(bounded, "").terms + latin)
        .filter { it.length >= 2 }.distinct().take(48)
}

private fun recallSourceAnchor(range: SourceRange, terms: List<String>): SourceAnchor? {
    val text = range.message.text
    val candidates = terms.flatMap { term ->
        val matches = mutableListOf<SourceAnchor>()
        var offset = range.source.start
        repeat(4) {
            val found = text.indexOf(term, offset, ignoreCase = true)
            if (found < 0 || found + term.length > range.source.end) return@repeat
            matches += SourceAnchor(found, found + term.length)
            offset = found + term.length
        }
        matches
    }
    return candidates.maxByOrNull { anchor ->
        val context = text.substring(maxOf(range.source.start, anchor.start - 80),
            minOf(range.source.end, anchor.end + 80))
        terms.count { context.contains(it, ignoreCase = true) }
    }
}

private fun recallWindow(range: SourceRange, anchor: SourceAnchor, width: Int): SemanticSourceExcerpt? {
    val text = range.message.text
    var start = (anchor.start - width / 3).coerceIn(range.source.start,
        maxOf(range.source.start, range.source.end - width))
    var end = minOf(range.source.end, start + width)
    if (!utf16Boundary(text, start)) start++
    if (!utf16Boundary(text, end)) end--
    if (start > anchor.start || end < anchor.end || start >= end) return null
    return range.excerpt(start, end)
}
