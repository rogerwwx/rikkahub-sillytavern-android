package me.rerere.rikkahub.ui.pages.assistant.detail

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.memory.semantic.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

private val memoryTypes = linkedMapOf("all" to "全部类型", "episodic" to "经历", "factual" to "事实",
    "relational" to "关系", "preference" to "偏好", "planned" to "计划", "unknown" to "未分类")
private val memoryStatuses = linkedMapOf("all" to "全部状态", "active" to "活跃", "expired" to "已过期",
    "dormant" to "休眠", "archived" to "归档", "superseded" to "已替代", "forgotten" to "已遗忘")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SemanticMemoryPage(id: String, conversationId: String) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val conversationsRepository: ConversationRepository = koinInject()
    val conversations by remember(id) { conversationsRepository.getConversationsOfAssistant(Uuid.parse(id)) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val conversationTitle = conversations.firstOrNull { it.id.toString() == conversationId }
        ?.title?.ifBlank { "未命名对话" }
    LaunchedEffect(id, conversationId) {
        context.rememberSemanticMemoryConversation(id, conversationId)
    }
    val vm: SemanticMemoryVM = koinViewModel(key = "$id:$conversationId", parameters = { parametersOf(id, conversationId) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val assistant = settings.assistants.find { it.id.toString() == id }
    val all by vm.memories.collectAsStateWithLifecycle()
    val filtered by vm.filtered.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val type by vm.typeFilter.collectAsStateWithLifecycle()
    val status by vm.statusFilter.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val dreamRuns by vm.dreamRuns.collectAsStateWithLifecycle()
    val dreamSchedule by vm.dreamSchedule.collectAsStateWithLifecycle()
    val sourcePreview by vm.sourcePreview.collectAsStateWithLifecycle()
    var page by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<SemanticMemoryEntity?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var deleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportIds by remember { mutableStateOf<Set<String>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let { uri -> vm.exportTo(uri, exportIds) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::importFrom)
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text("语义记忆")
                conversationTitle?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }, navigationIcon = { BackButton() }, actions = {
            TextButton(onClick = { nav.navigate(Screen.SemanticMemoryConversations(id, conversationId)) }) {
                Text("切换窗口")
            }
            TextButton(enabled = assistant != null && !busy, onClick = {
                isNew = true
                editor = SemanticMemoryEntity(assistantId = id, conversationId = conversationId, content = "")
            }) { Text("新增") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = page) {
                listOf("管理", "设置", "诊断与维护", "做梦").forEachIndexed { index, label ->
                    Tab(selected = page == index, onClick = { page = index }, text = { Text(label) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { value ->
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        Text(value, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.message.value = null }) { Text("收起") }
                    }
                }
            }
            when (page) {
                0 -> Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    OutlinedTextField(value = query, onValueChange = {
                        vm.query.value = it; vm.clearSelection()
                    }, label = { Text("搜索当前窗口的全部语义记忆") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row {
                        MemoryFilter(type, memoryTypes) { vm.typeFilter.value = it; vm.clearSelection() }
                        MemoryFilter(status, memoryStatuses) { vm.statusFilter.value = it; vm.clearSelection() }
                    }
                    Row {
                        TextButton(enabled = selected.size >= 2 && !busy, onClick = vm::organizeSelected) { Text("整理所选") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { vm.lifecycle("dormant") }) { Text("休眠") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { vm.lifecycle("archived") }) { Text("归档") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { vm.lifecycle("active") }) { Text("恢复") }
                    }
                    Text("筛选结果 ${filtered.size} / 全部 ${all.size}；已选 ${selected.size} 条。长按条目进入多选。",
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { vm.selectAll() }) { Text("全选结果") }
                        TextButton(onClick = { vm.clearSelection() }) { Text("全不选") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { deleteIds = selected.toSet() }) { Text("删除所选") }
                    }
                    Row {
                        TextButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("导入") }
                        TextButton(enabled = !busy, onClick = {
                            exportIds = selected.takeIf { it.isNotEmpty() }?.toSet()
                            exportLauncher.launch("semantic-memory.json")
                        }) { Text(if (selected.isEmpty()) "导出全部" else "导出所选") }
                        TextButton(enabled = !busy && filtered.isNotEmpty(), onClick = {
                            vm.rebuild(if (selected.isEmpty()) filtered.map { it.id } else selected.toList())
                        }) { Text(if (selected.isEmpty()) "重建筛选结果" else "重建所选") }
                    }
                    Text("导入和导出仅作用于本窗口。跨窗口导入保留原来源，视为用户确认的内容；向量需重新生成。", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(filtered, key = { it.id }) { memory ->
                            Card(Modifier.fillMaxWidth().combinedClickable(onClick = {
                                if (selected.isNotEmpty()) vm.toggle(memory.id) else { isNew = false; editor = memory }
                            }, onLongClick = { vm.toggle(memory.id) }),
                                colors = CardDefaults.cardColors(containerColor = if (memory.id in selected)
                                    MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(memory.content, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                    Text("${memoryTypes[memory.type]} · ${memoryStatuses[memory.status]} · 重要度 ${"%.2f".format(memory.importance)}" +
                                        if (memory.isCore) " · 核心" else "", style = MaterialTheme.typography.labelMedium)
                                    Text("版本 ${memory.contentVersion} · ${relativeTime(memory.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { vm.inspectSources(memory.id) }) { Text("查看原始来源") }
                                }
                            }
                        }
                        if (filtered.isEmpty()) item { Text("没有符合筛选条件的语义记忆", Modifier.padding(20.dp)) }
                    }
                }
                1 -> assistant?.let { current ->
                    SemanticSettingsPanel(current.semanticMemory, settings.providers, busy, vm::configure, vm::testConnection)
                }
                2 -> SemanticDiagnosticsPanel(vm, all.size, busy)
                3 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { nav.navigate(Screen.SemanticDreamSettings(id)) }) { Text("做梦、生命周期与 Jev 设置") }
                    Text("本窗口增量做梦", style = MaterialTheme.typography.titleMedium)
                    dreamSchedule?.let { Text("后台状态：${it.status} · 调度尝试 ${it.attempts}") }
                    Text("从最早未处理的完整轮次提取长期变化，包含压缩隐藏的原文。无值得保留的变化也会推进已核验来源的进度；断流、取消或来源变化不会提交草稿。")
                    Row {
                        TextButton(enabled = assistant?.semanticMemory?.enabled == true && !busy, onClick = vm::dream) { Text("开始／重试做梦") }
                        TextButton(onClick = vm::cancelDream) { Text("取消本窗口任务") }
                    }
                    TextButton(enabled = !busy, onClick = vm::retryDreamEmbedding) { Text("重试做梦向量待办") }
                    Text("指定条目整理：在管理页按主题选择至少两条后使用「整理所选」。原条目归档保留；核心条目默认受保护。", style = MaterialTheme.typography.bodySmall)
                    dreamRuns.take(30).forEach { run ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                            Text("${if (run.operation == "organize") "整理" else "反思"} · ${run.status} · 尝试 ${run.attempts} · 更新 ${run.changed} 条")
                            Text(if (run.operation == "organize") "指定条目 ${run.selectedIds.size} 条" else "来源对话 ${run.conversationId}", style = MaterialTheme.typography.bodySmall)
                            run.error?.let { Text(it) }
                            if (run.nextRetryAt > 0) Text("自动重试最早时间 ${Instant.ofEpochMilli(run.nextRetryAt)}", style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
            }
        }
    }

    editor?.let { memory ->
        SemanticMemoryEditor(memory, onDismiss = { editor = null }, onSave = { edited ->
            vm.save(edited, if (isNew) null else memory.contentVersion) { editor = null }
        }, onDelete = if (isNew) null else { { deleteIds = setOf(memory.id) } })
    }
    sourcePreview?.let { preview ->
        AlertDialog(onDismissRequest = { vm.sourcePreview.value = null }, title = { Text("记忆来源") },
            text = { Text(preview, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { vm.sourcePreview.value = null }) { Text("关闭") } })
    }
    if (deleteIds.isNotEmpty()) {
        val actual = deleteIds intersect all.map { it.id }.toSet()
        AlertDialog(onDismissRequest = { deleteIds = emptySet() }, title = { Text("删除 ${actual.size} 条语义记忆？") },
            text = { Text("按确认时仍存在的稳定 ID 删除；相关向量同时删除。") },
            confirmButton = { TextButton(enabled = actual.isNotEmpty(), onClick = {
                vm.delete(actual); editor = null; deleteIds = emptySet()
            }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteIds = emptySet() }) { Text("取消") } })
    }
}

@Composable
private fun MemoryFilter(value: String, choices: Map<String, String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(choices[value] ?: value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { onChange(key); expanded = false }) }
        }
    }
}

