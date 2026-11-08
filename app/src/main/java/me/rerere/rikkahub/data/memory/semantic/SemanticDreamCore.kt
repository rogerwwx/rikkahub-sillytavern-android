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
        require(dreamCompleteTurns(messages).any { it.first().index >= start && it.last().index <= end }) {
            "所选范围没有完整轮次，请从用户消息选到对应的助手回复"
        }
        return start..end
    }
}

data class SemanticDreamHistoryMessage(
    val messageId: String, val position: Int, val role: String, val text: String,
    val createdAt: Long, val processed: Boolean,
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
    return SemanticDreamHistory(turns.flatten().map { (position, message) ->
        SemanticDreamHistoryMessage(message.messageId, position, message.role, message.text, message.createdAt,
            (consumed[message.messageId] ?: 0) >= message.text.length)
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
private fun DreamMessage.sourceDay(): java.time.LocalDate = createdAtText?.let {
    runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull()
} ?: java.time.Instant.ofEpochMilli(createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

fun validDreamSources(sources: List<DreamSource>, messages: List<DreamMessage>): Boolean {
    var previousPosition = -1
    for (source in sources) {
        val position = if (messages.getOrNull(source.position)?.let { it.nodeId == source.nodeId && it.messageId == source.messageId } == true)
            source.position else messages.indexOfFirst { it.nodeId == source.nodeId && it.messageId == source.messageId }
        val message = messages.getOrNull(position) ?: return false
        if (position < previousPosition || message.sourceHash() != source.contentHash || !message.finished ||
            source.start < 0 || source.end !in source.start..message.text.length) return false
        previousPosition = position
    }
    return true
}

/** 包括隐藏原文；只消费从用户开始、助手正常结束且工具已经执行的完整轮次。 */
fun dreamCompleteTurns(messages: List<DreamMessage>): List<List<IndexedValue<DreamMessage>>> {
    val turns = mutableListOf<List<IndexedValue<DreamMessage>>>()
    var current = mutableListOf<IndexedValue<DreamMessage>>()
    fun flush() {
        if (current.isEmpty()) return
        if (current.any { it.value.role == "assistant" } && current.all { it.value.finished }) turns += current.toList()
        current = mutableListOf()
    }
    for (message in messages.withIndex()) {
        if (message.value.role == "user") {
            if (current.any { it.value.role == "assistant" }) flush()
            current += message
        } else if (current.isNotEmpty() && message.value.role == "assistant") current += message
    }
    flush()
    return turns
}

/** 完整轮次优先；单轮超预算时保存消息内偏移，不丢掉剩余正文。 */
fun dreamWindow(
    conversationId: String, messages: List<DreamMessage>, cursor: DreamCursor,
    charBudget: Int, overlapTurns: Int, segmentGapMinutes: Int = 180, splitByDay: Boolean = true,
    messageOverhead: Int = 0,
    firstPosition: Int = 0, lastPosition: Int = messages.lastIndex,
): DreamWindow? {
    require(charBudget > 0)
    val stableCursor = stableDreamCursor(messages, cursor)
    val validPrefix = stableCursor.processed
    val turns = dreamCompleteTurns(messages)
    val consumed = validPrefix.groupBy { it.messageId }.mapValues { (_, sources) -> sources.maxOf { it.end } }
    val pending = turns.filter { turn -> turn.first().index >= firstPosition && turn.last().index <= lastPosition &&
        turn.any { (consumed[it.value.messageId] ?: 0) < it.value.text.length } }
    if (pending.isEmpty()) return null
    val evidence = mutableListOf<DreamSource>()
    var remaining = charBudget
    var completed = 0
    val firstMessage = pending.first().first().value
    val firstTime = firstMessage.createdAt
    var previousTime = firstTime
    for (turn in pending) {
        val time = turn.first().value.createdAt
        if (evidence.isNotEmpty() && ((splitByDay && turn.first().value.sourceDay() != firstMessage.sourceDay()) ||
                    time - previousTime > segmentGapMinutes.toLong() * 60000)) break
        val size = turn.sumOf { val textSize = (it.value.text.length - (consumed[it.value.messageId] ?: 0)).coerceAtLeast(0)
            if (textSize == 0) 0 else textSize + messageOverhead }
        if (evidence.isNotEmpty() && size > remaining) break
        for ((position, message) in turn) {
            val start = consumed[message.messageId] ?: 0
            if (start >= message.text.length || remaining <= messageOverhead) continue
            var end = (start + remaining - messageOverhead).coerceAtMost(message.text.length)
            if (end < message.text.length && end > start && message.text[end - 1].isHighSurrogate()) end--
            if (end == start) break
            evidence += DreamSource(conversationId, message.nodeId, message.messageId, position, message.sourceHash(),
                message.role, message.createdAt, start, end, createdAtText = message.createdAtText)
            remaining -= end - start + messageOverhead
        }
        if (size <= charBudget && remaining >= 0 && turn.all { item ->
                (evidence.lastOrNull { it.messageId == item.value.messageId }?.end ?: consumed[item.value.messageId] ?: 0) >= item.value.text.length
            }) completed++
        previousTime = time
        if (remaining == 0 || size > charBudget) break
    }
    if (evidence.isEmpty()) return null
    val firstPosition = evidence.first().position
    val partial = evidence.first().takeIf { it.start > 0 }?.let { source ->
        val start = (source.start - minOf(500, charBudget / 8)).coerceAtLeast(0)
        "[同一消息已处理前段，仅理解]\n" + messages[source.position].text.substring(start, source.start)
    }.orEmpty()
    val contextTurns = mutableListOf<String>()
    var contextSize = partial.length
    for (turn in turns.filter { it.last().index < firstPosition }.takeLast(overlapTurns.coerceAtLeast(0)).asReversed()) {
        val text = turn.joinToString("\n") { "[理解材料 ${it.value.role}] ${it.value.text}" }
        if (contextSize + text.length > charBudget / 4) break
        contextTurns.add(0, text)
        contextSize += text.length
    }
    val context = (contextTurns + partial.takeIf { it.isNotBlank() }.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
    val transcript = evidence.joinToString("\n") { source ->
        "[新证据 id=${source.evidenceId} role=${source.role} messageDate=${source.createdAtText ?: java.time.Instant.ofEpochMilli(source.createdAt)} offset=${source.start}:${source.end}]\n" +
            messages[source.position].text.substring(source.start, source.end)
    }
    return DreamWindow(evidence, context, transcript, mergeDreamCoverage(messages, stableCursor, evidence), completed)
}

fun dreamExpiry(type: String, isCore: Boolean, evidenceAt: Long, ttlDays: Map<String, Int>): Long? =
    if (isCore) null else evidenceAt + (ttlDays[type] ?: 30).coerceIn(1, 36500).toLong() * 86400000
