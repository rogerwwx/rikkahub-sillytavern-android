package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.*

private val replacementJson = Json { ignoreUnknownKeys = true }
private val replacementManualFlags = listOf("manualEdited", "manualLifecycle", "manualRestored", "manualSupported")

private fun JsonObject.replacementId(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.isAutomaticReplacement(): Boolean {
    if (replacementId("origin") !in setOf("dream", "consolidation")) return false
    for (key in replacementManualFlags + "sourceWithdrawn") {
        val value = get(key) ?: continue
        val flag = value as? JsonPrimitive ?: return false
        if (flag.isString || flag.booleanOrNull == null) return false
        if (key in replacementManualFlags && flag.booleanOrNull == true) return false
    }
    for (key in listOf("supersededBy", "replaces")) {
        if (get(key) != null && get(key) != JsonNull && replacementId(key) == null) return false
    }
    return true
}

/** 不把未知导入或损坏来源当作支持；有效标记已由调用方核对当前原文。 */
private fun replacementSources(memory: SemanticMemoryEntity): List<DreamSource>? = runCatching {
    val elements = replacementJson.parseToJsonElement(memory.sourceJson) as? JsonArray ?: return null
    if (elements.isEmpty()) return null
    elements.map { element ->
        val raw = element as? JsonObject ?: return null
        for (key in listOf("valid", "isIncomplete")) {
            val value = raw[key] ?: continue
            val flag = value as? JsonPrimitive ?: return null
            if (flag.isString || flag.booleanOrNull == null) return null
        }
        val source = replacementJson.decodeFromJsonElement<DreamSource>(raw)
        if (source.conversationId != memory.conversationId || source.nodeId.isBlank() || source.messageId.isBlank() ||
            source.contentHash.isBlank() || source.position < 0 || source.start < 0 || source.end <= source.start ||
            source.role !in setOf("user", "assistant")) return null
        source
    }
}.getOrNull()

private fun replacementSupported(metadata: JsonObject, sources: List<DreamSource>): Boolean =
    if (metadata.replacementId("origin") == "consolidation") sources.all { it.valid } else sources.any { it.valid }

/** 只沿单个 supersededBy 指针检查重复 ID；环及无法核对的链不参与状态变更。 */
private fun unsafeReplacementChain(startId: String, metadata: Map<String, JsonObject?>): Boolean {
    val visited = mutableSetOf<String>()
    var id = startId
    while (true) {
        if (!visited.add(id)) return true
        val current = metadata[id] ?: return true
        val next = current["supersededBy"] ?: return false
        if (next == JsonNull) return false
        id = current.replacementId("supersededBy") ?: return true
    }
}

/**
 * 只规划双向对应的直接替代边；调用方在来源核对后的事务内应用，不重建多层历史状态。
 * sourceWithdrawn 是历史标记，恢复旧事实还要求 dormant 新事实当前确实缺少足够来源支持。
 */
fun planDreamReplacementRecovery(memories: List<SemanticMemoryEntity>, now: Long): List<DreamMutation> {
    // 重复 ID 没有唯一实体，不能通过输入顺序决定替代关系。
    val current = memories.groupBy { it.id }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    val metadata = current.mapValues { (_, memory) -> runCatching { dreamMetadata(memory) }.getOrNull() }
    return current.values.mapNotNull { old ->
        if (old.status !in setOf("active", "superseded") || old.id.isBlank() ||
            old.assistantId.isBlank() || old.conversationId.isBlank()) return@mapNotNull null
        val oldMetadata = metadata[old.id]?.takeIf { it.isAutomaticReplacement() } ?: return@mapNotNull null
        val successor = oldMetadata.replacementId("supersededBy")?.let(current::get) ?: return@mapNotNull null
        if (successor.assistantId != old.assistantId || successor.conversationId != old.conversationId)
            return@mapNotNull null
        val newMetadata = metadata[successor.id]?.takeIf { it.isAutomaticReplacement() } ?: return@mapNotNull null
        if (newMetadata.replacementId("replaces") != old.id || unsafeReplacementChain(old.id, metadata))
            return@mapNotNull null
        val oldSources = replacementSources(old) ?: return@mapNotNull null
        val newSources = replacementSources(successor) ?: return@mapNotNull null
        if (!replacementSupported(oldMetadata, oldSources) ||
            (!old.isCore && old.expiresAt != null && old.expiresAt <= now)) return@mapNotNull null
        val status = when {
            old.status == "active" && successor.status == "active" && replacementSupported(newMetadata, newSources) &&
                (successor.isCore || successor.expiresAt == null || successor.expiresAt > now) -> "superseded"
            old.status == "superseded" && successor.status == "dormant" &&
                (newMetadata["sourceWithdrawn"] as? JsonPrimitive)?.booleanOrNull == true &&
                !replacementSupported(newMetadata, newSources) -> "active"
            else -> return@mapNotNull null
        }
        DreamMutation(old, old.copy(
            status = status, updatedAt = now, contentVersion = old.contentVersion + 1,
            embeddedVersion = if (old.embeddedVersion == old.contentVersion) old.contentVersion + 1 else old.embeddedVersion,
        ))
    }
}
