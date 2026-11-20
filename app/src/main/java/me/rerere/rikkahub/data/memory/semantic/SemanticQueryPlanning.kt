package me.rerere.rikkahub.data.memory.semantic

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

data class SemanticQueryPlan(val queries: List<String> = emptyList(), val status: String)

/** 锚点只在实际送出的有界快照中校验，历史摘要只作为查找线索。 */
internal data class SemanticQueryPlanningInputs(
    val current: String, val recent: String, val history: String, val state: String,
) {
    fun sources(): Map<String, String> = linkedMapOf(
        "current" to current, "recent" to recent, "history" to history, "state" to state,
    )
}

internal fun semanticQueryPlanningInputs(current: String, recent: String, history: String, state: String) =
    SemanticQueryPlanningInputs(
        semanticContextExcerpt(current, 6000), semanticContextExcerpt(recent, 2400),
        semanticContextExcerpt(history, 4000), semanticContextExcerpt(state, 4000),
    )

/** 严格 JSON；任一条非法时整次放弃，不使用部分不完整协议。空数组是合法空规划。 */
internal fun parseSemanticQueryPlan(text: String, inputs: SemanticQueryPlanningInputs): SemanticQueryPlan {
    fun string(value: JsonElement?): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("查询字段不是字符串")
    return try {
        require(text.length <= 16000)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("查询规划不是对象")
        require(root.keys == setOf("queries"))
        val queries = root["queries"] as? JsonArray ?: error("查询列表不是数组")
        require(queries.size <= 2)
        val sources = inputs.sources()
        val result = queries.map { element ->
            val query = element as? JsonObject ?: error("查询不是对象")
            require(query.keys == setOf("text", "anchors"))
            val value = string(query["text"]).trim()
            require(value.isNotBlank() && value.length <= 240 && value != inputs.current.trim())
            val anchors = query["anchors"] as? JsonArray ?: error("锚点不是数组")
            require(anchors.size in 1..8)
            anchors.forEach { element ->
                val anchor = element as? JsonObject ?: error("锚点不是对象")
                require(anchor.keys == setOf("source", "quote"))
                val source = sources[string(anchor["source"])] ?: error("未知锚点来源")
                val quote = string(anchor["quote"])
                require(quote.count { it.isLetterOrDigit() } >= 2 &&
                    !quote.contains("…（中间省略）…") &&
                    quote.trim() !in setOf("这个", "那个", "这里", "那里", "然后", "继续", "好的", "嗯嗯"))
                // 分片检查也排除引用省略标记的一部分、或跨越人为拼接边界的锚点。
                require(source.split("\n…（中间省略）…\n").any { it.contains(quote) })
            }
            value
        }
        require(result.distinct().size == result.size)
        SemanticQueryPlan(result, if (result.isEmpty()) "empty" else "success")
    } catch (_: Exception) {
        SemanticQueryPlan(status = "invalid")
    }
}

internal data class SemanticPlannedCandidates(
    val hits: List<SemanticRankHit>,
    val baselineCandidateIds: List<String>,
    val plannedCandidateIds: List<String>,
)

/** 原融合序每两条插一条扩展独有候选，扩展间轮转；不保证旧 TopK 全部保留。 */
internal fun mergeSemanticPlannedCandidates(
    baseline: List<SemanticRankHit>, planned: List<List<SemanticRankHit>>,
): SemanticPlannedCandidates {
    val baselineIds = baseline.map { it.id }.toSet()
    val seen = baselineIds.toMutableSet()
    val routes = planned.map { route -> route.filter { it.id !in baselineIds }.iterator() }
    val additions = mutableListOf<SemanticRankHit>()
    do {
        var progressed = false
        routes.forEach { route ->
            while (route.hasNext()) {
                val next = route.next()
                progressed = true
                if (seen.add(next.id)) {
                    additions += next
                    break
                }
            }
        }
    } while (progressed)
    if (additions.isEmpty()) return SemanticPlannedCandidates(baseline, baseline.map { it.id }, emptyList())
    val merged = mutableListOf<SemanticRankHit>()
    var original = 0
    var extra = 0
    while (original < baseline.size || extra < additions.size) {
        repeat(2) { if (original < baseline.size) merged += baseline[original++] }
        if (extra < additions.size) merged += additions[extra++]
    }
    // 原分数跨查询不可比；MMR 仍负责去重，但相关性使用与融合序一致的有界排名分。
    return SemanticPlannedCandidates(
        merged.mapIndexed { index, hit -> hit.copy(score = 1.0 - index.toDouble() / merged.size) },
        baseline.map { it.id }, additions.map { it.id },
    )
}

internal data class SemanticPlannedBatch<T>(val value: T, val plannedFailed: Boolean)

/** 扩展 batch 失败恢复原 batch；外部取消不能触发第二次请求。 */
internal suspend fun <T> requestSemanticPlannedBatch(
    baseline: List<String>, planned: List<String>, request: suspend (List<String>) -> T,
): SemanticPlannedBatch<T> {
    if (planned.isEmpty()) return SemanticPlannedBatch(request(baseline), false)
    return try {
        SemanticPlannedBatch(request(baseline + planned), false)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        SemanticPlannedBatch(request(baseline), true)
    }
}
