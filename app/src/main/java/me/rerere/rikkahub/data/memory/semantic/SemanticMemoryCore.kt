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

data class SemanticRankHit(
    val id: String,
    val lexicalScore: Double?,
    val vectorScore: Double?,
    val score: Double,
    val vectorRanked: Boolean = vectorScore != null,
)

/** RRF 使用各路排名；最低余弦阈值只作用于向量路。 */
fun fuseSemanticRanks(
    lexical: List<Pair<String, Double>>,
    vector: List<Pair<String, Double>>,
    minSimilarity: Double,
    topK: Int,
    rrfK: Int,
): List<SemanticRankHit> {
    val scores = linkedMapOf<String, Double>()
    val lexicalScores = lexical.distinctBy { it.first }.toMap()
    val vectorScores = vector.filter { it.second.isFinite() && it.second >= minSimilarity }
        .distinctBy { it.first }.toMap()
    listOf(lexicalScores.keys, vectorScores.keys).forEach { route ->
        route.forEachIndexed { index, id ->
            scores[id] = (scores[id] ?: 0.0) + 1.0 / (rrfK.coerceAtLeast(1) + index + 1)
        }
    }
    return scores.map { (id, score) -> SemanticRankHit(id, lexicalScores[id], vectorScores[id], score) }
        .sortedWith(compareByDescending<SemanticRankHit> { it.score }.thenBy { it.id })
        .take(topK.coerceAtLeast(0))
}
