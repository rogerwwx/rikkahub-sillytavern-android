package me.rerere.rikkahub.data.memory.semantic

data class SemanticJobHeader(
    val id: String,
    val assistantId: String,
    val kind: String,
    val updatedAt: Long,
    val payloadLength: Int,
    val inlinePayload: String?,
)

/** SQLite substr 按 Unicode 码点切片，单字段最多 64 KiB，不把大任务塞进 CursorWindow。 */
internal suspend fun readSemanticJobPayload(
    header: SemanticJobHeader,
    readSlice: suspend (offset: Int, length: Int) -> String?,
): String {
    require(header.payloadLength >= 0)
    header.inlinePayload?.let { payload ->
        check(payload.codePointCount(0, payload.length) == header.payloadLength) { "任务内容长度不匹配" }
        return payload
    }
    val payload = StringBuilder()
    var offset = 1
    while (offset <= header.payloadLength) {
        val length = minOf(16_384, header.payloadLength - offset + 1)
        val part = readSlice(offset, length) ?: error("任务内容分片缺失")
        check(part.codePointCount(0, part.length) == length) { "任务内容分片不完整" }
        payload.append(part)
        offset += length
    }
    return payload.toString()
}