@Composable
private fun SemanticSettingsPanel(config: SemanticMemorySettings, providers: List<me.rerere.ai.provider.ProviderSetting>,
    busy: Boolean, configure: ((SemanticMemorySettings) -> SemanticMemorySettings) -> Unit, test: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模型和召回设置由本助手的所有窗口共用，各窗口分别保存记忆。", style = MaterialTheme.typography.bodySmall)
        Row {
            Column(Modifier.weight(1f)) {
                Text("启用当前助手的语义记忆")
                Text("启用后只召回当前对话窗口的语义记忆；关闭后恢复原记忆行为。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = config.enabled, onCheckedChange = { configure { old -> old.copy(enabled = it) } })
        }
        Text("Embedding 模型")
        ModelSelector(modelId = config.embeddingModelId, providers = providers, type = ModelType.EMBEDDING,
            onSelect = { model -> configure { it.copy(embeddingModelId = model.id) } })
        val selectedOwner = providers.firstOrNull { provider -> provider.models.any { it.id == config.embeddingModelId && it.type == ModelType.EMBEDDING } }
        val selectedModel = selectedOwner?.models?.firstOrNull { it.id == config.embeddingModelId }
        when {
            config.embeddingModelId == null -> Text("尚未选择 Embedding 模型。")
            selectedOwner == null || !selectedOwner.enabled || selectedModel?.providerOverwrite?.enabled == false ->
                Text("所选模型已失效或提供商已禁用，请重新选择；原模型 ID 已保留。")
        }
        Text("切换模型后旧向量会失效，请在管理页面创建重建任务。失效期间仍可使用 BM25 关键词召回。",
            style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !busy && config.embeddingModelId != null, onClick = test) { Text("测试连接") }
        NumberSetting("向量维度（空白使用模型默认值）", config.dimensions?.toString().orEmpty()) { raw ->
            if (raw.isBlank()) configure { it.copy(dimensions = null) }
            else raw.toIntOrNull()?.takeIf { it in 1..65536 }?.let { dimension -> configure { it.copy(dimensions = dimension) } }
        }
        NumberSetting("最多召回条数 TopK（1—50）", config.topK.toString()) { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..50 }?.let { value -> configure { it.copy(topK = value) } }
        }
        NumberSetting("最低原始向量相似度（-1—1）", config.minSimilarity.toString()) { raw ->
            raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in -1.0..1.0 }?.let { value -> configure { it.copy(minSimilarity = value) } }
        }
        Text("相似度门槛只过滤向量路；只有关键词命中的条目仍可经 BM25 进入结果。", style = MaterialTheme.typography.bodySmall)
        NumberSetting("候选倍率（1—10）", config.candidateMultiplier.toString()) { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..10 }?.let { value -> configure { it.copy(candidateMultiplier = value) } }
        }
        NumberSetting("RRF 排名融合参数（1—1000）", config.rrfK.toString()) { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..1000 }?.let { value -> configure { it.copy(rrfK = value) } }
        }
        NumberSetting("相关核心记忆优先名额（0—TopK）", config.coreBudget.toString()) { raw ->
            raw.toIntOrNull()?.takeIf { it in 0..config.topK }?.let { value -> configure { it.copy(coreBudget = value) } }
        }
    }
}

