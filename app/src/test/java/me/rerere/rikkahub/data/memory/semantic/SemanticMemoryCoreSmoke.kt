package me.rerere.rikkahub.data.memory.semantic

/** 无 Android 依赖的行为回放，使用实际生产检索和向量校验代码。 */
object SemanticMemoryCoreSmoke {
    private fun near(actual: Double, expected: Double, tolerance: Double = 1e-12) =
        check(kotlin.math.abs(actual - expected) <= tolerance) { "期望 $expected，实际 $actual" }

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
        // BM25 归一化后翻转：最好的一条是 1.0，最差 0.0。
        check(hits.first().lexicalScore == 1.0 && hits.first().vectorScore == 0.9)
        check(fuseSemanticRanks(emptyList(), listOf("无关" to 0.1), 0.35, 6, 60).isEmpty())
        check(fuseSemanticRanks(listOf("仅BM25" to -2.0), emptyList(), 0.9, 6, 60).single().id == "仅BM25")
        check(semanticContentHash("事实") != semanticContentHash("新事实"))

        // BM25：FTS5 原始分越小越相关，归一化翻转成越大越相关；并列时统一为 1.0。
        check(normalizeSemanticBm25(listOf("best" to -9.0, "mid" to -4.0, "worst" to -1.0))
            .map { it.second } == listOf(1.0, 0.375, 0.0))
        check(normalizeSemanticBm25(listOf("a" to -2.0, "b" to -2.0)).all { it.second == 1.0 })
        check(normalizeSemanticBm25(listOf("only" to -7.5)).single().second == 1.0)
        check(normalizeSemanticBm25(emptyList()).isEmpty())
        check(normalizeSemanticBm25(listOf("nan" to Double.NaN)).isEmpty())

        // 中文二元组：只补中文连续段、去重保序；英文数字不追加。
        check(semanticCjkBigrams(listOf("龙井茶"), "龙井茶 2024 matcha") == listOf("龙井茶", "龙井", "井茶"))
        check(semanticCjkBigrams(emptyList<String>(), "a").isEmpty())
        check(semanticCjkBigramQuery("龙井茶") == "\"龙井\" OR \"井茶\"")
        check(semanticCjkBigramQuery("matcha 2024").isEmpty())

        // score 融合：两路各自 min-max 后按 0.5/0.5 平均，两路共识按名次差加成 0.25。
        val fused = fuseSemanticScores(
            lexical = listOf("a" to 1.0, "b" to 0.0),
            vector = listOf("a" to 1.0, "c" to 0.0),
        )
        check(fused.map { it.id } == listOf("a", "b", "c"))
        near(fused[0].score, 1.25)
        near(fused[1].score, 0.0)
        near(fused[2].score, 0.0)
        // 名次差越大共识加成越小：lexical 第 1 与 vector 第 2 只拿 0.25*(1-1/3)。
        val gap = fuseSemanticScores(
            lexical = listOf("x" to 1.0, "y" to 0.5, "z" to 0.0),
            vector = listOf("y" to 1.0, "x" to 0.5, "w" to 0.0),
        )
        near(gap.first { it.id == "x" }.score, 0.5 * 1.0 + 0.5 * 0.5 + 0.25 * (1.0 - 1.0 / 3.0))
        near(gap.first { it.id == "y" }.score, 0.5 * 0.5 + 0.5 * 1.0 + 0.25 * (1.0 - 1.0 / 3.0))
        // 单路存在时权重按可用路线重新归一化，弱路不会稀释强路。
        near(fuseSemanticScores(listOf("solo" to 0.4), emptyList()).single().score, 1.0)

        // 加权：final = 相关性*2/3 + 重要度*1/3，不做时间新鲜度衰减（RP 场景剧情时间与真实时钟脱钩）。
        val weighted = weightSemanticRanks(
            fuseSemanticScores(listOf("high" to 0.0, "low" to 0.0), emptyList()),
            mapOf("high" to 1.0, "low" to 0.0),
        )
        check(weighted.map { it.id } == listOf("high", "low"))
        near(weighted[0].score, 2.0 / 3.0 + 1.0 / 3.0)
        near(weighted[1].score, 2.0 / 3.0 + 0.0)
        // 时间不参与排序：相关性与重要度相同，名次与分数必须完全一致。
        val timeIndependent = weightSemanticRanks(
            listOf(SemanticRankHit("a", null, null, 0.8), SemanticRankHit("b", null, null, 0.8)),
            emptyMap(),
        )
        check(timeIndependent.map { it.id } == listOf("a", "b"))
        check(timeIndependent[0].score == timeIndependent[1].score) { "同分候选不应因时间产生差异" }

        // MMR：字面几乎重复的候选被压到后面，而不是直接丢弃。
        val mmrHits = listOf(
            SemanticRankHit("d1", null, null, 1.0),
            SemanticRankHit("d2", null, null, 0.98),
            SemanticRankHit("d3", null, null, 0.60),
        )
        val contents = mapOf("d1" to "小明喜欢龙井茶", "d2" to "小明喜欢龙井茶", "d3" to "周末天气不错")
        check(applySemanticMmr(mmrHits, 2) { contents.getValue(it) }.map { it.id } == listOf("d1", "d3"))
        check(applySemanticMmr(mmrHits, 3) { contents.getValue(it) }.map { it.id } == listOf("d1", "d2", "d3"))
        check(applySemanticMmr(mmrHits, 0) { contents.getValue(it) }.isEmpty())

        // 单路失败时按 1/(k+排名+1) 占位，失败路线不参与名次比较。
        val lexicalDown = fuseSemanticRanks(emptyList(), listOf("v0" to 0.9, "v1" to 0.8),
            minSimilarity = 0.0, topK = 6, rrfK = 60, lexicalError = true)
        // 相关性占 2/3、重要度占 1/3；重要度缺省 0.5，无时间项。
        near(lexicalDown.first().score, (2.0 / 3.0) * 1.0 + (1.0 / 3.0) * 0.5)
        check(fuseSemanticRanks(emptyList(), listOf("v0" to 0.9), 0.0, 6, 60,
            lexicalError = true, vectorError = true).isEmpty())
        check(fuseSemanticRanks(listOf("l0" to -1.0), emptyList(), 0.0, 6, 60,
            lexicalError = true, vectorError = true).isEmpty())

        println("SemanticMemoryCoreSmoke: PASS（向量、阈值、BM25 归一化、中文二元组、score 融合、加权、MMR、退化、内容哈希）")
    }
}
