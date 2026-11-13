package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

data class DreamMessage(
    val nodeId: String, val messageId: String, val role: String, val text: String,
    val createdAt: Long, val finished: Boolean,
    val createdAtText: String? = null,
) {
    val snapshotHash: String by lazy { dreamHash("$nodeId:$messageId:$role:${createdAtText ?: createdAt}:$text") }
}

@Serializable
data class DreamSource(
    val conversationId: String, val nodeId: String, val messageId: String,
    val position: Int, val contentHash: String, val role: String, val createdAt: Long,
    val start: Int, val end: Int, val valid: Boolean = true,
    val createdAtText: String? = null,
    val isIncomplete: Boolean = false,
) {
    val evidenceId: String get() = dreamHash("$conversationId:$nodeId:$messageId:$contentHash:$start:$end")
}

@Serializable
data class DreamCursor(
    val processed: List<DreamSource> = emptyList(), val continuity: String = "",
    val successfulRuns: Int = 0,
)

@Serializable
data class SemanticDreamRange(
    val id: String,
    val startMessageId: String,
    val endMessageId: String,
) {
    fun positions(messages: List<DreamMessage>): IntRange {
        val start = messages.indexOfFirst { it.messageId == startMessageId }
        val end = messages.indexOfFirst { it.messageId == endMessageId }
        require(start >= 0 && end >= start) { "所选历史范围已变化，请重新选择起止消息" }
        return start..end
    }
}

data class SemanticDreamHistoryMessage(
    val messageId: String, val position: Int, val role: String, val text: String,
    val createdAt: Long, val processed: Boolean,
    val finished: Boolean = true,
)

data class SemanticDreamHistory(
    val messages: List<SemanticDreamHistoryMessage>, val completedTurns: Int,
    val processedTurns: Int, val pendingTurns: Int, val successfulRuns: Int,
)

private fun stableDreamCursor(messages: List<DreamMessage>, cursor: DreamCursor): DreamCursor {
    val valid = cursor.processed.takeWhile { source ->
        messages.getOrNull(source.position)?.let { it.nodeId == source.nodeId && it.messageId == source.messageId } == true &&
            validDreamSources(listOf(source), messages)
    }
    return if (valid.size == cursor.processed.size) cursor else cursor.copy(processed = valid, continuity = "")
}

fun mergeDreamCoverage(messages: List<DreamMessage>, cursor: DreamCursor, evidence: List<DreamSource>): DreamCursor {
    val processed = mutableListOf<DreamSource>()
    (stableDreamCursor(messages, cursor).processed + evidence).sortedWith(
        compareBy<DreamSource> { it.position }.thenBy { it.start }
    ).forEach { source ->
        val last = processed.lastOrNull()
        if (last != null && last.messageId == source.messageId && last.contentHash == source.contentHash &&
            source.start <= last.end) {
            processed[processed.lastIndex] = last.copy(end = maxOf(last.end, source.end))
        } else processed += source
    }
    return cursor.copy(processed = processed)
}

fun dreamHistory(messages: List<DreamMessage>, cursor: DreamCursor): SemanticDreamHistory {
    val consumed = stableDreamCursor(messages, cursor).processed.groupBy { it.messageId }
        .mapValues { (_, sources) -> sources.maxOf { it.end } }
    val turns = dreamCompleteTurns(messages)
    val processedTurns = turns.count { turn -> turn.all { (consumed[it.value.messageId] ?: 0) >= it.value.text.length } }
    return SemanticDreamHistory(messages.mapIndexed { position, message ->
        SemanticDreamHistoryMessage(message.messageId, position, message.role, message.text, message.createdAt,
            (consumed[message.messageId] ?: 0) >= message.text.length, message.finished)
    }, turns.size, processedTurns, turns.size - processedTurns, cursor.successfulRuns)
}

data class DreamWindow(
    val evidence: List<DreamSource>, val context: String, val transcript: String,
    val nextCursor: DreamCursor, val completeTurns: Int,
)

@Serializable
data class SemanticDreamChange(
    val operation: String, val evidence: List<String>, val targetId: String? = null,
    val expectedVersion: Int? = null, val content: String? = null,
    val type: String = "unknown", val importance: Double = 0.5, val scope: String = "user",
)

@Serializable
data class SemanticDreamDraft(val changes: List<SemanticDreamChange>, val continuity: String? = null)

private val dreamJson = Json { ignoreUnknownKeys = false }

fun cleanDreamJson(text: String): String {
    var cleaned = text.replace("\r\n", "\n").trim()
    if (cleaned.startsWith("```json\n") && cleaned.endsWith("\n```")) cleaned = cleaned.removePrefix("```json\n").removeSuffix("\n```")
    else if (cleaned.startsWith("```\n") && cleaned.endsWith("\n```")) cleaned = cleaned.removePrefix("```\n").removeSuffix("\n```")
    return cleaned
}

fun parseDreamDraft(text: String): SemanticDreamDraft {
    val cleaned = cleanDreamJson(text)
    return dreamJson.decodeFromString<SemanticDreamDraft>(cleaned).also { draft ->
        require(draft.continuity == null || draft.continuity.length <= 2000) { "衔接状态过长" }
        draft.changes.forEach { change ->
            require(change.operation in setOf("add", "reinforce", "replace")) { "未知记忆操作" }
            require(change.evidence.isNotEmpty() && change.evidence.distinct().size == change.evidence.size) { "缺少新证据或证据重复" }
            require(change.scope in setOf("user", "role")) { "记忆适用范围无效" }
            require(change.importance.isFinite() && change.importance in 0.0..1.0) { "重要度无效" }
            require(change.type in setOf("episodic", "factual", "relational", "preference", "planned", "unknown")) { "记忆类型无效" }
            if (change.operation != "reinforce") require(!change.content.isNullOrBlank() && change.content.length <= 12000) { "正文为空或过长" }
            if (change.operation != "add") require(change.targetId != null && change.expectedVersion != null) { "缺少目标版本" }
            else require(change.targetId == null && change.expectedVersion == null) { "新增操作不能覆盖现有条目" }
        }
    }
}

