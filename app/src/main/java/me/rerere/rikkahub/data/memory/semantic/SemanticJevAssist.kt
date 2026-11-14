package me.rerere.rikkahub.data.memory.semantic

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.jev.*
import java.util.Locale

class SemanticJevAssist(
    private val client: JevClient,
    private val repository: SemanticMemoryRepository,
) {
    suspend fun configurationRevision() = client.configurationRevision()

    /**
     * 召回重排：粗召回候选池按 Jev 相关性降序，取语义记忆设置的 topK 作为注入结果。
     *
     * 全部候选拿到分数后排序，沿用有限核心优先名额和 topK。任何一批缺答、非法值、
     * 超时或 HTTP 失败都返回原召回结果，同时记录原因；不判断草稿是否值得写入。
     */
    suspend fun rerankRecall(
        assistantId: String,
        conversationId: String,
        query: String,
        recentContext: String,
        settings: SemanticMemorySettings,
        result: SemanticRecallResult,
    ): SemanticRecallResult {
        val config = settings.dream.normalized()
        if (!config.jevEnabled || !config.jevRecall) return result
        val normalized = settings.normalized()
        // 粗召回按融合顺序取前 N 条；核心记忆即使落在 N 之后也补进候选池，
        // 否则"相关核心优先名额"会因为重排而静默失效（上限 N + coreBudget）。
        val ranked = result.candidates.ifEmpty { result.hits }
        val head = ranked.take(config.jevCandidateCount)
        val coreOutside = result.hits.filter { it.memory.isCore && head.none { h -> h.memory.id == it.memory.id } }
            .take(normalized.coreBudget)
        val pool = (head + coreOutside).distinctBy { it.memory.id }
        if (pool.isEmpty()) return result
        val items = pool.mapIndexed { index, hit -> RerankItem(hit, index) }
        val state = JevPrompts.memoryRerankState(semanticRerankContext(query, recentContext))
        val started = System.nanoTime()
        val event = try { start(assistantId, conversationId, "recall_rerank") }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("SemanticJev", "无法记录重排诊断，使用基础召回"); return result }
        val model = runCatching { client.modelVersion() }.getOrDefault("unknown")
        val (batches, skipped) = planRerankBatches(state, items)
        var failure: String? = if (batches.isEmpty()) "候选或检索上下文超出 64KB 请求体预算"
            else if (skipped > 0) "$skipped 条候选超出 64KB 请求体预算，未评分" else null
        val batchDetails = mutableListOf<RerankBatchDiagnostic>()
        try {
            val completed = if (batches.isEmpty()) true else withTimeoutOrNull(config.jevTimeoutMillis.toLong()) {
                for (batch in batches) {
                    if (failure != null) break
                    val questions = batch.associate { "r${it.position}" to it.question }
                    val diagnostic = RerankBatchDiagnostic(batch.size, batch.sumOf { it.hit.memory.content.length })
                    batchDetails += diagnostic
                    val batchStarted = System.nanoTime()
                    val response = try {
                        client.judge(state, questions, 10000, 1) { diagnostic.request = it }
                    } catch (cancel: CancellationException) {
                        diagnostic.status = "cancelled"
                        throw cancel
                    } catch (error: Exception) {
                        failure = safeError(error)
                        emptyMap()
                    } finally {
                        diagnostic.elapsedMillis = (System.nanoTime() - batchStarted) / 1_000_000.0
                    }
                    if (response.size != questions.size) failure = failure ?: "批量缺答或非法答案"
                    diagnostic.status = if (failure == null) "success" else "failure"
                    batch.forEach { item ->
                        val score = response["r${item.position}"]?.score
                        val maxScore = (item.question.criteria as JsonArray).lastIndex.toDouble()
                        if (score != null && score.isFinite() && score in 0.0..maxScore) item.score = score
                    }
                }
                true
            }
            if (completed == null) {
                failure = failure ?: "请求预算耗尽"
                batchDetails.lastOrNull()?.status = "timeout"
            }
            if (failure == null && items.any { it.score == null }) failure = "部分候选未评分"
            // 全部候选都有分数才排序；否则保留融合顺序，绝不把部分分数混进最终排名。
            val ordered = if (failure == null)
                items.sortedWith(compareByDescending<RerankItem> { it.score ?: 0.0 }.thenBy { it.position })
            else items.sortedBy { it.position }
            val core = ordered.filter { it.hit.memory.isCore }.take(normalized.coreBudget)
            val hits = if (failure == null)
                (core + ordered).distinctBy { it.position }.take(normalized.topK).map { it.hit }
            else result.hits
            finishRerank(event, model, items, ordered, hits, failure, normalized, batchDetails, started)
            return result.copy(hits = hits,
                filters = result.filters + ("jev_rerank_pool" to pool.size),
                durationNanos = result.durationNanos + System.nanoTime() - started,
                degraded = result.degraded + listOfNotNull(failure?.let { "Jev 重排回退：$it" }))
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                finishRerank(event, model, items, items, result.hits, "重排判断已取消", normalized,
                    batchDetails, started, cancelled = true)
            }
            throw cancel
        }
    }

    private suspend fun start(assistantId: String, conversationId: String, purpose: String): SemanticDiagnosticEvent {
        repository.recoverInterruptedDiagnostics()
        return SemanticDiagnosticEvent(assistantId = assistantId, conversationId = conversationId,
            operation = "jev", purpose = purpose).also {
            repository.dao.putEvent(it)
        }
    }

    /** 重排诊断：候选 id/分数/保留、批次数、耗时、分数分布与回退原因；不含正文。 */
    private suspend fun finishRerank(event: SemanticDiagnosticEvent, model: String, items: List<RerankItem>,
        ordered: List<RerankItem>, hits: List<SemanticRecallHit>, failure: String?,
        settings: SemanticMemorySettings, batches: List<RerankBatchDiagnostic>, started: Long,
        cancelled: Boolean = false) {
        val keptIds = hits.map { it.memory.id }.toSet()
        val finalRank = ordered.withIndex().associate { (index, item) -> item.hit.memory.id to index + 1 }
        val scores = items.mapNotNull { it.score }
        val details = buildJsonObject {
            put("mode", "rerank")
            put("model", model)
            put("candidates", items.size)
            put("batches", batches.size)
            put("batchDetails", JsonArray(batches.map { it.toJson() }))
            put("scored", scores.size)
            put("unscored", items.size - scores.size)
            put("topK", settings.topK)
            put("selected", hits.size)
            put("scoreMax", (items.first().question.criteria as JsonArray).lastIndex)
            put("elapsedMillis", (System.nanoTime() - started) / 1_000_000.0)
            put("scoreDistribution", scoreDistribution(scores))
            put("items", JsonArray(items.sortedBy { it.position }.map { item -> buildJsonObject {
                put("id", item.hit.memory.id)
                // 必须显式包成 JsonPrimitive：JsonObjectBuilder 的 map put 会返回旧值，elvis 会误判为 null。
                put("score", item.score?.let { JsonPrimitive(it) } ?: JsonNull)
                put("kept", item.hit.memory.id in keptIds)
                put("fusionRank", item.position + 1)
                put("finalRank", finalRank[item.hit.memory.id]?.let { JsonPrimitive(it) } ?: JsonNull)
                put("contentHash", semanticContentHash(item.hit.memory.content))
            } }))
            failure?.let { put("fallback", it) }
        }
        try {
            repository.dao.putEvent(event.copy(status = if (cancelled) "cancelled" else if (failure != null) "failure" else "success",
                durationNanos = System.nanoTime() - started, detailJson = details.toString(), error = failure))
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("SemanticJev", "无法保存重排诊断") }
    }
}

