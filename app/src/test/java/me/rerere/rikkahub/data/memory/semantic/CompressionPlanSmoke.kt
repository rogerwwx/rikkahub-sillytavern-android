package me.rerere.rikkahub.data.memory.semantic

import me.rerere.rikkahub.data.ai.context.*

object CompressionPlanSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val sources = listOf(CompressionSource("u1", "user", "甲"), CompressionSource("a1", "assistant", "乙"),
            CompressionSource("u2", "user", "丙"), CompressionSource("a2", "assistant", "丁"))
        val count: (String) -> Int = { it.length }
        val split: (String, Int) -> List<String> = { text, budget -> text.chunked(budget) }
        val groupCost = "甲\n\n乙".length
        val segments = planCompressionSegments(sources, groupCost, count, split)
        check(segments.size == 2)
        check(segments[0].sourceIds == listOf("u1", "a1") && !segments[0].partialTurn)
        check(segments[1].sourceIds == listOf("u2", "a2") && !segments[1].partialTurn)
        val long = "超长原文".repeat(100)
        val pieces = planCompressionSegments(listOf(CompressionSource("u", "user", long),
            CompressionSource("a", "assistant", "结束")), 10, count, split)
        check(pieces.all { it.partialTurn && it.text.length <= 10 })
        check(pieces.joinToString("") { it.text } == "$long\n\n结束")
        check(pieces.all { it.sourceIds == listOf("u", "a") && it.partCount == pieces.size })
        check(planCompressionSegments(sources, 1000, count, split).size == 1)
        println("CompressionPlanSmoke: PASS（完整轮次、超长轮次子段、完整原文覆盖）")
    }
}
