package me.rerere.rikkahub.data.memory.semantic

/** 无 Android 依赖的行为回放，使用实际生产检索和向量校验代码。 */
object SemanticMemoryCoreSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        check(SemanticVectors.validate(listOf(listOf(1f, 0f), listOf(0f, 1f)), 2, 2) == 2)
        listOf(
            listOf(listOf(1f, 0f)),
            listOf(listOf(1f, 0f), listOf(1f)),
            listOf(listOf(0f, 0f), listOf(1f, 0f)),
            listOf(listOf(Float.NaN, 0f), listOf(1f, 0f)),
            listOf(listOf(Float.POSITIVE_INFINITY, 0f), listOf(1f, 0f)),
        ).forEach { vectors -> check(runCatching { SemanticVectors.validate(vectors, 2, 2) }.isFailure) }
        val encoded = SemanticVectors.encode(listOf(0.5f, -1f))
        check(SemanticVectors.decode(encoded, 2) == listOf(0.5f, -1f))
        check(SemanticVectors.cosine(listOf(1f, 0f), listOf(1f, 0f)) == 1.0)
        check(SemanticVectors.cosine(listOf(1f, 0f), listOf(0f, 1f)) == 0.0)
        check(SemanticVectors.decode(byteArrayOf(1, 2), 2) == null)

        // 阈值只过滤向量路；词法单路仍可召回；重复 ID 只占一个位置。
        val hits = fuseSemanticRanks(
            lexical = listOf("中文人名" to -3.0, "词法独有" to -1.0),
            vector = listOf("低相似" to 0.1, "中文人名" to 0.9, "语义改写" to 0.8),
            minSimilarity = 0.35, topK = 3, rrfK = 60,
        )
        check(hits.map { it.id }.toSet() == setOf("中文人名", "词法独有", "语义改写"))
        check(hits.first().id == "中文人名")
        check(hits.first().lexicalScore == -3.0 && hits.first().vectorScore == 0.9)
        check(fuseSemanticRanks(emptyList(), listOf("无关" to 0.1), 0.35, 6, 60).isEmpty())
        check(fuseSemanticRanks(listOf("仅BM25" to -2.0), emptyList(), 0.9, 6, 60).single().id == "仅BM25")
        check(semanticContentHash("事实") != semanticContentHash("新事实"))
        println("SemanticMemoryCoreSmoke: PASS（向量、阈值、RRF、去重、内容哈希）")
    }
}
