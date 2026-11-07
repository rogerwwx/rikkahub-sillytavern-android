package me.rerere.rikkahub.data.memory.semantic

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** 同一维护状态机的可控后端回放；不替代 Android/Room/真实端点验收。 */
fun main() = semanticMaintenanceSmoke()

fun semanticMaintenanceSmoke() {
    fun run(block: suspend () -> Unit) {
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { result.getOrThrow() }
        })
    }
    run {
        val backend = MaintenanceFixture(123)
        SemanticMaintenanceEngine(backend).run(backend.job.id)
        check(backend.batches.map { it.size } == listOf(50, 50, 23))
        check(backend.job.success == 123 && backend.job.pending == 0)

        val yielded = MaintenanceFixture(123)
        SemanticMaintenanceEngine(yielded).run(yielded.job.id) { yielded.batches.isNotEmpty() }
        check(yielded.job.status == "pending" && yielded.job.success == 50 && yielded.job.pending == 73)
        SemanticMaintenanceEngine(yielded).run(yielded.job.id)
        check(yielded.batches.map { it.size } == listOf(50, 50, 23))

        val interrupted = MaintenanceFixture(123)
        interrupted.interruptAfterNetwork = true
        runCatching { SemanticMaintenanceEngine(interrupted).run(interrupted.job.id) }
        check(interrupted.vectors.size == 50)
        interrupted.interruptAfterNetwork = false
        SemanticMaintenanceEngine(interrupted).run(interrupted.job.id)
        check(interrupted.batches.map { it.size } == listOf(50, 50, 23))
        check(interrupted.job.success == 123)

        val changed = MaintenanceFixture(123)
        changed.deleted += "1"
        changed.versions["2"] = 2
        changed.fail += "3"
        SemanticMaintenanceEngine(changed).run(changed.job.id)
        check(changed.job.skipped == 2 && changed.job.failed == 1 && changed.job.success == 120)
        changed.fail.clear()
        changed.job = changed.job.retryFailures()
        SemanticMaintenanceEngine(changed).run(changed.job.id)
        check(changed.batches.last() == listOf("3") && changed.job.success == 121)

        val cancelled = MaintenanceFixture(123)
        cancelled.cancelAfterBatch = true
        SemanticMaintenanceEngine(cancelled).run(cancelled.job.id)
        check(cancelled.job.status == "cancelled" && cancelled.job.success == 50 && cancelled.job.pending == 73)
        cancelled.cancelAfterBatch = false
        cancelled.job = cancelled.job.resume()
        SemanticMaintenanceEngine(cancelled).run(cancelled.job.id)
        check(cancelled.job.success == 123)

        val model = MaintenanceFixture(2)
        model.liveFingerprint = "changed"
        SemanticMaintenanceEngine(model).run(model.job.id)
        check(model.job.status == "paused" && model.batches.isEmpty())
        model.liveFingerprint = "model"
        model.job = model.job.resume()
        SemanticMaintenanceEngine(model).run(model.job.id)
        check(model.job.success == 2)

        check(semanticSelectAll(listOf("1", "2")) == setOf("1", "2"))
        check(semanticToggleSelection(setOf("1"), "1").isEmpty())
    }
    println("PASS maintenance: batches, interruption, retry, edit/delete, cancellation, model, selection")
}

private class MaintenanceFixture(count: Int) : SemanticMaintenanceBackend {
    var job = SemanticMaintenanceJob("job", "assistant", "model", (1..count).map {
        SemanticMaintenanceItem(it.toString(), 1, "hash")
    })
    val vectors = mutableSetOf<String>()
    val versions = mutableMapOf<String, Int>()
    val deleted = mutableSetOf<String>()
    val fail = mutableSetOf<String>()
    val batches = mutableListOf<List<String>>()
    var liveFingerprint = "model"
    var interruptAfterNetwork = false
    var cancelAfterBatch = false
    override suspend fun load(id: String) = job
    override suspend fun store(job: SemanticMaintenanceJob) { this.job = job }
    override suspend fun fingerprint(job: SemanticMaintenanceJob) = liveFingerprint
    override suspend fun inspect(job: SemanticMaintenanceJob, item: SemanticMaintenanceItem) =
        if (item.id in deleted) null else SemanticMaintenanceSnapshot(
            versions[item.id] ?: 1, "hash", item.id in vectors,
        )
    override suspend fun embed(job: SemanticMaintenanceJob, items: List<SemanticMaintenanceItem>): Map<String, String?> {
        batches += items.map { it.id }
        val result = items.associate { it.id to if (it.id in fail) "可控网络失败" else null }
        vectors += items.filter { result[it.id] == null }.map { it.id }
        if (cancelAfterBatch) this.job = this.job.copy(status = "cancelled")
        if (interruptAfterNetwork) throw java.util.concurrent.CancellationException("强杀模拟")
        return result
    }
}
