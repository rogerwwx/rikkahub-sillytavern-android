package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteResponseTest {
    private fun response(reason: String?, vararg parts: UIMessagePart) = TextGenerationResult(
        id = "test", model = "test", finishReason = reason,
        message = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
    )

    @Test
    fun `nonstream normal text and complete object tools pass`() {
        assertTrue(validateCompleteResponse(response("stop", UIMessagePart.Text("complete")))
            .message.generationCompletion == StreamCompletion.NORMAL)
        assertTrue(validateCompleteResponse(response("tool_calls", UIMessagePart.Tool("tool", "read", input = "{}")))
            .message.generationCompletion == StreamCompletion.NORMAL)
        assertTrue(validateCompleteResponse(response("requires_action", UIMessagePart.Tool("tool", "read", input = "{}")))
            .message.generationCompletion == StreamCompletion.NORMAL)
    }

    @Test
    fun `nonstream incomplete reasons and empty or reasoning only replies fail`() {
        listOf(
            response("length", UIMessagePart.Text("cut off")),
            response(null, UIMessagePart.Text("unknown completion")),
            response("failed", UIMessagePart.Text("failed")),
            response("stop"), response("stop", UIMessagePart.Reasoning("reasoning only")),
            response("requires_action"),
            response("requires_action", UIMessagePart.Text("waiting for action without a tool")),
        ).forEach { assertTrue(runCatching { validateCompleteResponse(it) }.exceptionOrNull() is IncompleteStreamException) }
    }

    @Test
    fun `nonstream arguments must be complete objects even when text exists`() {
        listOf("", "{", "[]", "\"string\"").forEach { input ->
            listOf("tool_calls", "requires_action").forEach { reason ->
                val result = response(reason, UIMessagePart.Text("preview"), UIMessagePart.Tool("tool", "read", input = input))
                assertTrue(runCatching { validateCompleteResponse(result) }.exceptionOrNull() is IncompleteStreamException)
            }
        }
    }

    @Test
    fun `normal message flag cannot hide a truncated protocol reason`() {
        val result = response("length", UIMessagePart.Text("cut off"))
        assertTrue(runCatching {
            validateCompleteResponse(result.copy(message = result.message.copy(generationCompletion = StreamCompletion.NORMAL)))
        }.exceptionOrNull() is IncompleteStreamException)
    }
}
