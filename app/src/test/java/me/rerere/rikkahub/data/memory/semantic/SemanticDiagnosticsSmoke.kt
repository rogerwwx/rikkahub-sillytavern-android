package me.rerere.rikkahub.data.memory.semantic

fun main() = semanticDiagnosticsSmoke()

fun semanticDiagnosticsSmoke() {
    val events = listOf(
        SemanticDiagnosticEvent(assistantId = "a", operation = "embedding", purpose = "save", status = "success", durationNanos = 2_000_000),
        SemanticDiagnosticEvent(assistantId = "a", operation = "embedding", purpose = "query", status = "failure", durationNanos = 4_000_000, error = "响应校验失败"),
        SemanticDiagnosticEvent(assistantId = "a", operation = "embedding", purpose = "connection", status = "cancelled"),
        SemanticDiagnosticEvent(assistantId = "a", operation = "embedding", purpose = "rebuild", status = "running"),
        SemanticDiagnosticEvent(assistantId = "a", operation = "embedding", purpose = "query", status = "interrupted", durationNanos = 99_000_000),
        SemanticDiagnosticEvent(assistantId = "a", operation = "storage", purpose = "save", status = "conflict"),
        SemanticDiagnosticEvent(assistantId = "a", operation = "recall", purpose = "query", status = "empty", durationNanos = 8_000_000,
            detailJson = """{"lexicalCandidates":2,"vectorCandidates":1,"deduplicatedCandidates":2,"recalled":0,"injected":0,"invalid_vector":3,"degraded":"向量路失败"}"""),
        SemanticDiagnosticEvent(assistantId = "b", operation = "recall", purpose = "query", status = "success", durationNanos = 2_000_000,
            detailJson = """{"recalled":2,"injected":1,"changed_during_recall":1}"""),
    )
    val assistant = semanticDiagnostics(events.filter { it.assistantId == "a" })
    check(assistant.embedding.total == 5 && assistant.embedding.success == 1 && assistant.embedding.failure == 1)
    check(assistant.embedding.cancelled == 1 && assistant.embedding.running == 1 && assistant.embedding.averageMillis == 3.0)
    check(assistant.embedding.interrupted == 1)
    check(assistant.storageConflict == 1 && assistant.recall.empty == 1 && assistant.recall.averageMillis == 8.0)
    check(assistant.filters["invalid_vector"] == 3L && assistant.degraded == 1 && assistant.injected == 0L)
    val whole = semanticDiagnostics(events)
    check(whole.recalled == 2L && whole.injected == 1L && whole.filters["changed_during_recall"] == 1L)
    val rawOnly = events.last().copy(detailJson = """{"hits":[{"id":"m1","version":1,"type":"factual","lexicalScore":-2.0,"vectorScore":0.2,"score":0.01,"vectorRanked":false}]}""")
    val line = semanticRecallScoreLines(rawOnly).single()
    check(line.contains("余弦 0.2") && line.contains("向量参与 否"))
    println("PASS diagnostics: API/storage separation, purpose, cancellation/running, real durations, filters, assistant/library scope")
}
