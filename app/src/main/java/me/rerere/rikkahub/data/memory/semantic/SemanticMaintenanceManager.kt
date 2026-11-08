package me.rerere.rikkahub.data.memory.semantic

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

/** 固定快照与逐项进度均写入 semantic_job；Worker 不持有易失的任务清单。 */
class SemanticMaintenanceManager(private val repository: SemanticMemoryRepository) : SemanticMaintenanceBackend {
    private val json = Json

    suspend fun create(assistantId: String, conversationId: String, ids: List<String>, settings: SemanticMemorySettings): SemanticMaintenanceJob {
        val config = settings.normalized()
        val base = repository.fingerprint(config)
        val requested = ids.toSet()
        val entries = repository.list(assistantId, conversationId).filter { it.id in requested }
        require(entries.isNotEmpty()) { "没有可重建的记忆" }
        // 新任务探测当前服务的默认维度；旧任务恢复继续使用已持久化的维度快照。
        val dimension = config.dimensions ?: repository.testConnection(config, assistantId)
        val job = SemanticMaintenanceJob(UUID.randomUUID().toString(), assistantId, conversationId, base,
            entries.map { SemanticMaintenanceItem(it.id, it.contentVersion, it.contentHash, indexedAtBefore = it.indexedAt) })
        repository.database.withTransaction {
            check(repository.dao.jobs(assistantId).filter { it.kind == KIND && it.conversationId == conversationId }.none {
                decode(it).status in setOf("pending", "running")
            }) { "当前窗口已有运行中的重建任务，请先取消或等待完成" }
            repository.dao.putJob(SemanticJobEntity(job.id, assistantId, KIND,
                buildJsonObject {
                    put("settings", json.encodeToJsonElement(config))
                    put("dimension", dimension)
                    put("job", encodeJob(job))
                }.toString(), conversationId = conversationId))
        }
        return job
    }

    suspend fun jobs(assistantId: String, conversationId: String) = repository.dao.jobs(assistantId)
        .filter { it.kind == KIND && it.conversationId == conversationId }.map(::decode)

    suspend fun resume(assistantId: String, conversationId: String, id: String, retryFailures: Boolean = false): SemanticMaintenanceJob = repository.database.withTransaction {
        val entity = repository.dao.getJob(id) ?: error("维护任务不存在")
        check(entity.assistantId == assistantId && entity.conversationId == conversationId) { "维护任务不属于当前窗口" }
        val job = decode(entity)
        check(repository.dao.jobs(job.assistantId).filter {
            it.kind == KIND && it.id != id && it.conversationId == conversationId
        }.none {
            decode(it).status in setOf("pending", "running")
        }) { "当前窗口已有其他运行中的重建任务" }
        val resumed = if (retryFailures) job.retryFailures() else job.resume()
        put(entity, resumed)
        resumed
    }

    suspend fun cancel(assistantId: String, conversationId: String, id: String) = repository.database.withTransaction {
        repository.dao.getJob(id)?.let {
            check(it.assistantId == assistantId && it.conversationId == conversationId) { "维护任务不属于当前窗口" }
            put(it, decode(it).copy(status = "cancelled"))
        }
    }

    override suspend fun load(id: String): SemanticMaintenanceJob? = withContext(NonCancellable) {
        repository.dao.getJob(id)?.takeIf { it.kind == KIND }?.let(::decode)
    }

    override suspend fun store(job: SemanticMaintenanceJob) = withContext(NonCancellable) {
        repository.database.withTransaction {
            val entity = repository.dao.getJob(job.id) ?: return@withTransaction
            val current = decode(entity)
            put(entity, if (current.status == "cancelled") job.copy(status = "cancelled") else job)
        }
    }

    override suspend fun fingerprint(job: SemanticMaintenanceJob): String {
        val live = repository.assistantSettings(job.assistantId) ?: error("助手已删除")
        return repository.fingerprint(live)
    }

    override suspend fun inspect(job: SemanticMaintenanceJob, item: SemanticMaintenanceItem): SemanticMaintenanceSnapshot? {
        val memory = repository.get(job.assistantId, job.conversationId, item.id) ?: return null
        val payload = payload(job.id)
        val dimension = payload.getValue("dimension").jsonPrimitive.int
        return SemanticMaintenanceSnapshot(memory.contentVersion, memory.contentHash,
            repository.validVector(memory, job.fingerprint, dimension) && memory.indexedAt != item.indexedAtBefore)
    }

    override suspend fun embed(job: SemanticMaintenanceJob, items: List<SemanticMaintenanceItem>): Map<String, String?> {
        val payload = payload(job.id)
        val settings = json.decodeFromJsonElement<SemanticMemorySettings>(payload.getValue("settings"))
        return repository.embed(job.assistantId, job.conversationId, items.map { it.id }, settings, "rebuild",
            expectedVersions = items.associate { it.id to it.version }, targetFingerprint = job.fingerprint,
            expectedDimensions = payload.getValue("dimension").jsonPrimitive.int, force = true)
    }

    private suspend fun payload(id: String) = json.parseToJsonElement(
        (repository.dao.getJob(id) ?: error("维护任务不存在")).payloadJson).jsonObject

    private suspend fun put(entity: SemanticJobEntity, job: SemanticMaintenanceJob) {
        val old = json.parseToJsonElement(entity.payloadJson).jsonObject
        repository.dao.putJob(entity.copy(payloadJson = JsonObject(old + ("job" to encodeJob(job))).toString(),
            updatedAt = System.currentTimeMillis()))
    }

    companion object {
        const val KIND = "vector_rebuild"
        fun decode(entity: SemanticJobEntity): SemanticMaintenanceJob {
            val value = Json.parseToJsonElement(entity.payloadJson).jsonObject.getValue("job").jsonObject
            return SemanticMaintenanceJob(entity.id, entity.assistantId, checkNotNull(entity.conversationId),
                value.getValue("fingerprint").jsonPrimitive.content,
                value.getValue("items").jsonArray.map { raw ->
                    val item = raw.jsonObject
                    SemanticMaintenanceItem(item.getValue("id").jsonPrimitive.content, item.getValue("version").jsonPrimitive.int,
                        item.getValue("hash").jsonPrimitive.content, item.getValue("state").jsonPrimitive.content,
                        item["error"]?.jsonPrimitive?.contentOrNull, item["indexedAtBefore"]?.jsonPrimitive?.longOrNull)
                }, value.getValue("status").jsonPrimitive.content, value.getValue("currentBatch").jsonPrimitive.int,
                value["error"]?.jsonPrimitive?.contentOrNull)
        }

        private fun encodeJob(job: SemanticMaintenanceJob) = buildJsonObject {
            put("fingerprint", job.fingerprint); put("status", job.status); put("currentBatch", job.currentBatch)
            put("error", job.error?.let(::JsonPrimitive) ?: JsonNull)
            put("items", JsonArray(job.items.map { item -> buildJsonObject {
                put("id", item.id); put("version", item.version); put("hash", item.hash); put("state", item.state)
                put("error", item.error?.let(::JsonPrimitive) ?: JsonNull)
                put("indexedAtBefore", item.indexedAtBefore?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }
    }
}
