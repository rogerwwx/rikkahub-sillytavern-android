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
    ) throw IncompleteStreamException("响应未正常完成：$completion")
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

/** 压缩与记忆共用严格响应契约；开关只决定调用 Provider 的哪种传输方式。 */
suspend fun <T : ProviderSetting> Provider<T>.generateTextComplete(
    setting: T, messages: List<UIMessage>, params: TextGenerationParams, stream: Boolean,
): TextGenerationResult = if (stream) streamTextComplete(setting, messages, params)
else validateCompleteResponse(generateText(setting, messages, params))
