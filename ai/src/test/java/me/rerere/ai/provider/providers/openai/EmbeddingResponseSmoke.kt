package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object EmbeddingResponseSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        fun parse(text: String) = parseEmbeddingResponse(Json.parseToJsonElement(text).jsonObject, 2, 2)
        check(parse("""{"data":[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]}""") ==
            listOf(listOf(1f, 0f), listOf(0f, 1f)))
        listOf(
            """{"data":[{"index":0,"embedding":[1,0]}]}""",
            """{"data":[{"index":0,"embedding":[1,0]},{"index":0,"embedding":[0,1]}]}""",
            """{"data":[{"index":0,"embedding":[1,0]},{"index":2,"embedding":[0,1]}]}""",
            """{"data":[{"embedding":[1,0]},{"index":1,"embedding":[0,1]}]}""",
            """{"data":[{"index":0,"embedding":[1,0]},{"index":1,"embedding":[1]}]}""",
            """{"data":[{"index":0,"embedding":[1,0]},{"index":1,"embedding":[0,0]}]}""",
            """{"data":[{"index":0,"embedding":["NaN",0]},{"index":1,"embedding":[1,0]}]}""",
            """{"data":[{"index":0,"embedding":["Infinity",0]},{"index":1,"embedding":[1,0]}]}""",
        ).forEach { check(runCatching { parse(it) }.isFailure) }
        println("EmbeddingResponseSmoke: PASS（乱序、缺项、重复/越界、维度、数值）")
    }
}
