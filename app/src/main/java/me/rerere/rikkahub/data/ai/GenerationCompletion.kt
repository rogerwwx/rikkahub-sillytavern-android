package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StreamCompletion
import me.rerere.ai.ui.UIMessage

/** 每个请求先撤销上一请求的完成标志，重试复用同一消息ID及原始草稿快照。 */
internal fun List<UIMessage>.beginAssistantResponse(model: Model): List<UIMessage> {
    require(isNotEmpty())
    val response = if (last().role == MessageRole.ASSISTANT) last() else UIMessage(
        role = MessageRole.ASSISTANT, parts = emptyList(), modelId = model.id,
    )
    val started = response.copy(finishedAt = null, generationCompletion = StreamCompletion.UNKNOWN)
    return if (last().role == MessageRole.ASSISTANT) dropLast(1) + started else this + started
}

/** 群聊占位节点复用自身身份，传输完成状态、用量和模型信息来自实际生成消息。 */
internal fun UIMessage.bindResponseTo(target: UIMessage): UIMessage = copy(id = target.id, createdAt = target.createdAt)
