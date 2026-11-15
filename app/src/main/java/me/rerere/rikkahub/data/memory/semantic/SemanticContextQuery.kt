package me.rerere.rikkahub.data.memory.semantic

/** 仅在本次内存结果中保留查询明文，日志只记录策略、数量和哈希。 */
data class SemanticRecallQueries(
    val mainQuery: String,
    val mainVectorQuery: SemanticVectorQuery,
    val assistantQuery: String?,
    val assistantStrategy: String,
) {
    val vectorInputs: List<String> get() = mainVectorQuery.inputs + listOfNotNull(assistantQuery)
    val preferAssistant: Boolean get() = assistantStrategy == "continuation"
}

/** 沿用原承接语封闭名单，不能扩大成按短句长度猜测。 */
private val SEMANTIC_CONTINUATIONS = setOf(
    "嗯", "嗯嗯", "好", "好的", "继续", "接着", "然后呢", "后来呢", "还记得吗", "你还记得吗",
    "这件事呢", "那件事呢", "yes", "ok", "okay", "continue", "go on", "what happened next", "do you remember",
)

private fun isSemanticContinuation(text: String): Boolean =
    text.trim().trimEnd('。', '！', '？', '.', '!', '?', '…').trim().lowercase() in SEMANTIC_CONTINUATIONS

/** 聊天、缓存与测试页共用：主查询保持原视图，上文最多追加一个独立辅助视图。 */
fun semanticRecallQueries(
    query: String,
    previousAssistantMessage: String? = null,
    useAssistantContext: Boolean = true,
): SemanticRecallQueries {
    val current = query.trim()
    val previous = previousAssistantMessage.orEmpty().trim()
    val strategy = when {
        !useAssistantContext -> "disabled"
        current.isEmpty() -> "empty_query"
        previous.isEmpty() -> "no_context"
        semanticLexicalFocus(current) != current -> "topic_switch"
        isSemanticContinuation(current) -> "continuation"
        else -> "previous_assistant"
    }
    val auxiliary = if (strategy == "previous_assistant" || strategy == "continuation") {
        "assistant: ${semanticContextExcerpt(previous, 1200)}\nuser: $current"
    } else null
    return SemanticRecallQueries(current, semanticVectorQuery(current), auxiliary, strategy)
}

/** 超长上文保留首尾；预算含省略标记，不切开 UTF-16 代理对。 */
internal fun semanticContextExcerpt(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val omission = "\n…（中间省略）…\n"
    if (maxChars <= omission.length) return ""
    val headSize = (maxChars - omission.length) / 2
    val tailSize = maxChars - omission.length - headSize
    val headEnd = if (headSize > 0 && text[headSize - 1].isHighSurrogate()) headSize - 1 else headSize
    val tailStart = (text.length - tailSize).let { if (text[it].isLowSurrogate()) it + 1 else it }
    return text.substring(0, headEnd) + omission + text.substring(tailStart)
}
