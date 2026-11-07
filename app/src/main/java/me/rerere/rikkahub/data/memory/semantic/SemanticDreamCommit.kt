package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class DreamMutation(val before: SemanticMemoryEntity?, val after: SemanticMemoryEntity)

private val sourceCodec = Json { ignoreUnknownKeys = true }

fun dreamSources(memory: SemanticMemoryEntity): List<DreamSource> =
    sourceCodec.decodeFromString<List<JsonElement>>(memory.sourceJson).mapNotNull { source ->
        // 独立导入保留的未知来源不冒充自动证据，仍保留原 JSON。
        if ((source as? JsonObject)?.get("conversationId") == null) null
        else runCatching { sourceCodec.decodeFromJsonElement<DreamSource>(source) }.getOrNull()
    }

private fun addSources(old: String, evidence: List<DreamSource>): String {
    val existing = sourceCodec.parseToJsonElement(old).jsonArray
    val known = existing.mapNotNull { runCatching { sourceCodec.decodeFromJsonElement<DreamSource>(it).evidenceId }.getOrNull() }.toSet()
    return JsonArray(existing + evidence.filter { it.evidenceId !in known }.map { sourceCodec.encodeToJsonElement(it) }).toString()
}

fun dreamMetadata(memory: SemanticMemoryEntity): JsonObject = sourceCodec.parseToJsonElement(memory.metadataJson).jsonObject

private fun metadata(old: String = "{}", vararg changes: Pair<String, JsonElement>): String =
    JsonObject(sourceCodec.parseToJsonElement(old).jsonObject + changes.toMap()).toString()

/** 只规划；所有版本和来源校验由调用方在事务内再次执行。 */
fun planDreamChanges(
    assistantId: String, runId: String, draft: SemanticDreamDraft, window: DreamWindow,
    memories: List<SemanticMemoryEntity>, settings: SemanticDreamSettings, now: Long,
): List<DreamMutation> {
    val originals = memories.associateBy { it.id }
    val planned = originals.toMutableMap()
    val targets = mutableSetOf<String>()
    val evidenceMap = window.evidence.associateBy { it.evidenceId }
    draft.changes.forEachIndexed { index, change ->
        val evidence = change.evidence.map { evidenceMap[it] ?: error("引用了理解材料或未知来源") }
        require(change.scope != "user" || evidence.any { it.role == "user" }) { "现实用户事实必须有用户原始证据" }
        val content = change.content?.trim()
        val target = change.targetId?.let { id ->
            require(targets.add(id)) { "同一草稿重复修改目标" }
            val old = originals[id] ?: error("目标记忆不存在")
            require(old.assistantId == assistantId && old.contentVersion == change.expectedVersion) { "目标记忆版本已变化" }
            require(old.status == "active" && (old.expiresAt == null || old.expiresAt > now || old.isCore)) { "目标记忆已退出当前状态" }
            require(dreamMetadata(old)["scope"]?.jsonPrimitive?.contentOrNull.orEmpty().let { it.isEmpty() || it == change.scope }) { "不能跨适用范围替代事实" }
            old
        }
        val duplicate = if (change.operation == "add") planned.values.firstOrNull {
            it.status == "active" && it.content == content && dreamMetadata(it)["scope"]?.jsonPrimitive?.contentOrNull == change.scope
        } else null
        val old = target ?: duplicate
        val knownSources = old?.let(::dreamSources).orEmpty()
        val newEvidence = evidence.filter { source -> knownSources.none {
            if (change.operation == "replace") it.evidenceId == source.evidenceId
            else it.conversationId == source.conversationId && it.nodeId == source.nodeId &&
                it.messageId == source.messageId && it.contentHash == source.contentHash
        } }
        if (newEvidence.isEmpty()) return@forEachIndexed
        val evidenceAt = newEvidence.maxOf { it.createdAt }
        if (change.operation == "replace" && target != null) {
            val latestEvidence = dreamSources(target).filter { it.valid }.maxOfOrNull { it.createdAt }
            require(latestEvidence == null || evidenceAt >= latestEvidence) { "较早证据不能替代较新的当前事实" }
        }
        val ttl = SEMANTIC_MEMORY_TYPES.associateWith(settings::ttlDays)
        if (change.operation == "reinforce" || duplicate != null) {
            val current = checkNotNull(old)
            planned[current.id] = current.copy(
                sourceJson = addSources(current.sourceJson, newEvidence),
                expiresAt = dreamExpiry(current.type, current.isCore, maxOf(evidenceAt, dreamSources(current).maxOfOrNull { it.createdAt } ?: evidenceAt), ttl),
                updatedAt = now, contentVersion = current.contentVersion + 1,
                embeddedVersion = if (current.embeddedVersion == current.contentVersion) current.contentVersion + 1 else current.embeddedVersion,
                metadataJson = metadata(current.metadataJson, "reinforcementCount" to JsonPrimitive(
                    (dreamMetadata(current)["reinforcementCount"]?.jsonPrimitive?.intOrNull ?: 0) + 1)),
            )
        } else {
            val id = "dream:" + dreamHash("$assistantId:$runId:$index:${content.orEmpty()}")
            require(id !in planned) { "运行结果已经提交" }
            if (target != null) {
                require(!target.isCore || !settings.protectCore) { "核心事实受保护，请手动核对后编辑" }
                planned[target.id] = target.copy(status = "superseded", updatedAt = now,
                    contentVersion = target.contentVersion + 1,
                    embeddedVersion = if (target.embeddedVersion == target.contentVersion) target.contentVersion + 1 else target.embeddedVersion,
                    metadataJson = metadata(target.metadataJson, "supersededBy" to JsonPrimitive(id)))
            }
            val isCore = target?.isCore == true
            val expiry = dreamExpiry(change.type, isCore, evidenceAt, ttl)
            planned[id] = SemanticMemoryEntity(id = id, assistantId = assistantId, content = checkNotNull(content),
                type = change.type, importance = change.importance, isCore = isCore,
                expiresAt = expiry, status = if (expiry != null && expiry <= now) "expired" else "active", createdAt = now, updatedAt = now,
                sourceJson = addSources("[]", newEvidence), metadataJson = metadata("{}",
                    "origin" to JsonPrimitive("dream"), "scope" to JsonPrimitive(change.scope), "runId" to JsonPrimitive(runId),
                    "replaces" to (target?.id?.let(::JsonPrimitive) ?: JsonNull)))
        }
    }
    return planned.values.filter { it != originals[it.id] }.map { DreamMutation(originals[it.id], it) }
}

