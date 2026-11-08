package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationCompressionBoundaryTest {
    @Test fun compressedOriginalsRemainStoredWhileGenerationUsesSummaryAndTail() {
        val originals = List(72) { UIMessage.user("original $it").toMessageNode() }.toMutableList()
        originals[3] = originals[3].copy(
            messages = originals[3].messages + UIMessage.user("edited original"),
            selectIndex = 1,
        )
        val source = Conversation(assistantId = Uuid.random(), messageNodes = originals)
        val compressed = source.hidePrefixWithSummaries(originals[39].id, listOf("summary"))!!

        check(compressed.messageNodes.size == 73)
        check(compressed.messageNodes.take(40).map { it.id } == originals.take(40).map { it.id })
        check(compressed.messageNodes.take(40).all { node -> node.messages.all { it.isContextHidden } })
        check(compressed.messageNodes[3].currentMessage.toText() == "edited original")
        check(compressed.messageNodes.drop(41) == originals.drop(40))
        check(compressed.currentMessages.size == 33)
        check(compressed.currentMessages.first().isContextCheckpoint)
        check(compressed.currentMessages.drop(1) == originals.drop(40).map { it.currentMessage })
        check(compressed.currentMessages.none { it.isContextHidden })
        check(compressed.messagesBeforeNode(compressed.messageNodes.last().id).size == 32)
        check(compressed.messagesBeforeNode(compressed.messageNodes.last().id).none { it.isContextHidden })
    }
}
