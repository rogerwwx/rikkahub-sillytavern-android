package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Test

class MessageReasoningEditTest {
    @Test fun reasoningEditsPreserveBodyAndSignedProtocolParts() {
        val first = UIMessagePart.Reasoning(
            reasoning = "先考虑人物的处境",
            metadata = JsonObject(mapOf("signature" to JsonPrimitive("original-signature"))),
        )
        val body = UIMessagePart.Text("牵住你的手")
        val image = UIMessagePart.Image("image.png")
        val second = UIMessagePart.Reasoning(
            reasoning = "再考虑下一步",
            metadata = JsonObject(mapOf("encrypted_content" to JsonPrimitive("original-encrypted"))),
        )
        val tool = UIMessagePart.Tool("tool-1", "example", "{}", output = listOf(UIMessagePart.Text("done")))
        val original = UIMessage.assistant("").copy(
            parts = listOf(first, body, image, tool, second),
            isContextHidden = true,
            translation = "translation",
            mvuData = "{\"scene\":\"room\"}",
        )
        val node = MessageNode(messages = listOf(original))
        val editedNode = node.withEditedReasoning(original.id, "修改后的思考")
        val edited = editedNode.messageForDisplay()
        check(editedNode.id == node.id && editedNode.selectIndex == node.selectIndex)
        check((editedNode.currentMessage.parts.first() as UIMessagePart.Reasoning).reasoning == first.reasoning)
        check(edited.reasoningText() == "修改后的思考")
        check(edited.id == original.id && edited.createdAt == original.createdAt)
        check(edited.isContextHidden && edited.translation == original.translation && edited.mvuData == original.mvuData)
        check(edited.parts.size == original.parts.size)
        check(edited.parts[1] === body && edited.parts[2] === image && edited.parts[3] === tool)
        val editedFirst = edited.parts[0] as UIMessagePart.Reasoning
        val editedSecond = edited.parts[4] as UIMessagePart.Reasoning
        check(editedFirst.reasoning == "修改后的思考" && editedFirst.createdAt == first.createdAt)
        check(editedFirst.metadata?.get("signature") == first.metadata?.get("signature"))
        check(editedFirst.metadataAs<ClaudeReasoningMetadata>()?.signature == "original-signature")
        check(editedSecond.reasoning.isEmpty())
        check(editedSecond.metadata?.get("encrypted_content") == second.metadata?.get("encrypted_content"))
        check(editedNode.currentMessage.toText() == original.toText())
    }

    @Test fun clearingAndReeditingReasoningKeepTextAndOtherParts() {
        val original = UIMessage.assistant("正文").copy(
            parts = listOf(UIMessagePart.Reasoning("原始思考"), UIMessagePart.Text("正文")),
        )
        val node = MessageNode(messages = listOf(original))
        check(node.withEditedReasoning(original.id, "").currentMessage.reasoningText().isEmpty())
        val edited = node.withEditedReasoning(original.id, "第一次").withEditedReasoning(original.id, "第二次")
        check(edited.currentMessage.reasoningText() == "第二次")
        check(edited.currentMessage.toText() == original.toText())
        check(edited.withEditedReasoning(original.id, "第二次") === edited)
        val noReasoning = UIMessage.assistant("只有正文")
        val noReasoningNode = MessageNode(messages = listOf(noReasoning))
        check(noReasoningNode.withEditedReasoning(noReasoning.id, "不应新增思考") === noReasoningNode)
    }

    @Test fun reasoningOverrideIsKeptWithItsOriginalReplyBranch() {
        val first = UIMessage.assistant("第一版本").copy(
            parts = listOf(UIMessagePart.Reasoning("first", metadata = JsonObject(mapOf("signature" to JsonPrimitive("signed")))),
                UIMessagePart.Text("第一版本")),
        )
        val second = UIMessage.assistant("第二版本").copy(
            parts = listOf(UIMessagePart.Reasoning("second"), UIMessagePart.Text("第二版本")),
        )
        val node = MessageNode(messages = listOf(first, second), selectIndex = 0)
            .withEditedReasoning(first.id, "edited first")
        check(node.messageForDisplay().reasoningText() == "edited first")
        check(node.copy(selectIndex = 1).messageForDisplay().reasoningText() == "second")
        check(node.copy(selectIndex = 1).copy(selectIndex = 0).messageForDisplay().reasoningText() == "edited first")
        check(node.messages[1] === second)
        val restoredMessages = JsonInstant.decodeFromString<List<UIMessage>>(JsonInstant.encodeToString(node.messages))
        val restored = node.copy(messages = restoredMessages)
        check(restored.messageForDisplay().reasoningText() == "edited first")
        check((restored.currentMessage.parts.first() as UIMessagePart.Reasoning).reasoning == "first")
        check((restored.currentMessage.parts.first() as UIMessagePart.Reasoning).metadata?.get("signature") ==
            (first.parts.first() as UIMessagePart.Reasoning).metadata?.get("signature"))
    }

    @Test fun protectedMetadataRemainsDecodableAfterStorageReloadAndFurtherEdits() {
        val claude = ClaudeReasoningMetadata(signature = "signed", redacted = "redacted-data")
        val openai = OpenAIReasoningMetadata(reasoningId = "reasoning-item", encryptedContent = "encrypted-data")
        val originals = listOf(claude.toMetadata(), openai.toMetadata()).map { metadata ->
            UIMessage.assistant("正文").copy(parts = listOf(
                UIMessagePart.Reasoning("协议原文", metadata = metadata),
                UIMessagePart.Text("正文"),
            ), isContextHidden = true)
        }
        val nodes = originals.map { MessageNode(messages = listOf(it)).withEditedReasoning(it.id, "本地显示") }
        val restored = nodes.map { node ->
            node.copy(messages = JsonInstant.decodeFromString<List<UIMessage>>(JsonInstant.encodeToString(node.messages)))
                .withEditedReasoning(node.currentMessage.id, "再次修改")
        }
        restored.forEachIndexed { index, node ->
            check(node.messageForDisplay().reasoningText() == "再次修改")
            check(node.currentMessage.id == originals[index].id && node.currentMessage.isContextHidden)
            check(node.currentMessage.toText() == originals[index].toText())
            check((node.currentMessage.parts.first() as UIMessagePart.Reasoning).reasoning == "协议原文")
        }
        check((restored[0].currentMessage.parts.first() as UIMessagePart.Reasoning)
            .metadataAs<ClaudeReasoningMetadata>() == claude)
        check((restored[1].currentMessage.parts.first() as UIMessagePart.Reasoning)
            .metadataAs<OpenAIReasoningMetadata>() == openai)
    }
}