fun withdrawDreamSources(memory: SemanticMemoryEntity, invalidIds: Set<String>, now: Long): SemanticMemoryEntity {
    val sources = sourceCodec.parseToJsonElement(memory.sourceJson).jsonArray
    val next = sources.map { element ->
        val source = runCatching { sourceCodec.decodeFromJsonElement<DreamSource>(element) }.getOrNull()
        if (source != null && source.evidenceId in invalidIds) JsonObject(element.jsonObject + ("valid" to JsonPrimitive(false))) else element
    }
    if (next == sources) return memory
    val supported = next.any { element ->
        val source = runCatching { sourceCodec.decodeFromJsonElement<DreamSource>(element) }.getOrNull()
        source == null || source.valid
    }
    val automatic = dreamMetadata(memory)["origin"]?.jsonPrimitive?.contentOrNull in setOf("dream", "consolidation") &&
        dreamMetadata(memory)["manualRestored"]?.jsonPrimitive?.booleanOrNull != true &&
        dreamMetadata(memory)["manualEdited"]?.jsonPrimitive?.booleanOrNull != true &&
        dreamMetadata(memory)["manualSupported"]?.jsonPrimitive?.booleanOrNull != true &&
        dreamMetadata(memory)["manualLifecycle"]?.jsonPrimitive?.booleanOrNull != true
    return memory.copy(sourceJson = JsonArray(next).toString(), updatedAt = now,
        contentVersion = memory.contentVersion + 1,
        embeddedVersion = if (memory.embeddedVersion == memory.contentVersion) memory.contentVersion + 1 else memory.embeddedVersion,
        status = if (!supported && automatic && !memory.isCore && memory.status == "active") "dormant" else memory.status,
        metadataJson = metadata(memory.metadataJson, "sourceWithdrawn" to JsonPrimitive(true)))
}

/** 原选中来源恢复时复用原证据，不计强化、不延长 TTL。 */
fun restoreDreamSources(memory: SemanticMemoryEntity, restoredIds: Set<String>, now: Long): SemanticMemoryEntity {
    val sources = sourceCodec.parseToJsonElement(memory.sourceJson).jsonArray
    val next = sources.map { element ->
        val source = runCatching { sourceCodec.decodeFromJsonElement<DreamSource>(element) }.getOrNull()
        if (source != null && !source.valid && source.evidenceId in restoredIds) JsonObject(element.jsonObject + ("valid" to JsonPrimitive(true))) else element
    }
    if (next == sources) return memory
    val canResume = memory.status == "dormant" && dreamMetadata(memory)["sourceWithdrawn"]?.jsonPrimitive?.booleanOrNull == true &&
        dreamMetadata(memory)["origin"]?.jsonPrimitive?.contentOrNull in setOf("dream", "consolidation") &&
        listOf("manualLifecycle", "manualEdited", "manualRestored", "manualSupported").none {
            dreamMetadata(memory)[it]?.jsonPrimitive?.booleanOrNull == true
        } &&
        (memory.isCore || memory.expiresAt == null || memory.expiresAt > now)
    return memory.copy(sourceJson = JsonArray(next).toString(), updatedAt = now, contentVersion = memory.contentVersion + 1,
        embeddedVersion = if (memory.embeddedVersion == memory.contentVersion) memory.contentVersion + 1 else memory.embeddedVersion,
        status = if (canResume) "active" else memory.status)
}
