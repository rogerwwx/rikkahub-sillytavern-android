package me.rerere.rikkahub.data.ai.jev

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * TypeSafe System One Model（Jev）请求/响应模型。
 *
 * 协议要点（2026-11-14 核对 https://api.typesafe.ai/openapi.json）：
 * - 文本请求必填 state / model / questions；图片另有可选 assets，本客户端只发文本。
 * - questions 是 **map** 不是数组；question id 不会发给模型，只用于取回答案。
 * - type 必须小写（noul / choice / score），写成 Noul 会得到一个有误导性的通用 400。
 * - state 与 instructions 可以是 string | object | array（EntryType），所以用 JsonElement。
 */

const val JEV_DEFAULT_BASE_URL = "https://api.typesafe.ai"
const val JEV_DEFAULT_MODEL = "jev-latest"

/**
 * 用户填根地址时补默认路径；填了带路径的完整地址就原样使用，
 * 兼容自建网关把接口挂在任意路径下的情况。
 */
fun resolveJevEndpoint(raw: String): String {
    val base = raw.trim().trimEnd('/').ifEmpty { JEV_DEFAULT_BASE_URL }
    return when {
        base.endsWith("/v1/systemone") -> base
        base.endsWith("/v1") -> "$base/systemone"
        base.substringAfter("://", "").contains('/') -> base
        else -> "$base/v1/systemone"
    }
}

/**
 * model 不给默认值：序列化统一用 `encodeDefaults = false`，带默认值的字段会被整段省略，
 * 而服务端把 model 列为必填——省掉它换来的是 422 "Field required"。
 * 这里让它在类型层面就必填，杜绝再被漏掉。
 */
@Serializable
data class JevRequest(
    val state: JsonElement,
    val model: String,
    val questions: Map<String, JevQuestion>,
)

/**
 * 三种 question 共用一个 data class 而不是 sealed class：
 * 它们字段名重叠（type / instructions / criteria），拆开反而要在序列化时做多态分发，
 * 而 criteria 的类型差异（对象 vs 数组）本来就要在构造侧保证，序列化层管不了。
 */
@Serializable
data class JevQuestion(
    val type: String,
    val instructions: JsonElement? = null,
    val criteria: JsonElement? = null,
) {
    companion object {
        const val TYPE_NOUL = "noul"
        const val TYPE_CHOICE = "choice"
        const val TYPE_SCORE = "score"
    }
}

@Serializable
data class JevResponse(
    val model: String? = null,
    val answers: Map<String, JevAnswer> = emptyMap(),
    val usage: JevUsage? = null,
)

@Serializable
data class JevUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

/** 服务端可选用量逐字段读取；缺失或非法值是未知，不能补成 0，也不能影响有效答案。 */
internal fun jevResponseUsage(raw: JsonElement?): JevUsage? {
    val usage = raw as? JsonObject ?: return null
    fun tokens(key: String): Int? = (usage[key] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
    val input = tokens("input_tokens")
    val output = tokens("output_tokens")
    return if (input == null && output == null) null else JevUsage(input, output)
}

/** 每批诊断快照；用量仅属于收到的响应，不含没有 usage 的失败或重试。 */
data class JevCallDiagnostics(
    val requestedModel: String,
    val requestBytes: Int,
    val estimatedTextTokens: Int,
    val attempts: Int = 0,
    val responseModel: String? = null,
    val usage: JevUsage? = null,
)

/**
 * 三种回答也是共用一个类：
 * confidence 只存在于 choice / score 上（noul 没有），所以这里可空，
 * 由调用方按 type 决定能不能用。
 */
@Serializable
data class JevAnswer(
    val type: String = "",
    /** noul：yes 的概率，0..1 */
    val noul: Double? = null,
    /** choice：argmax 出来的选项名 */
    val choice: String? = null,
    /** score：档位的概率加权均值，档位从 0 开始算，所以是个小数 */
    val score: Double? = null,
    val confidence: Double? = null,
    /** score 的回显：{"0": "描述", "1": "描述"}，键是字符串下标 */
    val legend: Map<String, String>? = null,
    val probabilities: Map<String, Double>? = null,
)
