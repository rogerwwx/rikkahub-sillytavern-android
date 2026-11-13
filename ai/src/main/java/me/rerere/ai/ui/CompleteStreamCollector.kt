package me.rerere.ai.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult

/** 合并正文/推理/工具，验证整个流后才把正式结果交给后台消费者。 */
suspend fun collectCompleteStream(flow: Flow<StreamChunk>, model: Model? = null): TextGenerationResult {
    val guard = CompleteStreamGuard()
    val handler = StreamChunkHandler(model)
    var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = model?.id)
    flow.collect { chunk ->
        guard.accept(chunk)
        message = handler.handle(listOf(message), chunk).single()
    }
    val finish = guard.requireComplete()
    return TextGenerationResult(finish.responseId ?: message.id.toString(), finish.model ?: model?.modelId.orEmpty(),
        message, finish.finishReason, message.usage)
}

suspend fun <T : ProviderSetting> Provider<T>.streamTextComplete(
    setting: T, messages: List<UIMessage>, params: TextGenerationParams,
): TextGenerationResult = collectCompleteStream(streamText(setting, messages, params), params.model)

/** 非流式也须有明确正常完成证据；推理和历史工具不能代替本次正文。 */
fun validateCompleteResponse(result: TextGenerationResult): TextGenerationResult {
    val tools = result.message.getTools()
    if (result.finishReason.equals("requires_action", ignoreCase = true) && tools.isEmpty()) {
        throw IncompleteStreamException("工具交接响应缺少工具调用")
    }
    val reasonCompletion = protocolCompletion(result.finishReason, hasToolCalls = tools.isNotEmpty())
    val completion = result.message.generationCompletion ?: reasonCompletion
    if (completion != StreamCompletion.NORMAL ||
        reasonCompletion == StreamCompletion.TRUNCATED || reasonCompletion == StreamCompletion.FAILED
    ) throw IncompleteStreamException(
        message = "响应未正常完成：$completion",
        kind = when {
            reasonCompletion == StreamCompletion.TRUNCATED || completion == StreamCompletion.TRUNCATED ->
                StreamFailureKind.TRUNCATED
            reasonCompletion == StreamCompletion.FAILED || completion == StreamCompletion.FAILED ->
                StreamFailureKind.FAILED
            else -> StreamFailureKind.PROTOCOL
        },
        finishReason = result.finishReason,
    )
    if (result.message.parts.none { it is UIMessagePart.Text && it.text.isNotBlank() } && tools.isEmpty()) {
        throw IncompleteStreamException("响应正文为空")
    }
    val toolIds = mutableSetOf<String>()
    tools.forEach { tool ->
        if (tool.toolName.isBlank() || !toolIds.add(tool.toolCallId)) {
            throw IncompleteStreamException("工具调用未完成或 ID 重复")
        }
        val input = try { Json.parseToJsonElement(tool.input) }
        catch (_: Exception) { throw IncompleteStreamException("工具参数不是完整 JSON") }
        if (input !is JsonObject) throw IncompleteStreamException("工具参数必须是 JSON 对象")
    }
    return result.copy(message = result.message.copy(generationCompletion = StreamCompletion.NORMAL))
}

private val streamHttpStatus = Regex("""Failed to get [a-z ]*:? ?(\d{3})""")
private val streamBareStatus = Regex("""\b(?:HTTP|status)[ =:]*([1-5]\d{2})\b""", RegexOption.IGNORE_CASE)

/**
 * 把失败翻译成人能读的一句话：输出被截断（预算不足）、服务端拒绝、协议未完成、网络、HTTP 分开。
 *
 * [attempts] 是实际发出的请求次数（含重试），[elapsedMillis] 是这些尝试的总耗时。
 * 截断是确定性的：调用方若仍重发同样的请求，只会再次截断并白等一轮。
 */
fun completeStreamFailureText(
    error: Throwable,
    attempts: Int,
    elapsedMillis: Long,
    budgetTokens: Int? = null,
): String {
    val raw = error.message.orEmpty().trim()
    val seconds = String.format(java.util.Locale.US, "%.1f", elapsedMillis / 1000.0)
    val effort = "尝试 $attempts 次，耗时 $seconds 秒"
    val statusMatch = streamHttpStatus.find(raw) ?: streamBareStatus.find(raw)
    val status = statusMatch?.groupValues?.get(1)
    val reason = raw.substring(statusMatch?.groups?.get(1)?.range?.last?.plus(1) ?: 0)
        .trim().trimStart(':', '-', ' ').lineSequence().joinToString(" ")
        .replace(Regex("""(sk-|Bearer )[A-Za-z0-9._-]+"""), "***")
        .take(90).trim()
    val head = when {
        error is IncompleteStreamException && error.kind == StreamFailureKind.TRUNCATED -> buildString {
            append("模型输出被截断（输出预算不足）：")
            append(if (budgetTokens != null) "预算 $budgetTokens token 用完" else "输出达到上限")
            append("，finish=").append(error.finishReason ?: "length")
            append("；重发同样的请求只会再次截断，请调低目标 token 或换输出上限更大的模型")
            if (reason.isNotBlank()) append("（").append(reason).append("）")
        }
        error is IncompleteStreamException && error.kind == StreamFailureKind.FAILED ->
            "模型拒绝本次生成：" + (raw.ifBlank { "finish=" + (error.finishReason ?: "failed") })
        error is IncompleteStreamException -> "协议未正常完成：" + raw.ifBlank { "缺少终止信号" }
        status != null -> "请求失败 HTTP " + status + (if (reason.isBlank()) "" else "：" + reason)
        error is java.io.IOException -> "请求网络失败：" + raw.ifBlank { error.javaClass.simpleName } + "（可检查网络后重试）"
        else -> "请求失败：" + (raw.ifBlank { error.javaClass.simpleName })
    }
    return head + "；" + effort
}

/** 压缩与记忆共用严格响应契约；开关只决定调用 Provider 的哪种传输方式。 */
suspend fun <T : ProviderSetting> Provider<T>.generateTextComplete(
    setting: T, messages: List<UIMessage>, params: TextGenerationParams, stream: Boolean,
): TextGenerationResult = if (stream) streamTextComplete(setting, messages, params)
else validateCompleteResponse(generateText(setting, messages, params))
