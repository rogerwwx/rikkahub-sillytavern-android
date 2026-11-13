package me.rerere.rikkahub.ui.pages.assistant.detail

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Archive
import me.rerere.hugeicons.stroke.ArchiveRestore
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Circle
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Filter
import me.rerere.hugeicons.stroke.Link02
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.RefreshDot
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.data.memory.semantic.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
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
    val vm: SemanticMemoryVM = koinViewModel(key = "${id}:${conversationId}", parameters = { parametersOf(id, conversationId) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val assistant = settings.assistants.find { it.id.toString() == id }
    val all by vm.memories.collectAsStateWithLifecycle()
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
            IconButton(enabled = assistant != null && !busy, onClick = {
                isNew = true
                editor = SemanticMemoryEntity(assistantId = id, conversationId = conversationId, content = "")
            }) {
                Icon(HugeIcons.Add01, contentDescription = "新增记忆", modifier = Modifier.size(20.dp))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = page, containerColor = Color.Transparent) {
                listOf("管理", "设置", "诊断", "做梦").forEachIndexed { index, label ->
                    val icon = listOf(HugeIcons.Brain02, HugeIcons.Settings03, HugeIcons.Database02, HugeIcons.Moon02)[index]
                    Tab(selected = page == index, onClick = { page = index }, text = { Text(label) },
                        icon = { Icon(icon, null, Modifier.size(20.dp)) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { value ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.message.value = null }) { Text("知道了") }
                    }
                }
            }
            when (page) {
                0 -> SemanticMemoryListTab(
                    vm = vm,
                    total = all.size,
                    busy = busy,
                    onCreate = {
                        isNew = true
                        editor = SemanticMemoryEntity(assistantId = id, conversationId = conversationId, content = "")
                    },
                    onEdit = { memory -> isNew = false; editor = memory },
                    onDeleteRequest = { deleteIds = it },
                )
                1 -> assistant?.let { current ->
                    SemanticSettingsPanel(
                        config = current.semanticMemory,
                        providers = settings.providers,
                        busy = busy,
                        memoryCount = all.size,
                        selectedCount = selected.size,
                        configure = vm::configure,
                        test = vm::testConnection,
                        onExportAll = {
                            exportIds = null
                            exportLauncher.launch("semantic-memory.json")
                        },
                        onExportSelected = {
                            exportIds = selected.toSet()
                            exportLauncher.launch("semantic-memory-selected.json")
                        },
                        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    )
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
        AlertDialog(onDismissRequest = { deleteIds = emptySet() }, title = { Text("删除 " + actual.size + " 条语义记忆？") },
            text = { Text("按确认时仍存在的稳定 ID 删除；相关向量同时删除。") },
            confirmButton = { TextButton(enabled = actual.isNotEmpty(), onClick = {
                vm.delete(actual); editor = null; deleteIds = emptySet()
            }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteIds = emptySet() }) { Text("取消") } })
    }
}

/** 管理页：顶部搜索／筛选／更多，长按进入多选，选中后浮出底栏。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SemanticMemoryListTab(
    vm: SemanticMemoryVM,
    total: Int,
    busy: Boolean,
    onCreate: () -> Unit,
    onEdit: (SemanticMemoryEntity) -> Unit,
    onDeleteRequest: (Set<String>) -> Unit,
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val type by vm.typeFilter.collectAsStateWithLifecycle()
    val status by vm.statusFilter.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val filtered by vm.filtered.collectAsStateWithLifecycle()
    var overflowOpen by remember { mutableStateOf(false) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    val filterSummary = listOfNotNull(
        memoryTypes[type].takeIf { type != "all" },
        memoryStatuses[status].takeIf { status != "all" },
    ).joinToString(" · ")

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { vm.query.value = it; vm.clearSelection() },
                            placeholder = { Text("搜索记忆", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(HugeIcons.Search01, null, Modifier.size(18.dp)) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { vm.query.value = "" }) {
                                        Icon(HugeIcons.Cancel01, contentDescription = "清除搜索",
                                            modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            IconButton(onClick = { filterOpen = true }) {
                                Icon(HugeIcons.Filter, contentDescription = "筛选记忆",
                                    modifier = Modifier.size(20.dp),
                                    tint = if (filterSummary.isNotEmpty()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (filterSummary.isNotEmpty()) {
                                Spacer(Modifier.align(Alignment.TopEnd)
                                    .padding(top = 7.dp, end = 9.dp)
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape))
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(HugeIcons.MoreVertical, contentDescription = "更多操作",
                                    modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("全选（${filtered.size} 条）") },
                                    enabled = filtered.isNotEmpty(),
                                    leadingIcon = { Icon(HugeIcons.CheckList, null, Modifier.size(18.dp)) },
                                    onClick = { vm.selectAll(); overflowOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("反选") },
                                    enabled = filtered.isNotEmpty(),
                                    leadingIcon = { Icon(HugeIcons.ArrowTurnBackward, null, Modifier.size(18.dp)) },
                                    onClick = { vm.invertSelection(); overflowOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("全不选") },
                                    enabled = selected.isNotEmpty(),
                                    leadingIcon = { Icon(HugeIcons.Cancel01, null, Modifier.size(18.dp)) },
                                    onClick = { vm.clearSelection(); overflowOpen = false },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("重向量化筛选结果（${filtered.size} 条）") },
                                    enabled = filtered.isNotEmpty() && !busy,
                                    leadingIcon = { Icon(HugeIcons.Refresh01, null, Modifier.size(18.dp)) },
                                    onClick = { vm.rebuild(filtered.map { it.id }); overflowOpen = false },
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selected.isEmpty()) "共 ${filtered.size} 条" else "已选 ${selected.size} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = filterSummary.ifEmpty { if (total > 0) "长按多选" else "" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = if (selected.isEmpty()) 16.dp else 96.dp),
                    ) {
                        items(filtered, key = { it.id }) { memory ->
                            SemanticMemoryRow(
                                memory = memory,
                                selected = memory.id in selected,
                                selectionMode = selected.isNotEmpty(),
                                onToggle = { vm.toggle(memory.id) },
                                onOpen = { onEdit(memory) },
                                onInspect = { vm.inspectSources(memory.id) },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 62.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                            )
                        }
                        if (filtered.isEmpty()) {
                            item {
                                EmptyMemoryState(
                                    allEmpty = total == 0,
                                    filteredOut = query.isNotBlank() || filterSummary.isNotEmpty(),
                                    onCreate = onCreate,
                                )
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = selected.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            SemanticSelectionBar(
                count = selected.size,
                busy = busy,
                onClear = vm::clearSelection,
                onArchive = { vm.lifecycle("archived") },
                onSleep = { vm.lifecycle("dormant") },
                onRestore = { vm.lifecycle("active") },
                onOrganize = vm::organizeSelected,
                onRebuild = { vm.rebuild(selected.toList()) },
                onDelete = { onDeleteRequest(selected) },
            )
        }
    }
    if (filterOpen) {
        ModalBottomSheet(onDismissRequest = { filterOpen = false }) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("筛选记忆", style = MaterialTheme.typography.titleMedium)
                Text("状态", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    memoryStatuses.forEach { (key, label) ->
                        FilterChip(selected = status == key, label = { Text(label) },
                            onClick = { vm.statusFilter.value = key; vm.clearSelection() })
                    }
                }
                Text("类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    memoryTypes.forEach { (key, label) ->
                        FilterChip(selected = type == key, label = { Text(label) },
                            onClick = { vm.typeFilter.value = key; vm.clearSelection() })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        vm.typeFilter.value = "all"; vm.statusFilter.value = "all"; vm.clearSelection()
                    }) { Text("重置") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { filterOpen = false }) { Text("完成") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SemanticMemoryRow(
    memory: SemanticMemoryEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onInspect: () -> Unit,
) {
    val tone = memoryTone(memory.type)
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .combinedClickable(onClick = if (selectionMode) onToggle else onOpen, onLongClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(tone.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(memoryStatusIcon(memory.status, memory.isCore), contentDescription = null,
                modifier = Modifier.size(17.dp), tint = tone)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(memory.content, style = MaterialTheme.typography.bodyMedium,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MemoryChip(memoryTypes[memory.type] ?: memory.type, tone)
                if (memory.isCore) MemoryChip("核心", MaterialTheme.colorScheme.primary)
                if (memory.status != "active") {
                    MemoryChip(memoryStatuses[memory.status] ?: memory.status, statusTone(memory.status))
                }
                Spacer(Modifier.weight(1f))
                ImportanceMeter(memory.importance)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(relativeTime(memory.updatedAt), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                memory.expiresAt?.let {
                    Text("到期 " + relativeTime(it), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onInspect, modifier = Modifier.size(22.dp)) {
                    Icon(HugeIcons.Link02, contentDescription = "查看原始来源",
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (selectionMode) {
            Icon(
                imageVector = if (selected) HugeIcons.CheckmarkCircle02 else HugeIcons.Circle,
                contentDescription = if (selected) "已选中" else "未选中",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 7.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun MemoryChip(text: String, tone: Color) {
    Surface(shape = RoundedCornerShape(7.dp), color = tone.copy(alpha = 0.14f), contentColor = tone) {
        Text(text, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun ImportanceMeter(value: Double) {
    val filled = (value * 5).roundToInt().coerceIn(0, 5)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { index ->
            Spacer(
                Modifier.size(4.dp).background(
                    if (index < filled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    CircleShape,
                )
            )
        }
    }
}

@Composable
private fun EmptyMemoryState(allEmpty: Boolean, filteredOut: Boolean, onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(HugeIcons.Brain02, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Text(if (allEmpty) "这个窗口还没有记忆" else "没有符合条件的记忆",
            style = MaterialTheme.typography.titleSmall)
        Text(
            text = when {
                allEmpty -> "可以手动新增，或在「做梦」页从对话中提取"
                filteredOut -> "换个关键词，或点上方筛选重置条件"
                else -> "稍后可回来再看看"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (allEmpty) TextButton(onClick = onCreate) { Text("新增一条记忆") }
    }
}

@Composable
private fun SemanticSelectionBar(
    count: Int,
    busy: Boolean,
    onClear: () -> Unit,
    onArchive: () -> Unit,
    onSleep: () -> Unit,
    onRestore: () -> Unit,
    onOrganize: () -> Unit,
    onRebuild: () -> Unit,
    onDelete: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 " + count, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            SelectionAction(HugeIcons.Archive, "归档", enabled = !busy, onClick = onArchive)
            SelectionAction(HugeIcons.Refresh01, "重向量化", enabled = !busy, onClick = onRebuild)
            SelectionAction(HugeIcons.Delete01, "删除", enabled = !busy,
                tint = MaterialTheme.colorScheme.error, onClick = onDelete)
            Box {
                IconButton(onClick = { more = true }) {
                    Icon(HugeIcons.MoreVertical, contentDescription = "更多", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("整理为归纳记忆") }, enabled = count >= 2 && !busy,
                        leadingIcon = { Icon(HugeIcons.MagicWand01, null, Modifier.size(18.dp)) },
                        onClick = { more = false; onOrganize() })
                    DropdownMenuItem(text = { Text("休眠") }, enabled = !busy,
                        leadingIcon = { Icon(HugeIcons.Moon02, null, Modifier.size(18.dp)) },
                        onClick = { more = false; onSleep() })
                    DropdownMenuItem(text = { Text("恢复为活跃") }, enabled = !busy,
                        leadingIcon = { Icon(HugeIcons.ArchiveRestore, null, Modifier.size(18.dp)) },
                        onClick = { more = false; onRestore() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("全不选") },
                        onClick = { more = false; onClear() })
                }
            }
        }
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = tint, disabledContentColor = tint.copy(alpha = 0.38f)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun memoryTone(type: String): Color = when (type) {
    "episodic" -> MaterialTheme.colorScheme.primary
    "factual" -> MaterialTheme.colorScheme.tertiary
    "relational" -> MaterialTheme.colorScheme.secondary
    "preference" -> MaterialTheme.colorScheme.tertiary
    "planned" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun statusTone(status: String): Color = when (status) {
    "active" -> MaterialTheme.colorScheme.primary
    "expired", "superseded", "forgotten" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

private fun memoryStatusIcon(status: String, isCore: Boolean): ImageVector = when (status) {
    "archived" -> HugeIcons.Archive
    "dormant" -> HugeIcons.Moon02
    "expired" -> HugeIcons.Clock01
    "superseded" -> HugeIcons.RefreshDot
    "forgotten" -> HugeIcons.ViewOff
    else -> if (isCore) HugeIcons.Sparkles else HugeIcons.Brain02
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardGroup(modifier = Modifier.fillMaxWidth(), title = { Text("做梦进度") }) {
            formItem {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DreamMetric("已处理", processedMessages, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        Spacer(Modifier.width(1.dp).height(28.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                        DreamMetric("待处理", messages.size - processedMessages,
                            MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        Spacer(Modifier.width(1.dp).height(28.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                        DreamMetric("原文", messages.size, MaterialTheme.colorScheme.outline, Modifier.weight(1f))
                    }
                    LinearProgressIndicator(
                        progress = { if (messages.isEmpty()) 0f else processedMessages.toFloat() / messages.size },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (history == null) "正在读取历史…"
                            else "成功处理 " + history.successfulRuns + " 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f),
                        )
                        schedule?.let {
                            MemoryChip("后台" + statusLabel(it.status) + " · 尝试 " + it.attempts,
                                MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        CardGroup(modifier = Modifier.fillMaxWidth(), title = { Text("手动做梦") }) {
            formItem {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("未处理历史", "全部历史", "指定范围").forEachIndexed { index, label ->
                            FilterChip(selected = mode == index, onClick = { mode = index }, label = { Text(label) })
                        }
                    }
                    Text(when (mode) {
                        0 -> "从最早的未处理完整轮次继续，包含压缩隐藏的原文。"
                        1 -> "重新梳理本窗口全部原文，后台按顺序分片处理。"
                        else -> "按聊天里显示的楼层取区间，摘要和分隔线不占楼层。"
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
                        if (!validRange && messages.isNotEmpty()) Text(
                            "请输入 1～" + messages.size + " 之间且起点不大于终点的楼层。",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        TextButton(onClick = vm::cancelDream) { Text("取消任务") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("断流、取消或来源变化不会提交草稿",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = vm::retryDreamEmbedding) {
                            Text("重试向量待办", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        CardGroup(modifier = Modifier.fillMaxWidth(), title = { Text("设置") }) {
            item(
                onClick = openSettings,
                headlineContent = { Text("模型、自动做梦、生命周期与 Jev") },
                supportingContent = { Text("整理入口在管理页：长按多选后点底栏「更多」") },
                leadingContent = { CardGroupIcon(HugeIcons.Settings03) },
                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (runs.isNotEmpty()) {
            CardGroup(modifier = Modifier.fillMaxWidth(), title = { Text("最近任务") }) {
                runs.take(8).forEach { run ->
                    item(
                        headlineContent = { Text(run.title()) },
                        supportingContent = {
                            Text(buildString {
                                append("更新 ").append(run.changed).append(" 条 · 尝试 ").append(run.attempts)
                                if (run.operation == "organize") append(" · 所选 ").append(run.selectedIds.size)
                                run.error?.let { append(" · ").append(it.take(40)) }
                            })
                        },
                        trailingContent = {
                            Text(statusLabel(run.status), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DreamMetric(label: String, value: Int, tone: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = tone)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemanticSettingsPanel(
    config: SemanticMemorySettings,
    providers: List<ProviderSetting>,
    busy: Boolean,
    memoryCount: Int,
    selectedCount: Int,
    configure: ((SemanticMemorySettings) -> SemanticMemorySettings) -> Unit,
    test: () -> Unit,
    onExportAll: () -> Unit,
    onExportSelected: () -> Unit,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SemanticMemorySection("语义记忆", HugeIcons.Brain02) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用当前助手的语义记忆")
                    Text("每个对话窗口独立保存和召回记忆；模型、召回和注入设置由同一助手的窗口共用。",
                        style = MaterialTheme.typography.bodySmall,
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
            Text("切换模型后旧向量会失效，请在管理页的「更多」里重建。失效期间仍可使用 BM25 关键词召回。",
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
        CardGroup(modifier = Modifier.fillMaxWidth(), title = { Text("数据管理") }) {
            item(
                headlineContent = { Text("导出全部记忆") },
                supportingContent = { Text("当前窗口 " + memoryCount + " 条记忆的内容与元数据，不含向量缓存") },
                leadingContent = { CardGroupIcon(HugeIcons.Download04) },
                trailingContent = {
                    OutlinedButton(enabled = !busy && memoryCount > 0, onClick = onExportAll) { Text("导出") }
                },
            )
            item(
                headlineContent = { Text("导入记忆档案") },
                supportingContent = { Text("按稳定 ID 去重，保留原来源与生命周期状态") },
                leadingContent = { CardGroupIcon(HugeIcons.Upload02) },
                trailingContent = {
                    OutlinedButton(enabled = !busy, onClick = onImport) { Text("导入") }
                },
            )
            if (selectedCount > 0) item(
                headlineContent = { Text("导出所选") },
                supportingContent = { Text("仅导出管理页已选中的 " + selectedCount + " 条") },
                leadingContent = { CardGroupIcon(HugeIcons.Download04) },
                trailingContent = {
                    OutlinedButton(enabled = !busy, onClick = onExportSelected) { Text("导出") }
                },
            )
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            Text("类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                memoryTypes.filterKeys { it != "all" }.forEach { (key, label) ->
                    FilterChip(selected = type == key, onClick = { type = key }, label = { Text(label) })
                }
            }
            Text("状态", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                memoryStatuses.filterKeys { it != "all" }.forEach { (key, label) ->
                    FilterChip(selected = status == key, onClick = { status = key }, label = { Text(label) })
                }
            }
            NumberSetting("重要度（0—1）", importance) { importance = it }
            NumberSetting("置信度（0—1）", confidence) { confidence = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("核心记忆", Modifier.weight(1f))
                Switch(core, { core = it })
            }
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

private fun SemanticDreamRunState.title(): String = when {
    operation == "organize" -> if (trigger == "auto") "记忆整理（自动）" else "记忆整理（手动）"
    trigger == "range" -> "历史反思（指定范围）"
    trigger == "manual" -> "历史反思（手动）"
    else -> "历史反思（自动）"
}
/** 诊断页对齐 rikkars 的调试统计：分组卡片 + 紧凑的标签／取值行。 */
@Composable
private fun SemanticDiagnosticsPanel(vm: SemanticMemoryVM, windowTotal: Int, busy: Boolean) {
    val assistantEvents by vm.events.collectAsStateWithLifecycle()
    val allEvents by vm.allEvents.collectAsStateWithLifecycle()
    val sizes by vm.diskSizes.collectAsStateWithLifecycle()
    val windowActive by vm.activeCount.collectAsStateWithLifecycle()
    val windowValid by vm.validCount.collectAsStateWithLifecycle()
    val windowDimensions by vm.dimensions.collectAsStateWithLifecycle()
    val indexedAt by vm.indexedAt.collectAsStateWithLifecycle()
    val wholeCount by vm.wholeLibraryCount.collectAsStateWithLifecycle()
    val wholeActive by vm.wholeActiveCount.collectAsStateWithLifecycle()
    val wholeValid by vm.wholeValidCount.collectAsStateWithLifecycle()
    val wholeDimensions by vm.wholeDimensions.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    val recallQuery by vm.recallQuery.collectAsStateWithLifecycle()
    val recallResult by vm.recallResult.collectAsStateWithLifecycle()
    var whole by rememberSaveable { mutableStateOf(false) }
    val events = if (whole) allEvents else assistantEvents
    val report = remember(events) { semanticDiagnostics(events) }
    val lastRecall = remember(events) { events.filter { it.operation == "recall" }.maxByOrNull { it.startedAt } }
    val total = if (whole) wholeCount.toInt() else windowTotal
    val active = if (whole) wholeActive else windowActive
    val valid = if (whole) wholeValid else windowValid
    val dimensions = if (whole) wholeDimensions else windowDimensions
    val recentError = report.errors.firstOrNull()
    val belowSimilarity = report.filters["below_similarity"] ?: 0
    val invalidVector = report.filters["invalid_vector"] ?: 0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        item {
            CardGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                title = { Text("召回测试") }) {
                formItem {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = recallQuery,
                            onValueChange = { vm.recallQuery.value = it },
                            placeholder = { Text("输入一句话测试真实召回") },
                            leadingIcon = { Icon(HugeIcons.Search01, null, Modifier.size(18.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.testRecall() }, enabled = !busy && recallQuery.isNotBlank()) {
                                Text("运行召回")
                            }
                            Text("只检索本窗口", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                        recallResult?.let { result ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Text(result.hits.size.toString() + " 条命中 · " +
                                "%.1f".format(result.durationNanos / 1_000_000.0) + " ms",
                                style = MaterialTheme.typography.labelMedium)
                            Text("候选 BM25 " + result.lexicalCandidates + " · 向量 " + result.vectorCandidates,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            result.degraded.forEach {
                                Text(it, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            result.hits.take(5).forEach { hit ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(hit.memory.content, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(recallScoreLine(hit), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            CardGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                title = { Text("调试统计") }) {
                formItem {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = !whole, onClick = { whole = false }, label = { Text("当前窗口") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(selected = whole, onClick = { whole = true }, label = { Text("整库") })
                        }
                        StatRow("记忆条目", total.toString() + " 条")
                        StatRow("活跃条目", active.toString() + " 条")
                        StatRow("有效向量", valid.toString() + " 条")
                        StatRow("待生成向量", (active - valid).toString() + " 条")
                        StatRow("向量维度", dimensions.sorted().joinToString().ifEmpty { "未知" })
                        if (!whole) StatRow("最近索引", indexedAt?.let { relativeTime(it) } ?: "暂无")
                        StatRow("磁盘占用", formatBytes(sizes.values.sum()))
                        StatDivider()
                        StatRow("Embedding 请求", report.embedding.total.toString())
                        StatRow("成功 / 失败", report.embedding.success.toString() + " / " + report.embedding.failure)
                        StatRow("平均耗时", "%.1f".format(report.embedding.averageMillis) + " ms")
                        StatRow("向量落库", "成功 " + report.storageSuccess + " · 冲突 " + report.storageConflict +
                            " · 失败 " + report.storageFailure)
                        StatDivider()
                        StatRow("召回次数", report.recall.total.toString())
                        StatRow("上次召回命中", lastRecall?.let { semanticRecallScoreLines(it).size.toString() + " 条" } ?: "暂无")
                        StatRow("上次召回", lastRecall?.let { relativeTime(it.startedAt) } ?: "暂无")
                        StatRow("降级次数", report.degraded.toString())
                        StatRow("过滤（阈值 / 无效向量）", belowSimilarity.toString() + " / " + invalidVector)
                        recentError?.let {
                            StatRow("最近错误", it.error.orEmpty().take(60), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        if (jobs.isNotEmpty() || imports.isNotEmpty()) {
            item {
                CardGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    title = { Text("维护任务") }) {
                    jobs.forEach { job ->
                        formItem(
                            headlineContent = { Text("向量重建 · " + statusLabel(job.status)) },
                            supportingContent = {
                                Text(job.processed.toString() + "/" + job.items.size + " · 成功 " +
                                    job.success + " · 失败 " + job.failed + " · 跳过 " + job.skipped)
                            },
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (job.items.isNotEmpty()) LinearProgressIndicator(
                                    progress = { job.processed.toFloat() / job.items.size },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                job.error?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                Row {
                                    if (job.status in setOf("pending", "running")) TextButton(onClick = { vm.cancelRebuild(job.id) }) { Text("取消") }
                                    if (job.status in setOf("cancelled", "paused")) TextButton(enabled = !busy, onClick = { vm.resumeRebuild(job.id, false) }) { Text("恢复待办") }
                                    if (job.failed > 0 && job.status !in setOf("pending", "running")) TextButton(enabled = !busy, onClick = { vm.resumeRebuild(job.id, true) }) { Text("只重试失败") }
                                }
                                job.items.filter { it.error != null }.take(5).forEach {
                                    Text(it.id + "：" + it.error, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                    imports.forEach { job ->
                        formItem(
                            headlineContent = { Text("独立导入 · " + statusLabel(job.status)) },
                            supportingContent = {
                                Text(job.processed.toString() + "/" + job.total + " · 新增 " + job.imported +
                                    " · 重复 " + job.duplicates + " · 冲突 " + job.conflicts)
                            },
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (job.total > 0) LinearProgressIndicator(
                                    progress = { job.processed.toFloat() / job.total },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row {
                                    if (job.status == "pending") {
                                        TextButton(onClick = { vm.cancelImport(job.id) }) { Text("取消") }
                                        TextButton(enabled = !busy, onClick = { vm.resumeImport(job.id) }) { Text("继续导入") }
                                    }
                                    if (job.status in setOf("cancelled", "failure")) {
                                        TextButton(enabled = !busy, onClick = { vm.resumeImport(job.id) }) { Text("恢复导入") }
                                    }
                                }
                                job.errors.takeLast(3).forEach {
                                    Text(it, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, tint: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = tint ?: MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

private fun recallScoreLine(hit: SemanticRecallHit): String {
    val lexical = hit.rank.lexicalScore?.let { "BM25 " + "%.3f".format(it) } ?: "BM25 无"
    val vector = hit.rank.vectorScore?.let { "余弦 " + "%.3f".format(it) } ?: "余弦 无"
    val fused = "RRF " + "%.4f".format(hit.rank.score)
    val note = if (hit.rank.vectorRanked) "" else " · 仅关键词入榜"
    return lexical + " · " + vector + " · " + fused + note
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= (1L shl 20) -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}

private fun statusLabel(value: String) = when (value) {
    "pending" -> "待处理"; "running" -> "运行中"; "paused" -> "已暂停"; "cancelled" -> "已取消"
    "complete", "completed" -> "已完成"; "success" -> "成功"; "failure", "failed" -> "失败"; "empty" -> "空结果"
    "interrupted" -> "中断未知"; "conflict" -> "冲突"; "skipped" -> "已跳过"; else -> value
}

private fun relativeTime(time: Long) = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
