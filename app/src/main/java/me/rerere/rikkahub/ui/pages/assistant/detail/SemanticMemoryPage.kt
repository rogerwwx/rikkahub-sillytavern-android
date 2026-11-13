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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Tick02
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
    val dreamHistory by vm.dreamHistory.collectAsStateWithLifecycle()
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
            }) {
                Icon(HugeIcons.Add01, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = page) {
                listOf("管理", "设置", "诊断", "做梦").forEachIndexed { index, label ->
                    val icon = listOf(HugeIcons.Brain02, HugeIcons.Settings03, HugeIcons.Database02, HugeIcons.Moon02)[index]
                    Tab(selected = page == index, onClick = { page = index }, text = { Text(label) },
                        icon = { Icon(icon, null, Modifier.size(20.dp)) })
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
                0 -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = query, onValueChange = {
                        vm.query.value = it; vm.clearSelection()
                    }, placeholder = { Text("搜索当前窗口的记忆") },
                        leadingIcon = { Icon(HugeIcons.Search01, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MemoryFilter(type, memoryTypes) { vm.typeFilter.value = it; vm.clearSelection() }
                        MemoryFilter(status, memoryStatuses) { vm.statusFilter.value = it; vm.clearSelection() }
                    }
                    if (selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = selected.size >= 2 && !busy, onClick = vm::organizeSelected) { Text("整理所选") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { vm.lifecycle("dormant") }) { Text("休眠") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { vm.lifecycle("archived") }) { Text("归档") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { vm.lifecycle("active") }) { Text("恢复") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${filtered.size} 条记忆", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        Text(if (selected.isEmpty()) "共 ${all.size} 条 · 长按多选" else "已选 ${selected.size} 条",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.selectAll() }) { Text("全选结果") }
                        TextButton(onClick = { vm.clearSelection() }) { Text("全不选") }
                        TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { deleteIds = selected.toSet() }) { Text("删除所选") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("导入") }
                        TextButton(enabled = !busy, onClick = {
                            exportIds = selected.takeIf { it.isNotEmpty() }?.toSet()
                            exportLauncher.launch("semantic-memory.json")
                        }) { Text(if (selected.isEmpty()) "导出全部" else "导出所选") }
                        TextButton(enabled = !busy && filtered.isNotEmpty(), onClick = {
                            vm.rebuild(if (selected.isEmpty()) filtered.map { it.id } else selected.toList())
                        }) { Text(if (selected.isEmpty()) "重建筛选结果" else "重建所选") }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(filtered, key = { it.id }) { memory ->
                            Card(Modifier.fillMaxWidth().combinedClickable(onClick = {
                                if (selected.isNotEmpty()) vm.toggle(memory.id) else { isNew = false; editor = memory }
                            }, onLongClick = { vm.toggle(memory.id) }),
                                colors = CardDefaults.cardColors(containerColor = if (memory.id in selected)
                                    MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (memory.id in selected) Icon(HugeIcons.Tick02, null,
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        SuggestionChip(onClick = { vm.typeFilter.value = memory.type; vm.clearSelection() },
                                            label = { Text(memoryTypes[memory.type].orEmpty()) })
                                        Text(memoryStatuses[memory.status].orEmpty(), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (memory.isCore) Text("核心", style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(memory.content, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                    Text("重要度 ${"%.2f".format(memory.importance)} · ${relativeTime(memory.updatedAt)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { vm.inspectSources(memory.id) }) { Text("查看原始来源") }
                                }
                            }
                        }
                        if (filtered.isEmpty()) item {
                            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(HugeIcons.Brain02, null, Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (all.isEmpty()) "这个窗口还没有语义记忆" else "没有符合筛选条件的记忆")
                                Text(if (all.isEmpty()) "手动新增一条，或从做梦页提取对话记忆" else "试试其他关键词、类型或状态",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            Text("导入和导出仅作用于本窗口。跨窗口导入保留原来源，向量需重新生成。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                1 -> assistant?.let { current ->
                    SemanticSettingsPanel(current.semanticMemory, settings.providers, busy, vm::configure, vm::testConnection)
                }
                2 -> SemanticDiagnosticsPanel(vm, all.size, busy)
                3 -> SemanticDreamPanel(vm, dreamHistory, busy,
                    enabled = assistant?.semanticMemory?.enabled == true,
                    openSettings = { nav.navigate(Screen.SemanticDreamSettings(id)) })
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
private fun SemanticDreamPanel(
    vm: SemanticMemoryVM,
    history: SemanticDreamHistory?,
    busy: Boolean,
    enabled: Boolean,
    openSettings: () -> Unit,
) {
    val schedule by vm.dreamSchedule.collectAsStateWithLifecycle()
    val runs by vm.dreamRuns.collectAsStateWithLifecycle()
    var mode by rememberSaveable(vm.assistantId, vm.conversationId) { mutableIntStateOf(0) }
    var startFloor by rememberSaveable(vm.assistantId, vm.conversationId) { mutableStateOf("1") }
    var endFloor by rememberSaveable(vm.assistantId, vm.conversationId) { mutableStateOf("") }
    val messages = history?.messages.orEmpty()
    LaunchedEffect(messages.size) {
        if (endFloor.isEmpty() && messages.isNotEmpty()) endFloor = messages.size.toString()
    }
    val start = startFloor.trim().toIntOrNull()?.let { messages.getOrNull(it - 1) }
    val end = endFloor.trim().toIntOrNull()?.let { messages.getOrNull(it - 1) }
    val validRange = start != null && end != null && start.position <= end.position
    val processedMessages = messages.count { it.processed }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SemanticMemorySection("做梦进度", HugeIcons.Moon02) {
            if (history == null) {
                Text("正在读取当前窗口的历史…")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("$processedMessages", style = MaterialTheme.typography.headlineSmall)
                        Text("已处理消息", style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        Text("${messages.size - processedMessages}", style = MaterialTheme.typography.headlineSmall)
                        Text("待处理消息", style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        Text("${messages.size}", style = MaterialTheme.typography.headlineSmall)
                        Text("原文消息", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (messages.isNotEmpty()) LinearProgressIndicator(
                    progress = { processedMessages.toFloat() / messages.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("成功处理 ${history.successfulRuns} 次。标记只在完整结果通过来源核验后保存，提取结果为空也算已处理。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            schedule?.let {
                Text("后台${statusLabel(it.status)} · 尝试 ${it.attempts}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        SemanticMemorySection("手动做梦", HugeIcons.AiMagic) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("未处理历史", "全部历史", "指定范围").forEachIndexed { index, label ->
                    FilterChip(selected = mode == index, onClick = { mode = index }, label = { Text(label) })
                }
            }
            Text(when (mode) {
                0 -> "从最早未处理的完整轮次继续，包含压缩隐藏的原文。要包含开场、截断回复或独立消息，请选择全部历史或指定范围。"
                1 -> "重新梳理当前窗口的全部原文，包含当前选中的编辑正文、开场和截断回复，后台按顺序分片处理。"
                else -> "填写聊天中显示的起止楼层，包含区间内双方的全部原文；已处理的区间也可以重新梳理。"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mode == 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = startFloor,
                        onValueChange = { startFloor = it },
                        label = { Text("起始楼层") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endFloor,
                        onValueChange = { endFloor = it },
                        label = { Text("结束楼层") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("当前窗口共 ${messages.size} 楼，范围包含起止楼层；摘要和分隔线不占楼层。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!validRange && messages.isNotEmpty()) Text("请输入 1～${messages.size} 之间的起止楼层，起始楼层不能大于结束楼层。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && !busy && messages.isNotEmpty() && (mode != 2 || validRange),
                    onClick = {
                        when (mode) {
                            0 -> vm.dream()
                            1 -> vm.dreamAll()
                            else -> vm.dreamRange(checkNotNull(start).messageId, checkNotNull(end).messageId)
                        }
                    }) {
                    Icon(HugeIcons.Moon02, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (mode == 0) "开始／重试做梦" else "开始总结")
                }
                TextButton(onClick = vm::cancelDream) { Text("取消本窗口任务") }
            }
            TextButton(enabled = !busy, onClick = vm::retryDreamEmbedding) { Text("重试做梦向量待办") }
            Text("断流、取消或来源变化不会提交草稿。自动做梦根据未处理历史计算触发轮数。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SemanticMemorySection("做梦与整理设置", HugeIcons.Settings03) {
            OutlinedButton(onClick = openSettings) { Text("模型、自动做梦、生命周期与 Jev") }
            Text("整理已有记忆：在管理页按主题选择至少两条后点「整理所选」。原条目归档保留，核心条目默认受保护。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (runs.isNotEmpty()) {
            Text("最近任务", style = MaterialTheme.typography.titleMedium)
            runs.take(30).forEach { run ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (run.operation == "organize") "记忆整理" else "历史反思",
                                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(statusLabel(run.status), color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium)
                        }
                        Text("更新 ${run.changed} 条 · 尝试 ${run.attempts}",
                            style = MaterialTheme.typography.bodySmall)
                        if (run.operation == "organize") Text("所选记忆 ${run.selectedIds.size} 条",
                            style = MaterialTheme.typography.bodySmall)
                        run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (run.nextRetryAt > 0) Text("最早重试时间 ${Instant.ofEpochMilli(run.nextRetryAt)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SemanticSettingsPanel(config: SemanticMemorySettings, providers: List<me.rerere.ai.provider.ProviderSetting>,
    busy: Boolean, configure: ((SemanticMemorySettings) -> SemanticMemorySettings) -> Unit, test: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模型、召回和注入设置由本助手的所有窗口共用，各窗口分别保存记忆。", style = MaterialTheme.typography.bodySmall)
        SemanticMemorySection("语义记忆", HugeIcons.Brain02) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用当前助手的语义记忆")
                    Text("每个对话窗口独立保存和召回记忆。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = config.enabled, onCheckedChange = { configure { old -> old.copy(enabled = it) } })
            }
        }
        SemanticMemorySection("Embedding 模型", HugeIcons.AiMagic) {
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
            OutlinedButton(enabled = !busy && config.embeddingModelId != null, onClick = test) { Text("测试连接") }
            NumberSetting("向量维度（空白使用模型默认值）", config.dimensions?.toString().orEmpty()) { raw ->
                if (raw.isBlank()) configure { it.copy(dimensions = null) }
                else raw.toIntOrNull()?.takeIf { it in 1..65536 }?.let { dimension -> configure { it.copy(dimensions = dimension) } }
            }
        }
        SemanticMemorySection("召回设置", HugeIcons.Search01) {
            SemanticIntSlider("最多召回条数 · TopK", config.topK, 1..50) { value ->
                configure { it.copy(topK = value, coreBudget = it.coreBudget.coerceAtMost(value)) }
            }
            SemanticDecimalSlider("最低向量相似度", config.minSimilarity, -1.0..1.0) { value ->
                configure { it.copy(minSimilarity = value) }
            }
            Text("相似度门槛只过滤向量路；只有关键词命中的条目仍可经 BM25 进入结果。", style = MaterialTheme.typography.bodySmall)
            SemanticIntSlider("相关核心记忆优先名额", config.coreBudget, 0..config.topK) { value ->
                configure { it.copy(coreBudget = value) }
            }
        }
        SemanticMemorySection("记忆注入", HugeIcons.Brain02) {
            SemanticInjectionSettings(config.dream) { transform ->
                configure { old -> old.copy(dream = transform(old.dream)) }
            }
        }
        SemanticMemorySection("高级检索", HugeIcons.Settings03) {
            SemanticIntSlider("候选倍率", config.candidateMultiplier, 1..10) { value ->
                configure { it.copy(candidateMultiplier = value) }
            }
            NumberSetting("RRF 排名融合参数（1—1000）", config.rrfK.toString()) { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..1000 }?.let { value -> configure { it.copy(rrfK = value) } }
            }
            Text("候选倍率控制参与融合的候选量，RRF 参数控制关键词与向量排名的融合。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SemanticMemorySection("召回测试", HugeIcons.Search01) {
                OutlinedTextField(recallQuery, { vm.recallQuery.value = it }, label = { Text("测试召回查询") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy && recallQuery.isNotBlank(), onClick = { vm.testRecall() }) { Text("运行真实召回") }
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
            }
            SemanticMemorySection("向量索引", HugeIcons.Database02) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("$valid", style = MaterialTheme.typography.headlineSmall)
                        Text("有效向量", style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        Text("${total - valid}", style = MaterialTheme.typography.headlineSmall)
                        Text("待生成／失效", style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        Text("$total", style = MaterialTheme.typography.headlineSmall)
                        Text("窗口记忆", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (total > 0) LinearProgressIndicator(progress = { valid.toFloat() / total },
                    modifier = Modifier.fillMaxWidth())
                Text("当前窗口：$total 条；有效向量 $valid，失效/待生成 ${total - valid}，维度 ${dimensions.sorted().joinToString().ifEmpty { "未知" }}")
                Text("向量索引：Float32 精确余弦扫描；${if (valid > 0) "已就绪" else "等待生成有效向量"}")
                Text("最近成功索引：${indexedAt?.let(::relativeTime) ?: "暂无"}")
                Text("整库 $wholeCount 条；磁盘 ${sizes.values.sum()} 字节（整库，主库 + WAL + SHM）")
                Text("整库有效向量 $wholeValid，失效/待生成 ${wholeCount - wholeValid}；有效维度 ${wholeDimensions.sorted().joinToString().ifEmpty { "未知" }}")
                Text("有效向量要求模型、维度与内容版本一致；过期和归档的日常召回过滤另行统计。", style = MaterialTheme.typography.bodySmall)
                sizes.forEach { (name, bytes) -> Text("$name：$bytes 字节", style = MaterialTheme.typography.bodySmall) }
            }
            SemanticMemorySection("请求与召回统计", HugeIcons.Settings03) {
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
            }
        }
        items((if (whole) allEvents else assistantEvents).filter { it.operation == "recall" }.take(3), key = { "recall-${it.id}" }) { event ->
            Card { Column(Modifier.padding(12.dp)) {
                Text("最近实际召回 · ${relativeTime(event.startedAt)} · ${statusLabel(event.status)}")
                semanticRecallScoreLines(event).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
        }
        if (jobs.isNotEmpty() || imports.isNotEmpty()) item {
            Text("维护任务", style = MaterialTheme.typography.titleMedium)
        }
        items(jobs, key = { it.id }) { job -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("向量重建 · ${statusLabel(job.status)} · 当前批次 ${job.currentBatch}")
                if (job.items.isNotEmpty()) LinearProgressIndicator(
                    progress = { job.processed.toFloat() / job.items.size }, modifier = Modifier.fillMaxWidth())
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
        items(imports, key = { it.id }) { job -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("独立导入 · ${statusLabel(job.status)} · ${job.processed}/${job.total}")
                if (job.total > 0) LinearProgressIndicator(
                    progress = { job.processed.toFloat() / job.total }, modifier = Modifier.fillMaxWidth())
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
        if (report.errors.isNotEmpty()) item { Text("最近错误", style = MaterialTheme.typography.titleMedium) }
        items(report.errors, key = { it.id }) { event ->
            Card(Modifier.fillMaxWidth()) {
                Text("${relativeTime(event.startedAt)} · ${operationLabel(event.operation)}/${purposeLabel(event.purpose)} · ${event.error}",
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun countsText(c: SemanticOperationCounts) = "总计 ${c.total}，成功 ${c.success}，空结果 ${c.empty}，失败 ${c.failure}，取消 ${c.cancelled}，进行中 ${c.running}，中断未知 ${c.interrupted}；平均 ${"%.1f".format(c.averageMillis)} ms"
private fun purposeLabel(value: String) = when (value) { "connection" -> "连接测试"; "query" -> "查询"; "save" -> "保存"; "rebuild" -> "重建"; else -> value }
private fun operationLabel(value: String) = when (value) { "embedding" -> "向量请求"; "recall" -> "召回"; "storage" -> "向量落库"; else -> value }
private fun statusLabel(value: String) = when (value) {
    "pending" -> "待处理"; "running" -> "运行中"; "paused" -> "已暂停"; "cancelled" -> "已取消"
    "complete", "completed" -> "已完成"; "success" -> "成功"; "failure", "failed" -> "失败"; "empty" -> "空结果"
    "interrupted" -> "中断未知"; "conflict" -> "冲突"; "skipped" -> "已跳过"; else -> value
}
private fun relativeTime(time: Long) = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
