package me.rerere.rikkahub.data.ai.context

data class CompressionSource(val id: String, val role: String, val text: String)
data class CompressionSegment(val text: String, val sourceIds: List<String>, val partialTurn: Boolean = false,
    val part: Int = 1, val partCount: Int = 1)

/** 先按用户轮次组合；单轮超预算才分有编号的子段，正文不静默截断。 */
fun planCompressionSegments(sources: List<CompressionSource>, tokenBudget: Int,
    estimate: (String) -> Int, split: (String, Int) -> List<String>): List<CompressionSegment> {
    require(tokenBudget > 0)
    val turns = mutableListOf<MutableList<CompressionSource>>()
    sources.forEach { source ->
        if (turns.isEmpty() || source.role == "user") turns += mutableListOf<CompressionSource>()
        turns.last() += source
    }
    val segments = mutableListOf<CompressionSegment>()
    var pendingText = ""
    val pendingIds = mutableListOf<String>()
    fun flush() {
        if (pendingIds.isNotEmpty()) segments += CompressionSegment(pendingText, pendingIds.toList())
        pendingText = ""; pendingIds.clear()
    }
    turns.forEach { turn ->
        val text = turn.joinToString("\n\n") { it.text }
        if (estimate(text) > tokenBudget) {
            flush()
            val parts = split(text, tokenBudget)
            require(parts.joinToString("") == text && parts.all { estimate(it) <= tokenBudget }) { "压缩分段未覆盖完整原文" }
            parts.forEachIndexed { index, part -> segments += CompressionSegment(part, turn.map { it.id }, true, index + 1, parts.size) }
        } else {
            val combined = if (pendingIds.isEmpty()) text else "$pendingText\n\n$text"
            if (estimate(combined) > tokenBudget) flush()
            pendingText = if (pendingIds.isEmpty()) text else "$pendingText\n\n$text"
            pendingIds += turn.map { it.id }
        }
    }
    flush()
    return segments
}
