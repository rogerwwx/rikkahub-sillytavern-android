package me.rerere.rikkahub.data.ai.jev

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.io.ByteArrayOutputStream
import me.rerere.rikkahub.utils.l10n
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.security.MessageDigest

private const val TAG = "JevClient"

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** 会话级 Json：响应里可能出现我们没建模的字段，一律忽略而不是抛异常。 */
private val JevJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * 调用结果。**Uncertain 和 Failed 都必须走原有逻辑**——Jev 是隐形决策层，
 * 判不出来或者调不通的时候必须静默回退，不能影响对话，也不能弹错误。
 */
sealed interface JevResult<out T> {
    /** 按回答类型校验，且该类型的业务门槛达标 */
    data class Ok<T>(val value: T) : JevResult<T>

    /** 按回答类型仍不确定；certainty是本次使用的分布指标，不统一当作官方confidence。 */
    data class Uncertain(val certainty: Double?, val fallback: Double) : JevResult<Nothing>

    /** 没配置、网络失败、解析失败、鉴权失败……一律归到这里 */
    data class Failed(val reason: String) : JevResult<Nothing>
}

/**
 * Jev（TypeSafe System One Model）客户端。
 *
 * 只做判断、不生成文本。设计上永远不出现在用户可选的模型列表里，只是华灯设置里的
 * 一个隐形决策层。所有调用点都必须容忍它失败。
 */
