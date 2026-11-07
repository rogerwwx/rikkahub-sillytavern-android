package me.rerere.rikkahub.data.memory.semantic

import kotlinx.coroutines.runBlocking
import org.junit.Test

class SemanticJobPayloadTest {
    @Test fun smallPayloadDoesNotReadSlices() = runBlocking {
        val payload = "{\"status\":\"pending\"}"
        val header = SemanticJobHeader("job", "assistant", "content_import", 1L, payload.length, payload)
        check(readSemanticJobPayload(header) { _, _ -> error("unexpected slice") } == payload)
    }

    @Test fun largePayloadReadsBoundedSlicesAndPreservesUnicode() = runBlocking {
        val payload = "{\"archive\":\"" + "\u4e2d\u6587\uD83D\uDE00".repeat(120_000) + "\"}"
        val length = payload.codePointCount(0, payload.length)
        val header = SemanticJobHeader("job", "assistant", "content_import", 1L, length, null)
        val requests = mutableListOf<Pair<Int, Int>>()
        val result = readSemanticJobPayload(header) { offset, count ->
            check(count in 1..16_384)
            requests += offset to count
            val start = payload.offsetByCodePoints(0, offset - 1)
            val end = payload.offsetByCodePoints(start, count)
            payload.substring(start, end).also { check(it.toByteArray(Charsets.UTF_8).size <= 65_536) }
        }
        check(result == payload)
        check(requests.size > 1 && requests.first().first == 1)
        check(requests.sumOf { it.second } == length)
        check(requests.zipWithNext().all { (a, b) -> a.first + a.second == b.first })
    }

    @Test fun missingOrTruncatedSliceFailsInsteadOfReturningPartialJson() = runBlocking {
        val header = SemanticJobHeader("job", "assistant", "content_import", 1L, 20_000, null)
        check(runCatching { readSemanticJobPayload(header) { _, _ -> null } }.isFailure)
        check(runCatching { readSemanticJobPayload(header) { _, count -> "x".repeat(count - 1) } }.isFailure)
    }

    @Test fun emptyPayloadAndExactChunkBoundaryAreSupported() = runBlocking {
        val empty = SemanticJobHeader("job", "assistant", "dream_cursor", 1L, 0, "")
        check(readSemanticJobPayload(empty) { _, _ -> error("unexpected slice") }.isEmpty())
        val exact = empty.copy(payloadLength = 16_384, inlinePayload = null)
        var calls = 0
        check(readSemanticJobPayload(exact) { start, count ->
            calls++
            check(start == 1 && count == 16_384)
            "x".repeat(count)
        } == "x".repeat(16_384))
        check(calls == 1)
    }
}
