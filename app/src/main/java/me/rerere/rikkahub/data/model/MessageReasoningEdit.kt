package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

private val protectedReasoningKeys = setOf(
    "signature", "redacted_data", "reasoning_id", "encrypted_content",
    "thoughtSignature", "interactions_signature", "openrouter_reasoning_details",
)
private const val EDITED_REASONING_KEY = "huadeng_reasoning_override"

internal fun UIMessage.reasoningText(): String = parts.filterIsInstance<UIMessagePart.Reasoning>()
    .map { it.reasoning }.filter { it.isNotEmpty() }.joinToString("\n\n")

internal fun MessageNode.messageForDisplay(message: UIMessage = currentMessage): UIMessage {
    val override = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        .firstOrNull()?.metadata?.get(EDITED_REASONING_KEY)?.jsonPrimitive?.content
    return override?.let { message.withReasoningText(it) } ?: message
}

internal fun MessageNode.withEditedReasoning(messageId: Uuid, text: String): MessageNode {
    val original = messages.firstOrNull { it.id == messageId } ?: return this
    val reasoning = original.parts.filterIsInstance<UIMessagePart.Reasoning>()
    if (reasoning.isEmpty() || messageForDisplay(original).reasoningText() == text) return this
    // 签名、加密内容与协议 item ID 必须随原始思考回放，不能随可见文字一起改写。
    if (reasoning.any { it.metadata?.keys?.any(protectedReasoningKeys::contains) == true }) {
        val firstIndex = original.parts.indexOfFirst { it is UIMessagePart.Reasoning }
        val edited = original.copy(parts = original.parts.mapIndexed { index, part ->
            if (index == firstIndex) {
                val first = part as UIMessagePart.Reasoning
                first.copy(metadata = JsonObject(first.metadata.orEmpty() + (EDITED_REASONING_KEY to JsonPrimitive(text))))
            } else part
        })
        return copy(messages = messages.map { if (it.id == messageId) edited else it })
    }
    return copy(
        messages = messages.map { if (it.id == messageId) it.withReasoningText(text) else it },
    )
}

private fun UIMessage.withReasoningText(text: String): UIMessage {
    var first = true
    return copy(parts = parts.map { part ->
        if (part is UIMessagePart.Reasoning) {
            val edited = part.copy(reasoning = if (first) text else "")
            first = false
            edited
        } else part
    })
}
