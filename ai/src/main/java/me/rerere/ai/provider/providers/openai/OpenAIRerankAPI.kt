package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** SiliconFlow /rerank 兼容协议；并非 OpenAI 官方标准接口。 */
class OpenAIRerankAPI(client: OkHttpClient) {
    // 付费 POST 不自动重试；与宿主共用连接池、代理及拦截器。
    private val http = client.newBuilder().retryOnConnectionFailure(false).build()

    suspend fun rerank(
        setting: ProviderSetting.OpenAI,
        params: RerankParams,
        key: String,
        onDiagnostics: ((RerankCallDiagnostics) -> Unit)? = null,
    ): RerankResult = withContext(Dispatchers.IO) {
        val body = buildRerankRequestBody(params).toString()
        var diagnostic = RerankCallDiagnostics(params.model.modelId, body.toByteArray(Charsets.UTF_8).size)
        onDiagnostics?.invoke(diagnostic)
        val request = Request.Builder()
            .url(resolveRerankEndpoint(setting.baseUrl, setting.rerankPath))
            .headers(setting.mergeCustomHeaders(params.customHeaders))
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        diagnostic = diagnostic.copy(attempts = 1)
        onDiagnostics?.invoke(diagnostic)
        val response = awaitBody(request)
        // 不把上游错误正文（可能回显记忆或密钥）写入异常与诊断。
        if (response.first !in 200..299) throw RerankHttpException(response.first)
        val root = json.parseToJsonElement(response.second).jsonObject
        val responseModel = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val usageObject = root["usage"] as? JsonObject
        fun count(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
            (usageObject?.get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
        }
        val input = count("input_tokens", "prompt_tokens")
        val output = count("output_tokens", "completion_tokens")
        val usage = if (input == null && output == null) null else RerankUsage(input, output)
        diagnostic = diagnostic.copy(responseModel = responseModel, usage = usage)
        onDiagnostics?.invoke(diagnostic)
        val scores = parseRerankScores(root, params.documents.size)
        RerankResult(responseModel ?: params.model.modelId, scores, usage)
    }

    /** 读取响应体时也保持取消绑定，避免超时只取消响应头等待。 */
    private suspend fun awaitBody(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val text = if (it.isSuccessful) it.body?.byteStream()?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                require(output.size() + size <= 1_048_576) { "重排响应超出大小预算" }
                                output.write(buffer, 0, size)
                            }
                            output.toString(Charsets.UTF_8.name())
                        }.orEmpty() else ""
                        it.code to text
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

/** 空路径使用完整 baseUrl；非空路径拼接，也允许显式完整接口地址。 */
fun resolveRerankEndpoint(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val endpoint = path.trim()
    return when {
        endpoint.startsWith("https://") || endpoint.startsWith("http://") -> endpoint
        endpoint.isEmpty() -> base
        else -> base + "/" + endpoint.trimStart('/')
    }
}

class RerankHttpException(val code: Int) : IllegalStateException("重排 HTTP $code")

/** 全候选评分；禁止自定义请求体破坏索引映射或只返回 TopK。供调用端精确量测字节分批。 */
fun buildRerankRequestBody(params: RerankParams): JsonObject {
    require(params.query.isNotBlank() && params.documents.isNotEmpty()) { "重排 query 与 documents 不能为空" }
    require(params.model.modelId.isNotBlank()) { "重排模型 ID 不能为空" }
    require(params.customBody.none { it.key in setOf("model", "query", "documents", "top_n", "return_documents") }) {
        "重排 model/query/documents/top_n/return_documents 不可由自定义请求体覆盖"
    }
    require(params.instructionField in setOf("instruct", "instruction")) { "重排引导字段不受支持" }
    require(params.instruction == null || params.customBody.none { it.key == params.instructionField }) {
        "重排引导不可同时由选项与自定义请求体提供"
    }
    return buildJsonObject {
        put("model", params.model.modelId)
        put("query", params.query)
        put("documents", JsonArray(params.documents.map(::JsonPrimitive)))
        put("top_n", params.documents.size)
        put("return_documents", false)
        params.instruction?.takeIf { it.isNotBlank() }?.let { put(params.instructionField, it) }
    }.mergeCustomBody(params.customBody)
}

private fun parseRerankScores(root: JsonObject, count: Int): List<Double> {
    val rows = root["results"] as? JsonArray ?: error("重排响应缺少 results")
    require(rows.size == count) { "重排响应缺少候选评分" }
    val scores = arrayOfNulls<Double>(count)
    rows.forEach { raw ->
        val row = raw as? JsonObject ?: error("重排结果不是对象")
        val index = (row["index"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: error("重排结果 index 非法")
        require(index in scores.indices && scores[index] == null) { "重排结果 index 越界或重复" }
        val score = (row["relevance_score"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?: error("重排结果 relevance_score 非法")
        require(score.isFinite()) { "重排结果分数不是有限数" }
        scores[index] = score
    }
    return scores.map { it ?: error("重排响应缺少候选评分") }
}
