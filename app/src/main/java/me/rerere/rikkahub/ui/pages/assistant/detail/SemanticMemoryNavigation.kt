package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference

internal fun Context.rememberSemanticMemoryConversation(assistantId: String, conversationId: String) {
    writeStringPreference("semanticMemoryConversation:$assistantId", conversationId)
}

internal fun Context.lastSemanticMemoryConversation(
    assistantId: String,
    conversations: List<Conversation>,
): Conversation? {
    val lastId = readStringPreference("semanticMemoryConversation:$assistantId")
    return conversations.firstOrNull { it.id.toString() == lastId }
}

internal fun Navigator.openSemanticMemoryConversation(
    assistantId: String,
    conversationId: String,
    previousConversationId: String?,
) {
    navigate(Screen.SemanticMemory(assistantId, conversationId)) {
        // 替换选择页或先前的记忆页，返回时不重复进入旧窗口。
        popUpTo(
            previousConversationId?.let { Screen.SemanticMemory(assistantId, it) }
                ?: Screen.SemanticMemoryConversations(assistantId)
        ) { inclusive = true }
    }
}
