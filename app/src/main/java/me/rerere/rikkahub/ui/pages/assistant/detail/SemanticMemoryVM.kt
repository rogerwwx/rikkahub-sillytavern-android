package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.memory.semantic.*
import me.rerere.rikkahub.data.service.SemanticMaintenanceWorker
import java.io.ByteArrayOutputStream

class SemanticMemoryVM(
    val assistantId: String,
    private val repository: SemanticMemoryRepository,
    private val settingsStore: SettingsStore,
    private val context: Context,
    private val dreamManager: SemanticDreamManager,
    private val dreamScheduler: SemanticDreamScheduler,
) : ViewModel() {
    private val maintenance = SemanticMaintenanceManager(repository)
    private val transfers = SemanticTransferManager(repository)
    val settings = settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly,
        me.rerere.rikkahub.data.datastore.Settings.dummy())
    val memories = repository.observe(assistantId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val query = MutableStateFlow("")
    val typeFilter = MutableStateFlow("all")
    val statusFilter = MutableStateFlow("all")
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val filtered = combine(memories, query, typeFilter, statusFilter) { items, query, type, status ->
        items.filter { it.matches(query, type, status) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val jobs = MutableStateFlow<List<SemanticMaintenanceJob>>(emptyList())
    val imports = MutableStateFlow<List<SemanticImportProgress>>(emptyList())
    val events = MutableStateFlow<List<SemanticDiagnosticEvent>>(emptyList())
    val allEvents = MutableStateFlow<List<SemanticDiagnosticEvent>>(emptyList())
    val diskSizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val validCount = MutableStateFlow(0)
    val dimensions = MutableStateFlow<Set<Int>>(emptySet())
    val indexedAt = MutableStateFlow<Long?>(null)
    val wholeLibraryCount = MutableStateFlow(0L)
    val wholeValidCount = MutableStateFlow(0)
    val wholeDimensions = MutableStateFlow<Set<Int>>(emptySet())
    val recallQuery = MutableStateFlow("")
    val recallResult = MutableStateFlow<SemanticRecallResult?>(null)
    val dreamRuns = MutableStateFlow<List<SemanticDreamRunState>>(emptyList())
    val dreamSchedule = MutableStateFlow<SemanticDreamScheduleState?>(null)
    val sourcePreview = MutableStateFlow<String?>(null)
    private var importRunner: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    refreshDiagnostics()
                    jobs.value.filter { it.status == "pending" || it.status == "running" }
                        .forEach { SemanticMaintenanceWorker.schedule(context, it.id) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { message.value = "语义库读取失败，请重试" }
                delay(2000)
            }
        }
        viewModelScope.launch {
            memories.collect { items -> selected.value = selected.value intersect items.map { it.id }.toSet() }
        }
    }

    fun configure(transform: (SemanticMemorySettings) -> SemanticMemorySettings) = action {
        settingsStore.update { state -> state.copy(assistants = state.assistants.map { assistant ->
            if (assistant.id.toString() == assistantId) assistant.copy(semanticMemory = transform(assistant.semanticMemory).normalized())
            else assistant
        }) }
    }

    fun selectAll() {
        val current = memories.value.filter { it.matches(query.value, typeFilter.value, statusFilter.value) }
        selected.value = semanticSelectAll(current.map { it.id })
    }
    fun clearSelection() { selected.value = emptySet() }
    fun toggle(id: String) { selected.value = semanticToggleSelection(selected.value, id) }

    fun save(entity: SemanticMemoryEntity, expectedVersion: Int?, onSaved: () -> Unit) = action {
        val metadata = kotlinx.serialization.json.Json.parseToJsonElement(entity.metadataJson) as kotlinx.serialization.json.JsonObject
        val edited = if (expectedVersion != null) entity.copy(metadataJson = kotlinx.serialization.json.JsonObject(metadata +
            ("manualEdited" to kotlinx.serialization.json.JsonPrimitive(true))).toString()) else entity
        val saved = repository.save(edited, expectedVersion)
        onSaved()
        val config = repository.assistantSettings(assistantId) ?: return@action
        if (config.embeddingModelId != null) {
            val errors = repository.embed(assistantId, listOf(saved.id), config,
                expectedVersions = mapOf(saved.id to saved.contentVersion))
            message.value = if (errors[saved.id] == null) "记忆已保存并生成向量" else "记忆已保存；${errors[saved.id]}"
        } else message.value = "记忆已保存，选择 Embedding 模型后可生成向量"
    }

    fun delete(ids: Set<String>) = action {
        val count = repository.delete(assistantId, ids.toList())
        selected.value = selected.value - ids
        message.value = "已删除 $count 条语义记忆"
    }

    fun testConnection() = action {
        val config = repository.assistantSettings(assistantId) ?: error("助手不存在")
        message.value = "连接成功，向量维度 ${repository.testConnection(config, assistantId)}"
        refreshDiagnostics()
    }

    fun testRecall() = action {
        val text = recallQuery.value.trim()
        require(text.isNotBlank()) { "请输入召回查询" }
        val config = repository.assistantSettings(assistantId) ?: error("助手不存在")
        recallResult.value = repository.recall(assistantId, text, config)
        refreshDiagnostics()
    }

    fun rebuild(ids: List<String>) = action {
        val config = repository.assistantSettings(assistantId) ?: error("助手不存在")
        val job = maintenance.create(assistantId, ids, config)
        SemanticMaintenanceWorker.schedule(context, job.id)
        message.value = "已创建 ${job.items.size} 条的持久重建任务"
        refreshDiagnostics()
    }

    fun dream() = action {
        me.rerere.rikkahub.data.service.SemanticDreamWorker.scheduleManual(context, assistantId)
        message.value = "已排入后台做梦队列，离开页面后仍可继续；实际执行时间由 Android 调度"
        refreshDiagnostics()
    }

    fun cancelDream() = viewModelScope.launch { dreamScheduler.cancel(assistantId) }

    fun retryDreamEmbedding() = action { dreamManager.retryEmbedding(assistantId); refreshDiagnostics() }

    fun organizeSelected() = action {
        val result = dreamManager.organize(assistantId, selected.value.toList())
        message.value = if (result.status == "failed") result.error else "整理已完成，生成 ${result.changed} 条；原条目保留在归档"
        if (result.status != "failed") selected.value = emptySet()
        refreshDiagnostics()
    }

    fun lifecycle(status: String) = action { dreamManager.setStatus(assistantId, selected.value.toList(), status); refreshDiagnostics() }

    fun inspectSources(memoryId: String) = action { sourcePreview.value = dreamManager.readSources(assistantId, memoryId) }

    fun cancelRebuild(id: String) = action {
        maintenance.cancel(id)
        SemanticMaintenanceWorker.cancel(context, id)
        refreshDiagnostics()
    }

    fun resumeRebuild(id: String, failuresOnly: Boolean) = action {
        maintenance.resume(id, failuresOnly)
        SemanticMaintenanceWorker.schedule(context, id)
        refreshDiagnostics()
    }

    fun exportTo(uri: Uri, ids: Set<String>?) = action {
        val text = transfers.export(assistantId, ids)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("无法写入所选文件")
        message.value = "语义记忆已导出（内容与元数据，不含向量缓存）"
    }

    fun importFrom(uri: Uri) {
        importRunner = action {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(bytes.size() + read <= 64 * 1024 * 1024) { "导入文件超过 64 MiB" }
                    bytes.write(buffer, 0, read)
                }
                SemanticTransferCodec.decodeUtf8(bytes.toByteArray())
            } ?: error("无法读取所选文件")
            val id = transfers.prepare(assistantId, text)
            runImport(id)
            refreshDiagnostics()
        }
    }

    fun cancelImport(id: String) = viewModelScope.launch(Dispatchers.IO) {
        transfers.cancel(id)
        importRunner?.cancel()
        refreshDiagnostics()
    }

    fun resumeImport(id: String) {
        importRunner = action {
            transfers.resume(id)
            runImport(id)
            refreshDiagnostics()
        }
    }

    private suspend fun runImport(id: String) {
        transfers.run(id) { progress -> message.value = "导入 ${progress.processed}/${progress.total}，新增 ${progress.imported}" }
    }

    private fun action(block: suspend () -> Unit): Job = viewModelScope.launch(Dispatchers.IO) {
        busy.value = true
        try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            // JSON/HTTP 解码错误可能包含原文；不把完整异常正文写入界面或持久日志。
            message.value = if (error is java.nio.charset.CharacterCodingException) "文件不是有效的 UTF-8 文本，请修复编码后导入"
                else if (error is kotlinx.serialization.SerializationException) "JSON 格式损坏或字段不兼容"
                else error.message?.takeIf { it.length < 180 && !it.contains("http", true) } ?: "操作失败（${error.javaClass.simpleName}）"
        } finally { busy.value = false }
    }

    private suspend fun refreshDiagnostics() {
        repository.recoverInterruptedDiagnostics()
        events.value = repository.dao.events(assistantId)
        allEvents.value = repository.dao.events()
        diskSizes.value = repository.diskSizes()
        jobs.value = maintenance.jobs(assistantId)
        imports.value = transfers.jobs(assistantId)
        dreamRuns.value = dreamManager.runs(assistantId)
        dreamSchedule.value = dreamScheduler.load(assistantId)
        val items = repository.list(assistantId)
        val config = repository.assistantSettings(assistantId)
        val base = config?.let { fingerprintOrNull(it) }
        val valid = items.filter { base != null && repository.validVector(it, base, config?.dimensions) }
        validCount.value = valid.size
        dimensions.value = valid.mapNotNull { it.embeddingDimension }.toSet()
        indexedAt.value = valid.mapNotNull { it.indexedAt }.maxOrNull()
        repository.database.openHelper.readableDatabase.query("SELECT count(*) FROM semantic_memory").use {
            wholeLibraryCount.value = if (it.moveToFirst()) it.getLong(0) else 0L
        }
        val wholeValid = settingsStore.awaitLoaded().assistants.flatMap { assistant ->
            val config = assistant.semanticMemory
            val base = fingerprintOrNull(config)
            val rows = if (assistant.id.toString() == assistantId) items else repository.list(assistant.id.toString())
            rows.filter { base != null && repository.validVector(it, base, config.dimensions) }
        }
        wholeValidCount.value = wholeValid.size
        wholeDimensions.value = wholeValid.mapNotNull { it.embeddingDimension }.toSet()
    }

    private suspend fun fingerprintOrNull(config: SemanticMemorySettings): String? = try { repository.fingerprint(config) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }
}

private fun SemanticMemoryEntity.matches(query: String, type: String, status: String) =
    (query.isBlank() || content.contains(query.trim(), ignoreCase = true)) &&
        (type == "all" || this.type == type) && (status == "all" || this.status == status)
