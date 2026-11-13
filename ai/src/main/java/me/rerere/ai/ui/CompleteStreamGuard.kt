package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

@Serializable
enum class StreamCompletion { NORMAL, TRUNCATED, FAILED, UNKNOWN }

/**
 * 失败类别决定调用方能不能重试：[TRUNCATED] 是模型按 max_tokens 正常停下，
 * [FAILED] 是服务端拒绝，两者重发同样的请求只会得到同样的结果；
 * [PROTOCOL] 是连接中途结束、缺终止信号或正文/工具不完整，可能是瞬时故障。
 */
enum class StreamFailureKind { TRUNCATED, FAILED, PROTOCOL }

class IncompleteStreamException(
    message: String,
    val kind: StreamFailureKind = StreamFailureKind.PROTOCOL,
    /** 服务端给出的原始 finish reason，便于调用方记录与排查。 */
    val finishReason: String? = null,
) : IllegalStateException(message)

/** 只有真实协议终止信号能调用；连接结束本身不提供完成证据。 */
fun protocolCompletion(reason: String?): StreamCompletion = when (reason?.lowercase()) {
    "stop", "tool_calls", "function_call", "end_turn", "tool_use", "stop_sequence", "completed" -> StreamCompletion.NORMAL
    "length", "max_tokens", "incomplete", "incomplete:max_output_tokens" -> StreamCompletion.TRUNCATED
    "failed", "error", "content_filter", "safety", "recitation", "blocklist", "prohibited_content" -> StreamCompletion.FAILED
    else -> StreamCompletion.UNKNOWN
}

/** requires_action 只表示本次客户端工具交接；工具参数完整性由消费者另外验证。 */
fun protocolCompletion(reason: String?, hasToolCalls: Boolean): StreamCompletion =
    if (reason.equals("requires_action", ignoreCase = true) && hasToolCalls) StreamCompletion.NORMAL
    else protocolCompletion(reason)

/** 每次请求/重试创建新实例；不修改普通聊天的预览合并规则。 */
class CompleteStreamGuard {
    private data class ToolDraft(var name: String, val input: StringBuilder = StringBuilder(), var ended: Boolean = false)
    private val tools = linkedMapOf<String, ToolDraft>()
    private var terminal: StreamChunk.Finish? = null
    private var hasText = false

    fun accept(chunk: StreamChunk) {
        if (terminal != null && chunk !is StreamChunk.Usage) throw IncompleteStreamException("终止信号后仍有输出")
        when (chunk) {
            is StreamChunk.TextDelta -> hasText = hasText || chunk.text.isNotBlank()
            is StreamChunk.ToolCallStart -> {
                if (chunk.id in tools) throw IncompleteStreamException("工具调用 ID 重复")
                tools[chunk.id] = ToolDraft(chunk.toolName)
            }
            is StreamChunk.ToolCallDelta -> {
                val tool = tools[chunk.id] ?: throw IncompleteStreamException("缺少工具调用起点")
                if (tool.ended) throw IncompleteStreamException("工具结束后仍有参数")
                tool.name += chunk.toolNameDelta
                tool.input.append(chunk.inputDelta)
            }
            is StreamChunk.ToolCallEnd -> (tools[chunk.id]
                ?: throw IncompleteStreamException("缺少工具调用起点")).ended = true
            is StreamChunk.Finish -> terminal = chunk
            else -> Unit
        }
    }

    fun requireComplete(): StreamChunk.Finish {
        val finish = terminal ?: throw IncompleteStreamException("没有协议终止信号")
        if (finish.completion != StreamCompletion.NORMAL) throw IncompleteStreamException(
            message = "响应未正常完成：${finish.completion}",
            kind = when (finish.completion) {
                StreamCompletion.TRUNCATED -> StreamFailureKind.TRUNCATED
                StreamCompletion.FAILED -> StreamFailureKind.FAILED
                else -> StreamFailureKind.PROTOCOL
            },
            finishReason = finish.finishReason,
        )
        if (finish.finishReason.equals("requires_action", ignoreCase = true) && tools.isEmpty()) {
            throw IncompleteStreamException("工具交接响应缺少工具调用")
        }
        if (!hasText && tools.isEmpty()) throw IncompleteStreamException("响应正文为空")
        tools.values.forEach { tool ->
            if (!tool.ended || tool.name.isBlank()) throw IncompleteStreamException("工具调用未完成")
            val parsed = try { Json.parseToJsonElement(tool.input.toString()) }
            catch (_: Exception) { throw IncompleteStreamException("工具参数不是完整 JSON") }
            if (parsed !is JsonObject) throw IncompleteStreamException("工具参数必须是 JSON 对象")
        }
        return finish
    }
}
