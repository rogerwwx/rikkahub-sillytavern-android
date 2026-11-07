package me.rerere.ai.provider.stream

import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.google.InteractionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.providers.openai.ResponseApiStreamDecoder
import me.rerere.ai.ui.CompleteStreamGuard
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteDecoderTest {
    private fun complete(decoder: StreamChunkDecoder, vararg events: SseEvent): Boolean = runCatching {
        val guard = CompleteStreamGuard()
        events.flatMap { decoder.accept(it).chunks }.forEach(guard::accept)
        decoder.onClosed().forEach(guard::accept)
        guard.requireComplete()
    }.isSuccess

    @Test
    fun `transport done without protocol completion is rejected by every decoder`() {
        listOf<StreamChunkDecoder>(
            ChatCompletionsStreamDecoder(), ResponseApiStreamDecoder(), ClaudeStreamDecoder(),
            GoogleStreamDecoder("test", "test"), InteractionsStreamDecoder(),
        ).forEach { assertFalse(complete(it, SseEvent(data = "[DONE]"))) }
    }

    @Test
    fun `chat text with no stop or with length cannot commit`() {
        val text = SseEvent(data = """{"choices":[{"delta":{"content":"complete"}}]}""")
        fun finish(reason: String) = SseEvent(data = """{"choices":[{"delta":{},"finish_reason":"$reason"}]}""")
        assertFalse(complete(ChatCompletionsStreamDecoder(), text))
        assertFalse(complete(ChatCompletionsStreamDecoder(), text, finish("length")))
        assertTrue(complete(ChatCompletionsStreamDecoder(), text, finish("stop")))
    }

    @Test
    fun `claude preserves an explicit empty object but never repairs partial arguments`() {
        val start = SseEvent(event = "content_block_start", data =
            """{"index":0,"content_block":{"type":"tool_use","id":"tool","name":"memory_list","input":{}}}""")
        val end = SseEvent(event = "content_block_stop", data = """{"index":0}""")
        val reason = SseEvent(event = "message_delta", data = """{"delta":{"stop_reason":"tool_use"}}""")
        val stop = SseEvent(event = "message_stop", data = "{}")
        assertTrue(complete(ClaudeStreamDecoder(), start, end, reason, stop))
        val partial = SseEvent(event = "content_block_delta", data =
            """{"index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}""")
        assertFalse(complete(ClaudeStreamDecoder(), start, partial, end, reason, stop))
        assertFalse(complete(ClaudeStreamDecoder(), start, end, reason))
    }

    @Test
    fun `interactions preserves complete empty arguments`() {
        val start = SseEvent(event = "step.start", data =
            """{"index":0,"step":{"type":"function_call","id":"tool","name":"memory_list","arguments":{}}}""")
        val end = SseEvent(event = "step.stop", data = """{"index":0}""")
        val stop = SseEvent(event = "interaction.completed", data = """{"interaction":{"status":"completed"}}""")
        assertTrue(complete(InteractionsStreamDecoder(), start, end, stop))
        val requiresAction = SseEvent(event = "interaction.completed", data = """{"interaction":{"status":"requires_action"}}""")
        assertTrue(complete(InteractionsStreamDecoder(), start, end, requiresAction))
        val partial = SseEvent(event = "step.delta", data =
            """{"index":0,"delta":{"type":"arguments_delta","arguments":"{"}}""")
        assertFalse(complete(InteractionsStreamDecoder(), start, partial, end, requiresAction))
        assertFalse(complete(InteractionsStreamDecoder(), requiresAction))
        val textStart = SseEvent(event = "step.start", data =
            """{"index":0,"step":{"type":"model_output"}}""")
        val text = SseEvent(event = "step.delta", data =
            """{"index":0,"delta":{"type":"text","text":"waiting without a tool"}}""")
        assertFalse(complete(InteractionsStreamDecoder(), textStart, text, end, requiresAction))
        assertFalse(complete(InteractionsStreamDecoder(), start, end))
    }

    @Test
    fun `response failed is an error instead of a complete response`() {
        val chunks = mutableListOf<StreamChunk>()
        val decoder = ResponseApiStreamDecoder()
        val failure = runCatching {
            chunks += decoder.accept(SseEvent(data =
                """{"type":"response.failed","response":{"status":"failed","error":{"message":"failed"}}}"""
            )).chunks
        }
        assertTrue(failure.isFailure)
        assertTrue(chunks.none { it is StreamChunk.Finish })
    }
}
