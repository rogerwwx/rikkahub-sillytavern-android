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

/** 原始 bm25 可能是 -1e-6 量级：保留 6 位有效数字，绝不用 3 位小数把它显示成 0。 */
internal fun formatRawBm25(value: Double): String =
    if (value == 0.0) "0" else "%.6g".format(java.util.Locale.ROOT, value)

/**
 * 单条命中的分数说明（界面与诊断共用）。
 *
 * 必须区分三种情况：没有进入词法榜、进入词法榜但本次归一化为 0（最弱命中天然如此）、以及路内名次；
 * 同时给出原始 bm25，避免把"归一化 0"读成"没有命中"。
 */
fun semanticRecallScoreLine(hit: SemanticRecallHit): String {
    val lexical = when {
        hit.rank.lexicalRank == null && hit.rank.lexicalScore == null -> "BM25 无（未进入词法榜）"
        else -> "BM25 归一化 " + (hit.rank.lexicalScore?.let { "%.3f".format(it) } ?: "-") +
            (hit.rank.lexicalRank?.let { "（词法第 " + it + "）" } ?: "")
    }
    val raw = hit.rank.lexicalRawScore?.let { "raw " + formatRawBm25(it) }
    val vector = (hit.rank.vectorScore?.let { "余弦 " + "%.3f".format(it) } ?: "余弦 无") +
        (hit.rank.vectorRank?.let { "（向量第 " + it + "）" } ?: "")
    // 综合分 = 融合分 + 重要度加权（2:1）；不按现实时间做新鲜度衰减。
    val note = if (hit.rank.vectorRanked) "" else " · 仅关键词入榜"
    return listOfNotNull(lexical, raw, vector, "综合 " + "%.4f".format(hit.rank.score)).joinToString(" · ") + note
}

/**
 * 召回事件的分数说明。
 *
 * BM25 是本次候选之间的相对归一化值（最弱命中就是 0），因此必须与"没有进入词法榜"分开表述，
 * 并同时给出原始 bm25 与路内名次；原始值可能极小，用足够有效位显示，不四舍五入成 0。
 */
fun semanticRecallScoreLines(event: SemanticDiagnosticEvent): List<String> = runCatching {
    Json.parseToJsonElement(event.detailJson).jsonObject["hits"]?.jsonArray?.map { raw ->
        val hit = raw.jsonObject
        fun text(key: String) = hit[key]?.jsonPrimitive?.contentOrNull
        val vectorRanked = hit["vectorRanked"]?.jsonPrimitive?.booleanOrNull ?: (text("vectorScore") != null)
        val lexicalRank = hit["lexicalRank"]?.jsonPrimitive?.intOrNull
        val lexical = when {
            text("lexicalScore") == null && lexicalRank == null -> "未进入词法榜"
            else -> "BM25 归一化 " + (text("lexicalScore") ?: "-") +
                (lexicalRank?.let { "（词法第 $it）" } ?: "")
        }
        val rawScore = text("lexicalRawScore")?.let { "raw $it" }
        val vectorRank = hit["vectorRank"]?.jsonPrimitive?.intOrNull
        val vector = (text("vectorScore") ?: "无") + (vectorRank?.let { "（向量第 $it）" } ?: "")
        "${text("id")} / v${text("version")} / ${text("type")}：" +
            listOfNotNull(lexical, rawScore, "余弦 $vector", "综合 ${text("score")}").joinToString("；") +
            "；向量参与 ${if (vectorRanked) "是" else "否"}"
    } ?: emptyList()
}.getOrDefault(emptyList())