/** 只保存长度、计数、模型和用量，不保存候选、场景、响应正文或连接信息。 */
private class RerankBatchDiagnostic(val questions: Int, val contentChars: Int) {
    var request: JevCallDiagnostics? = null
    var status: String = "running"
    var elapsedMillis: Double = 0.0

    fun toJson(): JsonObject = buildJsonObject {
        put("questions", questions)
        put("contentChars", contentChars)
        put("status", status)
        put("elapsedMillis", elapsedMillis)
        request?.let {
            put("requestedModel", it.requestedModel)
            it.responseModel?.let { model -> put("responseModel", model) }
            put("requestBytes", it.requestBytes)
            put("estimatedTextTokens", it.estimatedTextTokens)
            put("attempts", it.attempts)
            it.usage?.let { usage -> put("usage", buildJsonObject {
                usage.inputTokens?.let { count -> put("input_tokens", count) }
                usage.outputTokens?.let { count -> put("output_tokens", count) }
            }) }
        }
    }
}

private fun safeError(error: Exception) = if (error is JevHttpException) "HTTP ${error.code}"
    else "辅助请求或协议失败（${error.javaClass.simpleName}）"

/** 一条待重排候选：融合顺序位置、序列化后的 item 文本与独立问题，分数回填在这里。 */
private class RerankItem(val hit: SemanticRecallHit, val position: Int) {
    val text: String = semanticRerankItemText(hit.memory)
    val question: JevQuestion = JevPrompts.memoryRerank(text)
    var score: Double? = null
}

