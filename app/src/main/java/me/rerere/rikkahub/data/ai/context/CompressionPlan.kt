package me.rerere.rikkahub.data.ai.context

data class CompressionSource(val id: String, val role: String, val text: String)
data class CompressionSegment(val text: String, val sourceIds: List<String>, val partialTurn: Boolean = false,
    val part: Int = 1, val partCount: Int = 1, val sources: List<CompressionSource> = emptyList())

/** 先按用户轮次组合；单轮超预算才分有编号的子段，正文不静默截断。 */
fun planCompressionSegments(sources: List<CompressionSource>, tokenBudget: Int,
    estimate: (String) -> Int, split: (String, Int) -> List<String>,
    sourceOverheadTokens: Int = 0): List<CompressionSegment> {
    require(tokenBudget > 0)
    require(sourceOverheadTokens >= 0)
    val turns = mutableListOf<MutableList<CompressionSource>>()
    sources.forEach { source ->
        if (turns.isEmpty() || source.role == "user") turns += mutableListOf<CompressionSource>()
        turns.last() += source
    }
    val segments = mutableListOf<CompressionSegment>()
    var pendingText = ""
    val pendingSources = mutableListOf<CompressionSource>()
    fun flush() {
        if (pendingSources.isNotEmpty()) segments += CompressionSegment(
            pendingText, pendingSources.map { it.id }, sources = pendingSources.toList())
        pendingText = ""; pendingSources.clear()
    }
    turns.forEach { turn ->
        val text = turn.joinToString("\n\n") { it.text }
        val turnOverhead = turn.size * sourceOverheadTokens
        if (estimate(text) + turnOverhead > tokenBudget) {
            flush()
            val textBudget = tokenBudget - turnOverhead
            require(textBudget > 0) { "压缩轮次的消息开销超过输入预算" }
            val parts = split(text, textBudget)
            require(parts.joinToString("") == text && parts.all { estimate(it) <= textBudget }) { "压缩分段未覆盖完整原文" }
            var offset = 0
            parts.forEachIndexed { index, part ->
                segments += CompressionSegment(part, turn.map { it.id }, true, index + 1, parts.size,
                    sliceCompressionSources(turn, offset, offset + part.length))
                offset += part.length
            }
        } else {
            val combined = if (pendingSources.isEmpty()) text else "$pendingText\n\n$text"
            if (estimate(combined) + (pendingSources.size + turn.size) * sourceOverheadTokens > tokenBudget) flush()
            pendingText = if (pendingSources.isEmpty()) text else "$pendingText\n\n$text"
            pendingSources += turn
        }
    }
    flush()
    return segments
}

/** 分段只切正文，保留每一片所属的原消息身份；消息间的分隔换行无需成为独立消息。 */
private fun sliceCompressionSources(sources: List<CompressionSource>, start: Int, end: Int): List<CompressionSource> {
    var offset = 0
    return sources.mapNotNull { source ->
        val from = (start - offset).coerceAtLeast(0)
        val to = (end - offset).coerceAtMost(source.text.length)
        offset += source.text.length + 2
        if (from < to) source.copy(text = source.text.substring(from, to)) else null
    }
}
