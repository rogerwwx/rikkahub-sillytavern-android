package me.rerere.rikkahub.data.memory.semantic

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal data class SemanticSourceRequestWindow(
    val messages: List<DreamMessage>,
    val filters: Map<String, Int> = emptyMap(),
)

/** 冻结输入的真实末楼是唯一上界；临时提示和压缩摘要不提供原文身份。 */
internal fun semanticSourceRequestBoundary(messages: List<UIMessage>): String? =
    messages.lastOrNull {
        !it.isSynthetic && !it.isContextCheckpoint &&
            it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT)
    }?.id?.toString()

internal fun semanticSourceRequestWindow(
    messages: List<DreamMessage>, boundaryId: String?, boundaryRole: String?, boundaryText: String?,
): SemanticSourceRequestWindow {
    val index = messages.indexOfLast { it.messageId == boundaryId }
    if (index < 0) return SemanticSourceRequestWindow(emptyList(), mapOf("source_request_boundary_missing" to 1))
    val boundary = messages[index]
    return if (boundary.role != boundaryRole || boundary.text != boundaryText)
        SemanticSourceRequestWindow(emptyList(), mapOf("source_request_boundary_changed" to 1))
    else SemanticSourceRequestWindow(messages.take(index + 1))
}

/** 在冻结修订上读源；前后复查使挂起读取和网络等待后的新分支都不能混入。 */
internal suspend fun readSemanticSourceRequestWindow(
    frozenRevision: Long,
    boundaryId: String?,
    boundaryRole: String?,
    boundaryText: String?,
    currentSourceRevision: () -> Long,
    sourceMessages: suspend () -> List<DreamMessage>,
): SemanticSourceRequestWindow {
    fun stale() = SemanticSourceRequestWindow(emptyList(), mapOf("source_request_stale" to 1))
    if (currentSourceRevision() != frozenRevision) return stale()
    val messages = sourceMessages()
    if (currentSourceRevision() != frozenRevision) return stale()
    return semanticSourceRequestWindow(messages, boundaryId, boundaryRole, boundaryText)
}

internal data class SemanticSourceFinalRequest(val plan: SemanticSourceRecallPlan, val messages: List<UIMessage>)

/** 只替换转换后仍完整存在的注入；先扣掉注入自身，避免其原文造成虚假的历史可见性。 */
internal fun finalizeSemanticSourceRecall(
    plan: SemanticSourceRecallPlan,
    messages: List<UIMessage>,
    historyIds: Set<String>,
): SemanticSourceFinalRequest {
    if (plan.prompt.isEmpty() || messages.none { message ->
        message.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains(plan.prompt) }
    }) return SemanticSourceFinalRequest(plan, messages)
    val visible = messages.filter {
        it.id.toString() in historyIds && !it.isSynthetic && !it.isContextCheckpoint
    }.groupBy { it.id.toString() }.mapValues { (_, sameId) ->
        // 同一 ID 被转换器复制时不把不相邻片段拼成一段不存在的原文。
        sameId.singleOrNull()?.parts?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text.replace(plan.prompt, "") }.orEmpty()
    }
    val pruned = pruneVisibleSemanticSources(plan, visible)
    if (pruned.prompt == plan.prompt) return SemanticSourceFinalRequest(pruned, messages)
    return SemanticSourceFinalRequest(pruned, messages.map { message ->
        message.copy(parts = message.parts.map { part ->
            if (part is UIMessagePart.Text) part.copy(text = part.text.replace(plan.prompt, pruned.prompt)) else part
        })
    })
}

internal data class SemanticSourceRequestValidity(val summaryCurrent: Boolean, val sourceCurrent: Boolean)

/** 先检查记忆版本，后读实时配置，最后同步核对来源修订；来源失效与摘要失效分别回退。 */
internal suspend fun inspectSemanticSourceRequest(
    settings: SemanticMemorySettings,
    expectedRecallRevision: String,
    sourceRevision: Long?,
    recallRevision: suspend () -> String,
    currentSettings: suspend () -> SemanticMemorySettings?,
    currentSourceRevision: () -> Long,
): SemanticSourceRequestValidity {
    val memoryCurrent = recallRevision() == expectedRecallRevision
    val configCurrent = currentSettings() == settings
    val sourceCurrent = sourceRevision == null || currentSourceRevision() == sourceRevision
    return SemanticSourceRequestValidity(memoryCurrent && configCurrent, sourceCurrent)
}

