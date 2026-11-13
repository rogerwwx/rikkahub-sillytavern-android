package me.rerere.rikkahub.data.memory.semantic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

fun semanticContentHash(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

object SemanticVectors {
    fun validate(vectors: List<List<Float>>, count: Int, dimensions: Int? = null): Int {
        require(vectors.size == count && count > 0) { "向量响应数量不匹配" }
        val dimension = dimensions ?: vectors.first().size
        require(dimension in 1..65536) { "向量维度无效" }
        vectors.forEach { vector ->
            require(vector.size == dimension) { "向量维度不一致" }
            require(vector.all { it.isFinite() }) { "向量含 NaN/Inf" }
            require(vector.any { it != 0f }) { "零向量无效" }
        }
        return dimension
    }

    fun encode(vector: List<Float>): ByteArray = ByteBuffer.allocate(vector.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { vector.forEach { putFloat(it) } }.array()

    fun decode(bytes: ByteArray?, dimensions: Int?): List<Float>? {
        if (bytes == null || dimensions == null || dimensions !in 1..65536 || bytes.size != dimensions * 4) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vector = List(dimensions) { buffer.float }
        return vector.takeIf { runCatching { validate(listOf(it), 1, dimensions) }.isSuccess }
    }

    fun cosine(a: List<Float>, b: List<Float>): Double {
        require(a.size == b.size)
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        a.indices.forEach { i ->
            dot += a[i].toDouble() * b[i]
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) 0.0 else (dot / sqrt(aa * bb)).coerceIn(-1.0, 1.0)
    }
}

/** 融合前的单条候选；score 为当前阶段的分数（融合分数或加权后的最终分数）。 */
data class SemanticRankHit(
    val id: String,
    val lexicalScore: Double?,
    val vectorScore: Double?,
    val score: Double,
    val vectorRanked: Boolean = vectorScore != null,
)

enum class SemanticFusionMode { SCORE, RRF }

/**
 * 检索口径常量，出处 LivingMemory 2.9.1 core/retrieval/。
 * 除加权项外与 LM 默认值一致；加权按本应用的 RP 场景去掉时间新鲜度（见 RELEVANCE_WEIGHT 注释）。
 * 这里保留常量而非设置项：本轮不新增界面开关，改口径只需要改这一处。
 */
object SemanticRankingDefaults {
    /** hybrid_retriever.py:104 —— 默认 score 分数融合，rrf 只是可选模式。 */
    val FUSION_MODE = SemanticFusionMode.SCORE
    /** hybrid_retriever.py:105-107 —— 分数融合里 BM25 一路的权重。 */
    const val BM25_WEIGHT = 0.5
    /** hybrid_retriever.py:113-115 —— 两路都召回且名次接近的共识加成上限。 */
    const val AGREEMENT_BONUS = 0.25
    /**
     * 相关性／重要度权重。LivingMemory hybrid_retriever.py:96-98 是 0.5/0.25/0.25，
     * 第三项是"按真实时钟算的时间新鲜度"。本应用用于角色扮演：剧情内时间可以过一个月，
     * 也可以一直是同一天，真实时钟与剧情时序无关，用 recency 会把"同一天发生的两件事"
     * 强行分出高低。因此去掉新鲜度项，并把剩余两项按原 2:1 比例重新归一，
     * 使综合分仍落在 [0,1]：0.5/0.75 = 2/3，0.25/0.75 = 1/3。
     */
    const val RELEVANCE_WEIGHT = 2.0 / 3.0
    const val IMPORTANCE_WEIGHT = 1.0 / 3.0
    /** hybrid_retriever.py:101 —— MMR 相关性／多样性权衡。 */
    const val MMR_LAMBDA = 0.7
}

/**
 * FTS5 bm25() 越小越相关（原始值为负数），这里按 LivingMemory bm25_retriever.py:250-265
 * 翻转为 [0,1] 且越大越相关；全部相同时统一为 1.0。
 */
fun normalizeSemanticBm25(scores: List<Pair<String, Double>>): List<Pair<String, Double>> {
    val finite = scores.distinctBy { it.first }.filter { it.second.isFinite() }
    if (finite.isEmpty()) return emptyList()
    val high = finite.maxOf { it.second }
    val low = finite.minOf { it.second }
    return finite.map { (id, score) -> id to if (high == low) 1.0 else (high - score) / (high - low) }
}

private val SEMANTIC_CJK_RUN = Regex("[\\u4E00-\\u9FFF]+")

/**
 * 查询侧中文二元组：与 LivingMemory bm25_retriever.py:23-40 with_cjk_bigrams 相同，
 * 只在中文连续段上取相邻两字，去重保序；英文数字段不追加，避免噪声。
 * 索引侧无法注入（FTS 内容由触发器直接写入 semantic_memory.content），只做查询侧扩展。
 */
fun semanticCjkBigrams(tokens: List<String>, text: String): List<String> {
    val merged = tokens.toMutableList()
    SEMANTIC_CJK_RUN.findAll(text).forEach { match ->
        val run = match.value
        for (index in 0 until run.length - 1) merged += run.substring(index, index + 2)
    }
    return merged.distinct()
}

/** jieba 查询无词元时的占位词：FTS5 不接受空 MATCH 串，且该词元不会命中任何记忆。 */
const val SEMANTIC_NO_LEXICAL_TERM = "\"__semantic_no_terms__\""

/** 把查询文本的中文二元组拼成 FTS5 的 OR 表达式；没有中文时返回空串。 */
fun semanticCjkBigramQuery(text: String): String =
    semanticCjkBigrams(emptyList(), text).joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }

/**
 * 分数级融合（LivingMemory rrf_fusion.py:145-237 fuse_scores）。
 * 两路各自在本次查询内 min-max 归一化到 [0,1] 后按权重求和；某一路缺失时按可用路线
 * 重新归一化权重；两路都召回时按名次接近程度追加共识加成。
 */
fun fuseSemanticScores(
    lexical: List<Pair<String, Double>>,
    vector: List<Pair<String, Double>>,
    bm25Weight: Double = SemanticRankingDefaults.BM25_WEIGHT,
    agreementBonus: Double = SemanticRankingDefaults.AGREEMENT_BONUS,
    limit: Int = Int.MAX_VALUE,
): List<SemanticRankHit> {
    if (lexical.isEmpty() && vector.isEmpty()) return emptyList()
    val weight = bm25Weight.coerceIn(0.0, 1.0)

    fun normalize(route: List<Pair<String, Double>>): Map<String, Double> {
        if (route.isEmpty()) return emptyMap()
        val values = route.toMap()
        val low = values.values.min()
        val high = values.values.max()
        return if (high - low < 1e-9) values.mapValues { 1.0 }
        else values.mapValues { (_, value) -> (value - low) / (high - low) }
    }

    val lexicalRank = lexical.withIndex().associate { (rank, item) -> item.first to rank }
    val vectorRank = vector.withIndex().associate { (rank, item) -> item.first to rank }
    val lexicalNorm = normalize(lexical)
    val vectorNorm = normalize(vector)
    val lexicalScores = lexical.toMap()
    val vectorScores = vector.toMap()
    val span = maxOf(lexical.size, vector.size, 1)

    data class Scored(val hit: SemanticRankHit, val bestRank: Int)

    val scored = (lexicalNorm.keys + vectorNorm.keys).toSet().map { id ->
        var weighted = 0.0
        var weightTotal = 0.0
        lexicalNorm[id]?.let { weighted += weight * it; weightTotal += weight }
        vectorNorm[id]?.let { weighted += (1.0 - weight) * it; weightTotal += 1.0 - weight }
        var score = if (weightTotal != 0.0) weighted / weightTotal else 0.0
        val lexicalPosition = lexicalRank[id]
        val vectorPosition = vectorRank[id]
        if (agreementBonus > 0 && lexicalPosition != null && vectorPosition != null) {
            val rankGap = kotlin.math.abs(lexicalPosition - vectorPosition)
            score += agreementBonus * (1.0 - rankGap.toDouble() / span)
        }
        val bestRank = minOf(
            lexicalPosition ?: (lexical.size + 1),
            vectorPosition ?: (vector.size + 1),
        )
        Scored(SemanticRankHit(id, lexicalScores[id], vectorScores[id], score), bestRank)
    }.sortedWith(
        compareByDescending<Scored> { it.hit.score }.thenBy { it.bestRank }.thenBy { it.hit.id }
    )
    return scored.map { it.hit }.take(limit.coerceAtLeast(0))
}

/** 排名级融合（LivingMemory rrf_fusion.py:48-143）：只有排名参与，k 默认 60。 */
fun fuseSemanticRrf(
    lexical: List<Pair<String, Double>>,
    vector: List<Pair<String, Double>>,
    rrfK: Int,
    limit: Int = Int.MAX_VALUE,
): List<SemanticRankHit> {
    if (lexical.isEmpty() && vector.isEmpty()) return emptyList()
    val k = rrfK
    val lexicalRank = lexical.withIndex().associate { (rank, item) -> item.first to rank }
    val vectorRank = vector.withIndex().associate { (rank, item) -> item.first to rank }
    val lexicalScores = lexical.toMap()
    val vectorScores = vector.toMap()
    val scores = linkedMapOf<String, Double>()
    lexicalRank.forEach { (id, rank) -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + rank + 1) }
    vectorRank.forEach { (id, rank) -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + rank + 1) }
    return scores.map { (id, score) -> SemanticRankHit(id, lexicalScores[id], vectorScores[id], score) }
        .sortedWith(compareByDescending<SemanticRankHit> { it.score }.thenBy { it.id })
        .take(limit.coerceAtLeast(0))
}

