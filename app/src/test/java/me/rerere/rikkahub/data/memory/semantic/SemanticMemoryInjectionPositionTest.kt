package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AuthorNotePosition
import me.rerere.rikkahub.data.model.InjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMemoryInjectionPositionTest {
    private val messages = listOf(
        UIMessage.system("主提示词"),
        UIMessage.system("角色卡").copy(annotations = listOf(UIMessageAnnotation.CharacterCardData)),
        UIMessage.user("示例问题").copy(annotations = listOf(UIMessageAnnotation.ExampleMessage)),
        UIMessage.assistant("示例回答").copy(annotations = listOf(UIMessageAnnotation.ExampleMessage)),
        UIMessage.user("历史问题"),
        UIMessage.assistant("历史回答"),
        UIMessage.user("最新问题"),
    )
    private val config = SemanticMemorySettings(enabled = true)

    @Test
    fun `position choices persist and retain the previous default`() {
        val restored = Json.decodeFromString<SemanticDreamSettings>("{}")
        assertEquals(InjectionPosition.AT_DEPTH, restored.injectionPosition)
        assertEquals(0, restored.injectionDepth)
        assertEquals("system", restored.injectionRole)
        for (position in InjectionPosition.entries) {
            val changed = restored.copy(injectionPosition = position)
            assertEquals(changed, Json.decodeFromString<SemanticDreamSettings>(Json.encodeToString(changed)))
        }
    }

    @Test
    fun `standalone memory uses the selected message role`() {
        for ((role, expected) in listOf(
            "system" to MessageRole.SYSTEM,
            "user" to MessageRole.USER,
            "assistant" to MessageRole.ASSISTANT,
        )) {
            val selected = config.copy(dream = config.dream.copy(injectionRole = role))
            val restored = Json.decodeFromString<SemanticMemorySettings>(Json.encodeToString(selected))
            val result = injectSemanticPrompt(messages, "记忆", restored, 3)
            assertEquals(expected, result.last().role)
            assertEquals("记忆", result.last().toText())
        }
    }

    @Test
    fun `standalone positions use the mode injection anchors`() {
        val positions = mapOf(
            InjectionPosition.BEFORE_CHARACTER to 1,
            InjectionPosition.AFTER_CHARACTER to 2,
            InjectionPosition.ANTAGONIZE to 2,
            InjectionPosition.TOP_OF_CHAT to 2,
            InjectionPosition.BOTTOM_OF_CHAT to 6,
            InjectionPosition.AFTER_DIALOG to 6,
            InjectionPosition.EM_TOP to 2,
            InjectionPosition.EM_BOTTOM to 4,
            InjectionPosition.AT_DEPTH to 7,
        )
        for ((position, index) in positions) {
            val result = injectSemanticPrompt(messages, "记忆", config.copy(
                dream = config.dream.copy(injectionPosition = position)), 3)
            assertEquals(position.name, "记忆", result[index].toText())
            assertTrue(result[index].isSynthetic)
            assertEquals(messages, result.filterNot { it.toText() == "记忆" })
        }
    }

    @Test
    fun `system prompt positions merge into the main prompt only`() {
        for ((position, expected) in listOf(
            InjectionPosition.BEFORE_SYSTEM_PROMPT to "记忆\n主提示词",
            InjectionPosition.AFTER_SYSTEM_PROMPT to "主提示词\n记忆",
        )) {
            val result = injectSemanticPrompt(messages, "记忆", config.copy(
                dream = config.dream.copy(injectionPosition = position, injectionRole = "user")), 3)
            assertEquals(messages.size, result.size)
            assertEquals(MessageRole.SYSTEM, result.first().role)
            assertEquals(expected, result.first().toText())
            assertEquals(messages.drop(1), result.drop(1))
        }
        assertEquals("主提示词", messages.first().toText())
    }

    @Test
    fun `author note position follows the global location and depth`() {
        val selected = config.copy(dream = config.dream.copy(injectionPosition = InjectionPosition.AUTHOR_NOTE))
        val inChat = injectSemanticPrompt(messages, "记忆", selected, 3, AuthorNotePosition.IN_CHAT, 1)
        assertEquals("记忆", inChat[6].toText())
        val inPrompt = injectSemanticPrompt(messages, "记忆", selected, 3, AuthorNotePosition.IN_PROMPT)
        assertEquals("记忆", inPrompt[2].toText())
        val beforePrompt = injectSemanticPrompt(messages, "记忆", selected, 3, AuthorNotePosition.BEFORE_PROMPT)
        assertEquals("记忆\n主提示词", beforePrompt.first().toText())
    }

    @Test
    fun `depth insertion preserves the tool call boundary`() {
        val tool = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool(
            toolCallId = "call", toolName = "calculator", input = "{}")))
        val source = listOf(UIMessage.system("主提示词"), UIMessage.user("问题"), tool)
        val result = injectSemanticPrompt(source, "记忆",
            config.copy(dream = config.dream.copy(injectionDepth = 1)), 2)
        assertEquals(listOf("主提示词", "记忆", "问题"), result.take(3).map { it.toText() })
        assertEquals(tool, result.last())
    }
}
