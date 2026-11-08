package me.rerere.rikkahub.data.memory.semantic

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID

data class SemanticImportProgress(val id: String, val total: Int, val processed: Int, val imported: Int,
    val duplicates: Int, val conflicts: Int, val status: String, val errors: List<String>)

/** 每批数据与进度在同库事务提交；重复文件、取消和重启均不会追加同一证据。 */
class SemanticTransferManager(private val repository: SemanticMemoryRepository) {
    suspend fun export(assistantId: String, conversationId: String, ids: Set<String>? = null): String = SemanticTransferCodec.encode(
        repository.list(assistantId, conversationId).filter { ids == null || it.id in ids }.map { it.transferRecord() })

    suspend fun prepare(assistantId: String, conversationId: String, text: String): String {
        // 完整校验必须在第一次写入前完成。
        val records = SemanticTransferCodec.resolveRelationships(
            SemanticTransferCodec.remap(SemanticTransferCodec.decode(text), assistantId, conversationId),
            repository.list(assistantId, conversationId).map { it.transferRecord() })
        val id = UUID.randomUUID().toString()
        repository.dao.putJob(SemanticJobEntity(id, assistantId, KIND, buildJsonObject {
            put("archive", SemanticTransferCodec.encode(records)); put("total", records.size); put("processed", 0); put("imported", 0)
            put("duplicates", 0); put("conflicts", 0); put("status", "pending"); put("errors", JsonArray(emptyList()))
        }.toString(), conversationId = conversationId))
        return id
    }

    suspend fun jobs(assistantId: String, conversationId: String): List<SemanticImportProgress> = repository.dao.jobs(assistantId)
        .filter { it.kind == KIND && it.conversationId == conversationId }.map { progress(it) }

    suspend fun cancel(assistantId: String, conversationId: String, id: String) = changeStatus(assistantId, conversationId, id, "cancelled")
    suspend fun resume(assistantId: String, conversationId: String, id: String) = changeStatus(assistantId, conversationId, id, "pending")

