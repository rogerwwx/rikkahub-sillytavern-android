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
    fun sum(key: String) = details.sumOf { (it[key] as? JsonPrimitive)?.longOrNull ?: 0 }
    val filters = setOf("inactive_or_expired", "invalid_vector", "below_similarity", "changed_during_recall",
        "inactive_status", "expired", "missing_vector", "old_model", "stale_content_version", "content_hash_changed",
        "dimension_mismatch", "invalid_vector_values", "injection_budget", "source_invalid", "source_missing",
        "source_overlap", "source_visible", "source_budget", "source_expanded", "source_no_anchor",
        "source_request_stale", "source_request_boundary_missing", "source_request_boundary_changed",
        "semantic_request_stale", "state_planned", "state_confirmed", "state_entries_planned",
        "state_entries_confirmed", "state_request_gap", "state_request_stale", "state_budget_skipped").associateWith(::sum)
    return SemanticDiagnostics(counts(embedding), counts(recall), embedding.groupBy { it.purpose }.mapValues { counts(it.value) },
        events.count { it.operation == "storage" && it.status == "success" },
        events.count { it.operation == "storage" && it.status == "conflict" },
        events.count { it.operation == "storage" && it.status == "failure" },
        sum("recalled"), sum("injected"), sum("lexicalCandidates"), sum("vectorCandidates"), sum("deduplicatedCandidates"),
        filters, details.count { it["degraded"]?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true },
        events.filter { it.error != null }.sortedByDescending { it.startedAt }.take(10))
}

/**
 * 展示该召回事件最近一次组装的请求；工具续轮、重试共用 eventId，审计覆盖更新而非累计网络注入次数。
 * 逐字确认只说明最终文本存在；近义文本经过转换后未匹配，不能断定未发送。缺字段显示未知。
 */
fun semanticInjectionSummary(event: SemanticDiagnosticEvent): List<String> {
    val detail = runCatching { Json.parseToJsonElement(event.detailJson) as? JsonObject }.getOrNull()
    fun idCount(key: String): Int? {
        val ids = detail?.get(key) as? JsonArray ?: return null
        return ids.size.takeIf {
            ids.all { id -> id is JsonPrimitive && id.isString && id.content.isNotBlank() }
        }
    }
    fun count(key: String): Long? =
        (detail?.get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    fun amount(value: Number?, unit: String) = value?.let { "$it $unit" } ?: "未知"
    fun sourceSkipped(key: String) = count(key)?.toString() ?: "未知"
    val requestCounts = listOf("source_request_stale", "source_request_boundary_missing",
        "source_request_boundary_changed", "semantic_request_stale").map(::count)
    return listOf(
        "记忆：选中 ${amount(idCount("selectedIds"), "条")} → " +
            "预算接受 ${amount(idCount("budgetAcceptedIds"), "条")} → " +
            "逐字确认存在 ${amount(idCount("confirmedInjectedIds"), "条")}",
        "来源：展开 ${amount(count("sourceExpanded"), "片")} → " +
            "逐字确认存在 ${amount(count("sourceConfirmed"), "片")}",
        "预算跳过：摘要 ${amount(count("injection_budget"), "条")} · " +
            "来源 ${amount(count("source_budget"), "片")}",
        "转换或缺失：${amount(count("injectionTransformedOrMissing"), "项")}",
        "注入估算：${amount(count("injectionEstimatedTokens"), "token")}",
        "来源跳过：无效 ${sourceSkipped("source_invalid")} · 缺失 ${sourceSkipped("source_missing")} · " +
            "重叠 ${sourceSkipped("source_overlap")} · 已可见 ${sourceSkipped("source_visible")} · " +
            "未定位 ${sourceSkipped("source_no_anchor")}",
    ) + listOfNotNull(
        if (requestCounts.any { it != null }) {
            "请求校验：来源过时 ${sourceSkipped("source_request_stale")} · " +
                "记忆/设置过时 ${sourceSkipped("semantic_request_stale")} · " +
                "无有效边界 ${sourceSkipped("source_request_boundary_missing")} · " +
                "边界已变 ${sourceSkipped("source_request_boundary_changed")}"
        } else null,
        if ((count("source_request_stale") ?: 0) > 0) "来源过时：已回退摘要。" else null,
        if ((count("semantic_request_stale") ?: 0) > 0) "记忆/设置过时：已撤下全部记忆块。" else null,
        if (count("state_entries_planned") != null || count("state_entries_confirmed") != null) {
            "状态：预算接受 ${amount(count("state_entries_planned"), "格")} → " +
                "逐字确认存在 ${amount(count("state_entries_confirmed"), "格")}"
        } else null,
        if (count("state_covered_count") != null) {
            "状态覆盖前 ${count("state_covered_count")} 条原文 · " +
                "状态估算 ${amount(count("state_estimated_tokens"), "token")}"
        } else null,
        if ((count("state_request_gap") ?: 0) > 0) "状态覆盖或正文可见性不足：本次已撤下状态块。" else null,
        if ((count("state_request_stale") ?: 0) > 0) "状态或规划依据已变化：本次已回退普通召回。" else null,
        if (count("plannedQueryCount") != null) {
            "查询规划：扩展查询 ${amount(count("plannedQueryCount"), "条")} · " +
                "新增候选 ${amount(idCount("plannedCandidateIds"), "条")}"
        } else null,
    )
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
    hit.rank.assistantEvidence?.let { auxiliary ->
        return "当前：" + listOfNotNull(lexical, raw, vector,
            "综合 " + (hit.rank.mainQueryScore?.let { "%.4f".format(it) } ?: "无")).joinToString(" · ") +
            "\n辅助：BM25 " + (auxiliary.lexicalScore?.let { "%.3f".format(it) } ?: "无") +
            (auxiliary.lexicalRank?.let { "（词法第 $it）" } ?: "") +
            (auxiliary.lexicalRawScore?.let { " · raw " + formatRawBm25(it) } ?: "") +
            " · 余弦 " + (auxiliary.vectorScore?.let { "%.3f".format(it) } ?: "无") +
            (auxiliary.vectorRank?.let { "（向量第 $it）" } ?: "") +
            " · 综合 " + "%.4f".format(auxiliary.score) +
            "\n晚融合 " + "%.4f".format(hit.rank.score)
    }
    val note = if (hit.rank.vectorRanked) "" else " · 仅关键词入榜"
    if (hit.rank.mainQueryScore != null) {
        return listOfNotNull(lexical, raw, vector, "当前综合 " + "%.4f".format(hit.rank.mainQueryScore),
            "晚融合 " + "%.4f".format(hit.rank.score)).joinToString(" · ") + note
    }
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
        val auxiliary = hit["assistantEvidence"] as? JsonObject
        val contextScores = if (auxiliary != null) {
            "；当前综合 ${text("mainQueryScore") ?: "无"}；辅助综合 " +
                (auxiliary["score"]?.jsonPrimitive?.contentOrNull ?: "无") +
                "；辅助词法 " + (auxiliary["lexicalScore"]?.jsonPrimitive?.contentOrNull ?: "无") +
                "；辅助余弦 " + (auxiliary["vectorScore"]?.jsonPrimitive?.contentOrNull ?: "无")
        } else ""
        "${text("id")} / v${text("version")} / ${text("type")}：" +
            listOfNotNull(lexical, rawScore, "余弦 $vector", "综合 ${text("score")}").joinToString("；") +
            "；当前向量参与 ${if (vectorRanked) "是" else "否"}" + contextScores
    } ?: emptyList()
}.getOrDefault(emptyList())
