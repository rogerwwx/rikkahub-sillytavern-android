package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.*

data class SemanticOperationCounts(val total: Int, val success: Int, val failure: Int, val empty: Int,
    val cancelled: Int, val running: Int, val interrupted: Int, val averageMillis: Double)

data class SemanticDiagnostics(
    val embedding: SemanticOperationCounts,
    val recall: SemanticOperationCounts,
    val embeddingPurposes: Map<String, SemanticOperationCounts>,
    val storageSuccess: Int,
    val storageConflict: Int,
    val storageFailure: Int,
    val recalled: Long,
    val injected: Long,
    val lexicalCandidates: Long,
    val vectorCandidates: Long,
    val deduplicatedCandidates: Long,
    val filters: Map<String, Long>,
    val degraded: Int,
    val errors: List<SemanticDiagnosticEvent>,
)

/** 只汇总真实入口持久事件；网络成功和落库冲突使用不同统计。 */
fun semanticDiagnostics(events: List<SemanticDiagnosticEvent>): SemanticDiagnostics {
    fun counts(operation: List<SemanticDiagnosticEvent>): SemanticOperationCounts {
        val finished = operation.filter { it.status !in setOf("running", "cancelled", "interrupted") }
        return SemanticOperationCounts(operation.size, operation.count { it.status == "success" },
            operation.count { it.status == "failure" }, operation.count { it.status == "empty" },
            operation.count { it.status == "cancelled" }, operation.count { it.status == "running" },
            operation.count { it.status == "interrupted" },
            if (finished.isEmpty()) 0.0 else finished.sumOf { it.durationNanos }.toDouble() / finished.size / 1_000_000)
    }
    val embedding = events.filter { it.operation == "embedding" }
    val recall = events.filter { it.operation == "recall" }
    val details = recall.mapNotNull { runCatching { Json.parseToJsonElement(it.detailJson).jsonObject }.getOrNull() }
    fun sum(key: String) = details.sumOf { it[key]?.jsonPrimitive?.longOrNull ?: 0 }
    val filters = setOf("inactive_or_expired", "invalid_vector", "below_similarity", "changed_during_recall",
        "inactive_status", "expired", "missing_vector", "old_model", "stale_content_version", "content_hash_changed",
        "dimension_mismatch", "invalid_vector_values").associateWith(::sum)
    return SemanticDiagnostics(counts(embedding), counts(recall), embedding.groupBy { it.purpose }.mapValues { counts(it.value) },
        events.count { it.operation == "storage" && it.status == "success" },
        events.count { it.operation == "storage" && it.status == "conflict" },
        events.count { it.operation == "storage" && it.status == "failure" },
        sum("recalled"), sum("injected"), sum("lexicalCandidates"), sum("vectorCandidates"), sum("deduplicatedCandidates"),
        filters, details.count { it["degraded"]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true },
        events.filter { it.error != null }.sortedByDescending { it.startedAt }.take(10))
}

fun semanticRecallScoreLines(event: SemanticDiagnosticEvent): List<String> = runCatching {
    Json.parseToJsonElement(event.detailJson).jsonObject["hits"]?.jsonArray?.map { raw ->
        val hit = raw.jsonObject
        val vectorRanked = hit["vectorRanked"]?.jsonPrimitive?.booleanOrNull
            ?: (hit["vectorScore"]?.jsonPrimitive?.contentOrNull != null)
        "${hit["id"]?.jsonPrimitive?.content} / v${hit["version"]?.jsonPrimitive?.content} / ${hit["type"]?.jsonPrimitive?.content}" +
            "：BM25 ${hit["lexicalScore"]?.jsonPrimitive?.contentOrNull ?: "无"}；余弦 ${hit["vectorScore"]?.jsonPrimitive?.contentOrNull ?: "无"}；RRF ${hit["score"]?.jsonPrimitive?.content}；向量参与 ${if (vectorRanked) "是" else "否"}"
    } ?: emptyList()
}.getOrDefault(emptyList())
