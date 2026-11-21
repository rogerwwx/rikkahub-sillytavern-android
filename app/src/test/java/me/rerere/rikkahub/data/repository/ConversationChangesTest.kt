package me.rerere.rikkahub.data.repository

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test

class ConversationChangesTest {
    @Test fun branchSwitchOnlyUpdatesPosition() {
        val node = MessageNode(messages = listOf(UIMessage.user("原分支"), UIMessage.user("备选")))
        val changes = conversationNodeChanges(listOf(node), listOf(node.copy(selectIndex = 1)))
        assertEquals(listOf(MessageNodePosition(node.id, 0, 1)), changes.positioned)
        assertTrue(changes.written.isEmpty() && changes.reindexed.isEmpty() && changes.deleted.isEmpty())
    }

    @Test fun editAdditionDeletionAndReorderOnlyTouchAffectedNodes() {
        val nodes = List(4) { MessageNode.of(UIMessage.user("原文 $it")) }
        val edited = nodes[1].copy(messages = listOf(nodes[1].currentMessage.copy(parts = listOf(UIMessagePart.Text("新正文")))))
        val added = MessageNode.of(UIMessage.user("新增"))
        val changes = conversationNodeChanges(nodes, listOf(nodes[2], edited, added, nodes[0]))
        assertEquals(listOf(1 to edited, 2 to added), changes.written)
        assertEquals(listOf(edited, added), changes.reindexed)
        assertEquals(listOf(nodes[3].id), changes.deleted)
        assertEquals(listOf(MessageNodePosition(nodes[2].id, 0, 0), MessageNodePosition(nodes[0].id, 3, 0)), changes.positioned)
    }

    @Test fun hiddenAndReasoningChangesDoNotRebuildTextIndex() {
        val message = UIMessage.user("相同正文").copy(parts = listOf(UIMessagePart.Text("相同正文"), UIMessagePart.Reasoning("旧思考")))
        val node = MessageNode.of(message)
        val changed = node.copy(messages = listOf(message.copy(isContextHidden = true,
            parts = listOf(UIMessagePart.Text("相同正文"), UIMessagePart.Reasoning("新思考")))))
        val changes = conversationNodeChanges(listOf(node), listOf(changed))
        assertEquals(listOf(0 to changed), changes.written)
        assertTrue(changes.reindexed.isEmpty())
        assertTrue(conversationNodeChanges(listOf(node), listOf(node.copy(isFavorite = true))).written.isEmpty())
    }

    @Test fun removingTextAndEditingUnselectedBranchReindexTheNode() {
        val node = MessageNode(messages = listOf(UIMessage.user("选中正文"), UIMessage.user("备选正文")))
        val changed = node.copy(messages = listOf(node.messages[0], node.messages[1].copy(parts = emptyList())))
        assertEquals(listOf(changed), conversationNodeChanges(listOf(node), listOf(changed)).reindexed)
        assertEquals(listOf(node.id), conversationNodeChanges(listOf(node), emptyList()).deleted)
    }

    @Test fun noChangeDoesNotWriteAndInvalidNodesAreRejected() {
        val node = MessageNode.of(UIMessage.user("正文"))
        val unchanged = conversationNodeChanges(listOf(node), listOf(node))
        assertTrue(unchanged.written.isEmpty() && unchanged.positioned.isEmpty() && unchanged.reindexed.isEmpty())
        assertTrue(runCatching { conversationNodeChanges(listOf(node), listOf(node, node)) }.isFailure)
        assertTrue(runCatching { conversationNodeChanges(listOf(node), listOf(node.copy(selectIndex = 4))) }.isFailure)
    }
}