/**
 * 融合后的加权（改自 LivingMemory hybrid_retriever.py:352-461）。
 * final = 相关性*2/3 + 重要度*1/3，加权求和而非相乘，单一维度低分不会清零。
 *
 * 与 LM 的唯一差别：**不做时间新鲜度衰减**。LM 用 recency = exp(-decay*天数) 奖励新记忆，
 * 那是为持续在线的助手对话设计的；角色扮演里剧情内时间可以和真实时钟完全脱钩
 * （过一个月仍停在同一天，或同一天内跳过数周），按真实时钟加权会让剧情上同等重要的
 * 记忆仅因"写入得早"而系统性落后。剧情内的时间先后应当由记忆正文自身承载
 * （"三天前""上个月"这类内容），而不是由写入时刻反推。
 *
 * [importance] 是 id → 重要度（缺失按 0.5），不再需要创建/访问时间。
 */
fun weightSemanticRanks(
    hits: List<SemanticRankHit>,
    importance: Map<String, Double> = emptyMap(),
    relevanceWeight: Double = SemanticRankingDefaults.RELEVANCE_WEIGHT,
    importanceWeight: Double = SemanticRankingDefaults.IMPORTANCE_WEIGHT,
): List<SemanticRankHit> {
    if (hits.isEmpty()) return emptyList()
    val maxScore = hits.maxOf { it.score }.let { if (it <= 0.0) 1.0 else it }
    return hits.map { hit ->
        val weight = importance[hit.id].let { if (it != null && it.isFinite()) it else 0.5 }.coerceIn(0.0, 1.0)
        hit.copy(score = relevanceWeight * (hit.score / maxScore) + importanceWeight * weight)
    }.sortedWith(compareByDescending<SemanticRankHit> { it.score }.thenBy { it.id })
}

