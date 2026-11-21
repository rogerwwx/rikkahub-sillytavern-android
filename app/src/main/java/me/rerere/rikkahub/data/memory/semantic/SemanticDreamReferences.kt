package me.rerere.rikkahub.data.memory.semantic

/** 短引用仅属于一次请求，解析后恢复真实 ID，正文与版本号不参与映射。 */
internal class SemanticDreamReferences(
    evidence: List<DreamSource>,
    memoryIds: List<String> = emptyList(),
    checkpoint: SemanticStateCheckpoint? = null,
) {
    private class References(prefix: String, ids: List<String>) {
        private val aliases = ids.mapIndexed { index, id -> id to "$prefix${index + 1}" }.toMap()
        private val originals = aliases.entries.associate { (id, alias) -> alias to id }

        init {
            require(aliases.size == ids.size) { "请求中的引用 ID 重复" }
        }

        fun alias(id: String): String = checkNotNull(aliases[id]) { "引用不属于本次请求" }
        fun original(alias: String): String = requireNotNull(originals[alias]) { "未知或类型不匹配的短引用" }
    }

    private val evidenceReferences = References("e", evidence.map { it.evidenceId })
    private val memoryReferences = References("m", memoryIds)
    private val stateReferences = References("s", checkpoint?.entries.orEmpty().map { it.id })
    private val originalRevision = checkpoint?.revision
    val baseRevision: String? = checkpoint?.let { "r1" }

    fun evidenceId(id: String): String = evidenceReferences.alias(id)
    fun memoryId(id: String): String = memoryReferences.alias(id)
    fun stateId(id: String): String = stateReferences.alias(id)
    fun expandMemoryIds(ids: List<String>): List<String> = ids.map(memoryReferences::original)

    fun parseDraft(text: String, stateOnly: Boolean = false): SemanticDreamDraft {
        val draft = parseDreamDraft(text, baseRevision, stateOnly)
        return draft.copy(
            changes = draft.changes.map { change ->
                change.copy(evidence = change.evidence.map(evidenceReferences::original),
                    targetId = change.targetId?.let(memoryReferences::original))
            },
            state = draft.state?.let { state ->
                state.copy(baseRevision = checkNotNull(originalRevision), changes = state.changes.map { change ->
                    change.copy(evidence = change.evidence.map(evidenceReferences::original),
                        targetId = change.targetId?.let(stateReferences::original))
                })
            },
        )
    }
}
