package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ContextSummaryEditTest {
    @Test
    fun `editing a legacy summary keeps its node and checkpoint while changing role to system`() {
        val summary = UIMessage.user("summary").copy(isContextCheckpoint = true)
        val node = summary.toMessageNode()
        val tail = UIMessage.user("latest").toMessageNode()
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node, tail))

        val result = source.editMessage(summary.id, listOf(UIMessagePart.Text("updated summary")))
        val saved = result.messageNodes.first().currentMessage

        assertEquals(node.id, result.messageNodes.first().id)
        assertEquals(summary.id, saved.id)
        assertEquals(1, result.messageNodes.first().messages.size)
        assertEquals(MessageRole.SYSTEM, saved.role)
        assertTrue(saved.isContextCheckpoint)
        assertEquals("updated summary", saved.toText())
        assertEquals(tail, result.messageNodes.last())
        assertEquals(saved, Json.decodeFromString<UIMessage>(Json.encodeToString(saved)))
    }

    @Test
    fun `editing an archived summary preserves hidden state and other branches`() {
        val summary = UIMessage.user("old summary").copy(isContextCheckpoint = true, isContextHidden = true)
        val alternate = summary.copy(id = Uuid.random())
        val node = MessageNode(messages = listOf(summary, alternate))
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val result = source.editMessage(summary.id, listOf(UIMessagePart.Text("archived edit")))
        val savedNode = result.messageNodes.single()

        assertEquals(node.selectIndex, savedNode.selectIndex)
        assertEquals(2, savedNode.messages.size)
        assertEquals(alternate, savedNode.messages.last())
        assertTrue(savedNode.currentMessage.isContextHidden)
        assertTrue(savedNode.currentMessage.isContextCheckpoint)
        assertEquals(MessageRole.SYSTEM, savedNode.currentMessage.role)
        assertTrue(result.currentMessages.isEmpty())
    }
}
