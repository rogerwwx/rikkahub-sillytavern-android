package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 批量响应以 index 对齐输入；不容忍缺项、重复和越界索引。 */
internal fun parseEmbeddingResponse(body: JsonObject, count: Int, dimensions: Int?): List<List<Float>> {
    val data = body["data"]?.jsonArray ?: error("No embedding data")
    require(data.size == count && count > 0) { "Embedding count mismatch" }
    val ordered = arrayOfNulls<List<Float>>(count)
    data.forEach { entry ->
        val obj = entry.jsonObject
        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: error("Missing embedding index")
        require(index in 0 until count && ordered[index] == null) { "Duplicate or invalid embedding index" }
        ordered[index] = obj["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toFloat() }
            ?: error("Missing embedding vector")
    }
    val vectors = ordered.map { it ?: error("Missing embedding item") }
    val dimension = dimensions ?: vectors.first().size
    require(dimension > 0) { "Empty embedding vector" }
    vectors.forEach {
        require(it.size == dimension && it.all(Float::isFinite) && it.any { value -> value != 0f }) {
            "Invalid embedding dimensions or values"
        }
    }
    return vectors
}
