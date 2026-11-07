package me.rerere.ai.provider.providers.openai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.IncompleteStreamException
import me.rerere.ai.ui.StreamCompletion
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.generateTextComplete
import me.rerere.ai.util.json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 仅使用本机 HTTP 固定响应；验证实际 Provider/OkHttp 传输，不评价模型质量。 */
class OpenAIProviderTransportTest {
    private val model = Model(modelId = "fixture-model", abilities = listOf(ModelAbility.TOOL))
    private val params = TextGenerationParams(
        model = model,
        customBody = listOf(CustomBody("fixture", JsonPrimitive("retained"))),
    )

    @Test
    fun `both API routes use the requested transport and preserve normal text and usage`() = runBlocking {
        LocalHttpFixture().use { fixture ->
            for (responsesApi in listOf(false, true)) for (stream in listOf(false, true)) {
                fixture.reply = reply(responsesApi, stream)
                val result = fixture.provider.generateTextComplete(
                    fixture.setting(responsesApi), listOf(UIMessage.user("hello")), params, stream,
                )
                assertEquals("answer", result.message.toText())
                assertEquals(StreamCompletion.NORMAL, result.message.generationCompletion)
                assertEquals(7, result.usage?.promptTokens)
                assertEquals(3, result.usage?.completionTokens)
                val request = fixture.takeRequest()
                assertEquals(if (responsesApi) "/v1/responses" else "/v1/chat/completions", request.path)
                assertEquals(stream, request.body["stream"]!!.jsonPrimitive.boolean)
                assertEquals(model.modelId, request.body["model"]!!.jsonPrimitive.content)
                assertEquals("retained", request.body["fixture"]!!.jsonPrimitive.content)
                assertTrue(if (responsesApi) "input" in request.body else "messages" in request.body)
            }
        }
    }

    @Test
    fun `length missing terminal and malformed tool arguments never produce complete responses`() = runBlocking {
        val noArgs = Tool(
            name = "memory_list", description = "fixture",
            parameters = { InputSchema.Obj(JsonObject(emptyMap())) },
            execute = { error("Provider transport must not execute tools") },
        )
        LocalHttpFixture().use { fixture ->
            for (responsesApi in listOf(false, true)) for (stream in listOf(false, true)) {
                for (reason in listOf("length", null)) {
                    fixture.reply = reply(responsesApi, stream, reason = reason)
                    assertTrue(runCatching {
                        fixture.provider.generateTextComplete(fixture.setting(responsesApi),
                            listOf(UIMessage.user("hello")), params, stream)
                    }.exceptionOrNull() is IncompleteStreamException)
                    fixture.takeRequest()
                }
                for (arguments in listOf("{}", "", "{")) {
                    fixture.reply = reply(responsesApi, stream, arguments = arguments)
                    val result = runCatching {
                        fixture.provider.generateTextComplete(fixture.setting(responsesApi),
                            listOf(UIMessage.user("list")), params.copy(tools = listOf(noArgs)), stream)
                    }
                    if (arguments == "{}") {
                        assertEquals("{}", result.getOrThrow().message.getTools().single().input)
                        assertEquals(StreamCompletion.NORMAL, result.getOrThrow().message.generationCompletion)
                    } else assertTrue(result.exceptionOrNull() is IncompleteStreamException)
                    val request = fixture.takeRequest()
                    assertEquals(1, request.body["tools"]!!.jsonArray.size)
                }
            }
        }
    }

    @Test
    fun `HTTP failure propagates through both real transports`() = runBlocking {
        LocalHttpFixture().use { fixture ->
            fixture.reply = LocalReply("""{"error":{"message":"fixture rate limit"}}""", status = 429)
            for (responsesApi in listOf(false, true)) for (stream in listOf(false, true)) {
                assertTrue(runCatching {
                    fixture.provider.generateTextComplete(fixture.setting(responsesApi),
                        listOf(UIMessage.user("hello")), params, stream)
                }.isFailure)
                fixture.takeRequest()
            }
        }
    }

