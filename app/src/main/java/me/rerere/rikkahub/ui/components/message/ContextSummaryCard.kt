package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.l10n

@Composable
internal fun ContextSummaryCard(
    message: UIMessage,
    onEdit: () -> Unit,
    onMore: () -> Unit,
    loading: Boolean,
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = l10n("上下文摘要", "Context summary"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onEdit, enabled = !loading) { Text(l10n("编辑", "Edit")) }
                TextButton(onClick = onMore) { Text(l10n("更多", "More")) }
            }
            Text(
                text = if (message.isContextHidden) l10n("历史摘要 · 已被新摘要覆盖", "Archived summary")
                    else l10n("系统消息", "System message"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = message.toText(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) l10n("收起", "Collapse") else l10n("展开全文", "Expand"))
            }
        }
    }
}

@Composable
internal fun ContextSummaryEditor(
    message: UIMessage,
    savingEnabled: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(message.id) { mutableStateOf(message.toText()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(l10n("编辑上下文摘要", "Edit context summary")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                minLines = 8,
                label = { Text(l10n("摘要内容", "Summary")) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = savingEnabled && text.isNotBlank()) {
                Text(l10n("保存", "Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(l10n("取消", "Cancel")) } },
    )
}
