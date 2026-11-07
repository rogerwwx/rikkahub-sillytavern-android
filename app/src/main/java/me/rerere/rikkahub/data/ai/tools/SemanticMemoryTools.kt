package me.rerere.rikkahub.data.ai.tools

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.memory.semantic.SemanticMemoryEntity
import me.rerere.rikkahub.data.memory.semantic.SemanticMemoryRepository
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId

fun buildAssistantMemoryTools(
    legacy: MemoryRepository,
    semantic: SemanticMemoryRepository,
    assistant: Assistant,
): List<Tool> = when {
    assistant.semanticMemory.enabled -> buildSemanticMemoryTools(semantic, assistant)
    assistant.enableMemory -> buildMemoryTools(legacy, assistant.memoryId).map { tool ->
        val execute = tool.execute
        tool.copy(execute = { args ->
            check(semantic.assistantSettings(assistant.id.toString())?.enabled != true) {
                "已切换语义记忆模式，旧记忆工具停用"
            }
            execute(args)
        })
    }
    else -> emptyList()
}

private fun buildSemanticMemoryTools(repository: SemanticMemoryRepository, assistant: Assistant): List<Tool> {
    val assistantId = assistant.id.toString()
    fun schema(properties: JsonObject, required: List<String>) = InputSchema.Obj(properties = properties, required = required)
    fun field(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }
    fun output(text: String) = listOf(UIMessagePart.Text(text))
    suspend fun requireEnabled() {
        check(repository.assistantSettings(assistantId)?.enabled == true) { "当前助手语义记忆已关闭" }
    }
    return listOf(
        Tool(
            name = "memory_semantic_search",
            description = "检索当前助手的长期记忆，返回稳定 ID、正文和版本。",
            parameters = { schema(buildJsonObject { put("query", field("string", "要查找的事实或经历")) }, listOf("query")) },
            execute = { args ->
                requireEnabled()
                val config = repository.assistantSettings(assistantId)!!
                val result = repository.recall(assistantId, args.jsonObject["query"]!!.jsonPrimitive.content, config)
                output(buildJsonArray { result.hits.forEach { hit ->
                    add(buildJsonObject {
                        put("id", hit.memory.id); put("content", hit.memory.content); put("version", hit.memory.contentVersion)
                        put("type", hit.memory.type); put("score", hit.rank.score)
                    })
                } }.toString())
            },
        ),
        Tool(
            name = "memory_semantic_save",
            description = "保存有证据、值得长期记住的一条简洁事实。编辑先检索并提供 id 和 version；不得把推测或普通寒暄存为事实。",
            parameters = { schema(buildJsonObject {
                put("content", field("string", "完整的单条记忆正文"))
                put("type", field("string", "episodic/factual/relational/preference/planned/unknown"))
                put("id", field("string", "编辑时提供已有 ID"))
                put("version", field("integer", "编辑时提供最近读取的版本"))
            }, listOf("content", "type")) },
            execute = { args ->
                requireEnabled()
                val obj = args.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                val old = id?.let { repository.get(assistantId, it) ?: error("条目已删除") }
                val entity = (old ?: SemanticMemoryEntity(assistantId = assistantId, content = "")).copy(
                    content = obj["content"]!!.jsonPrimitive.content,
                    type = obj["type"]!!.jsonPrimitive.content,
                    metadataJson = if (old == null) "{}" else JsonObject(Json.parseToJsonElement(old.metadataJson).jsonObject +
                        ("manualEdited" to JsonPrimitive(true))).toString(),
                )
                val saved = repository.save(entity, if (id == null) null else obj["version"]?.jsonPrimitive?.intOrNull ?: error("编辑必须提供版本"))
                val vectorError = try {
                    repository.embed(assistantId, listOf(saved.id), repository.assistantSettings(assistantId)!!)[saved.id]
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) { "Embedding 未完成（${error.javaClass.simpleName}）" }
                output(buildJsonObject {
                    put("id", saved.id); put("version", saved.contentVersion)
                    put("contentSaved", true); put("vectorReady", vectorError == null)
                    vectorError?.let { put("vectorError", it) }
                }.toString())
            },
        ),
        Tool(
            name = "memory_semantic_delete",
            description = "删除当前助手的一条长期记忆。必须先读取并提供 ID 和版本。",
            parameters = { schema(buildJsonObject {
                put("id", field("string", "条目 ID")); put("version", field("integer", "最近读取的版本"))
            }, listOf("id", "version")) },
            execute = { args ->
                requireEnabled()
                val obj = args.jsonObject
                val id = obj["id"]!!.jsonPrimitive.content
                repository.database.withTransaction {
                    val old = repository.get(assistantId, id) ?: error("条目已删除")
                    check(old.contentVersion == obj["version"]!!.jsonPrimitive.intOrNull) { "版本冲突" }
                    repository.dao.delete(assistantId, listOf(id))
                }
                output("已删除")
            },
        ),
    )
}