@Composable
private fun NumberSetting(label: String, value: String, onChange: (String) -> Unit) {
    var input by remember(label) { mutableStateOf(value) }
    var focused by remember(label) { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        // 聚焦期间保留「0.」等草稿，失焦后同步已保存值和外部归一化结果。
        if (!focused) input = value
    }
    OutlinedTextField(value = input, onValueChange = { input = it; onChange(it) }, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemanticMemoryEditor(memory: SemanticMemoryEntity, onDismiss: () -> Unit,
    onSave: (SemanticMemoryEntity) -> Unit, onDelete: (() -> Unit)?) {
    var content by remember(memory.id) { mutableStateOf(memory.content) }
    var type by remember(memory.id) { mutableStateOf(memory.type) }
    var status by remember(memory.id) { mutableStateOf(memory.status) }
    var importance by remember(memory.id) { mutableStateOf(memory.importance.toString()) }
    var confidence by remember(memory.id) { mutableStateOf(memory.confidence.toString()) }
    var core by remember(memory.id) { mutableStateOf(memory.isCore) }
    var metadata by remember(memory.id) { mutableStateOf(memory.metadataJson) }
    var sources by remember(memory.id) { mutableStateOf(memory.sourceJson) }
    val originalDate = memory.expiresAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()
    var expires by remember(memory.id) { mutableStateOf(originalDate) }
    val date = runCatching { LocalDate.parse(expires).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    val weight = importance.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
    val trust = confidence.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (onDelete == null) "新增语义记忆" else "编辑语义记忆", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(content, { content = it }, label = { Text("记忆正文") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Row { MemoryFilter(type, memoryTypes.filterKeys { it != "all" }) { type = it }
                MemoryFilter(status, memoryStatuses.filterKeys { it != "all" }) { status = it } }
            NumberSetting("重要度（0—1）", importance) { importance = it }
            NumberSetting("置信度（0—1）", confidence) { confidence = it }
            Row { Text("核心记忆", Modifier.weight(1f)); Switch(core, { core = it }) }
            OutlinedTextField(expires, { expires = it }, label = { Text("到期日期 YYYY-MM-DD（当地0点，可留空）") },
                isError = expires.isNotBlank() && date == null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(metadata, { metadata = it }, label = { Text("附加元数据 JSON 对象") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sources, { sources = it }, label = { Text("来源元数据 JSON 数组") }, modifier = Modifier.fillMaxWidth())
            Row {
                Button(enabled = content.isNotBlank() && weight != null && trust != null && (expires.isBlank() || date != null),
                    onClick = { onSave(memory.copy(content = content, type = type, status = status, importance = weight!!,
                        confidence = trust!!, isCore = core, metadataJson = metadata, sourceJson = sources,
                        expiresAt = if (expires == originalDate) memory.expiresAt else date)) }) { Text("保存") }
                TextButton(onClick = onDismiss) { Text("取消") }
                onDelete?.let { TextButton(onClick = it) { Text("删除") } }
            }
        }
    }
}

@Composable
private fun SemanticDiagnosticsPanel(vm: SemanticMemoryVM, total: Int, busy: Boolean) {
    val assistantEvents by vm.events.collectAsStateWithLifecycle()
    val allEvents by vm.allEvents.collectAsStateWithLifecycle()
    val sizes by vm.diskSizes.collectAsStateWithLifecycle()
    val valid by vm.validCount.collectAsStateWithLifecycle()
    val dimensions by vm.dimensions.collectAsStateWithLifecycle()
    val indexedAt by vm.indexedAt.collectAsStateWithLifecycle()
    val wholeCount by vm.wholeLibraryCount.collectAsStateWithLifecycle()
    val wholeValid by vm.wholeValidCount.collectAsStateWithLifecycle()
    val wholeDimensions by vm.wholeDimensions.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    val recallQuery by vm.recallQuery.collectAsStateWithLifecycle()
    val recallResult by vm.recallResult.collectAsStateWithLifecycle()
    var whole by remember { mutableStateOf(false) }
    val report = remember(assistantEvents, allEvents, whole) { semanticDiagnostics(if (whole) allEvents else assistantEvents) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(recallQuery, { vm.recallQuery.value = it }, label = { Text("测试召回查询") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy && recallQuery.isNotBlank(), onClick = { vm.testRecall() }) { Text("运行真实召回") }
            Text("测试使用当前助手配置，只检索本窗口的记忆，会计入查询请求与逻辑召回；不会注入聊天。",
                style = MaterialTheme.typography.bodySmall)
            recallResult?.let { result ->
                Text("本次候选：BM25 ${result.lexicalCandidates}，向量 ${result.vectorCandidates}；最终 ${result.hits.size} 条")
                Text("本次过滤：${result.filters}；耗时 ${"%.1f".format(result.durationNanos / 1_000_000.0)} ms")
                result.degraded.forEach { Text(it) }
                result.hits.forEach { hit ->
                    Text(hit.memory.content)
                    Text("BM25 ${hit.rank.lexicalScore ?: "无"}；余弦 ${hit.rank.vectorScore ?: "无"}；RRF ${hit.rank.score}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("向量是否参与融合：${if (hit.rank.vectorRanked) "是" else "否（仍可由 BM25 命中）"}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("当前窗口：$total 条；有效向量 $valid，失效/待生成 ${total - valid}，维度 ${dimensions.sorted().joinToString().ifEmpty { "未知" }}")
            Text("向量索引：Float32 精确余弦扫描；${if (valid > 0) "已就绪" else "等待生成有效向量"}")
            Text("最近成功索引：${indexedAt?.let(::relativeTime) ?: "暂无"}")
            Text("整库 $wholeCount 条；磁盘 ${sizes.values.sum()} 字节（整库，主库 + WAL + SHM）")
            Text("整库有效向量 $wholeValid，失效/待生成 ${wholeCount - wholeValid}；有效维度 ${wholeDimensions.sorted().joinToString().ifEmpty { "未知" }}")
            Text("有效向量要求模型、维度与内容版本一致；过期和归档的日常召回过滤另行统计。", style = MaterialTheme.typography.bodySmall)
            sizes.forEach { (name, bytes) -> Text("$name：$bytes 字节", style = MaterialTheme.typography.bodySmall) }
            Row { Text("显示整库事件统计", Modifier.weight(1f)); Switch(whole, { whole = it }) }
            Text("${if (whole) "整库" else "当前窗口"}持久事件；缓存复用不增加请求或逻辑召回，上次进程中断的未知结果单独统计。",
                style = MaterialTheme.typography.bodySmall)
            Text("网络计数为 generateEmbedding 业务 API 调用，供应商内部连接重试不另计；成功含响应向量校验。平均耗时为已终结事件的单调时钟累计值。",
                style = MaterialTheme.typography.bodySmall)
            Text("Embedding 网络：${countsText(report.embedding)}")
            report.embeddingPurposes.forEach { (purpose, counts) -> Text("${purposeLabel(purpose)}：${countsText(counts)}", style = MaterialTheme.typography.bodySmall) }
            Text("落库事件：成功 ${report.storageSuccess}，冲突 ${report.storageConflict}，失败 ${report.storageFailure}")
            Text("逻辑召回：${countsText(report.recall)}")
            Text("累计候选：BM25 ${report.lexicalCandidates}，向量 ${report.vectorCandidates}，去重 ${report.deduplicatedCandidates}")
            Text("累计召回 ${report.recalled}，注入 ${report.injected}，降级 ${report.degraded} 次")
            Text("过滤：非活跃/过期 ${report.filters["inactive_or_expired"]}，无效向量 ${report.filters["invalid_vector"]}，低于相似度 ${report.filters["below_similarity"]}")
            Text("召回期间版本/状态变化：${report.filters["changed_during_recall"]}")
            Text("状态细分：非活跃 ${report.filters["inactive_status"]}，到期 ${report.filters["expired"]}")
            Text("向量细分：缺失 ${report.filters["missing_vector"]}，旧模型 ${report.filters["old_model"]}，旧内容版本 ${report.filters["stale_content_version"]}，哈希变化 ${report.filters["content_hash_changed"]}，维度不符 ${report.filters["dimension_mismatch"]}，异常数值 ${report.filters["invalid_vector_values"]}")
            Text("组合过滤与细分原因重叠，不能相加为总过滤数。", style = MaterialTheme.typography.bodySmall)
        }
        items((if (whole) allEvents else assistantEvents).filter { it.operation == "recall" }.take(3), key = { "recall-${it.id}" }) { event ->
            Card { Column(Modifier.padding(12.dp)) {
                Text("最近实际召回 · ${relativeTime(event.startedAt)} · ${statusLabel(event.status)}")
                semanticRecallScoreLines(event).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
        }
        items(jobs, key = { it.id }) { job -> Card {
            Column(Modifier.padding(12.dp)) {
                Text("向量重建 · ${statusLabel(job.status)} · 当前批次 ${job.currentBatch}")
                Text("总数 ${job.items.size}，已处理 ${job.processed}，成功 ${job.success}，失败 ${job.failed}，跳过 ${job.skipped}，待处理 ${job.pending}")
                Text("成功表示此任务曾有效落库；处理后的编辑可能再次使向量失效。", style = MaterialTheme.typography.bodySmall)
                job.error?.let { Text(it) }
                Row {
                    if (job.status in setOf("pending", "running")) TextButton(onClick = { vm.cancelRebuild(job.id) }) { Text("取消") }
                    if (job.status in setOf("cancelled", "paused")) TextButton(enabled = !busy, onClick = { vm.resumeRebuild(job.id, false) }) { Text("恢复待办") }
                    if (job.failed > 0 && job.status !in setOf("pending", "running")) TextButton(enabled = !busy, onClick = { vm.resumeRebuild(job.id, true) }) { Text("只重试失败") }
                }
                job.items.filter { it.error != null }.take(10).forEach { Text("${it.id}：${it.error}", style = MaterialTheme.typography.bodySmall) }
                if (job.failed + job.skipped > 10) Text("此处显示前 10 条，完整逐项结果已持久保存。", style = MaterialTheme.typography.bodySmall)
            }
        } }
        items(imports, key = { it.id }) { job -> Card {
            Column(Modifier.padding(12.dp)) {
                Text("独立导入 · ${statusLabel(job.status)} · ${job.processed}/${job.total}")
                Text("新增 ${job.imported}，重复跳过 ${job.duplicates}，冲突保留 ${job.conflicts}")
                Row {
                    if (job.status == "pending") {
                        TextButton(onClick = { vm.cancelImport(job.id) }) { Text("取消") }
                        TextButton(enabled = !busy, onClick = { vm.resumeImport(job.id) }) { Text("继续导入") }
                    }
                    if (job.status in setOf("cancelled", "failure")) TextButton(enabled = !busy, onClick = { vm.resumeImport(job.id) }) { Text("恢复导入") }
                }
                job.errors.takeLast(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        } }
        item { Text("最近错误", style = MaterialTheme.typography.titleMedium) }
        items(report.errors, key = { it.id }) { event ->
            Text("${relativeTime(event.startedAt)} · ${operationLabel(event.operation)}/${purposeLabel(event.purpose)} · ${event.error}")
        }
    }
}

private fun countsText(c: SemanticOperationCounts) = "总计 ${c.total}，成功 ${c.success}，空结果 ${c.empty}，失败 ${c.failure}，取消 ${c.cancelled}，进行中 ${c.running}，中断未知 ${c.interrupted}；平均 ${"%.1f".format(c.averageMillis)} ms"
private fun purposeLabel(value: String) = when (value) { "connection" -> "连接测试"; "query" -> "查询"; "save" -> "保存"; "rebuild" -> "重建"; else -> value }
private fun operationLabel(value: String) = when (value) { "embedding" -> "向量请求"; "recall" -> "召回"; "storage" -> "向量落库"; else -> value }
private fun statusLabel(value: String) = when (value) {
    "pending" -> "待处理"; "running" -> "运行中"; "paused" -> "已暂停"; "cancelled" -> "已取消"
    "complete" -> "已完成"; "success" -> "成功"; "failure", "failed" -> "失败"; "empty" -> "空结果"
    "interrupted" -> "中断未知"; "conflict" -> "冲突"; "skipped" -> "已跳过"; else -> value
}
private fun relativeTime(time: Long) = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
