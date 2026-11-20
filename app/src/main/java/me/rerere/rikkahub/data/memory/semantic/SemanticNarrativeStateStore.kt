package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString

/**
 * 同一作用域按覆盖数倒序逐个读取，命中最长有效前缀后停止，不物化全部历史快照。
 * 单份最多 200 格、60,000 字符；每轮完整快照累计占磁盘，不自动删除用户历史。
 */
class SemanticNarrativeStateStore internal constructor(
    private val readHeaders: suspend (String) -> List<SemanticJobHeader>,
    private val readJob: suspend (String) -> SemanticJobEntity?,
    private val writeJob: suspend (SemanticJobEntity) -> Unit,
) {
    constructor(repository: SemanticMemoryRepository) : this(
        readHeaders = { repository.dao.jobHeaders(it) },
        readJob = { repository.dao.getJob(it) },
        writeJob = { repository.dao.putJob(it) },
    )

    suspend fun read(assistantId: String, conversationId: String, messages: List<DreamMessage>): SemanticStateView {
        val empty = emptySemanticState(assistantId, conversationId)
        val headers = readHeaders(assistantId).filter {
            it.assistantId == assistantId && it.conversationId == conversationId && it.kind == "narrative_state"
        }.map { it to stateJobCoverage(it.id, assistantId, conversationId) }
            .filter { it.second <= messages.size }
            .sortedWith(compareByDescending<Pair<SemanticJobHeader, Int>> { it.second }
                .thenByDescending { it.first.updatedAt }.thenBy { it.first.id })
        for ((header, coveredCount) in headers) {
            val job = readJob(header.id) ?: throw IllegalArgumentException("状态检查点任务内容缺失：${header.id}")
            require(job.id == header.id && job.assistantId == assistantId &&
                job.conversationId == conversationId && job.kind == "narrative_state") { "状态检查点任务作用域或类型不匹配" }
            val checkpoint = try {
                require(job.payloadJson.length <= 60_000) { "完整状态超过 60,000 字符" }
                semanticStateJson.decodeFromString<SemanticStateCheckpoint>(job.payloadJson)
                    .also(::validateSemanticStateCheckpoint)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("状态检查点载荷损坏：${error.message}", error)
            }
            require(checkpoint.assistantId == assistantId && checkpoint.conversationId == conversationId &&
                checkpoint.coveredCount == coveredCount && job.id == stateJobId(checkpoint)) {
                "状态检查点载荷作用域或任务身份不匹配"
            }
            if (semanticStateMatches(checkpoint, messages)) {
                return SemanticStateView(checkpoint, messages, messages.size - checkpoint.coveredCount)
            }
        }
        return SemanticStateView(empty, messages, messages.size)
    }

    /** 调用方持有来源锁并在现有 Room 事务内调用；这里不装配管理器或单独提交事务。 */
    suspend fun put(checkpoint: SemanticStateCheckpoint) {
        validateSemanticStateCheckpoint(checkpoint)
        writeJob(SemanticJobEntity(
            id = stateJobId(checkpoint), assistantId = checkpoint.assistantId, kind = "narrative_state",
            payloadJson = semanticStateJson.encodeToString(checkpoint), conversationId = checkpoint.conversationId,
        ))
    }
}

private fun stateJobScope(assistantId: String, conversationId: String) =
    dreamHash("${assistantId.length}:$assistantId${conversationId.length}:$conversationId")

private fun stateJobId(checkpoint: SemanticStateCheckpoint): String =
    "narrative_state:${stateJobScope(checkpoint.assistantId, checkpoint.conversationId)}:" +
        "${checkpoint.coveredCount}:${checkpoint.prefixHash}:${checkpoint.revision}"

private fun stateJobCoverage(id: String, assistantId: String, conversationId: String): Int {
    val parts = id.split(':')
    require(parts.size == 5 && parts[0] == "narrative_state" &&
        parts[1] == stateJobScope(assistantId, conversationId) &&
        parts[3].matches(Regex("[0-9a-f]{64}")) && parts[4].matches(Regex("[0-9a-f]{64}"))) {
        "状态检查点任务身份格式无效"
    }
    return requireNotNull(parts[2].toIntOrNull()?.takeIf { it >= 0 && it.toString() == parts[2] }) {
        "状态检查点任务覆盖长度格式无效"
    }
}
