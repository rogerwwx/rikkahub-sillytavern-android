package me.rerere.rikkahub.data.memory.semantic

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore

data class SemanticRecallHit(val memory: SemanticMemoryEntity, val rank: SemanticRankHit)
data class SemanticRecallResult(
    val hits: List<SemanticRecallHit>,
    val lexicalCandidates: Int,
    val vectorCandidates: Int,
    val filters: Map<String, Int>,
    val degraded: List<String>,
    val durationNanos: Long,
    val eventId: String,
    /** 融合排序后的候选池（TopK 截断之前），供 Jev 重排序等下游阶段使用。 */
    val candidates: List<SemanticRecallHit> = emptyList(),
)

/** 手动管理、工具、自动召回和维护共同使用的唯一语义后端。 */
class SemanticMemoryRepository(
    val database: SemanticMemoryDatabase,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val context: Context,
) {
    /** 启动时由唯一做梦管理器安装来源检查，缓存复用也必须经过它。 */
    var beforeRecall: (suspend (String, String) -> Unit)? = null
    private val diagnosticsRecovery = Mutex()
    @Volatile private var diagnosticsRecovered = false
    val dao get() = database.dao()
    fun observe(assistantId: String, conversationId: String) = dao.observe(assistantId, conversationId)
    suspend fun list(assistantId: String, conversationId: String) = dao.list(assistantId, conversationId)
    suspend fun get(assistantId: String, conversationId: String, id: String) = dao.get(assistantId, conversationId, id)

    /** 单例首次使用时将上个进程的未终结请求区分为未知结果，不伪报仍在运行。 */
    suspend fun recoverInterruptedDiagnostics() {
        if (diagnosticsRecovered) return
        diagnosticsRecovery.withLock {
            if (!diagnosticsRecovered) {
                dao.interruptRunningEvents()
                diagnosticsRecovered = true
            }
        }
    }

    suspend fun assistantSettings(assistantId: String): SemanticMemorySettings? =
        settingsStore.awaitLoaded().assistants.find { it.id.toString() == assistantId }?.semanticMemory?.normalized()

    suspend fun save(entity: SemanticMemoryEntity, expectedVersion: Int? = null): SemanticMemoryEntity =
        database.withTransaction {
            require(entity.assistantId.isNotBlank() && entity.conversationId.isNotBlank()) { "记忆必须属于一个对话窗口" }
            require(entity.content.isNotBlank()) { "记忆正文不能为空" }
            require(entity.type in SEMANTIC_MEMORY_TYPES && entity.status in SEMANTIC_MEMORY_STATUSES) { "记忆类型或状态无效" }
            require(entity.importance.isFinite() && entity.importance in 0.0..1.0 &&
                entity.confidence.isFinite() && entity.confidence in 0.0..1.0) { "重要度和置信度必须在 0—1 之间" }
            require(Json.parseToJsonElement(entity.metadataJson) is JsonObject) { "元数据必须为对象" }
            require(Json.parseToJsonElement(entity.sourceJson) is JsonArray) { "来源必须为数组" }
            val old = dao.get(entity.assistantId, entity.conversationId, entity.id)
            // null 表示只允许新建；编辑必须携带读取的版本，删除后不能复活。
            check(if (expectedVersion == null) old == null else old?.contentVersion == expectedVersion) { "记忆版本冲突或条目已删除，请重新读取" }
            val changed = old != null && old.content != entity.content
            val cached = old?.takeIf {
                !changed && it.contentHash == semanticContentHash(it.content) &&
                    it.embeddedVersion == it.contentVersion &&
                    SemanticVectors.decode(it.embedding, it.embeddingDimension) != null
            }
            val saved = entity.copy(
                contentVersion = old?.contentVersion?.plus(1) ?: entity.contentVersion.coerceAtLeast(1),
                contentHash = semanticContentHash(entity.content),
                updatedAt = if (old == null) entity.updatedAt else System.currentTimeMillis(),
                embedding = cached?.embedding,
                embeddingFingerprint = cached?.embeddingFingerprint,
                embeddingDimension = cached?.embeddingDimension,
                embeddedVersion = cached?.let { it.contentVersion + 1 },
                indexedAt = cached?.indexedAt,
                accessCount = old?.accessCount ?: entity.accessCount,
                lastAccessAt = old?.lastAccessAt ?: entity.lastAccessAt,
            )
            if (old == null) dao.insert(saved) else dao.put(saved)
            saved
        }

    suspend fun delete(assistantId: String, conversationId: String, ids: List<String>): Int = database.withTransaction {
        ids.distinct().chunked(500).sumOf { dao.delete(assistantId, conversationId, it) }
    }

    private suspend fun resolve(config: SemanticMemorySettings): Pair<ProviderSetting, Model> {
        val id = config.embeddingModelId ?: error("请明确选择语义记忆 Embedding 模型")
        val providers = settingsStore.awaitLoaded().providers
        val owner = providers.firstOrNull { p -> p.enabled && p.models.any { it.id == id && it.type == ModelType.EMBEDDING } }
            ?: error("选定的 Embedding 模型已失效或提供商已禁用")
        val model = owner.models.first { it.id == id }
        require(model.customBodies.none { it.key in setOf("input", "model", "dimensions") }) {
            "语义 Embedding 的 input/model/dimensions 请使用模型选择和维度设置，不可由自定义请求体覆盖"
        }
        val provider = (model.providerOverwrite ?: owner).copyProvider()
        check(provider.enabled) { "Embedding 模型覆盖的提供商已禁用" }
        check(providerManager.getProviderByType(provider).supportsEmbedding) { "选定的提供商尚未实现 Embedding 调用" }
        return provider to model
    }

    private fun fingerprintOf(provider: ProviderSetting, model: Model, config: SemanticMemorySettings): String {
        // 指纹类型不能依赖会被 R8 改名的运行时类名。
        val providerType = when (provider) {
            is ProviderSetting.OpenAI -> "openai"
            else -> error("选定的提供商尚未定义稳定的 Embedding 指纹类型")
        }
        val endpoint = (provider as? ProviderSetting.ApiKey)?.baseUrl?.trimEnd('/') ?: providerType
        // 只持久化哈希，不在诊断/导出中记录请求头或凭证。
        return "semantic-v1:" + semanticContentHash(listOf(
            provider.id.toString(), providerType, endpoint, model.id.toString(), model.modelId,
            config.dimensions?.toString() ?: "auto",
            Json.encodeToString(provider.customHeaders), Json.encodeToString(model.customHeaders),
            Json.encodeToString(model.customBodies), "text-utf8-v1",
        ).joinToString("\u0000"))
    }

    suspend fun fingerprint(settings: SemanticMemorySettings): String {
        val config = settings.normalized()
        val (provider, model) = resolve(config)
        return fingerprintOf(provider, model, config)
    }

    fun validVector(memory: SemanticMemoryEntity, fingerprint: String, dimensions: Int? = null): Boolean =
        memory.embeddingFingerprint == "$fingerprint:${memory.embeddingDimension}" &&
            memory.embeddedVersion == memory.contentVersion &&
            memory.contentHash == semanticContentHash(memory.content) &&
            (dimensions == null || dimensions == memory.embeddingDimension) &&
            SemanticVectors.decode(memory.embedding, memory.embeddingDimension) != null

    private suspend fun request(
        assistantId: String, input: List<String>, config: SemanticMemorySettings, purpose: String,
        expectedDimensions: Int? = null,
        conversationId: String? = null,
    ): Pair<String, List<List<Float>>> {
        val (provider, model) = resolve(config)
        val fingerprint = fingerprintOf(provider, model, config)
        recoverInterruptedDiagnostics()
        val event = SemanticDiagnosticEvent(assistantId = assistantId, conversationId = conversationId,
            operation = "embedding", purpose = purpose)
        dao.putEvent(event)
        val start = System.nanoTime()
        try {
            val result = providerManager.getProviderByType(provider).generateEmbedding(provider, EmbeddingGenerationParams(
                model = model, input = input, dimensions = config.dimensions,
                customHeaders = model.customHeaders, customBody = model.customBodies,
            ))
            require(result.model == model.modelId) { "Embedding 响应模型与所选模型不一致" }
            val dimension = SemanticVectors.validate(result.embeddings, input.size, expectedDimensions ?: config.dimensions)
            dao.putEvent(event.copy(status = "success", durationNanos = System.nanoTime() - start,
                detailJson = buildJsonObject { put("items", input.size); put("dimension", dimension) }.toString()))
            return "$fingerprint:$dimension" to result.embeddings
        } catch (error: CancellationException) {
            withContext(NonCancellable) { dao.putEvent(event.copy(status = "cancelled", durationNanos = System.nanoTime() - start)) }
            throw error
        } catch (error: Exception) {
            // Provider 错误可能包含完整 HTTP 正文，禁止持久化。
            val reason = readableEmbeddingError(error)
            dao.putEvent(event.copy(status = "failure", durationNanos = System.nanoTime() - start, error = reason))
            throw IllegalStateException(reason)
        }
    }

    suspend fun testConnection(settings: SemanticMemorySettings, assistantId: String = ""): Int =
        request(assistantId, listOf("语义记忆连接测试"), settings.normalized(), "connection").second.single().size

    suspend fun embed(
        assistantId: String, conversationId: String, ids: List<String>, settings: SemanticMemorySettings, purpose: String = "save",
        expectedVersions: Map<String, Int>? = null, targetFingerprint: String? = null,
        expectedDimensions: Int? = null,
        force: Boolean = false,
    ): Map<String, String?> = withContext(Dispatchers.IO) {
        val results = linkedMapOf<String, String?>()
        val config = settings.normalized()
        val base = fingerprint(config)
        check(targetFingerprint == null || targetFingerprint == base) { "维护目标模型已变化" }
        require(expectedDimensions == null || config.dimensions == null || expectedDimensions == config.dimensions) {
            "维护目标维度与配置不一致"
        }
        var pinnedDimension = expectedDimensions ?: config.dimensions ?: list(assistantId, conversationId)
            .firstOrNull { validVector(it, base) }?.embeddingDimension
        for (batch in ids.distinct().chunked(50)) {
            val entries = batch.mapNotNull { id ->
                val memory = get(assistantId, conversationId, id)
                when {
                    memory == null -> { results[id] = "条目已删除"; null }
                    expectedVersions != null && expectedVersions[id] != memory.contentVersion ->
                        { results[id] = "内容版本已变化"; null }
                    !force && validVector(memory, base, pinnedDimension) -> { results[id] = null; null }
                    else -> memory
                }
            }
            if (entries.isEmpty()) continue
            try {
                val (actual, vectors) = request(assistantId, entries.map { it.content }, config, purpose, pinnedDimension, conversationId)
                pinnedDimension = vectors.first().size
                // 请求期间 Provider/端点/模型参数变化，也不得写入旧配置结果。
                check(fingerprint(config) == base) { "Embedding 配置在请求期间变化" }
                val live = assistantSettings(assistantId)
                check(live == null || (live.embeddingModelId == config.embeddingModelId && live.dimensions == config.dimensions)) {
                    "助手 Embedding 模型已变化"
                }
                database.withTransaction {
                    entries.forEachIndexed { index, memory ->
                        val count = dao.storeVector(assistantId, conversationId, memory.id, memory.contentVersion, memory.contentHash,
                            SemanticVectors.encode(vectors[index]), actual, vectors[index].size,
                            maxOf(System.currentTimeMillis(), (memory.indexedAt ?: 0L) + 1L))
                        results[memory.id] = if (count == 1) null else "条目已编辑或删除，向量未写入"
                    }
                    dao.putEvent(SemanticDiagnosticEvent(assistantId = assistantId, conversationId = conversationId,
                        operation = "storage", purpose = purpose,
                        status = if (entries.all { results[it.id] == null }) "success" else "conflict",
                        detailJson = buildJsonObject {
                            put("saved", entries.count { results[it.id] == null }); put("conflicts", entries.count { results[it.id] != null })
                        }.toString()))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                entries.forEach { results[it.id] = "向量生成或保存失败（${error.javaClass.simpleName}）" }
                dao.putEvent(SemanticDiagnosticEvent(assistantId = assistantId, conversationId = conversationId,
                    operation = "storage", purpose = purpose,
                    status = "failure", error = "向量未保存（${error.javaClass.simpleName}）"))
            }
        }
        results
    }

    /**
     * [sideEffects] 为 false 时只读取，不累计召回次数（界面召回测试、预览不该污染真实统计）。
     */
    suspend fun recall(assistantId: String, conversationId: String, query: String, settings: SemanticMemorySettings,
        sideEffects: Boolean = true): SemanticRecallResult =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            beforeRecall?.invoke(assistantId, conversationId)
            recoverInterruptedDiagnostics()
            val event = SemanticDiagnosticEvent(assistantId = assistantId, conversationId = conversationId,
                operation = "recall", purpose = "query")
            dao.putEvent(event)
            try {
                val config = settings.normalized()
                val retrievalQuery = query.trim()
                val now = System.currentTimeMillis()
                val all = list(assistantId, conversationId)
                val active = all.filter { it.status == "active" && (it.isCore || it.expiresAt == null || it.expiresAt > now) }
                val filters = linkedMapOf("inactive_or_expired" to all.size - active.size)
                filters["inactive_status"] = all.count { it.status != "active" }
                filters["expired"] = all.count { it.status == "active" && !it.isCore && it.expiresAt != null && it.expiresAt <= now }
                val degraded = mutableListOf<String>()
                var lexicalError = false
                var vectorError = false
                val rawVectorScores = mutableMapOf<String, Double>()
                // 启用 Jev 时每路容量至少覆盖 N，保证重排池能拿到两路头部；关闭时保持原容量，不加请求。
                val jevPool = config.dream.jevCandidateCount.takeIf { config.dream.jevEnabled && config.dream.jevRecall }
                val limit = maxOf(config.topK * config.candidateMultiplier, jevPool ?: 0)
                // 词法只检索"当前话题"：向量仍用完整 query，这里派生聚焦文本再由原生切词收敛成内容词 OR。
                // 不再把全文所有跨词二元组同权并入主查询——那会把语气/动作词混进 BM25 主信号。
                val lexicalFocus = semanticLexicalFocus(retrievalQuery)
                val nativeTokens = if (lexicalFocus.isBlank()) "" else semanticNativeTokens(lexicalFocus)
                val lexicalTerms = semanticLexicalTerms(lexicalFocus, nativeTokens.orEmpty())
                val lexicalExpression = semanticLexicalExpression(lexicalTerms.atoms)
                if (nativeTokens == null) filters["lexical_unsegmented"] = 1
                val lexical = try {
                    if (retrievalQuery.isBlank() || lexicalExpression.isEmpty()) emptyList() else {
                        // 绑定顺序保持 0..4（MATCH 表达式、助手、窗口、时间、条数）。
                        dao.lexical(SimpleSQLiteQuery("""SELECT m.id AS id, bm25(semantic_fts) AS score
                            FROM semantic_fts JOIN semantic_memory m ON m.id = semantic_fts.id
                            WHERE semantic_fts MATCH ?1
                            AND m.assistantId = ?2 AND m.conversationId = ?3 AND m.status = 'active'
                            AND (m.isCore = 1 OR m.expiresAt IS NULL OR m.expiresAt > ?4)
                            ORDER BY score ASC, m.id LIMIT ?5""", arrayOf<Any?>(
                            lexicalExpression, assistantId, conversationId, now, limit)))
                            .map { it.id to it.score }
                    }
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    lexicalError = true
                    degraded += "BM25 检索失败（${error.javaClass.simpleName}）"
                    emptyList()
                }
                val vector = try {
                    val base = fingerprint(config)
                    val (queryFingerprint, queryVectors) = request(assistantId, listOf(retrievalQuery), config, "query",
                        conversationId = conversationId)
                    val queryVector = queryVectors.single()
                    check(queryFingerprint == "$base:${queryVector.size}" && fingerprint(config) == base) {
                        "Embedding 查询配置在请求期间变化"
                    }
                    val live = assistantSettings(assistantId)
                    check(live == null || (live.embeddingModelId == config.embeddingModelId && live.dimensions == config.dimensions)) {
                        "Embedding 查询模型在请求期间变化"
                    }
                    val valid = active.filter { memory ->
                        val reason = when {
                            memory.embedding == null -> "missing_vector"
                            memory.embeddingFingerprint != "$base:${memory.embeddingDimension}" -> "old_model"
                            memory.embeddedVersion != memory.contentVersion -> "stale_content_version"
                            memory.contentHash != semanticContentHash(memory.content) -> "content_hash_changed"
                            memory.embeddingDimension != queryVector.size -> "dimension_mismatch"
                            SemanticVectors.decode(memory.embedding, memory.embeddingDimension) == null -> "invalid_vector_values"
                            else -> null
                        }
                        if (reason != null) filters[reason] = (filters[reason] ?: 0) + 1
                        reason == null
                    }
                    filters["invalid_vector"] = active.size - valid.size
                    valid.map { it.id to SemanticVectors.cosine(queryVector, SemanticVectors.decode(it.embedding, it.embeddingDimension)!!) }
                        .also { scores ->
                            rawVectorScores.putAll(scores)
                            filters["below_similarity"] = scores.count { it.second < config.minSimilarity }
                        }
                        .filter { it.second >= config.minSimilarity }.sortedByDescending { it.second }.take(limit)
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    vectorError = true
                    degraded += if (error.message?.startsWith("请明确") == true || error.message?.startsWith("选定") == true ||
                        error.message?.startsWith("语义 Embedding") == true || error.message?.startsWith("Embedding ") == true)
                        error.message!! else "向量路不可用（${error.javaClass.simpleName}）"
                    filters["invalid_vector"] = active.size
                    emptyList()
                }
                // 融合走 LivingMemory 默认的 score 分数融合，随后只做重要度加权；
                // 角色扮演里剧情时间与真实时钟脱钩，故不按写入/访问时间做新鲜度衰减。
                // 单路失败时按排名占位，避免与失败路线比较名次。
                val importance = active.associate { it.id to it.importance }
                val ranked = fuseSemanticRanks(lexical, vector, config.minSimilarity, limit * 2, config.rrfK,
                    importance, lexicalError = lexicalError, vectorError = vectorError)
                // 核心只在已相关的候选内获得有限优先名额，不能绕过检索门槛。
                // 网络等待期间编辑/删除/归档的候选退出本次结果；过滤发生在 TopK 之前。
                val currentById = dao.revisions(assistantId, conversationId).associateBy { it.id }
                val byId = active.mapNotNull { snapshot ->
                    currentById[snapshot.id]?.takeIf {
                        it.contentVersion == snapshot.contentVersion && it.contentHash == snapshot.contentHash &&
                            it.status == "active" && (it.isCore || it.expiresAt == null || it.expiresAt > System.currentTimeMillis())
                    }?.let { snapshot }
                }.associateBy { it.id }
                filters["changed_during_recall"] = active.size - byId.size
                // 候选池保持融合顺序，供下游（Jev 重排序）在不低于 TopK 的范围内重排。
                fun hitOf(rank: SemanticRankHit): SemanticRecallHit? = byId[rank.id]?.let {
                    SemanticRecallHit(it, rank.copy(vectorScore = rawVectorScores[rank.id] ?: rank.vectorScore))
                }
                val candidates = ranked.mapNotNull(::hitOf)
                val stable = ranked.filter { it.id in byId }
                // MMR 用词袋 Jaccard 把近似重复的记忆往后排；放在迟到变更剔除之后，避免浪费名额。
                val ordered = applySemanticMmr(stable, config.topK) { id -> byId[id]?.content ?: "" }
                val core = stable.filter { byId[it.id]?.isCore == true }.take(config.coreBudget)
                val selected = (core + ordered).distinctBy { it.id }.take(config.topK)
                val hits = selected.mapNotNull(::hitOf)
                val duration = System.nanoTime() - start
                val status = if (degraded.size == 2) "failure" else if (hits.isEmpty()) "empty" else "success"
                val result = SemanticRecallResult(hits, lexical.size, vector.size, filters, degraded, duration, event.id,
                    candidates = candidates)
                val fusionRankById = candidates.withIndex().associate { (index, hit) -> hit.memory.id to index + 1 }
                dao.putEvent(event.copy(status = status, durationNanos = duration, detailJson = buildJsonObject {
                    put("lexicalCandidates", lexical.size); put("vectorCandidates", vector.size)
                    // 词法视图只记策略/数量/哈希，不保存 query、词元明文或正文。
                    put("lexicalStrategy", lexicalTerms.strategy)
                    put("lexicalTermCount", lexicalTerms.atoms.size)
                    put("lexicalFocusHash", semanticContentHash(lexicalFocus))
                    put("lexicalMatchHash", semanticContentHash(lexicalExpression))
                    jevPool?.let { put("jevCandidateCount", it) }
                    put("deduplicatedCandidates", ranked.size); put("recalled", hits.size); put("injected", 0)
                    put("fusionMode", SemanticRankingDefaults.FUSION_MODE.name.lowercase())
                    // candidatePool 保持既有数字语义（候选池条数）；明细另用 candidateDetails，避免同名覆盖。
                    put("candidatePool", candidates.size)
                    put("candidateDetails", JsonArray(candidates.take(SEMANTIC_DIAGNOSTIC_POOL_LIMIT)
                        .mapIndexed { index, hit -> buildJsonObject {
                            put("id", hit.memory.id)
                            put("fusionRank", index + 1)
                            put("lexicalRawScore", hit.rank.lexicalRawScore?.let(::JsonPrimitive) ?: JsonNull)
                            put("lexicalRank", hit.rank.lexicalRank?.let(::JsonPrimitive) ?: JsonNull)
                            put("vectorRank", hit.rank.vectorRank?.let(::JsonPrimitive) ?: JsonNull)
                            put("vectorScore", hit.rank.vectorScore?.let(::JsonPrimitive) ?: JsonNull)
                        } }))
                    // 明细有上限：显式记录是否被截断，不能把截断后的明细当成完整全池。
                    put("candidateDetailsLimit", SEMANTIC_DIAGNOSTIC_POOL_LIMIT)
                    put("candidateDetailsTruncated", candidates.size > SEMANTIC_DIAGNOSTIC_POOL_LIMIT)
                    put("queryHash", semanticContentHash(retrievalQuery))
                    put("hits", JsonArray(hits.map { hit -> buildJsonObject {
                        put("id", hit.memory.id); put("version", hit.memory.contentVersion)
                        put("type", hit.memory.type); put("score", hit.rank.score)
                        put("lexicalScore", hit.rank.lexicalScore?.let(::JsonPrimitive) ?: JsonNull)
                        put("lexicalRawScore", hit.rank.lexicalRawScore?.let(::JsonPrimitive) ?: JsonNull)
                        put("lexicalRank", hit.rank.lexicalRank?.let(::JsonPrimitive) ?: JsonNull)
                        put("vectorScore", hit.rank.vectorScore?.let(::JsonPrimitive) ?: JsonNull)
                        put("vectorRank", hit.rank.vectorRank?.let(::JsonPrimitive) ?: JsonNull)
                        put("vectorRanked", hit.rank.vectorRanked)
                        put("fusionRank", fusionRankById[hit.memory.id]?.let(::JsonPrimitive) ?: JsonNull)
                    } }))
                    put("degraded", degraded.joinToString("；"))
                    filters.forEach { (key, count) -> put(key, count) }
                }.toString(), error = degraded.takeIf { it.isNotEmpty() }?.joinToString("；")))
                if (sideEffects && hits.isNotEmpty()) dao.recordAccess(assistantId, conversationId, hits.map { it.memory.id }, now)
                result
            } catch (error: CancellationException) {
                withContext(NonCancellable) { dao.putEvent(event.copy(status = "cancelled", durationNanos = System.nanoTime() - start)) }
                throw error
            } catch (error: Exception) {
                dao.putEvent(event.copy(status = "failure", durationNanos = System.nanoTime() - start,
                    error = "召回失败（${error.javaClass.simpleName}）"))
                throw error
            }
        }

    /**
     * 读原生 jieba_query 的切词结果：复用现有只读连接与 Cursor，不新增 DAO 接口或 schema。
     * 失败/不可用时返回 null，词法退化为中文二元组补召回。
     */
    private suspend fun semanticNativeTokens(focus: String): String? = withContext(Dispatchers.IO) {
        try {
            database.openHelper.readableDatabase.query("SELECT COALESCE(jieba_query(?), '')", arrayOf(focus))
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // 原生切词不可用只降级词法策略，不能吞掉取消。
            null
        }
    }

    suspend fun recordInjection(result: SemanticRecallResult) {
        val event = dao.getEvent(result.eventId) ?: return
        val details = Json.parseToJsonElement(event.detailJson) as JsonObject
        dao.putEvent(event.copy(detailJson = JsonObject(details + ("injected" to
            kotlinx.serialization.json.JsonPrimitive(result.hits.size))).toString()))
    }

    /** 同轮只复用配置与候选内容都相同的召回；状态/到期/删除同样会使缓存失效。 */
    suspend fun recallRevision(assistantId: String, conversationId: String, config: SemanticMemorySettings): String {
        beforeRecall?.invoke(assistantId, conversationId)
        val now = System.currentTimeMillis()
        val model = try { fingerprint(config)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { "invalid" }
        return semanticContentHash("$assistantId:$conversationId:" + config.toString() + model + dao.revisions(assistantId, conversationId).joinToString {
            "${it.id}:${it.contentVersion}:${it.updatedAt}:${it.status}:${it.isCore}:${it.expiresAt}:${it.expiresAt?.let { time -> time > now }}:${it.embeddingFingerprint}:${it.indexedAt}"
        })
    }

    fun diskSizes(): Map<String, Long> = listOf("", "-wal", "-shm").associate { suffix ->
        (SemanticMemoryDatabase.NAME + suffix) to context.getDatabasePath(SemanticMemoryDatabase.NAME + suffix).length()
    }
}

private fun readableEmbeddingError(error: Exception): String {
    val knownValidationErrors = setOf(
        "向量响应数量不匹配", "向量维度无效", "向量维度不一致", "向量含 NaN/Inf", "零向量无效",
        "No embedding data", "Embedding count mismatch", "Missing embedding index",
        "Duplicate or invalid embedding index", "Missing embedding vector", "Missing embedding item",
        "Empty embedding vector", "Invalid embedding dimensions or values",
        "Embedding 响应模型与所选模型不一致",
    )
    if (error.message in knownValidationErrors) return "Embedding 响应校验失败：${error.message}"
    val status = Regex("^Failed to generate embedding: (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)
    return if (status != null) "Embedding 请求失败：HTTP $status" else
        "Embedding 请求或响应校验失败（${error.javaClass.simpleName}）"
}

/** 诊断里候选全池的上限：只够定位"排在哪"，不把整库写进事件。 */
private const val SEMANTIC_DIAGNOSTIC_POOL_LIMIT = 200

fun SemanticRecallResult.prompt(): String = if (hits.isEmpty()) "" else buildString {
    appendLine(SEMANTIC_PROMPT_HEADER)
    hits.distinctBy { it.memory.id }.forEach { hit ->
        appendLine("[${hit.memory.id} / ${hit.memory.type} / v${hit.memory.contentVersion}] ${hit.memory.content}")
    }
}
