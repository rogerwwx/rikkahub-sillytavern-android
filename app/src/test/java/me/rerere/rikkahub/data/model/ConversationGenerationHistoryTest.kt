package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationGenerationHistoryTest {
    @Test fun regenerateAfterCompressionUsesOnlyTheVisiblePrefix() {
        val hiddenUser = UIMessage.user("原始问题").copy(isContextHidden = true)
        val hiddenReply = UIMessage.assistant("原始回复").copy(isContextHidden = true)
        val checkpoint = UIMessage.user("压缩摘要").copy(isContextCheckpoint = true)
        val question = UIMessage.user("新问题")
        val reply = UIMessage.assistant("旧回复")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(hiddenUser, hiddenReply, checkpoint, question, reply).map { it.toMessageNode() },
        )
        val history = conversation.messagesBeforeNode(conversation.messageNodes.last().id)
        check(history == listOf(checkpoint, question))
        val updated = conversation.updateCurrentMessages(history + UIMessage.assistant("新版本"))
        check(updated.messageNodes.take(4) == conversation.messageNodes.take(4))
        check(updated.messageNodes.last().id == conversation.messageNodes.last().id)
        check(updated.messageNodes.last().messages.map { it.toText() } == listOf("旧回复", "新版本"))
    }

    @Test fun resendBoundaryExcludesTheOldReplyAndFollowingTurns() {
        val hidden = UIMessage.user("已压缩").copy(isContextHidden = true)
        val checkpoint = UIMessage.user("摘要").copy(isContextCheckpoint = true)
        val question = UIMessage.user("重发问题")
        val reply = UIMessage.assistant("原回复")
        val later = UIMessage.user("后续问题")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(hidden, checkpoint, question, reply, later).map { it.toMessageNode() },
        )
        check(conversation.messagesBeforeNode(conversation.messageNodes[3].id) == listOf(checkpoint, question))
    }

    @Test fun continuationKeepsSelectedMessagesAndCheckpointMetadata() {
        val hidden = UIMessage.user("隐藏原文").copy(isContextHidden = true)
        val checkpoint = UIMessage.user("摘要").copy(isContextCheckpoint = true)
        val oldQuestion = UIMessage.user("未选分支")
        val selectedQuestion = UIMessage.user("已选分支")
        val target = UIMessage.assistant("待续写")
        val targetNode = target.toMessageNode()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                hidden.toMessageNode(), checkpoint.toMessageNode(),
                MessageNode(messages = listOf(oldQuestion, selectedQuestion), selectIndex = 1), targetNode,
            ),
        )
        check(conversation.messagesBeforeNode(targetNode.id) == listOf(checkpoint, selectedQuestion))
        val inserted = UIMessage.user("新增前文").toMessageNode()
        val changed = conversation.copy(messageNodes = listOf(inserted) + conversation.messageNodes)
        check(changed.messagesBeforeNode(targetNode.id) == listOf(inserted.currentMessage, checkpoint, selectedQuestion))
        check(conversation.messageNodes.first().currentMessage == hidden)
    }

    @Test fun missingBoundaryCannotGenerateAgainstTheWholeConversation() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("保留问题").toMessageNode()),
        )
        check(runCatching { conversation.messagesBeforeNode(Uuid.random()) }.isFailure)
    }
}
