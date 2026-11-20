package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.memory.semantic.SemanticStateView
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun SemanticNarrativeStatePanel(
    view: SemanticStateView?,
    error: String?,
    enabled: Boolean,
    automatic: Boolean,
    canUpdate: Boolean,
    onUpdate: () -> Unit,
    onSources: (String) -> Unit,
    onLocate: (String) -> Unit,
) {
    CardGroup(modifier = Modifier.fillMaxWidth(), title = { Text("剧情状态") }) {
        formItem {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("状态随「做梦」更新。旧库首次会在后台补状态，不会重复生成旧回忆；" +
                    "之后在同一次做梦中提取回忆和状态。",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (automatic) "自动做梦已开启，状态跟随现有做梦设置更新，也可以手动点更新。"
                    else "自动做梦未开启，需要手动点「更新状态」。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("不会为状态每轮强制发起新调用。这是模型提取的状态预览，来源可核验不代表事实一定正确。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!enabled) Text("状态功能默认关闭，可在语义记忆设置中开启。",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (view != null) {
                    Text(semanticNarrativeCoverageLine(view), style = MaterialTheme.typography.labelMedium)
                    if (view.pendingMessages > 0) Text("覆盖缺口是尚未补入状态的原文；旧状态可能落后于当前剧情。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (error == null) {
                    Text("正在读取状态…", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = canUpdate && enabled, onClick = onUpdate) { Text("更新状态") }
            }
        }
        if (view != null) {
            semanticNarrativeGroups(view.checkpoint.entries).forEach { group ->
                formItem {
                    var expanded by rememberSaveable(view.checkpoint.assistantId, view.checkpoint.conversationId,
                        group.kind) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${group.label} · ${group.entries.size} 格", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall)
                            if (group.entries.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "收起" else "查看")
                            }
                        }
                        if (group.entries.isEmpty()) Text("暂无状态", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (expanded) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("主体／字段", Modifier.weight(0.45f), style = MaterialTheme.typography.labelSmall)
                                Text("当前值", Modifier.weight(0.55f), style = MaterialTheme.typography.labelSmall)
                            }
                            group.entries.forEach { entry ->
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("${entry.subject}\n${entry.facet}", Modifier.weight(0.45f),
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(entry.value.ifEmpty { "（空值）" }, Modifier.weight(0.55f),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Text("${semanticNarrativeStatusLabel(entry.status)} · 版本 ${entry.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (entry.reason.isNotBlank()) Text("理由：${entry.reason}",
                                    style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onSources(entry.id) }) { Text("查看来源") }
                                    entry.sources.firstOrNull { semanticNarrativeSourceCurrent(view, it) }?.let { source ->
                                        TextButton(onClick = { onLocate(source.nodeId) }) {
                                            Text("定位原文 ${source.position + 1}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
