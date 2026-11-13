package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.memory.semantic.DEFAULT_SEMANTIC_CONSOLIDATION_PROMPT
import me.rerere.rikkahub.data.memory.semantic.DEFAULT_SEMANTIC_REFLECTION_PROMPT
import me.rerere.rikkahub.data.memory.semantic.SemanticDreamSettings
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.extensions.InjectionPositionSelector
import me.rerere.rikkahub.ui.pages.extensions.InjectionRoleSelector
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticDreamSettingsPage(id: String) {
    val vm: SemanticDreamSettingsVM = koinViewModel(parameters = { parametersOf(id) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val schedule by vm.schedule.collectAsStateWithLifecycle()
    val events by vm.jevEvents.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val assistant = settings.assistants.find { it.id.toString() == id }
    val config = assistant?.semanticMemory?.dream ?: SemanticDreamSettings()
    val change: ((SemanticDreamSettings) -> SemanticDreamSettings) -> Unit = { vm.configure(it) }
    Scaffold(topBar = { TopAppBar(title = { Text("语义做梦设置") }, navigationIcon = { BackButton() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (assistant == null) { Text("助手不存在"); return@Column }
            Text("同一助手的各窗口共用这些设置，记忆内容分别保存。此页的手动执行和取消作用于助手的全部窗口。",
                style = MaterialTheme.typography.bodySmall)
            if (!assistant.semanticMemory.enabled) Text("语义记忆尚未启用。这些设置会保留，启用后才参与语义做梦；旧记忆设置独立保存。")
            SemanticMemorySection("自动做梦", HugeIcons.Moon02) {
                DreamSwitch("自动做梦", config.automatic) { value -> change { it.copy(automatic = value) } }
                Text("攒够完整轮数立即排队，否则空闲后排队；失败按指数退避自动重试，不做次数上限。Android 后台调度可能延后，无法保证严格时刻执行。",
                    style = MaterialTheme.typography.bodySmall)
                DreamInt("累计完整轮数（1—1000）", config.minCompletedTurns, 1..1000) { value -> change { it.copy(minCompletedTurns = value) } }
                DreamInt("空闲分钟（1—1440）", config.idleMinutes, 1..1440) { value -> change { it.copy(idleMinutes = value) } }
                DreamInt("定期整理间隔小时（1—720）", config.reviewIntervalHours, 1..720) { value -> change { it.copy(reviewIntervalHours = value) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = assistant.semanticMemory.enabled, onClick = vm::dreamNow) { Text("全部窗口做梦 / 重试") }
                    TextButton(onClick = { vm.cancel() }) { Text("取消全部窗口任务") }
                }
                schedule?.let { Text("调度状态：${dreamStatus(it.status)}；累计失败 ${it.attempts}。${it.error.orEmpty()}") }
                message?.let { Text(it) }
            }
            SemanticMemorySection("做梦模型", HugeIcons.AiMagic) {
                ModelSelector(modelId = config.modelId, providers = settings.providers, type = ModelType.CHAT,
                    onSelect = { model -> change { it.copy(modelId = model.id) } })
                TextButton(onClick = { change { it.copy(modelId = null) } }) { Text("使用全局默认聊天模型") }
                val actualModel = settings.findModelById(config.modelId ?: settings.chatModelId)
                Text("实际选择：${actualModel?.displayName?.ifBlank { actualModel.modelId } ?: "模型已失效，请重新选择"}。未指定时回退全局默认聊天模型，独立于旧工具模型。",
                    style = MaterialTheme.typography.bodySmall)
            }
            SemanticMemorySection("做梦提示词", HugeIcons.Edit01) {
                Text("提取用于从对话中记录记忆，归纳用于合并已有条目。自动和手动做梦共用这些设置；编辑后保存生效，留空使用默认。系统会保留必要的来源与输出格式要求。",
                    style = MaterialTheme.typography.bodySmall)
                DreamPromptEditor(id, "记忆提取提示词", config.reflectionPrompt, DEFAULT_SEMANTIC_REFLECTION_PROMPT) { value ->
                    change { it.copy(reflectionPrompt = value) }
                }
                DreamPromptEditor(id, "记忆归纳提示词", config.consolidationPrompt, DEFAULT_SEMANTIC_CONSOLIDATION_PROMPT) { value ->
                    change { it.copy(consolidationPrompt = value) }
                }
            }
            SemanticMemorySection("生成与预算", HugeIcons.Settings03) {
                DreamDecimal("温度（0—2）", config.temperature, 0.0..2.0) { value -> change { it.copy(temperature = value) } }
                var reasoningChoices by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { reasoningChoices = true }) { Text("推理强度：${config.reasoningLevel}") }
                    DropdownMenu(expanded = reasoningChoices, onDismissRequest = { reasoningChoices = false }) {
                        linkedMapOf("off" to "关闭", "auto" to "自动", "low" to "低", "medium" to "中", "high" to "高", "xhigh" to "很高", "max" to "最大").forEach { (level, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { change { it.copy(reasoningLevel = level) }; reasoningChoices = false })
                        }
                    }
                }
                Text("实际支持的推理等级由所选模型决定；过高的推理预算可能占用输出预算，截断时不会提交草稿。", style = MaterialTheme.typography.bodySmall)
                Text("不限制输入输出 token：输入按原文轮次完整发送，输出由模型自身上限决定。",
                    style = MaterialTheme.typography.bodySmall)
            }
            SemanticMemorySection("手动总结分块", HugeIcons.Database02) {
                DreamInt("每块轮数（0 表示不分块）", config.manualChunkTurns, 0..500) { value -> change { it.copy(manualChunkTurns = value) } }
                Text("只在手动总结时生效：用户消息加模型回复算一轮，按此轮数切块依次发送；填 0 则整段一次请求。自动做梦始终一次取全部待处理轮次。",
                    style = MaterialTheme.typography.bodySmall)
            }
            SemanticMemorySection("记忆注入", HugeIcons.Brain02) {
                SemanticInjectionSettings(config, change)
            }
            SemanticMemorySection("记忆归纳", HugeIcons.AiMagic) {
                DreamInt("每几次成功做梦检查归纳（1—100）", config.consolidationEveryRuns, 1..100) { value -> change { it.copy(consolidationEveryRuns = value) } }
                DreamInt("自动归纳最少候选（2—1000）", config.consolidationMinCandidates, 2..1000) { value -> change { it.copy(consolidationMinCandidates = value) } }
                DreamSwitch("保护核心记忆免于自动到期与归纳", config.protectCore) { value -> change { it.copy(protectCore = value) } }
            }
            SemanticMemorySection("非核心记忆保留天数", HugeIcons.Database02) {
                listOf("经历" to config.episodicTtlDays, "事实" to config.factualTtlDays, "关系" to config.relationalTtlDays,
                    "偏好" to config.preferenceTtlDays, "计划" to config.plannedTtlDays, "未知" to config.unknownTtlDays).forEachIndexed { index, (label, value) ->
                    DreamInt(label, value, 1..3650) { days -> change { old -> when (index) {
                        0 -> old.copy(episodicTtlDays = days); 1 -> old.copy(factualTtlDays = days); 2 -> old.copy(relationalTtlDays = days)
                        3 -> old.copy(preferenceTtlDays = days); 4 -> old.copy(plannedTtlDays = days); else -> old.copy(unknownTtlDays = days)
                    } } }
                }
            }
            SemanticMemorySection("Jev 可选辅助", HugeIcons.AiMagic) {
                DreamSwitch("Jev 辅助总开关", config.jevEnabled) { value -> change { it.copy(jevEnabled = value) } }
                DreamSwitch("召回候选重排", config.jevRecall) { value -> change { it.copy(jevRecall = value) } }
                DreamSwitch("做梦候选审查", config.jevDreamReview) { value -> change { it.copy(jevDreamReview = value) } }
                Text("复用华灯设置的 Jev /v1/systemone 连接；保持基础 Embedding 与混合检索。重排按相关性降序取语义记忆设置的 TopK，做梦审查只筛选草稿；判断失败或缺答整体回退原融合顺序，不删除记忆、不充当新证据；重排分数只表示相关性，不代表记忆真实性。",
                    style = MaterialTheme.typography.bodySmall)
                DreamInt("送进 Jev 的粗召回条数（1—100）", config.jevCandidateCount, 1..100) { value -> change { it.copy(jevCandidateCount = value) } }
                DreamDecimalField("重排最低相关性（0—1，0 表示不过滤）", config.jevMinRelevance, 0.0..1.0) { value -> change { it.copy(jevMinRelevance = value) } }
                Text("粗召回按融合顺序取前 N 条送进重排，超过 32 个问题或 64KB 请求体自动分批；N 受候选池大小限制。最低相关性为 0 时只排序不硬过滤，过滤后为空表示本轮没有相关记忆。",
                    style = MaterialTheme.typography.bodySmall)
                DreamInt("Jev 全批请求预算毫秒（250—10000）", config.jevTimeoutMillis, 250..10000) { value -> change { it.copy(jevTimeoutMillis = value) } }
                DreamDecimal("召回肯定概率门槛", config.jevRecallYes, 0.5..1.0) { value -> if (value > config.jevRecallNo) change { it.copy(jevRecallYes = value) } }
                DreamDecimal("召回否定概率门槛", config.jevRecallNo, 0.0..0.5) { value -> if (value < config.jevRecallYes) change { it.copy(jevRecallNo = value) } }
                DreamDecimal("长期价值肯定概率门槛", config.jevRetentionYes, 0.5..1.0) { value -> if (value > config.jevRetentionNo) change { it.copy(jevRetentionYes = value) } }
                DreamDecimal("长期价值否定概率门槛", config.jevRetentionNo, 0.0..0.5) { value -> if (value < config.jevRetentionYes) change { it.copy(jevRetentionNo = value) } }
                DreamDecimal("原文支持肯定概率门槛", config.jevEvidenceYes, 0.5..1.0) { value -> if (value > config.jevEvidenceNo) change { it.copy(jevEvidenceYes = value) } }
                DreamDecimal("原文支持否定概率门槛", config.jevEvidenceNo, 0.0..0.5) { value -> if (value < config.jevEvidenceYes) change { it.copy(jevEvidenceNo = value) } }
                Text("Noul 表示肯定概率，没有独立 confidence。两道门槛之间回退；这些初始门槛仍需真实回放校准。", style = MaterialTheme.typography.bodySmall)
            }
            SemanticMemorySection("最近 Jev 诊断", HugeIcons.Database02) {
                events.forEach { event -> Text("${if (event.purpose == "recall") "召回筛选" else "做梦审查"} · ${dreamStatus(event.status)} · ${"%.1f".format(event.durationNanos / 1_000_000.0)} ms\n${jevDiagnosticText(event.detailJson)}\n${event.error.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall) }
                if (events.isEmpty()) Text("尚无 Jev 辅助请求。开启后会记录原候选、保留/排除、不确定及额外耗时。")
            }
        }
    }
}

@Composable
internal fun SemanticInjectionSettings(
    config: SemanticDreamSettings,
    change: ((SemanticDreamSettings) -> SemanticDreamSettings) -> Unit,
) {
    Text("记忆注入位置", style = MaterialTheme.typography.titleMedium)
    InjectionPositionSelector(config.injectionPosition) { position ->
        change { it.copy(injectionPosition = position) }
    }
    if (config.injectionPosition == InjectionPosition.BEFORE_SYSTEM_PROMPT ||
        config.injectionPosition == InjectionPosition.AFTER_SYSTEM_PROMPT) {
        Text("并入主系统提示词，使用 system 身份。", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("注入身份")
        InjectionRoleSelector(MessageRole.valueOf(config.injectionRole.uppercase()), onSelect = { role ->
            change { it.copy(injectionRole = role.name.lowercase()) }
        })
    }
    when (config.injectionPosition) {
        InjectionPosition.AT_DEPTH -> {
            DreamInt("注入深度（0—1000）", config.injectionDepth, 0..1000) { value ->
                change { it.copy(injectionDepth = value) }
            }
            Text("深度 0 在聊天末尾，1 在最后一条消息前；遇工具调用边界会调整到安全位置。",
                style = MaterialTheme.typography.bodySmall)
        }
        InjectionPosition.AUTHOR_NOTE ->
            Text("跟随全局作者备注的位置与深度；放在主系统提示词之前时使用 system 身份。",
                style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
    DreamInt("注入 token 预算（64—16000）", config.injectionTokenBudget, 64..16000) { value ->
        change { it.copy(injectionTokenBudget = value) }
    }
    Text("记忆正文不截断，预算不足跳过整条。", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun DreamPromptEditor(
    assistantId: String, label: String, value: String, defaultPrompt: String, save: (String) -> Unit,
) {
    val current = value.ifBlank { defaultPrompt }
    var draft by rememberSaveable(assistantId, label, value) { mutableStateOf(current) }
    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10)
    Row {
        TextButton(enabled = draft != current, onClick = { save(draft) }) { Text("保存提示词") }
        TextButton(onClick = { draft = defaultPrompt; save("") }) { Text("恢复默认提示词") }
    }
}

@Composable private fun DreamSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = change)
    }
}
@Composable private fun DreamInt(label: String, value: Int, range: IntRange, change: (Int) -> Unit) =
    DreamNumber(label, value.toString()) { raw -> raw.toIntOrNull()?.takeIf { it in range }?.let(change) }
@Composable private fun DreamDecimal(label: String, value: Double, range: ClosedFloatingPointRange<Double>, change: (Double) -> Unit) =
    SemanticDecimalSlider(label, value, range, onChange = change)
/** 数值项统一用输入框：阈值 0 必须能精确填写，滑块做不到。 */
@Composable private fun DreamDecimalField(label: String, value: Double, range: ClosedFloatingPointRange<Double>, change: (Double) -> Unit) {
    var draft by remember(label) { mutableStateOf(decimalText(value)) }
    var focused by remember(label) { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) draft = decimalText(value) }
    OutlinedTextField(value = draft, onValueChange = { text ->
        draft = text
        text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in range }?.let(change)
    }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
}
private fun decimalText(value: Double) =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
@Composable private fun DreamNumber(label: String, value: String, change: (String) -> Unit) {
    var draft by remember(label) { mutableStateOf(value) }
    var focused by remember(label) { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) draft = value }
    OutlinedTextField(value = draft, onValueChange = { draft = it; change(it) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
}
private fun dreamStatus(value: String) = when (value) {
    "pending" -> "等待执行"; "running" -> "运行中"; "completed", "success" -> "已完成"
    "failed", "failure" -> "失败并保留待办"; "cancelled" -> "已取消"; else -> value
}
private fun jevDiagnosticText(raw: String): String = try {
    val details = Json.parseToJsonElement(raw).jsonObject
    // 表达式体函数里不能 return，重排事件走独立分支。
    if ((details["mode"] as? JsonPrimitive)?.contentOrNull == "rerank") {
        rerankDiagnosticText(details)
    } else {
        val before = details["before"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val after = details["after"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        "原候选 ${before.size}，保留 ${after.size}，排除 ${before.size - after.size}，不确定 ${details["uncertain"]?.jsonPrimitive?.intOrNull ?: 0}。\n" +
            "原候选 ID：${before.take(8).joinToString()}\n保留 ID：${after.take(8).joinToString()}" +
            if (before.size > 8) "\n此处显示前8条，完整判断分布与映射保存在诊断记录中。" else ""
    }
} catch (_: Exception) { "诊断尚未完成" }

/** 召回重排事件：候选分数、注入条数、批次数、耗时与回退原因。 */
private fun rerankDiagnosticText(details: JsonObject): String {
    val items = details["items"]?.jsonArray.orEmpty().map { it.jsonObject }
    val kept = items.filter { (it["kept"] as? JsonPrimitive)?.booleanOrNull == true }
    val scores = items.mapNotNull { (it["score"] as? JsonPrimitive)?.takeIf { s -> s !is JsonNull }?.doubleOrNull }
    val low = (details["scoreDistribution"] as? JsonObject)?.let { (it["min"] as? JsonPrimitive)?.doubleOrNull }
    val high = (details["scoreDistribution"] as? JsonObject)?.let { (it["max"] as? JsonPrimitive)?.doubleOrNull }
    val distribution = if (low == null || high == null) "无" else "%.2f".format(low) + "—" + "%.2f".format(high)
    val fallback = (details["fallback"] as? JsonPrimitive)?.contentOrNull
    val lines = items.take(8).joinToString("；") { item ->
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val score = (item["score"] as? JsonPrimitive)?.takeIf { s -> s !is JsonNull }?.doubleOrNull
        val rank = (item["finalRank"] as? JsonPrimitive)?.intOrNull
        id + "=" + (score?.let { "%.2f".format(it) } ?: "未评分") + (rank?.let { "#" + it } ?: "")
    }
    return "召回重排 " + items.size + " 条，已评分 " + scores.size + "，注入 " + kept.size +
        "，批次 " + ((details["batches"] as? JsonPrimitive)?.intOrNull ?: 0) +
        "；分数 " + distribution + "，最低相关性 " + ((details["minRelevance"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) +
        "，TopK " + ((details["topK"] as? JsonPrimitive)?.intOrNull ?: 0) +
        "，耗时 " + "%.1f".format((details["elapsedMillis"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) + " ms" +
        (fallback?.let { "\n回退原因：" + it } ?: "") + "\n候选分数：" + lines +
        if (items.size > 8) "\n此处显示前8条，完整分数与排名保存在诊断记录中。" else ""
}