fun dreamHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun DreamMessage.sourceHash() = snapshotHash

fun validDreamSources(sources: List<DreamSource>, messages: List<DreamMessage>): Boolean {
    var previousPosition = -1
    for (source in sources) {
        val position = if (messages.getOrNull(source.position)?.let { it.nodeId == source.nodeId && it.messageId == source.messageId } == true)
            source.position else messages.indexOfFirst { it.nodeId == source.nodeId && it.messageId == source.messageId }
        val message = messages.getOrNull(position) ?: return false
        if (position < previousPosition || message.sourceHash() != source.contentHash || (!message.finished && !source.isIncomplete) ||
            source.start < 0 || source.end !in source.start..message.text.length) return false
        previousPosition = position
    }
    return true
}

/** 包括隐藏原文；只消费从用户开始、助手正常结束且工具已经执行的完整轮次。 */
fun dreamCompleteTurns(messages: List<DreamMessage>): List<List<IndexedValue<DreamMessage>>> =
    dreamTurns(messages, includeIncomplete = false)

/** 手动范围保留选中的原文，含开场、独立用户消息和未确认正常结束的回复。 */
private fun dreamTurns(messages: List<DreamMessage>, includeIncomplete: Boolean): List<List<IndexedValue<DreamMessage>>> {
    val turns = mutableListOf<List<IndexedValue<DreamMessage>>>()
    var current = mutableListOf<IndexedValue<DreamMessage>>()
    fun flush() {
        if (current.isEmpty()) return
        if (includeIncomplete || (current.any { it.value.role == "assistant" } && current.all { it.value.finished }))
            turns += current.toList()
        current = mutableListOf()
    }
    for (message in messages.withIndex()) {
        if (message.value.role == "user") {
            if (current.any { it.value.role == "assistant" }) flush()
            current += message
        } else if (message.value.role == "assistant" && (includeIncomplete || current.isNotEmpty())) current += message
    }
    flush()
    return turns
}

/** 完整轮次优先；不做 token 切分，只按调用方给的轮数上限取整轮。 */
fun dreamWindow(
    conversationId: String, messages: List<DreamMessage>, cursor: DreamCursor,
    maxTurns: Int = 0, overlapTurns: Int = 1,
    firstPosition: Int = 0, lastPosition: Int = messages.lastIndex,
    includeIncomplete: Boolean = false,
): DreamWindow? {
    val stableCursor = stableDreamCursor(messages, cursor)
    val consumed = stableCursor.processed.groupBy { it.messageId }.mapValues { (_, sources) -> sources.maxOf { it.end } }
    val turns = dreamTurns(messages, includeIncomplete)
    val pending = turns.map { turn ->
        if (includeIncomplete) turn.filter { it.index in firstPosition..lastPosition } else turn
    }.filter { turn -> turn.isNotEmpty() && turn.first().index >= firstPosition && turn.last().index <= lastPosition &&
        turn.any { (consumed[it.value.messageId] ?: 0) < it.value.text.length } }
    if (pending.isEmpty()) return null
    val evidence = mutableListOf<DreamSource>()
    val selected = if (maxTurns <= 0) pending else pending.take(maxTurns)
    for (turn in selected) {
        for ((position, message) in turn) {
            val start = consumed[message.messageId] ?: 0
            if (start >= message.text.length) continue
            evidence += DreamSource(conversationId, message.nodeId, message.messageId, position, message.sourceHash(),
                message.role, message.createdAt, start, message.text.length, createdAtText = message.createdAtText,
                isIncomplete = !message.finished)
        }
    }
    if (evidence.isEmpty()) return null
    val firstPosition = evidence.first().position
    val partial = evidence.first().takeIf { it.start > 0 }?.let { source ->
        val start = (source.start - 500).coerceAtLeast(0)
        "[同一消息已处理前段，仅理解]\n" + messages[source.position].text.substring(start, source.start)
    }.orEmpty()
    val context = (turns.filter { it.last().index < firstPosition }.takeLast(overlapTurns.coerceAtLeast(0)).asReversed()
        .map { turn -> turn.joinToString("\n") { "[理解材料 " + it.value.role + "] " + it.value.text } } + partial)
        .filter { it.isNotBlank() }.joinToString("\n")
    val transcript = evidence.joinToString("\n") { source ->
        "[新证据 id=" + source.evidenceId + " role=" + source.role + " messageDate=" +
            (source.createdAtText ?: java.time.Instant.ofEpochMilli(source.createdAt)) +
            " offset=" + source.start + ":" + source.end + (if (source.isIncomplete) " state=未确认正常完成" else "") + "]\n" +
            messages[source.position].text.substring(source.start, source.end)
    }
    return DreamWindow(evidence, context, transcript, mergeDreamCoverage(messages, stableCursor, evidence), selected.size)
}

fun dreamExpiry(type: String, isCore: Boolean, evidenceAt: Long, ttlDays: Map<String, Int>): Long? =
    if (isCore) null else evidenceAt + (ttlDays[type] ?: 30).coerceIn(1, 36500).toLong() * 86400000