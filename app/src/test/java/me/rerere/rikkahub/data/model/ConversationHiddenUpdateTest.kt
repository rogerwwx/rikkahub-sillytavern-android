package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationHiddenUpdateTest {
    @Test fun visibleUpdatesKeepHiddenSourcesAndNodeIdentity() {
        val original = UIMessage.user("source").copy(isContextHidden = true)
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        val question = UIMessage.user("new question")
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            original.toMessageNode(), checkpoint.toMessageNode(), question.toMessageNode(),
        ))
        val reply = UIMessage.assistant("reply")
        val updated = before.updateCurrentMessages(before.currentMessages + reply)
        check(updated.messageNodes.take(3).map { it.id } == before.messageNodes.map { it.id })
        check(updated.messageNodes.first() == before.messageNodes.first())
        check(updated.currentMessages.map { it.id } == listOf(checkpoint.id, question.id, reply.id))
        check(updated.messageNodes.last().currentMessage == reply)
    }

    @Test fun streamedReplyUpdatesTheVisibleBranchWithoutDuplicatingHiddenHistory() {
        val hidden = UIMessage.assistant("hidden").copy(isContextHidden = true)
        val user = UIMessage.user("question")
        val reply = UIMessage.assistant("partial")
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            hidden.toMessageNode(), user.toMessageNode(), reply.toMessageNode(),
        ))
        val updated = before.updateCurrentMessages(listOf(user, reply.copy(parts = UIMessage.assistant("complete").parts)))
        check(updated.messageNodes.size == 3)
        check(updated.messageNodes.first() == before.messageNodes.first())
        check(updated.messageNodes.last().id == before.messageNodes.last().id)
        check(updated.messageNodes.last().messages.size == 1)
        check(updated.currentMessages.last().toText() == "complete")
    }

    @Test fun incomingStableIdsFollowNodesAfterAnInsertion() {
        val user = UIMessage.user("question")
        val reply = UIMessage.assistant("partial")
        val inserted = UIMessage.user("inserted")
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            inserted.toMessageNode(), user.toMessageNode(), reply.toMessageNode(),
        ))
        val updated = before.updateCurrentMessages(listOf(user, reply.copy(parts = UIMessage.assistant("complete").parts)))
        check(updated.messageNodes.first() == before.messageNodes.first())
        check(updated.messageNodes[1] == before.messageNodes[1])
        check(updated.messageNodes.last().currentMessage.toText() == "complete")
    }

    @Test fun regenerationKeepsTheVisibleNodeAndExistingBranchSelection() {
        val hidden = UIMessage.user("hidden").copy(isContextHidden = true)
        val user = UIMessage.user("question")
        val oldReply = UIMessage.assistant("old")
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            hidden.toMessageNode(), user.toMessageNode(), oldReply.toMessageNode(),
        ))
        val alternative = UIMessage.assistant("alternative")
        val regenerated = before.updateCurrentMessages(listOf(user, alternative))
        check(regenerated.messageNodes.first() == before.messageNodes.first())
        check(regenerated.messageNodes.last().id == before.messageNodes.last().id)
        check(regenerated.messageNodes.last().messages == listOf(oldReply, alternative))
        val selectedOld = regenerated.copy(messageNodes = regenerated.messageNodes.toMutableList().also {
            it[it.lastIndex] = it.last().copy(selectIndex = 0)
        })
        val updated = selectedOld.updateCurrentMessages(listOf(user, alternative.copy(parts = UIMessage.assistant("later").parts)))
        check(updated.messageNodes.last().selectIndex == 0)
        check(updated.messageNodes.last().currentMessage == oldReply)
    }
}
