package me.rerere.rikkahub.data.memory.semantic

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.jev.*

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

class SemanticJevAssist(
    private val client: JevClient,
    private val repository: SemanticMemoryRepository,
) {
    suspend fun configurationRevision() = client.configurationRevision()

    suspend fun filterRecall(
        assistantId: String,
        query: String,
        recentContext: String,
        settings: SemanticMemorySettings,
        result: SemanticRecallResult,
    ): SemanticRecallResult {
        val config = settings.dream.normalized()
        if (!config.jevEnabled || !config.jevRecall || result.hits.isEmpty()) return result
        val protected = result.hits.filter { it.memory.isCore }.take(settings.coreBudget).map { it.memory.id }.toSet()
        val candidates = result.hits.map { hit -> SemanticJevCandidate(hit.memory.id, hit.memory.content,
            hit.memory.type, scope = memoryScope(hit.memory), protected = hit.memory.id in protected) }
        val state = buildJsonObject {
            put("current_input", query)
            put("recent_context_for_understanding_only", recentContext)
            put("candidates", encodeCandidates(candidates))
        }
        val decisions = linkedMapOf<String, JevDecision>()
        val answers = linkedMapOf<String, JevVerdict>()
        val started = System.nanoTime()
        val event = try { start(assistantId, "recall") }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("SemanticJev", "无法记录辅助诊断，使用基础召回"); return result }
        if (state.toString().length > config.maxInputTokens) {
            finish(event, candidates, candidates.map { it.id }.toSet(), decisions, answers, started, "判断材料超出单次输入预算，使用基础召回")
            return result
        }
        var failure: String? = null
        try {
            val completed = withTimeoutOrNull(config.jevTimeoutMillis.toLong()) {
                candidates.chunked(JevClient.JEV_MAX_QUESTIONS).forEach { batch ->
                    val questions = batch.mapIndexed { index, candidate ->
                        "q$index" to JevPrompts.memoryRelevance("候选 ${candidate.id}，范围 ${candidate.scope}：${candidate.content}")
                    }.toMap()
                    val response = try { client.judge(state, questions, 10000, 1) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { failure = safeError(error); emptyMap() }
                    if (response.size != questions.size && failure == null) failure = "批量缺答或非法答案，逐候选回退"
                    batch.forEachIndexed { index, candidate ->
                        response["q$index"]?.let { answers[candidate.id] = it }
                        decisions[candidate.id] = JevProbability.classify(response["q$index"]?.noul,
                            config.jevRecallYes, config.jevRecallNo)
                    }
                }
                true
            }
            if (completed == null) failure = "请求预算耗尽"
            val hits = result.hits.filter { it.memory.id in protected || decisions[it.memory.id] != JevDecision.NO }
            finish(event, candidates, hits.map { it.memory.id }.toSet(), decisions, answers, started, failure)
            return result.copy(hits = hits,
                filters = result.filters + ("jev_rejected" to (result.hits.size - hits.size)),
                durationNanos = result.durationNanos + System.nanoTime() - started,
                degraded = result.degraded + listOfNotNull(failure?.let { "Jev 回退：$it" }))
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { runCatching { repository.dao.putEvent(event.copy(status = "cancelled",
                durationNanos = System.nanoTime() - started, error = "辅助判断已取消")) } }
            throw cancel
        }
    }

    suspend fun reviewCandidates(
        assistantId: String,
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
        val event = try { start(assistantId, "dream_review") }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("SemanticJev", "无法记录辅助诊断，使用基础草稿"); return candidates.map { it.id }.toSet() }
        if (state.toString().length > config.maxInputTokens) {
            val keep = candidates.map { it.id }.toSet()
            finish(event, candidates, keep, decisions, answers, started, "判断材料超出单次输入预算，使用基础草稿")
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

    private suspend fun start(assistantId: String, purpose: String): SemanticDiagnosticEvent {
        repository.recoverInterruptedDiagnostics()
        return SemanticDiagnosticEvent(assistantId = assistantId, operation = "jev", purpose = purpose).also {
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
