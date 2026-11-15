package me.rerere.ai.provider

/** scores 与原始 documents 一一对应；只比较同次重排的分数，不假定为概率。 */
data class RerankParams(
    val model: Model,
    val query: String,
    val documents: List<String>,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /** Qwen 任务引导；普通重排留空，不按模型名称自动开启。 */
    val instruct: String? = null,
    /** 硅基流动 Qwen 使用 instruction；阿里云 Qwen 兼容接口使用 instruct。 */
    val siliconFlowInstruction: Boolean = false,
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
