package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.memory.semantic.SemanticDreamRange
import me.rerere.rikkahub.data.memory.semantic.dreamMessages
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationFloorNumbersTest {
    @Test fun editsSwipesAndRepeatedCompressionKeepFloorsAndRangeSources() {
        val nodes = List(8) { index ->
            UIMessage.user("dialogue $index")
                .copy(role = if (index % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER)
                .toMessageNode()
        }
        val source = Conversation(assistantId = Uuid.random(), messageNodes = nodes)
        val expected = nodes.mapIndexed { index, node -> node.id to index + 1 }.toMap()
        val replacement = UIMessage.user("edited dialogue")
        val edited = source.copy(messageNodes = nodes.mapIndexed { index, node ->
            if (index == 3) node.copy(messages = node.messages + replacement, selectIndex = 1) else node
        })
        val compressed = edited.hidePrefixWithSummaries(nodes[2].id, listOf("first summary"))!!
            .hidePrefixWithSummaries(nodes[6].id, listOf("second summary"))!!
        val swiped = compressed.copy(messageNodes = compressed.messageNodes.map { node ->
            if (node.id == nodes[3].id) node.copy(selectIndex = 0) else node
        })
        for (conversation in listOf(source, edited, compressed, swiped)) {
            check(conversation.messageFloorNumbers() == expected)
            check(conversation.messageNodes.messageFloorNumbers() == expected)
        }
        val history = compressed.dreamMessages()
        check(history.map { it.nodeId } == nodes.map { it.id.toString() })
        check(history[3].messageId == replacement.id.toString() && history[3].text == "edited dialogue")
        check(SemanticDreamRange("floors", history[3].messageId, history[5].messageId).positions(history) == 3..5)
        check(SemanticDreamRange("single", history[3].messageId, history[3].messageId).positions(history) == 3..3)
        check(swiped.dreamMessages()[3].messageId == nodes[3].currentMessage.id.toString())
        check(compressed.currentMessages.none { it.isContextHidden })
    }

    @Test fun summariesAndNonDialogueRecordsDoNotConsumeFloors() {
        val first = UIMessage.user("first").toMessageNode()
        val second = UIMessage.user("second").copy(role = MessageRole.ASSISTANT).toMessageNode()
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("system").copy(role = MessageRole.SYSTEM).toMessageNode(), first,
            UIMessage.user("summary").copy(isContextCheckpoint = true).toMessageNode(),
            UIMessage.user("injection").copy(isSynthetic = true).toMessageNode(),
            UIMessage.user("").copy(parts = emptyList()).toMessageNode(), second,
        ))
        check(source.messageFloorNumbers() == mapOf(first.id to 1, second.id to 2))
        check(source.dreamMessages().map { it.nodeId } == listOf(first.id.toString(), second.id.toString()))
    }
}
