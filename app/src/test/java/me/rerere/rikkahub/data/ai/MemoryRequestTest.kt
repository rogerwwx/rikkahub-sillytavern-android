package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.util.mergeCustomBody
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRequestTest {
    @Test fun customBodiesPreserveEvidenceAndPromptsForEveryProviderShape() {
        val providerFields = listOf(
            "messages" to "system",
            "input" to "instructions",
            "input" to "system_instruction",
            "contents" to "systemInstruction",
        )
        for ((evidenceField, promptField) in providerFields) {
            val request = buildJsonObject {
                put(evidenceField, "真实来源")
                put(promptField, "按来源提取记忆")
                put("model", "selected-model")
                put("stream", true)
                put("max_tokens", 512)
            }
            val custom = listOf(
                CustomBody(evidenceField, JsonPrimitive("被覆盖的来源")),
                CustomBody(promptField, JsonPrimitive("被覆盖的提示")),
                CustomBody("model", JsonPrimitive("other-model")),
                CustomBody("stream", JsonPrimitive(false)),
                CustomBody("max_tokens", JsonPrimitive(1)),
                CustomBody("seed", JsonPrimitive(42)),
                CustomBody("temperature", JsonPrimitive(0.25)),
            )
            val merged = request.mergeCustomBody(custom.withoutMemoryOverrides())
            request.forEach { (key, value) -> assertEquals("$evidenceField/$key", value, merged[key]) }
            assertEquals(JsonPrimitive(42), merged["seed"])
            assertEquals(JsonPrimitive(0.25), merged["temperature"])
        }
    }
}
