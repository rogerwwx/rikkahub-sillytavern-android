package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.buildRerankRequestBody
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/** 只解析明确选择的模型，不自动挑别的模型或改写记忆向量。 */
class SemanticRerankClient(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) {
    data class Selection(val provider: ProviderSetting.OpenAI, val model: Model) {
        fun parameters(query: String, documents: List<String>, instruct: String?, siliconFlowInstruction: Boolean) = RerankParams(
            model = model, query = query, documents = documents,
            customHeaders = model.customHeaders, customBody = model.customBodies, instruct = instruct, siliconFlowInstruction = siliconFlowInstruction,
        )
    }

    suspend fun select(id: Uuid): Selection {
        val owner = settingsStore.awaitLoaded().providers.firstOrNull { it.models.any { model -> model.id == id } }
            ?: error("重排模型已失效")
        check(owner.enabled) { "重排模型所属提供商已禁用" }
        val model = owner.models.first { it.id == id }.copy()
        check(model.type == ModelType.RERANK) { "选定模型不是重排类型" }
        val effective = (model.providerOverwrite ?: owner).copyProvider()
        check(effective.enabled) { "重排模型覆盖的提供商已禁用" }
        val provider = effective as? ProviderSetting.OpenAI ?: error("提供商不支持当前重排协议")
        check(providerManager.getProviderByType(provider).supportsRerank) { "提供商未实现重排调用" }
        return Selection(provider, model)
    }

    /** 仅返回哈希；路径、凭证、模型和自定义参数变化均使同轮缓存失效。 */
    suspend fun configurationRevision(id: Uuid): String {
        val owner = settingsStore.awaitLoaded().providers.firstOrNull { it.models.any { model -> model.id == id } }
        val model = owner?.models?.firstOrNull { it.id == id }
        val provider = (model?.providerOverwrite ?: owner) as? ProviderSetting.OpenAI
        return semanticContentHash(listOf(
            id.toString(), owner?.enabled.toString(), model?.type?.name.orEmpty(), model?.modelId.orEmpty(),
            provider?.id?.toString().orEmpty(), provider?.enabled.toString(), provider?.baseUrl.orEmpty(),
            provider?.rerankPath.orEmpty(), provider?.apiKey.orEmpty(),
            Json.encodeToString(provider?.customHeaders.orEmpty()), Json.encodeToString(model?.customHeaders.orEmpty()),
            Json.encodeToString(model?.customBodies.orEmpty()),
        ).joinToString("\u0000"))
    }

    fun requestBytes(params: RerankParams): Int = buildRerankRequestBody(params).toString().toByteArray(Charsets.UTF_8).size

    suspend fun rerank(selection: Selection, params: RerankParams, onDiagnostics: (RerankCallDiagnostics) -> Unit): RerankResult =
        providerManager.getProviderByType(selection.provider).rerank(selection.provider, params, onDiagnostics)
}