/**
 * MMR 去重（LivingMemory hybrid_retriever.py:463-516）：用词袋 Jaccard 作相似度代理，
 * 高 lambda 偏相关性、低 lambda 偏多样性；接近重复的记忆被降到后面而不是直接丢弃。
 */
fun applySemanticMmr(
    hits: List<SemanticRankHit>,
    k: Int,
    lambda: Double = SemanticRankingDefaults.MMR_LAMBDA,
    contentOf: (String) -> String = { "" },
): List<SemanticRankHit> {
    if (k <= 0) return emptyList()
    if (hits.size <= k) return hits

    fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet().ifEmpty { setOf("<empty>") }

    val selected = mutableListOf<SemanticRankHit>()
    val candidates = hits.toMutableList()
    while (candidates.isNotEmpty() && selected.size < k) {
        if (selected.isEmpty()) {
            selected += candidates.removeAt(0)
            continue
        }
        val selectedTokens = selected.map { tokens(contentOf(it.id)) }
        var bestIndex = -1
        var bestScore = -1.0
        candidates.forEachIndexed { index, candidate ->
            val candidateTokens = tokens(contentOf(candidate.id))
            val maxSimilarity = selectedTokens.maxOf { other ->
                (candidateTokens intersect other).size.toDouble() / maxOf((candidateTokens union other).size, 1)
            }
            val mmr = lambda * candidate.score - (1 - lambda) * maxSimilarity
            if (mmr > bestScore) {
                bestScore = mmr
                bestIndex = index
            }
        }
        if (bestIndex < 0) break
        selected += candidates.removeAt(bestIndex)
    }
    return selected
}