/** 来源修订变化时仅撤下原文，摘要沿用相同候选与预算。 */
internal fun semanticSourceSummaryFallback(
    plan: SemanticSourceRecallPlan,
    selected: SemanticRecallResult,
    settings: SemanticMemorySettings,
    assistantId: String,
    conversationId: String,
): SemanticSourceRecallPlan = planSemanticSourceRecall(selected,
    settings.copy(sourceExpansionEnabled = false), assistantId, conversationId).let { summary ->
    summary.copy(filters = plan.filters + mapOf(
        "injection_budget" to (summary.filters["injection_budget"] ?: 0),
        "source_expanded" to 0, "source_request_stale" to 1))
}

internal data class SemanticInjectionAudit(
    val selectedIds: List<String>,
    val budgetAcceptedIds: List<String>,
    val confirmedInjectedIds: List<String>,
    val sourceConfirmed: Int,
    val injectionTransformedOrMissing: Int,
)

/** 这里只证明最终请求中逐字存在，不证明服务端接受或模型遵守。 */
internal fun auditSemanticSourceRecall(
    selectedIds: List<String>,
    plan: SemanticSourceRecallPlan,
    requestTexts: List<String>,
    escapeXml: Boolean = false,
): SemanticInjectionAudit {
    fun confirmed(text: String): Boolean {
        if (text.isEmpty()) return false
        val expected = if (escapeXml) text.replace("<", "&lt;").replace(">", "&gt;") else text
        return requestTexts.any { it.contains(expected) }
    }
    val accepted = plan.result.hits.map { it.memory.id }.distinct()
    val ids = accepted.filter { id -> plan.memoryLines[id]?.let(::confirmed) == true }
    val sources = plan.excerpts.count { confirmed(semanticSourceExcerptBlock(it)) }
    return SemanticInjectionAudit(selectedIds.distinct(), accepted, ids, sources,
        accepted.size - ids.size + plan.excerpts.size - sources)
}

/** 保留粗召回/重排明细，只追加可核验身份及计数，绝不持久化查询与证据正文。 */
internal fun semanticInjectionDetails(
    details: JsonObject,
    plan: SemanticSourceRecallPlan,
    audit: SemanticInjectionAudit,
): JsonObject = JsonObject(details + buildJsonObject {
    put("selectedIds", JsonArray(audit.selectedIds.map(::JsonPrimitive)))
    put("budgetAcceptedIds", JsonArray(audit.budgetAcceptedIds.map(::JsonPrimitive)))
    put("confirmedInjectedIds", JsonArray(audit.confirmedInjectedIds.map(::JsonPrimitive)))
    put("injected", audit.confirmedInjectedIds.size)
    put("injectionEstimatedTokens", plan.estimatedTokens)
    put("sourceExpanded", plan.excerpts.size)
    put("sourceConfirmed", audit.sourceConfirmed)
    put("injectionTransformedOrMissing", audit.injectionTransformedOrMissing)
    plan.filters.forEach { (key, count) -> put(key, count) }
    put("transformed_or_missing", audit.injectionTransformedOrMissing)
    put("sourceExcerpts", JsonArray(plan.excerpts.map { excerpt -> buildJsonObject {
        put("conversationId", excerpt.conversationId)
        put("nodeId", excerpt.nodeId)
        put("messageId", excerpt.messageId)
        put("contentHash", excerpt.contentHash)
        put("start", excerpt.start)
        put("end", excerpt.end)
        put("memoryIds", JsonArray(excerpt.memoryIds.map(::JsonPrimitive)))
    } }))
})

internal suspend fun recordSemanticInjectionSafely(record: suspend () -> Unit) {
    try { record() }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) {
        // 诊断失败不阻断正文生成；异常可能含正文，因此不记录异常文本。
    }
}
