package me.rerere.rikkahub.data.memory.semantic

import java.util.concurrent.CancellationException

data class SemanticMaintenanceItem(
    val id: String,
    val version: Int,
    val hash: String,
    val state: String = "pending",
    val error: String? = null,
    val indexedAtBefore: Long? = null,
)

data class SemanticMaintenanceJob(
    val id: String,
    val assistantId: String,
    val fingerprint: String,
    val items: List<SemanticMaintenanceItem>,
    val status: String = "pending",
    val currentBatch: Int = 0,
    val error: String? = null,
) {
    val success get() = items.count { it.state == "success" }
    val failed get() = items.count { it.state == "failed" }
    val skipped get() = items.count { it.state == "skipped" }
    val pending get() = items.count { it.state == "pending" || it.state == "running" }
    val processed get() = success + failed + skipped
    fun resume() = copy(status = "pending", error = null,
        items = items.map { if (it.state == "running") it.copy(state = "pending") else it })
    fun retryFailures() = resume().copy(items = items.map {
        if (it.state == "failed" || it.state == "running") it.copy(state = "pending", error = null) else it
    })
}

data class SemanticMaintenanceSnapshot(val version: Int, val hash: String, val valid: Boolean)

/** 持久状态和真实网络调用由适配器提供，测试与 Worker 使用同一个状态机。 */
interface SemanticMaintenanceBackend {
    suspend fun load(id: String): SemanticMaintenanceJob?
    suspend fun store(job: SemanticMaintenanceJob)
    suspend fun fingerprint(job: SemanticMaintenanceJob): String
    suspend fun inspect(job: SemanticMaintenanceJob, item: SemanticMaintenanceItem): SemanticMaintenanceSnapshot?
    suspend fun embed(job: SemanticMaintenanceJob, items: List<SemanticMaintenanceItem>): Map<String, String?>
}

class SemanticMaintenanceEngine(private val backend: SemanticMaintenanceBackend) {
    suspend fun run(id: String, shouldYield: () -> Boolean = { false }) {
        var job = backend.load(id) ?: return
        if (job.status in setOf("cancelled", "complete", "paused")) return
        job = job.resume().copy(status = "running")
        backend.store(job)
        try {
            while (job.pending > 0) {
                if (backend.load(id)?.status == "cancelled") return
                if (shouldYield()) {
                    backend.store(job.copy(status = "pending"))
                    return
                }
                val current = try { backend.fingerprint(job) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                if (current != job.fingerprint) {
                    backend.store(job.copy(status = "paused", error = "目标模型已变化或失效；恢复原模型后继续，或新建重建任务"))
                    return
                }
                val batch = job.items.filter { it.state == "pending" }.take(50)
                val changes = mutableMapOf<String, SemanticMaintenanceItem>()
                val network = mutableListOf<SemanticMaintenanceItem>()
                for (item in batch) {
                    val memory = backend.inspect(job, item)
                    changes[item.id] = when {
                        memory == null -> item.copy(state = "skipped", error = "条目已删除")
                        memory.version != item.version || memory.hash != item.hash ->
                            item.copy(state = "skipped", error = "固定快照的内容版本已变化")
                        memory.valid -> item.copy(state = "success", error = null)
                        else -> item.copy(state = "running", error = null).also { network += item }
                    }
                }
                job = job.copy(items = job.items.map { changes[it.id] ?: it }, currentBatch = job.currentBatch + 1)
                backend.store(job)
                if (network.isNotEmpty()) {
                    val results = try { backend.embed(job, network) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        network.associate { it.id to "向量生成或落库失败（${error.javaClass.simpleName}）" }
                    }
                    for (item in network) {
                        val memory = backend.inspect(job, item)
                        changes[item.id] = when {
                            memory == null -> item.copy(state = "skipped", error = "请求期间条目已删除")
                            memory.version != item.version || memory.hash != item.hash ->
                                item.copy(state = "skipped", error = "请求期间内容版本已变化")
                            results.containsKey(item.id) && results[item.id] == null && memory.valid ->
                                item.copy(state = "success", error = null)
                            else -> item.copy(state = "failed", error = results[item.id] ?: "响应缺项或向量未有效落库")
                        }
                    }
                    job = job.copy(items = job.items.map { changes[it.id] ?: it })
                }
                // 取消保留本批已落库结果和余下待办，不能被迟到的批次覆盖。
                val cancelled = backend.load(id)?.status == "cancelled"
                job = job.copy(status = if (cancelled) "cancelled" else if (job.pending == 0) "complete" else "running")
                backend.store(job)
                if (cancelled) return
            }
            backend.store(job.copy(status = "complete"))
        } catch (cancel: CancellationException) {
            val cancelled = backend.load(id)?.status == "cancelled"
            backend.store(job.resume().copy(status = if (cancelled) "cancelled" else "pending"))
            throw cancel
        }
    }
}

/** 全选替换为当前完整筛选集合，与 LazyColumn 是否已渲染无关。 */
fun semanticSelectAll(filteredIds: List<String>): Set<String> = filteredIds.toSet()
fun semanticToggleSelection(selected: Set<String>, id: String): Set<String> =
    if (id in selected) selected - id else selected + id
