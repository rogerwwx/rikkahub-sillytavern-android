package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.CustomBody

private val memoryRequestFields = setOf(
    "model", "stream",
    "messages", "input", "contents",
    "system", "instructions", "systemInstruction", "system_instruction",
    "max_tokens", "max_completion_tokens", "max_output_tokens",
)

/** 保留本次记忆或压缩的证据、提示、模型与传输设置，其他模型扩展参数照常保留。 */
internal fun List<CustomBody>.withoutMemoryOverrides(): List<CustomBody> =
    filterNot { it.key in memoryRequestFields }
