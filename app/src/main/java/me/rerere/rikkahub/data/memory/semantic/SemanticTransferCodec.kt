package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.*
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 独立内容格式不导出向量和凭证；所有导入向量均由当前模型重新生成。 */
data class SemanticTransferRecord(
    val id: String,
    val assistantId: String,
    val conversationId: String,
    val content: String,
    val type: String,
    val importance: Double,
    val confidence: Double,
    val isCore: Boolean,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long?,
    val contentVersion: Int,
    val metadata: JsonObject,
    val sources: JsonArray,
    val accessCount: Long = 0,
    val lastAccessAt: Long? = null,
)

object SemanticTransferCodec {
    private val json = Json { prettyPrint = true }
    private val types = setOf("episodic", "factual", "relational", "preference", "planned", "unknown")
    private val statuses = setOf("active", "expired", "dormant", "archived", "superseded", "forgotten")
    private val relationKeys = setOf("parentId", "parentIds", "childId", "childIds", "mergedFrom", "mergedInto",
        "supersedes", "supersededBy", "replacedBy", "replaces", "relatedIds", "memoryId", "memoryIds",
        "parent_id", "parent_ids", "child_id", "child_ids", "merged_from", "merged_into", "superseded_by",
        "replaced_by", "related_ids", "memory_id", "memory_ids", "parents", "children",
        "consolidatedFrom", "consolidatedInto")

    fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun encode(records: List<SemanticTransferRecord>): String {
        validate(records)
        return json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("schemaVersion", 2); put("format", "huadeng-semantic-memory")
            put("vectorCache", false)
            put("memories", JsonArray(records.map(::recordJson)))
        })
    }

    fun decode(text: String): List<SemanticTransferRecord> {
        require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024 * 1024) { "导入文件超过 64 MiB" }
        val root = json.parseToJsonElement(text).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 2 &&
            root.getValue("format").jsonPrimitive.content == "huadeng-semantic-memory") { "不支持的语义记忆格式或版本" }
        val records = root.getValue("memories").jsonArray.map { element ->
            val value = element.jsonObject
            fun text(key: String) = value.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            fun nullableLong(key: String) = value[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
            val content = text("content")
            require(text("contentHash") == semanticContentHash(content)) { "记忆正文哈希不匹配" }
            SemanticTransferRecord(text("id"), text("assistantId"), text("conversationId"), content, text("type"),
                value.getValue("importance").jsonPrimitive.double, value.getValue("confidence").jsonPrimitive.double,
                value.getValue("isCore").jsonPrimitive.boolean, text("status"),
                value.getValue("createdAt").jsonPrimitive.long, value.getValue("updatedAt").jsonPrimitive.long,
                nullableLong("expiresAt"), value.getValue("contentVersion").jsonPrimitive.int,
                value.getValue("metadata").jsonObject, value.getValue("sources").jsonArray,
                value["accessCount"]?.jsonPrimitive?.long ?: 0, nullableLong("lastAccessAt"))
        }
        validate(records)
        return records
    }

    /** 跨窗口导入使用确定性 ID；原始聊天来源保留，导入内容由用户明确支持。 */
    fun remap(records: List<SemanticTransferRecord>, targetAssistant: String, targetConversation: String): List<SemanticTransferRecord> {
        fun sameWindow(record: SemanticTransferRecord) =
            record.assistantId == targetAssistant && record.conversationId == targetConversation
        val ids = records.associate { record -> record.id to if (sameWindow(record)) record.id else
            UUID.nameUUIDFromBytes("semantic-import:$targetAssistant:$targetConversation:${record.assistantId}:${record.conversationId}:${record.id}"
                .toByteArray(Charsets.UTF_8)).toString() }
        return records.map { record -> record.copy(id = ids.getValue(record.id), assistantId = targetAssistant,
            conversationId = targetConversation,
            metadata = JsonObject(mapRelationships(record.metadata, ids).jsonObject +
                if (sameWindow(record)) emptyMap() else mapOf("manualSupported" to JsonPrimitive(true)))) }
    }

    fun evidenceKey(record: SemanticTransferRecord): String? = record.sources.takeIf { it.isNotEmpty() }?.let {
        semanticContentHash(JsonArray(it.map(::canonicalJson).sortedBy { source -> source.toString() }).toString())
    }

    /** 来源重复被跳过时，关系仍指向实际保留的旧条目，不留下被跳过的新 ID。 */
    fun resolveRelationships(records: List<SemanticTransferRecord>, existing: List<SemanticTransferRecord>): List<SemanticTransferRecord> {
        val existingIds = existing.map { it.id }.toSet()
        val sourceIds = existing.mapNotNull { record -> evidenceKey(record)?.let { it to record.id } }.toMap().toMutableMap()
        val aliases = records.associate { record -> record.id to when {
            record.id in existingIds -> record.id
            else -> evidenceKey(record)?.let { sourceIds.getOrPut(it) { record.id } } ?: record.id
        } }
        return records.map { it.copy(metadata = mapRelationships(it.metadata, aliases).jsonObject) }
    }

    private fun mapRelationships(value: JsonElement, ids: Map<String, String>, relation: Boolean = false): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) -> mapRelationships(child, ids,
            key in relationKeys || (relation && key == "id")) })
        is JsonArray -> JsonArray(value.map { mapRelationships(it, ids, relation) })
        is JsonPrimitive -> if (relation && value.isString) JsonPrimitive(ids[value.content] ?: value.content) else value
    }

    private fun canonicalJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalJson(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalJson))
        is JsonPrimitive -> value
    }

    private fun validate(records: List<SemanticTransferRecord>) {
        require(records.size <= 100_000 && records.map { it.id }.distinct().size == records.size) { "条目过多或存在重复 ID" }
        records.forEach { record ->
            require(record.id.isNotBlank() && record.id.length <= 256 && record.assistantId.isNotBlank() &&
                record.conversationId.isNotBlank()) { "条目 ID 或对话窗口无效" }
            require(record.content.isNotBlank() && record.content.length <= 1_000_000) { "记忆正文为空或过长" }
            require(record.type in types && record.status in statuses) { "记忆类型或生命周期状态无效" }
            require(record.importance.isFinite() && record.importance in 0.0..1.0 &&
                record.confidence.isFinite() && record.confidence in 0.0..1.0) { "重要度或置信度无效" }
            require(record.contentVersion > 0 && record.createdAt >= 0 && record.updatedAt >= 0 &&
                (record.expiresAt == null || record.expiresAt >= 0) && record.accessCount >= 0 &&
                (record.lastAccessAt == null || record.lastAccessAt >= 0)) { "版本、时间或访问统计无效" }
        }
    }

    private fun recordJson(record: SemanticTransferRecord) = buildJsonObject {
        put("id", record.id); put("assistantId", record.assistantId); put("conversationId", record.conversationId)
        put("content", record.content)
        put("contentHash", semanticContentHash(record.content)); put("type", record.type)
        put("importance", record.importance); put("confidence", record.confidence); put("isCore", record.isCore)
        put("status", record.status); put("createdAt", record.createdAt); put("updatedAt", record.updatedAt)
        put("expiresAt", record.expiresAt?.let(::JsonPrimitive) ?: JsonNull)
        put("contentVersion", record.contentVersion); put("metadata", record.metadata); put("sources", record.sources)
        put("accessCount", record.accessCount); put("lastAccessAt", record.lastAccessAt?.let(::JsonPrimitive) ?: JsonNull)
    }
}