    suspend fun fail(id: String, error: Exception) = withContext(NonCancellable) {
        repository.database.withTransaction {
            val entity = repository.dao.getJob(id) ?: return@withTransaction
            val p = Json.parseToJsonElement(entity.payloadJson).jsonObject
            val errors = p.getValue("errors").jsonArray + JsonPrimitive("导入批次失败（${error.javaClass.simpleName}），已提交批次保留，可恢复剩余条目")
            repository.dao.putJob(entity.copy(payloadJson = JsonObject(p + buildJsonObject {
                put("status", "failure"); put("errors", JsonArray(errors))
            }).toString(), updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun changeStatus(assistantId: String, conversationId: String, id: String, status: String) = repository.database.withTransaction {
        val entity = repository.dao.getJob(id) ?: return@withTransaction
        check(entity.assistantId == assistantId && entity.conversationId == conversationId) { "导入任务不属于当前窗口" }
        val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
        repository.dao.putJob(entity.copy(payloadJson = JsonObject(payload + ("status" to JsonPrimitive(status))).toString(),
            updatedAt = System.currentTimeMillis()))
    }

    suspend fun run(id: String, onProgress: (SemanticImportProgress) -> Unit = {}) {
        try { runBatches(id, onProgress) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) { fail(id, error); throw error }
    }

    private suspend fun runBatches(id: String, onProgress: (SemanticImportProgress) -> Unit) {
        val initial = repository.dao.getJob(id) ?: error("导入任务不存在")
        val records = SemanticTransferCodec.decode(Json.parseToJsonElement(initial.payloadJson).jsonObject
            .getValue("archive").jsonPrimitive.content)
        check(records.all { it.assistantId == initial.assistantId && it.conversationId == initial.conversationId }) {
            "导入内容与目标窗口不一致"
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val entity = repository.dao.getJob(id) ?: return
            val before = progress(entity, records.size)
            if (before.status == "cancelled" || before.processed == records.size) return
            val batch = records.drop(before.processed).take(50)
            val committed = repository.database.withTransaction {
                val latest = repository.dao.getJob(id) ?: error("导入任务不存在")
                val state = progress(latest, records.size)
                if (state.status == "cancelled") return@withTransaction state
                check(state.processed == before.processed) { "导入任务正在另一个执行器中运行" }
                val existing = repository.list(entity.assistantId, checkNotNull(entity.conversationId)).map { it.transferRecord() }
                val byId = existing.associateBy { it.id }.toMutableMap()
                val bySource = existing.mapNotNull { record -> SemanticTransferCodec.evidenceKey(record)?.let { it to record } }.toMap().toMutableMap()
                var imported = state.imported
                var duplicates = state.duplicates
                var conflicts = state.conflicts
                val errors = state.errors.toMutableList()
                for (record in batch) {
                    val old = byId[record.id]
                    val evidence = SemanticTransferCodec.evidenceKey(record)
                    val sourceMatch = evidence?.let { bySource[it] }
                    when {
                        old != null && old == record -> duplicates++
                        old != null && old.content == record.content && old.sources == record.sources -> duplicates++
                        old != null -> { conflicts++; errors += "${record.id}：稳定 ID 已存在不同内容，保留现有条目" }
                        sourceMatch != null && sourceMatch.content == record.content -> duplicates++
                        sourceMatch != null -> { conflicts++; errors += "${record.id}：同一来源已存在不同内容，保留现有证据" }
                        else -> {
                            repository.save(record.entity())
                            byId[record.id] = record
                            if (evidence != null) bySource[evidence] = record
                            imported++
                        }
                    }
                }
                val processed = state.processed + batch.size
                val payload = Json.parseToJsonElement(latest.payloadJson).jsonObject
                val next = JsonObject(payload + buildJsonObject {
                    put("processed", processed); put("imported", imported); put("duplicates", duplicates); put("conflicts", conflicts)
                    put("status", if (processed == records.size) "complete" else "pending")
                    put("errors", JsonArray(errors.map(::JsonPrimitive)))
                })
                val saved = latest.copy(payloadJson = next.toString(), updatedAt = System.currentTimeMillis())
                repository.dao.putJob(saved)
                progress(saved, records.size)
            }
            onProgress(committed)
            if (committed.status in setOf("cancelled", "complete")) return
        }
    }

    private fun progress(entity: SemanticJobEntity, total: Int? = null): SemanticImportProgress {
        val p = Json.parseToJsonElement(entity.payloadJson).jsonObject
        return SemanticImportProgress(entity.id,
            total ?: p.getValue("total").jsonPrimitive.int,
            p.getValue("processed").jsonPrimitive.int, p.getValue("imported").jsonPrimitive.int,
            p.getValue("duplicates").jsonPrimitive.int, p.getValue("conflicts").jsonPrimitive.int,
            p.getValue("status").jsonPrimitive.content, p.getValue("errors").jsonArray.map { it.jsonPrimitive.content })
    }

    companion object { const val KIND = "content_import" }
}

fun SemanticMemoryEntity.transferRecord() = SemanticTransferRecord(id, assistantId, conversationId, content, type, importance,
    confidence, isCore, status, createdAt, updatedAt, expiresAt, contentVersion,
    Json.parseToJsonElement(metadataJson).jsonObject, Json.parseToJsonElement(sourceJson).jsonArray, accessCount, lastAccessAt)

private fun SemanticTransferRecord.entity() = SemanticMemoryEntity(id = id, assistantId = assistantId, conversationId = conversationId, content = content,
    type = type, importance = importance, confidence = confidence, isCore = isCore, status = status,
    createdAt = createdAt, updatedAt = updatedAt, expiresAt = expiresAt, contentVersion = contentVersion,
    metadataJson = metadata.toString(), sourceJson = sources.toString(), accessCount = accessCount, lastAccessAt = lastAccessAt)
