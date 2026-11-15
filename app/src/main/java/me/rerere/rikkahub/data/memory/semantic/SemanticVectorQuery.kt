package me.rerere.rikkahub.data.memory.semantic

/** 向量查询视图：首条始终是完整原文，最多补一条原文中的圆括号叙述。 */
data class SemanticVectorQuery(val inputs: List<String>, val strategy: String)

/** 只用结构与长度过滤短括注，不根据剧情词猜测意图。 */
private const val SEMANTIC_NARRATION_MIN_CHARS = 12

/**
 * 提取严格配对的半角/全角圆括号，保留完整子句、否定与时间，不截断或改写。
 * 未配对、括号外没有实质内容、仅有短注释或明确换题时只用完整原文。
 */
fun semanticVectorQuery(text: String): SemanticVectorQuery {
    val full = text.trim()
    val original = SemanticVectorQuery(listOf(full), "full")
    // 复用词法已有的换题/历史转述判断；避免再次强化换题前的动作。
    if (semanticLexicalFocus(full) != full) return original

    val brackets = ArrayDeque<Char>()
    val narrations = mutableListOf<String>()
    var start = 0
    var outsideContent = false
    full.forEachIndexed { index, char ->
        when (char) {
            '(', '（' -> {
                if (brackets.isEmpty()) start = index + 1
                brackets.addLast(char)
            }
            ')', '）' -> {
                val expected = if (char == ')') '(' else '（'
                if (brackets.isEmpty() || brackets.removeLast() != expected) return original
                if (brackets.isEmpty()) {
                    val narration = full.substring(start, index).trim()
                    if (narration.count { it.isLetterOrDigit() } >= SEMANTIC_NARRATION_MIN_CHARS) {
                        narrations += narration
                    }
                }
            }
            else -> if (brackets.isEmpty() && char.isLetterOrDigit()) outsideContent = true
        }
    }
    if (brackets.isNotEmpty() || !outsideContent || narrations.isEmpty()) return original
    return SemanticVectorQuery(listOf(full, narrations.joinToString("\n")), "full_and_parenthetical")
}
