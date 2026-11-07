package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NonStreamCompletionTest {
    private val model = Model(modelId = "test-model")

    private fun response(reason: String?, completion: StreamCompletion? = null) = TextGenerationResult(
        id = "response",
        model = model.modelId,
        message = UIMessage.assistant("next").copy(generationCompletion = completion),
        finishReason = reason,
        usage = TokenUsage(promptTokens = 3, completionTokens = 2),
    )

    @Test
    fun `nonstream protocol completion is recorded for every new response`() {
        val cases = listOf(
            "stop" to StreamCompletion.NORMAL,
            "tool_calls" to StreamCompletion.NORMAL,
            "end_turn" to StreamCompletion.NORMAL,
            "STOP" to StreamCompletion.NORMAL,
            "completed" to StreamCompletion.NORMAL,
            "requires_action" to StreamCompletion.UNKNOWN,
            "length" to StreamCompletion.TRUNCATED,
            "MAX_TOKENS" to StreamCompletion.TRUNCATED,
            "incomplete" to StreamCompletion.TRUNCATED,
            "content_filter" to StreamCompletion.FAILED,
            "failed" to StreamCompletion.FAILED,
            "unknown" to StreamCompletion.UNKNOWN,
            "pause_turn" to StreamCompletion.UNKNOWN,
            null to StreamCompletion.UNKNOWN,
        )
        cases.forEach { (reason, completion) ->
            val message = listOf(UIMessage.user("hello")).handleTextGenerationResult(response(reason), model).last()
            assertEquals("finish reason: $reason", completion, message.generationCompletion)
            assertNotNull(message.finishedAt)
            assertEquals("next", message.toText())
            assertEquals(model.id, message.modelId)
        }
    }

    @Test
    fun `same role continuation replaces prior completion with this request state`() {
        StreamCompletion.entries.forEach { previous ->
            listOf("stop", "length", "failed", null).forEach { reason ->
                val base = UIMessage.assistant("previous").copy(generationCompletion = previous)
                val message = listOf(base).handleTextGenerationResult(response(reason), model).single()
                assertEquals("$previous followed by $reason", protocolCompletion(reason), message.generationCompletion)
                assertEquals(base.id, message.id)
                assertEquals(base.createdAt, message.createdAt)
                assertEquals("previousnext", message.toText())
            }
        }
    }

    @Test
    fun `completed tool step does not make unknown final step complete`() {
        val first = TextGenerationResult(
            id = "tool-step", model = model.modelId, finishReason = "tool_calls",
            message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool("call", "read", input = "{}", output = listOf(UIMessagePart.Text("result"))),
            )),
            usage = TokenUsage(promptTokens = 10, completionTokens = 5),
        )
        val afterTool = listOf(UIMessage.user("read")).handleTextGenerationResult(first, model)
        assertEquals(StreamCompletion.NORMAL, afterTool.last().generationCompletion)
        val final = afterTool.handleTextGenerationResult(response(null), model).last()
        assertEquals(StreamCompletion.UNKNOWN, final.generationCompletion)
        assertEquals(afterTool.last().id, final.id)
        assertEquals("{}", final.getTools().single().input)
        assertEquals("result", (final.getTools().single().output.single() as UIMessagePart.Text).text)
        assertEquals(3, final.usage?.promptTokens)
        assertEquals(2, final.usage?.completionTokens)
    }

    @Test
    fun `known protocol failure overrides a contradictory normal message flag`() {
        listOf("length" to StreamCompletion.TRUNCATED, "failed" to StreamCompletion.FAILED).forEach { (reason, expected) ->
            val message = listOf(UIMessage.user("hello"))
                .handleTextGenerationResult(response(reason, StreamCompletion.NORMAL), model).last()
            assertEquals(expected, message.generationCompletion)
        }
    }

    @Test
    fun `explicit adapter completion survives an unknown reason and same role merge`() {
        StreamCompletion.entries.forEach { completion ->
            val message = listOf(UIMessage.assistant("previous"))
                .handleTextGenerationResult(response(null, completion), model).single()
            assertEquals(completion, message.generationCompletion)
        }
    }

    @Test
    fun `requires action needs a tool from this response rather than prior history`() {
        val prior = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("old", "read", "{}", output = listOf(UIMessagePart.Text("done"))),
        )).copy(generationCompletion = StreamCompletion.NORMAL)
        val text = listOf(prior).handleTextGenerationResult(response("requires_action", StreamCompletion.NORMAL), model).single()
        assertEquals(StreamCompletion.UNKNOWN, text.generationCompletion)
        val current = response("requires_action").copy(message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("new", "read", "{}")),
        ))
        assertEquals(StreamCompletion.NORMAL, listOf(prior).handleTextGenerationResult(current, model).single().generationCompletion)
    }
}