/** 按问题数上限与 64KB 请求体同时分批；单条超预算的候选不评分并计数。 */
private fun planRerankBatches(state: JsonElement, items: List<RerankItem>): Pair<List<List<RerankItem>>, Int> {
    val limit = JevClient.JEV_MAX_QUESTIONS.coerceAtMost(32)
    fun measure(candidate: List<RerankItem>) =
        estimateRequestBodyBytes(state, candidate.associate { "r${it.position}" to it.question })
    val batches = mutableListOf<List<RerankItem>>()
    var current = mutableListOf<RerankItem>()
    var skipped = 0
    items.forEach { item ->
        if (measure(listOf(item)) > JevClient.JEV_MAX_REQUEST_BYTES) { skipped++; return@forEach }
        if (current.isNotEmpty() && (current.size >= limit || measure(current + item) > JevClient.JEV_MAX_REQUEST_BYTES)) {
            batches += current
            current = mutableListOf()
        }
        current += item
    }
    if (current.isNotEmpty()) batches += current
    return batches to skipped
}

/**
 * 剧情检索上下文；有上限时保留最新部分，资料不当作新的用户消息。
 */
internal fun semanticRerankContext(query: String, recentContext: String): String {
    val trimmed = recentContext.trim()
    var offset = (trimmed.length - JEV_RERANK_RECENT_CHARS).coerceAtLeast(0)
    // 不从 UTF-16 代理对中间开始；截取后的首行可能缺角色标签，角色从完整输入读取。
    if (offset > 0 && trimmed[offset].isLowSurrogate() && trimmed[offset - 1].isHighSurrogate()) offset++
    val recent = trimmed.substring(offset)
    val speakers = trimmed.lineSequence()
        .map { line -> line.substringBefore(':', "").trim() }
        .mapNotNull { RERANK_SPEAKERS[it.lowercase(Locale.ROOT)] }
        .distinct().take(4).toList()
    return buildString {
        appendLine("检索上下文（召回重排用，不是本轮用户消息）")
        appendLine("当前场景：${query.trim().take(JEV_RERANK_QUERY_CHARS)}")
        appendLine("参与人物：${speakers.ifEmpty { listOf("按最近上下文推断") }.joinToString("、")}")
        append("最近上下文：\n").append(recent)
    }
}

/**
 * 只提供人物归属和完整正文；存储时间、重要度和访问量不代表本轮剧情相关性。
 */
internal fun semanticRerankItemText(memory: SemanticMemoryEntity): String {
    val metadata = runCatching { Json.parseToJsonElement(memory.metadataJson) as? JsonObject }.getOrNull()
    val scope = (metadata?.get("scope") as? JsonPrimitive)?.contentOrNull ?: "user"
    return buildString {
        appendLine("[人物] ${RERANK_SCOPES[scope] ?: scope}")
        append("[内容] ").append(memory.content)
    }
}

/** 分数分布只保留聚合值，避免诊断里出现整段正文。 */
private fun scoreDistribution(scores: List<Double>): JsonObject {
    if (scores.isEmpty()) return buildJsonObject { put("count", 0) }
    val sorted = scores.sorted()
    return buildJsonObject {
        put("count", sorted.size)
        put("min", sorted.first()); put("p50", sorted[(sorted.size - 1) / 2]); put("max", sorted.last())
        put("mean", sorted.average())
        put("buckets", JsonArray((0 until 5).map { index -> JsonPrimitive(sorted.count { it.toInt().coerceIn(0, 4) == index }) }))
    }
}

private const val JEV_RERANK_QUERY_CHARS = 600
private const val JEV_RERANK_RECENT_CHARS = 1300
private val RERANK_SPEAKERS = mapOf("user" to "用户", "assistant" to "助手", "system" to "系统")
private val RERANK_SCOPES = mapOf("user" to "用户（通用事实）", "role" to "角色（角色专属）")