class JevClient(
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) {
    private data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val threshold: Double,
    )

    /**
     * 返回 null 表示当前配置不可用（Key 未填或地址非法）。
     * 设置页据此显示黄色提示——开关可以打开，但实际不会生效。
     */
    private suspend fun effectiveConfig(): Config? {
        val h = settingsStore.settingsFlow.value.huadengSettings
        val base = h.jevBaseUrl.trim().trimEnd('/').ifEmpty { JEV_DEFAULT_BASE_URL }
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val key = h.jevApiKey.trim()
        if (key.isEmpty()) return null
        val model = h.jevModel.trim().ifEmpty { JEV_DEFAULT_MODEL }
        return Config(base, key, model, h.jevConfidenceThreshold.toDouble())
    }

    /** 设置页用来判断要不要显示黄色提示 */
    suspend fun isConfigured(): Boolean = effectiveConfig() != null

    /** 同轮缓存随连接、凭证与模型变化失效；不暴露原配置或密钥。 */
    suspend fun configurationRevision(): String = MessageDigest.getInstance("SHA-256")
        .digest(effectiveConfig().toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * 发一次判断请求。questions 至少一条。
     *
     * 返回 map 的键与传入的 question id 一致；缺失的 id 表示模型没回答，
     * 调用方应把缺失视为失败而不是当成"否"——避免静默改变行为。
     */
    suspend fun judge(
        state: JsonElement,
        questions: Map<String, JevQuestion>,
        requestBudgetMillis: Long = 5000,
        maxRetries: Int = 1,
    ): Map<String, JevVerdict> {
        val config = effectiveConfig() ?: throw JevUnavailableException(l10n("Jev 未配置", "Jev is not configured"))
        if (questions.isEmpty()) return emptyMap()
        require(questions.size <= JEV_MAX_QUESTIONS) {
            l10n("单次请求最多 $JEV_MAX_QUESTIONS 个问题，当前 ${questions.size} 个，请分批", "At most $JEV_MAX_QUESTIONS questions per request; currently ${questions.size}. Please split into batches.")
        }

        val body = JevJson.encodeToString(
            JevRequest(state = state, model = config.model, questions = questions)
        )
        val url = resolveJevEndpoint(config.baseUrl)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            withTimeout(requestBudgetMillis.coerceIn(250, 10000)) {
                executeWithRetry(request, maxRetries.coerceIn(0, 2))
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw JevUnavailableException("Jev 请求超时")
        }
        if (response.code !in 200..299) {
            throw JevHttpException(response.code, "Jev 请求失败")
        }

        val answers = try {
            val root = JevJson.parseToJsonElement(response.body) as? JsonObject
                ?: throw JevProtocolException("Jev 响应顶层不是对象")
            root["answers"] as? JsonObject ?: throw JevProtocolException("Jev 响应缺少 answers 对象")
        } catch (e: Exception) {
            throw JevProtocolException(l10n("Jev 响应解析失败", "Failed to parse Jev response"))
        }
        if (answers.keys.any { it !in questions }) throw JevProtocolException("Jev 返回了未知问题 ID")
        return questions.keys.mapNotNull { id ->
            val answer = answers[id]?.let { raw ->
                try { JevJson.decodeFromJsonElement(JevAnswer.serializer(), raw) }
                catch (_: Exception) { null }
            }
            answer?.let { JevProtocol.verdict(questions.getValue(id), it)?.let { verdict -> id to verdict } }
        }.toMap()
    }

    /**
     * 一次判断 + 阈值判定。低于阈值返回 Uncertain，调用方自行决定是否回退。
     * Noul 直接使用肯定/否定概率门槛；Choice 使用所选项概率；Score 使用该类型的分布集中度。
     */
    suspend fun judgeOne(
        state: JsonElement,
        id: String,
        question: JevQuestion,
    ): JevResult<JevVerdict> {
        val config = try {
            effectiveConfig() ?: throw JevUnavailableException(l10n("Jev 未配置", "Jev is not configured"))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.d(TAG, "judgeOne 未配置: ${e.message}")
            return JevResult.Failed(l10n("Jev 未配置", "Jev is not configured"))
        }
        val verdict = try {
            judge(state, mapOf(id to question))[id]
                ?: throw JevProtocolException(l10n("响应缺少 question id=$id", "Response is missing question id=$id"))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.d(TAG, "judgeOne 失败: ${e.javaClass.simpleName}")
            return JevResult.Failed(l10n("Jev 调用或协议校验失败", "Jev request or protocol validation failed"))
        }
        if (verdict.type == JevQuestion.TYPE_NOUL) {
            val yes = config.threshold.coerceIn(0.5, 1.0)
            if (JevProbability.classify(verdict.noul, yes, 1.0 - yes) == JevDecision.UNCERTAIN) {
                return JevResult.Uncertain(null, yes)
            }
        } else {
            val certainty = if (verdict.type == JevQuestion.TYPE_CHOICE) verdict.probabilities[verdict.choice]
                else verdict.confidence
            if (certainty == null || certainty < config.threshold) return JevResult.Uncertain(certainty, config.threshold)
        }
        return JevResult.Ok(verdict)
    }

    private data class HttpResult(val code: Int, val body: String, val retryAfter: Long?)

    private suspend fun executeWithRetry(request: Request, maxRetries: Int): HttpResult {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val response = awaitResponse(request)
                if (!shouldRetry(response.code) || attempt >= maxRetries) return response
                delay(response.retryAfter ?: backoffMs(attempt))
                attempt++
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt >= maxRetries) throw e
                delay(backoffMs(attempt))
                attempt++
            }
        }
    }

    /** OkHttp 取消绑定协程；响应体在网络线程读取并关闭，取消不遗留同步 execute。 */
    private suspend fun awaitResponse(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = if (it.isSuccessful) it.body?.byteStream()?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > 1_048_576) throw JevProtocolException("Jev 响应超出大小预算")
                                output.write(buffer, 0, count)
                            }
                            output.toString(Charsets.UTF_8.name())
                        }.orEmpty() else ""
                        HttpResult(it.code, body, retryAfterMs(it))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun shouldRetry(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /** 优先 retry-after-ms（毫秒），其次 Retry-After（秒） */
    private fun retryAfterMs(response: okhttp3.Response): Long? {
        response.header("retry-after-ms")?.toLongOrNull()?.let { return it.coerceIn(0, JEV_BACKOFF_CAP_MS) }
        response.header("Retry-After")?.toLongOrNull()?.let {
            return (it * 1000).coerceIn(0, JEV_BACKOFF_CAP_MS)
        }
        return null
    }

    /** 500ms 起，上限 5s，带 25% 抖动（照抄官方 SDK 的退避策略） */
    private fun backoffMs(attempt: Int): Long {
        val base = (500L shl attempt).coerceAtMost(JEV_BACKOFF_CAP_MS)
        val jitter = (base * 0.25 * Math.random()).toLong()
        return base + jitter
    }

    /** 设置页只展示模型名，用于诊断里记录本次判断实际使用的版本。 */
    suspend fun modelVersion(): String = effectiveConfig()?.model ?: "unconfigured"

    companion object {
        /**
         * 官方没有"问题数量"上限，真正的约束是 64k token/请求。
         * 这里设一个保守上限防止一次请求铺得太开，超了要分批。
         */
        const val JEV_MAX_QUESTIONS = 32

        /** 官方请求体上限；分批按真实序列化长度计算，不按字符数估算。 */
        const val JEV_MAX_REQUEST_BYTES = 64 * 1024

        private const val JEV_BACKOFF_CAP_MS = 5_000L
    }
}

/**
 * 按实际序列化结果估算请求体字节数，供调用方守住 64KB。
 * 模型名按 128 字符保守预留，因此估算值不小于真实请求体。
 */
fun estimateRequestBodyBytes(state: JsonElement, questions: Map<String, JevQuestion>): Int =
    JevJson.encodeToString(JevRequest(state, "x".repeat(128), questions)).toByteArray(Charsets.UTF_8).size

class JevUnavailableException(message: String) : Exception(message)
class JevHttpException(val code: Int, message: String) : Exception("HTTP $code: $message")
class JevProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