/**
 * 召回排序总入口：BM25 归一化 → 两路融合（默认 score，可回退 rrf）→ 重要度加权。
 * 不做时间新鲜度衰减，理由见 [weightSemanticRanks]。
 * 最低相似度只作用于向量路（本项目既有契约，LivingMemory 把它放在融合后过滤）。
 * 单路失败时按 LivingMemory 的退化口径用 1/(k+排名+1) 占位，避免与失败路线比较名次。
 */
fun fuseSemanticRanks(
    lexical: List<Pair<String, Double>>,
    vector: List<Pair<String, Double>>,
    minSimilarity: Double,
    topK: Int,
    rrfK: Int,
    importance: Map<String, Double> = emptyMap(),
    mode: SemanticFusionMode = SemanticRankingDefaults.FUSION_MODE,
    lexicalError: Boolean = false,
    vectorError: Boolean = false,
): List<SemanticRankHit> {
    val capacity = topK.coerceAtLeast(0)
    if (capacity == 0) return emptyList()
    if (lexicalError && vectorError) return emptyList()

    fun rankOnly(route: List<Pair<String, Double>>, scoreOf: (String) -> Double, fromVector: Boolean) =
        route.withIndex().map { (rank, item) ->
            SemanticRankHit(item.first, if (fromVector) null else scoreOf(item.first),
                if (fromVector) scoreOf(item.first) else null,
                1.0 / (rrfK + rank + 1))
        }.take(capacity)

    val lexicalNormalized = normalizeSemanticBm25(lexical)
    val vectorGated = vector.distinctBy { it.first }
        .filter { it.second.isFinite() && it.second >= minSimilarity }
    val lexicalScores = lexicalNormalized.toMap()
    val vectorScores = vectorGated.toMap()
    val fused = when {
        lexicalError -> rankOnly(vectorGated, { vectorScores.getValue(it) }, fromVector = true)
        vectorError -> rankOnly(lexicalNormalized, { lexicalScores.getValue(it) }, fromVector = false)
        mode == SemanticFusionMode.RRF -> fuseSemanticRrf(lexicalNormalized, vectorGated, rrfK, capacity)
        else -> fuseSemanticScores(lexicalNormalized, vectorGated, limit = capacity)
    }
    return weightSemanticRanks(fused, importance).take(capacity)
}