    @Test
    fun `real embedding transport reorders indices and rejects malformed responses and HTTP errors`() = runBlocking {
        LocalHttpFixture().use { fixture ->
            val embeddingParams = EmbeddingGenerationParams(model, listOf("first", "second"), dimensions = 2)
            fixture.reply = LocalReply("""{"model":"fixture-model","data":[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]}""")
            val result = fixture.provider.generateEmbedding(fixture.setting(), embeddingParams)
            assertEquals(listOf(listOf(1f, 0f), listOf(0f, 1f)), result.embeddings)
            val request = fixture.takeRequest()
            assertEquals("/v1/embeddings", request.path)
            assertEquals(listOf("first", "second"), request.body["input"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("2", request.body["dimensions"]!!.jsonPrimitive.content)
            for (body in listOf(
                """{"data":[{"index":0,"embedding":[1,0]},{"index":0,"embedding":[0,1]}]}""",
                """{"data":[{"index":0,"embedding":[1,0]}]}""",
                """{"data":[{"index":0,"embedding":[1]},{"index":1,"embedding":[0,1]}]}""",
            )) {
                fixture.reply = LocalReply(body)
                assertTrue(runCatching { fixture.provider.generateEmbedding(fixture.setting(), embeddingParams) }.isFailure)
                fixture.takeRequest()
            }
            fixture.reply = LocalReply("""{"error":{"message":"fixture rate limit"}}""", status = 429)
            assertTrue(runCatching { fixture.provider.generateEmbedding(fixture.setting(), embeddingParams) }.isFailure)
            fixture.takeRequest()
            Unit
        }
    }

    @Test
    fun `cancellation before response headers cancels the actual call in both transports`() = runBlocking {
        for (responsesApi in listOf(false, true)) for (stream in listOf(false, true)) LocalHttpFixture().use { fixture ->
            fixture.holdResponse = true
            val job = async(Dispatchers.IO) {
                fixture.provider.generateTextComplete(fixture.setting(responsesApi), listOf(UIMessage.user("hello")), params, stream)
            }
            try {
                withContext(Dispatchers.IO) { fixture.takeRequest() }
                job.cancel()
                withTimeout(2000) { job.join() }
                assertTrue(job.isCancelled)
                assertTrue(fixture.lastCall.get()!!.isCanceled())
            } finally {
                fixture.releaseResponse.countDown()
                job.cancel()
            }
        }
    }

    private fun reply(responsesApi: Boolean, stream: Boolean, reason: String? = "stop", arguments: String? = null): LocalReply {
        val status = when (reason) { "stop" -> "completed"; "length" -> "incomplete"; else -> null }
        val usage = if (responsesApi) """{"input_tokens":7,"output_tokens":3,"total_tokens":10}"""
            else """{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}"""
        val item = if (arguments == null) """{"type":"message","content":[{"type":"output_text","text":"answer"}]}"""
            else """{"type":"function_call","id":"fc","call_id":"call","name":"memory_list","arguments":${JsonPrimitive(arguments)}}"""
        if (!stream) return LocalReply(if (responsesApi) {
            """{"id":"response","model":"fixture-model","status":${status?.let(::JsonPrimitive) ?: "null"},"output":[$item],"usage":$usage}"""
        } else {
            val message = if (arguments == null) """{"role":"assistant","content":"answer"}"""
                else """{"role":"assistant","tool_calls":[{"id":"call","type":"function","function":{"name":"memory_list","arguments":${JsonPrimitive(arguments)}}}]}"""
            val finish = if (arguments != null) "tool_calls" else reason
            """{"id":"response","model":"fixture-model","choices":[{"message":$message,"finish_reason":${finish?.let(::JsonPrimitive) ?: "null"}}],"usage":$usage}"""
        })
        val events = if (responsesApi) {
            val output = if (arguments == null) listOf(
                """{"type":"response.output_text.delta","item_id":"text","delta":"answer"}""",
            ) else listOf(
                """{"type":"response.output_item.added","item":$item}""",
                """{"type":"response.output_item.done","item":$item}""",
            )
            output + if (status == null) emptyList() else listOf(
                """{"type":"response.$status","response":{"id":"response","model":"fixture-model","status":"$status","usage":$usage}}""",
            )
        } else {
            val delta = if (arguments == null) """{"content":"answer"}""" else
                """{"tool_calls":[{"index":0,"id":"call","function":{"name":"memory_list","arguments":${JsonPrimitive(arguments)}}}]}"""
            val finish = if (arguments != null) "tool_calls" else reason
            listOf("""{"id":"response","model":"fixture-model","choices":[{"delta":$delta}]}""") +
                if (finish == null) emptyList() else listOf(
                    """{"choices":[{"delta":{},"finish_reason":"$finish"}],"usage":$usage}""",
                )
        }
        return LocalReply(events.joinToString("") { "data: $it\n\n" } +
            if (reason == null) "" else "data: [DONE]\n\n", contentType = "text/event-stream")
    }
}

private data class LocalReply(val body: String, val status: Int = 200, val contentType: String = "application/json")
private data class LocalRequest(val path: String, val body: JsonObject)

private class LocalHttpFixture : AutoCloseable {
    private val requests = LinkedBlockingQueue<LocalRequest>()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "provider-http-fixture").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val lastCall = AtomicReference<Call>()
    val releaseResponse = CountDownLatch(1)
    @Volatile var reply = LocalReply("{}")
    @Volatile var holdResponse = false
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .eventListener(object : EventListener() {
            override fun callStart(call: Call) { lastCall.set(call) }
        })
        .build()
    val provider = OpenAIProvider(client, context = null)

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                requests.add(LocalRequest(exchange.requestURI.path, json.parseToJsonElement(body).jsonObject))
                if (holdResponse) releaseResponse.await(10, TimeUnit.SECONDS)
                val response = reply
                val bytes = response.body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", response.contentType)
                exchange.sendResponseHeaders(response.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun setting(responsesApi: Boolean = false) = ProviderSetting.OpenAI(
        baseUrl = "http://127.0.0.1:${server.address.port}/v1",
        apiKey = "fixture-key",
        useResponseApi = responsesApi,
    )

    fun takeRequest(): LocalRequest = requests.poll(3, TimeUnit.SECONDS) ?: error("HTTP fixture received no request")

    override fun close() {
        releaseResponse.countDown()
        server.stop(0)
        executor.shutdownNow()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }
}
