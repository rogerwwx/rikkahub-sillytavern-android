package me.rerere.rikkahub.data.memory.semantic

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.jev.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 草稿候选保持任务03的稳定映射；审查只筛选，不制造事实或改变替代对象。 */
data class SemanticJevCandidate(
    val id: String,
    val content: String,
    val type: String = "unknown",
    val operation: String = "add",
    val targetId: String? = null,
    val scope: String = "user",
    val evidence: List<String> = emptyList(),
    val protected: Boolean = false,
)

/** Jev 是可选辅助：材料一次发送，超过这个长度只回退基础结果，不截断也不影响主流程。 */
private const val JEV_MAX_STATE_CHARS = 200_000

class SemanticJevAssist(
    private val client: JevClient,
    private val repository: SemanticMemoryRepository,
) {
    suspend fun configurationRevision() = client.configurationRevision()

    /**
     * 召回重排：粗召回候选池按 Jev 相关性降序，取语义记忆设置的 topK 作为注入结果。
     *
     * 它不是逐条是/否过滤：只要全部候选都拿到分数就按分数排序，minRelevance 只在 >0 时
     * 作为可选门槛；任何一批缺答、非法值、超时或 HTTP 失败都整体回退到原融合顺序并
     * 截到 topK，同时把原因写进诊断。这里不修改记忆正文、状态或访问计数。
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
        val state = buildJsonObject {
            put("retrieval_context", semanticRerankContext(query, recentContext))
            put("task", "逐条候选判断与当前场景的相关性，输出肯定概率；候选之间互相独立")
        }
        val started = System.nanoTime()
        val event = try { start(assistantId, conversationId, "recall") }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("SemanticJev", "无法记录重排诊断，使用基础召回"); return result }
        val model = runCatching { client.modelVersion() }.getOrDefault("unknown")
        val (batches, skipped) = planRerankBatches(state, items)
        var failure: String? = if (batches.isEmpty()) "候选或检索上下文超出 64KB 请求体预算"
            else if (skipped > 0) "$skipped 条候选超出 64KB 请求体预算，未评分" else null
        var batchesSent = 0
        try {
            val completed = if (batches.isEmpty()) true else withTimeoutOrNull(config.jevTimeoutMillis.toLong()) {
                for (batch in batches) {
                    if (failure != null) break
                    val questions = batch.associate { "r${it.position}" to it.question }
                    val response = try { client.judge(state, questions, 10000, 1) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { failure = safeError(error); emptyMap() }
                    batchesSent++
                    if (response.size != questions.size) failure = failure ?: "批量缺答或非法答案"
                    batch.forEach { item ->
                        val probability = response["r${item.position}"]?.noul
                        if (probability != null && probability.isFinite() && probability in 0.0..1.0) item.probability = probability
                    }
                }
                true
            }
            if (completed == null) failure = failure ?: "请求预算耗尽"
            if (failure == null && items.any { it.probability == null }) failure = "部分候选未评分"
            // 全部候选都有分数才排序；否则保留融合顺序，绝不把部分分数混进最终排名。
            val ordered = if (failure == null)
                items.sortedWith(compareByDescending<RerankItem> { it.probability ?: 0.0 }.thenBy { it.position })
            else items.sortedBy { it.position }
            val kept = if (failure == null && config.jevMinRelevance > 0.0)
                ordered.filter { (it.probability ?: 0.0) >= config.jevMinRelevance } else ordered
            // 与基础召回同口径：核心只在"已通过门槛"的候选内拿有限优先名额，
            // 不能绕过最低相关性，也不会把整体排序彻底推翻。
            val core = kept.filter { it.hit.memory.isCore }.take(normalized.coreBudget)
            val hits = (core + kept).distinctBy { it.position }.take(normalized.topK).map { it.hit }
            finishRerank(event, model, items, ordered, hits, failure, config, normalized, batchesSent, started)
            return result.copy(hits = hits,
                filters = result.filters + ("jev_rerank_pool" to pool.size) + ("jev_below_relevance" to (ordered.size - kept.size)),
                durationNanos = result.durationNanos + System.nanoTime() - started,
                degraded = result.degraded + listOfNotNull(failure?.let { "Jev 重排回退：$it" }))
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { runCatching { repository.dao.putEvent(event.copy(status = "cancelled",
                durationNanos = System.nanoTime() - started, error = "重排判断已取消")) } }
            throw cancel
        }
    }

    /**
     * 做梦草稿审查：保留/证据/关系三问，和召回重排是两条独立路径。
     * 这里只筛选草稿候选，不制造事实、不改变替代对象，也不参与注入排序。
     */
    suspend fun reviewCandidates(
        assistantId: String,
        conversationId: String,
        newEvidence: String,
        oldContext: String,
        candidates: List<SemanticJevCandidate>,
        settings: SemanticMemorySettings,
    ): Set<String> {
        val config = settings.dream.normalized()
        if (!config.jevEnabled || !config.jevDreamReview || candidates.isEmpty()) return candidates.map { it.id }.toSet()
        val state = buildJsonObject {
            put("new_original_evidence", newEvidence)
            put("old_memories_and_continuity_not_new_evidence", oldContext)
            put("candidates", encodeCandidates(candidates))
        }
        val decisions = linkedMapOf<String, JevDecision>()
        val answers = linkedMapOf<String, JevVerdict>()
        val started = System.nanoTime()
        val event = try { start(assistantId, conversationId, "dream_review") }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("SemanticJev", "无法记录辅助诊断，使用基础草稿"); return candidates.map { it.id }.toSet() }
        if (state.toString().length > JEV_MAX_STATE_CHARS) {
            val keep = candidates.map { it.id }.toSet()
            finish(event, candidates, keep, decisions, answers, started, "判断材料过长，使用基础草稿")
            return keep
        }
        var failure: String? = null
        try {
            val completed = withTimeoutOrNull(config.jevTimeoutMillis.toLong()) {
                candidates.chunked(JevClient.JEV_MAX_QUESTIONS / 3).forEach { batch ->
                    val questions = batch.flatMapIndexed { index, candidate ->
                        val description = "候选 ${candidate.id}，适用范围 ${candidate.scope}：${candidate.content}"
                        listOf("v$index" to noul("$description\n是否值得长期保留？普通一餐和寒暄通常不值得；明确记住请求、持久偏好、重要共同经历、承诺、状态更新可保留。"),
                            "e$index" to noul("$description\n新增原始证据是否直接支持此陈述及其范围？旧记忆和衔接材料不是新增证据；一次吃面不能推断长期偏好；用户事实不能来自助手推测。"),
                            "r$index" to JevQuestion("choice", JsonPrimitive("$description\n与state中旧事实是什么关系？仅比较内容和范围，不读取其他问题的答案。"),
                                buildJsonObject { put("confirm", "再次确认同一事实"); put("update", "新证据改变旧状态")
                                    put("new", "新事实或没有匹配旧事实"); put("unknown", "证据不足，无法判断") }))
                    }.toMap()
                    val response = try { client.judge(state, questions, 10000, 1) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { failure = safeError(error); emptyMap() }
                    if (response.size != questions.size && failure == null) failure = "批量缺答或非法答案，逐候选回退"
                    batch.forEachIndexed { index, candidate ->
                        val value = response["v$index"]
                        val evidence = response["e$index"]
                        val worth = JevProbability.classify(value?.noul, config.jevRetentionYes, config.jevRetentionNo)
                        val supported = JevProbability.classify(evidence?.noul, config.jevEvidenceYes, config.jevEvidenceNo)
                        decisions[candidate.id] = when {
                            value == null || evidence == null -> JevDecision.UNCERTAIN
                            worth == JevDecision.NO || supported == JevDecision.NO -> JevDecision.NO
                            worth == JevDecision.YES && supported == JevDecision.YES -> JevDecision.YES
                            else -> JevDecision.UNCERTAIN
                        }
                        response["r$index"]?.let { answers[candidate.id] = it }
                        value?.let { answers["${candidate.id}:value"] = it }
                        evidence?.let { answers["${candidate.id}:evidence"] = it }
                    }
                }
                true
            }
            if (completed == null) failure = "请求预算耗尽"
            val keep = candidates.filter { it.protected || decisions[it.id] != JevDecision.NO }.map { it.id }.toSet()
            finish(event, candidates, keep, decisions, answers, started, failure)
            return keep
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { runCatching { repository.dao.putEvent(event.copy(status = "cancelled",
                durationNanos = System.nanoTime() - started, error = "辅助审查已取消")) } }
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

    private suspend fun finish(event: SemanticDiagnosticEvent, candidates: List<SemanticJevCandidate>, keep: Set<String>,
        decisions: Map<String, JevDecision>, answers: Map<String, JevVerdict>, started: Long, failure: String?) {
        val details = buildJsonObject {
            put("before", JsonArray(candidates.map { JsonPrimitive(it.id) }))
            put("after", JsonArray(keep.map(::JsonPrimitive)))
            put("retained", keep.size); put("excluded", candidates.size - keep.size)
            put("uncertain", candidates.count { decisions[it.id] == null || decisions[it.id] == JevDecision.UNCERTAIN })
            put("answers", buildJsonObject { answers.forEach { (id, verdict) -> put(id, buildJsonObject {
                verdict.noul?.let { put("p", it) }; verdict.choice?.let { put("choice", it) }
                put("probabilities", buildJsonObject { verdict.probabilities.forEach { (key, p) -> put(key, p) } })
            }) } })
            put("content_hashes", buildJsonObject { candidates.forEach { put(it.id, semanticContentHash(it.content + it.scope)) } })
        }
        try {
            repository.dao.putEvent(event.copy(status = if (failure != null) "failure" else "success",
                durationNanos = System.nanoTime() - started, detailJson = details.toString(), error = failure))
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("SemanticJev", "无法保存辅助诊断") }
    }

    /** 重排诊断：候选 id/分数/保留、批次数、耗时、分数分布与回退原因；不含正文。 */
    private suspend fun finishRerank(event: SemanticDiagnosticEvent, model: String, items: List<RerankItem>,
        ordered: List<RerankItem>, hits: List<SemanticRecallHit>, failure: String?,
        config: SemanticDreamSettings, settings: SemanticMemorySettings, batches: Int, started: Long) {
        val keptIds = hits.map { it.memory.id }.toSet()
        val finalRank = ordered.withIndex().associate { (index, item) -> item.hit.memory.id to index + 1 }
        val scores = items.mapNotNull { it.probability }
        val details = buildJsonObject {
            put("mode", "rerank")
            put("model", model)
            put("candidates", items.size)
            put("batches", batches)
            put("scored", scores.size)
            put("unscored", items.size - scores.size)
            put("topK", settings.topK)
            put("minRelevance", config.jevMinRelevance)
            put("elapsedMillis", (System.nanoTime() - started) / 1_000_000.0)
            put("scoreDistribution", scoreDistribution(scores))
            put("items", JsonArray(items.sortedBy { it.position }.map { item -> buildJsonObject {
                put("id", item.hit.memory.id)
                // 必须显式包成 JsonPrimitive：JsonObjectBuilder 的 map put 会返回旧值，elvis 会误判为 null。
                put("score", item.probability?.let { JsonPrimitive(it) } ?: JsonNull)
                put("kept", item.hit.memory.id in keptIds)
                put("fusionRank", item.position + 1)
                put("finalRank", finalRank[item.hit.memory.id]?.let { JsonPrimitive(it) } ?: JsonNull)
                put("contentHash", semanticContentHash(item.hit.memory.content))
            } }))
            failure?.let { put("fallback", it) }
        }
        try {
            repository.dao.putEvent(event.copy(status = if (failure != null) "failure" else "success",
                durationNanos = System.nanoTime() - started, detailJson = details.toString(), error = failure))
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("SemanticJev", "无法保存重排诊断") }
    }
}

private fun noul(instructions: String) = JevQuestion("noul", JsonPrimitive(instructions))
private fun encodeCandidates(candidates: List<SemanticJevCandidate>) = JsonArray(candidates.map { candidate -> buildJsonObject {
    put("id", candidate.id); put("content", candidate.content); put("scope", candidate.scope)
    put("type", candidate.type); put("operation", candidate.operation)
    candidate.targetId?.let { put("targetId", it) }
    put("evidence_ids", JsonArray(candidate.evidence.map(::JsonPrimitive)))
} })
private fun memoryScope(memory: SemanticMemoryEntity): String = try {
    Json.parseToJsonElement(memory.metadataJson).jsonObject["scope"]?.jsonPrimitive?.content ?: "user"
} catch (_: Exception) { "user" }
private fun safeError(error: Exception) = if (error is JevHttpException) "HTTP ${error.code}"
    else "辅助请求或协议失败（${error.javaClass.simpleName}）"

/** 一条待重排候选：融合顺序位置、序列化后的 item 文本与独立问题，分数回填在这里。 */
private class RerankItem(val hit: SemanticRecallHit, val position: Int) {
    val text: String = semanticRerankItemText(hit.memory)
    val question: JevQuestion = JevPrompts.memoryRerank(text)
    var probability: Double? = null
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
 * 剧情检索上下文：当前场景/参与人物/当前意图/需要找回的信息＋检索原则。
 * 紧凑但有上限；最近上下文只作理解用，不当作新的用户消息。
 */
internal fun semanticRerankContext(query: String, recentContext: String): String {
    val speakers = recentContext.lineSequence()
        .map { line -> line.substringBefore(':', "").trim() }
        .mapNotNull { RERANK_SPEAKERS[it] }
        .distinct().take(4).toList()
    return buildString {
        appendLine("检索上下文（召回重排用，不是本轮用户消息）")
        appendLine("当前场景：${query.trim().take(JEV_RERANK_QUERY_CHARS)}")
        appendLine("参与人物：${speakers.ifEmpty { listOf("按最近上下文推断") }.joinToString("、")}")
        appendLine("当前意图：在回复前找回能与本轮剧情衔接、并可能改变本轮回应的长期记忆")
        appendLine("需要找回的信息：与本场景直接相关的共同经历、既有事实、关系、偏好与计划")
        appendLine("检索原则：仅有相同关键词或相同话题不构成相关；只判断相关性，不判断真实性")
        append("最近上下文：\n").append(recentContext.trim().take(JEV_RERANK_RECENT_CHARS))
    }
}

/**
 * 候选文本模板：正文原样保留，其余字段来自 content/type/importance/createdAt/metadataJson。
 * 不扩写、不为凑关键词改写正文；正文只作为被判断的数据。
 */
internal fun semanticRerankItemText(memory: SemanticMemoryEntity): String {
    val metadata = runCatching { Json.parseToJsonElement(memory.metadataJson) as? JsonObject }.getOrNull()
    val scope = (metadata?.get("scope") as? JsonPrimitive)?.contentOrNull ?: "user"
    val sources = runCatching { Json.parseToJsonElement(memory.sourceJson).jsonArray.size }.getOrDefault(0)
    val relations = buildList {
        (metadata?.get("supersededBy") as? JsonPrimitive)?.contentOrNull?.let { add("替代为 $it") }
        (metadata?.get("consolidatedFrom") as? JsonArray)?.let { add("归纳自 ${it.size} 条") }
        if (sources > 0) add("来源 $sources 条")
    }
    return buildString {
        appendLine("[时间] ${RERANK_TIME.format(Instant.ofEpochMilli(memory.createdAt).atZone(ZoneId.systemDefault()))}")
        appendLine("[人物] ${RERANK_SCOPES[scope] ?: scope}")
        appendLine("[类型] ${memory.type}；重要度 ${(memory.importance * 100).roundToInt() / 100.0}")
        appendLine("[内容] ${memory.content}")
        appendLine("[关联] ${relations.ifEmpty { listOf("无") }.joinToString("；")}")
        append("[状态] ${memory.status}${if (memory.isCore) "；核心" else ""}；访问 ${memory.accessCount} 次")
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
        put("buckets", JsonArray((0 until 5).map { index -> JsonPrimitive(sorted.count { ((it * 5).toInt()).coerceIn(0, 4) == index }) }))
    }
}

private val RERANK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val JEV_RERANK_QUERY_CHARS = 600
private const val JEV_RERANK_RECENT_CHARS = 1300
private val RERANK_SPEAKERS = mapOf("user" to "用户", "assistant" to "助手", "system" to "系统")
private val RERANK_SCOPES = mapOf("user" to "用户（通用事实）", "role" to "角色（角色专属）")