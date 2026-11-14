package me.rerere.rikkahub.data.memory.semantic

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamCompletion
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext

/** 本轮召回的固定输入；当前消息单独交给重排，背景只用于消解承接语与场景。 */
internal data class SemanticRecallInput(
    val currentUserMessage: String,
    val retrievalQuery: String,
    val recentContext: String,
) {
    fun cacheKey(revision: String, jevRevision: String): String =
        semanticContentHash(listOf(revision, jevRevision, currentUserMessage, retrievalQuery, recentContext)
            .joinToString("") { "${it.length}:$it" })
}

/**
 * 只读本次请求已选中的可见历史，不从原始节点补回被裁剪的原文。
 * 在工具循环前调用一次，工具结果和本轮助手草稿不改变召回查询。
 */
internal fun buildSemanticRecallInput(messages: List<UIMessage>, contextMessageLimit: Int): SemanticRecallInput {
    val visible = messages.filterNot { it.isContextHidden }.limitContext(contextMessageLimit)
        .filterNot { it.isContextCheckpoint || it.isSynthetic }
    val currentIndex = visible.indexOfLast { it.role == MessageRole.USER }
    if (currentIndex < 0) {
        // 续写裁剪后可能只剩助手预填；使用这个可见场景，不补回已不可见的用户正文。
        val tail = visible.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.takeIf { it.toText().isNotBlank() && isSemanticSceneComplete(it) }
        val context = semanticTurnText(listOfNotNull(tail), 1200)
        return SemanticRecallInput("", context, context)
    }
    val current = visible[currentIndex].toText()
    // 续写、代写及安静生成可能以已有助手回复/预填结尾，它已属于冻结输入中的最新场景。
    val hasAssistantTail = visible.drop(currentIndex + 1).any {
        it.role == MessageRole.ASSISTANT && it.toText().isNotBlank()
    }
    val history = if (hasAssistantTail) visible else visible.take(currentIndex)
    val previousUserIndex = if (hasAssistantTail) currentIndex else history.indexOfLast { it.role == MessageRole.USER }
    val turn = if (previousUserIndex < 0) emptyList() else {
        val replies = history.drop(previousUserIndex + 1).filter { it.role == MessageRole.ASSISTANT }
        // 旧历史没有完成标记时沿用可见正文；明确截断或仍有待执行工具的回复不算完整轮次。
        val complete = replies.isNotEmpty() && replies.all(::isSemanticSceneComplete)
        if (complete) (listOf(history[previousUserIndex]) + replies).filter { it.toText().isNotBlank() }
        else emptyList()
    }
    // 下游重排只保留最近 1300 字；在此完成角色内摘录，避免下游再次截掉整个用户或助手段。
    val context = semanticTurnText(turn, 1200)
    val query = if (hasAssistantTail && turn.isNotEmpty()) {
        current + "\n" + semanticTurnText(turn.filter { it.role == MessageRole.ASSISTANT }, 1200)
    } else if (isSemanticContinuation(current) && turn.isNotEmpty()) {
        // 只扩展明确承接语；具体短指令及长输入保持全文，不用长度猜测用户是否换了话题。
        current + "\n" + context
    } else current
    return SemanticRecallInput(current, query, context)
}

private fun isSemanticSceneComplete(message: UIMessage): Boolean =
    message.getTools().all { it.isExecuted } &&
        (message.generationCompletion == null ||
            (message.generationCompletion == StreamCompletion.NORMAL && message.finishedAt != null))

private val SEMANTIC_CONTINUATIONS = setOf(
    "嗯", "嗯嗯", "好", "好的", "继续", "接着", "然后呢", "后来呢", "还记得吗", "你还记得吗",
    "这件事呢", "那件事呢", "yes", "ok", "okay", "continue", "go on", "what happened next", "do you remember",
)

private fun isSemanticContinuation(text: String): Boolean =
    text.trim().trimEnd('。', '！', '？', '.', '!', '?', '…').trim().lowercase() in SEMANTIC_CONTINUATIONS

/** 整轮放得下时不截断；超长背景给每个角色保留首尾，剩余空间优先留给最新回复。 */
private fun semanticTurnText(turn: List<UIMessage>, maxChars: Int): String {
    if (turn.isEmpty()) return ""
    // 一轮只有用户段和助手段；合并助手的分段回复，避免大量角色标签独自耗尽预算。
    val segments = turn.groupBy { it.role }
    val labels = segments.keys.map { "${it.name.lowercase()}: " }
    val texts = segments.values.map { messages -> messages.joinToString("\n") { it.toText() } }
    val overhead = labels.sumOf { it.length } + texts.size - 1
    val available = (maxChars - overhead).coerceAtLeast(0)
    if (texts.sumOf { it.length } <= available) {
        return texts.indices.joinToString("\n") { labels[it] + texts[it] }
    }
    val budgets = IntArray(texts.size) { minOf(texts[it].length, available / texts.size) }
    var remaining = available - budgets.sum()
    for (index in texts.indices.reversed()) {
        val extra = minOf(texts[index].length - budgets[index], remaining)
        budgets[index] += extra
        remaining -= extra
    }
    return texts.indices.joinToString("\n") { labels[it] + semanticContextExcerpt(texts[it], budgets[it]) }
}

private fun semanticContextExcerpt(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val omission = "\n…（中间省略）…\n"
    if (maxChars <= omission.length) return ""
    val headSize = (maxChars - omission.length) / 2
    val tailSize = maxChars - omission.length - headSize
    val headEnd = if (headSize > 0 && text[headSize - 1].isHighSurrogate()) headSize - 1 else headSize
    val tailStart = (text.length - tailSize).let { if (text[it].isLowSurrogate()) it + 1 else it }
    return text.substring(0, headEnd) + omission + text.substring(tailStart)
}
