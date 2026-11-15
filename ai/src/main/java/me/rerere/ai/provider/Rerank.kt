package me.rerere.ai.provider

/** scores 与原始 documents 一一对应；只比较同次重排的分数，不假定为概率。 */
data class RerankParams(
    val model: Model,
    val query: String,
    val documents: List<String>,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /** 仅在实际 HTTP 接口支持时发送，不按模型名称推断。 */
    val instruction: String? = null,
    /** Qwen 官方兼容接口用 instruct；其他明确支持 instruction 的接口仍可选择原字段。 */
    val instructionField: String = "instruction",
)

data class RerankResult(val model: String, val scores: List<Double>, val usage: RerankUsage? = null)
data class RerankUsage(val inputTokens: Long? = null, val outputTokens: Long? = null)

/** 无正文、地址、请求头或凭证；缺失用量保持 null。 */
data class RerankCallDiagnostics(
    val requestedModel: String,
    val requestBytes: Int,
    val attempts: Int = 0,
    val responseModel: String? = null,
    val usage: RerankUsage? = null,
)
