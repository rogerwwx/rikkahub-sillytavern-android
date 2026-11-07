package me.rerere.ai.provider.providers.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamCompletion
import me.rerere.ai.ui.protocolCompletion
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import me.rerere.ai.util.parseErrorDetail

internal class ClaudeStreamDecoder : StreamChunkDecoder {
    private val blocks = mutableMapOf<Int, ClaudeStreamBlock>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val dataJson = json.parseToJsonElement(event.data).jsonObject
        if (event.event == "error") {
            throw (dataJson["error"] ?: dataJson).parseErrorDetail()
        }

        dataJson["message"]?.jsonObject?.let { message ->
            responseId = message["id"]?.jsonPrimitive?.contentOrNull ?: responseId
            responseModel = message["model"]?.jsonPrimitive?.contentOrNull ?: responseModel
        }
        dataJson["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let {
            finishReason = it
        }

        val chunks = buildList {
            parseTokenUsage(dataJson)?.let { add(StreamChunk.Usage(it)) }
            val index = dataJson["index"]?.jsonPrimitive?.intOrNull
            val contentBlock = dataJson["content_block"]?.jsonObject

            if (event.event == "content_block_start" && index != null && contentBlock != null) {
                val kind = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: ""
                val blockId = contentBlock["id"]?.jsonPrimitive?.contentOrNull
                    ?: contentBlock["tool_use_id"]?.jsonPrimitive?.contentOrNull
                    ?: "${responseId ?: event.id ?: "response"}:block-$index"
                val metadata = when (kind) {
                    "thinking" -> contentBlock["signature"]?.jsonPrimitive?.contentOrNull?.let {
                        ClaudeReasoningMetadata(signature = it).toMetadata()
                    }
                    else -> null
                }
                blocks[index] = ClaudeStreamBlock(kind, blockId, metadata,
                    initialInput = if (kind == "tool_use") contentBlock["input"]?.jsonObject?.toString() else null)
                when (kind) {
                    "text" -> add(StreamChunk.TextStart(blockId))
                    "thinking", "redacted_thinking" -> add(StreamChunk.ReasoningStart(blockId, metadata))
                    "tool_use" -> {
                        add(StreamChunk.ToolCallStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        ))
                        val input = contentBlock["input"]?.jsonObject
                        if (input != null && input.isNotEmpty()) {
                            blocks.getValue(index).hasInput = true
                            add(StreamChunk.ToolCallDelta(
                                id = blockId,
                                inputDelta = json.encodeToString(input),
                            ))
                        }
                    }
                    else -> if (kind.isClaudeServerToolUseType()) {
                        add(StreamChunk.ServerToolStart(
                            id = blockId,
                            toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            input = contentBlock["input"],
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                call = contentBlock,
                                callIndex = index,
                            ).toMetadata(),
                        ))
                    } else if (kind.isClaudeServerToolResultType()) {
                        val output = contentBlock["content"]
                        add(StreamChunk.ServerToolEnd(
                            id = blockId,
                            output = output,
                            status = if (output.isClaudeServerToolError()) {
                                ServerToolStatus.FAILED
                            } else {
                                ServerToolStatus.COMPLETED
                            },
                            metadata = ServerToolMetadata(
                                protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                result = contentBlock,
                                resultIndex = index,
                            ).toMetadata(),
                        ))
                    }
                }
            }

            if (event.event == "content_block_delta" && index != null) {
                val block = blocks[index] ?: error("Unknown content block index: $index")
                val delta = dataJson["delta"]?.jsonObject ?: JsonObject(emptyMap())
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> add(StreamChunk.TextDelta(
                        block.id,
                        delta["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    ))
                    "thinking_delta" -> add(StreamChunk.ReasoningDelta(
                        block.id,
                        delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "",
                        block.metadata,
                    ))
                    "signature_delta" -> {
                        val metadata = delta["signature"]?.jsonPrimitive?.contentOrNull?.let {
                            ClaudeReasoningMetadata(signature = it).toMetadata()
                        }
                        blocks[index] = block.copy(metadata = metadata ?: block.metadata)
                        add(StreamChunk.ReasoningDelta(block.id, "", metadata))
                    }
                    "input_json_delta" -> {
                        val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (block.kind.isClaudeServerToolUseType()) {
                            add(StreamChunk.ServerToolInputDelta(block.id, partialJson))
                        } else {
                            if (partialJson.isNotEmpty()) block.hasInput = true
                            add(StreamChunk.ToolCallDelta(id = block.id, inputDelta = partialJson))
                        }
                    }
                }
            }

            if (event.event == "content_block_stop" && index != null) {
                val block = blocks.remove(index) ?: error("Unknown content block index: $index")
                addAll(endBlock(block))
            }
        }

        return if (event.event == "message_stop") {
            DecodeResult(chunks + finish(protocolComplete = true), completed = true)
        } else {
            DecodeResult(chunks)
        }
    }

    override fun onClosed(): List<StreamChunk> = finish()

    private fun finish(protocolComplete: Boolean = false): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return buildList {
            blocks.values.forEach { addAll(endBlock(it)) }
            blocks.clear()
            add(StreamChunk.Finish(finishReason, responseId, responseModel,
                if (protocolComplete) protocolCompletion(finishReason) else StreamCompletion.UNKNOWN))
        }
    }

    private fun endBlock(block: ClaudeStreamBlock): List<StreamChunk> = when (block.kind) {
        "text" -> listOf(StreamChunk.TextEnd(block.id))
        "thinking", "redacted_thinking" -> listOf(StreamChunk.ReasoningEnd(block.id, block.metadata))
        "tool_use" -> buildList {
            // 完整空对象是合法的无参数调用；缺失输入或半截 delta 不补成 {}。
            if (!block.hasInput && block.initialInput != null) {
                add(StreamChunk.ToolCallDelta(block.id, inputDelta = block.initialInput))
            }
            add(StreamChunk.ToolCallEnd(block.id))
        }
        else -> if (block.kind.isClaudeServerToolUseType()) {
            listOf(StreamChunk.ServerToolInputEnd(block.id))
        } else {
            emptyList()
        }
    }

    private fun parseTokenUsage(bodyJson: JsonObject): TokenUsage? {
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
        )
    }

    private data class ClaudeStreamBlock(
        val kind: String,
        val id: String,
        val metadata: JsonObject? = null,
        val initialInput: String? = null,
        var hasInput: Boolean = false,
    )
}
