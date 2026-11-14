package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.rikkahub.R

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    // 助手未开启「压缩／记忆流式」时，压缩一次返回全部结果，没有可看的中间过程，就不显示草稿框。
    streaming: Boolean,
    draft: String?,
    onConfirm: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int, historyAsMessages: Boolean, bufferedText: Boolean, turnsPerSegment: Int) -> Job
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var selectedTokens by remember { mutableIntStateOf(2000) }
    var keepRecentMessages by remember { mutableIntStateOf(32) }
    var historyAsMessages by remember { mutableStateOf(false) }
    var bufferedText by remember { mutableStateOf(false) }
    var segmentTurnsText by remember { mutableStateOf("50") }
    val turnsPerSegment = segmentTurnsText.toIntOrNull() ?: 0
    val tokenOptions = listOf(500, 4000)
    var customMode by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("2000") }
    val customTokens = customText.toIntOrNull()?.takeIf { it > 0 }
    val effectiveTokens = if (customMode) customTokens ?: selectedTokens else selectedTokens
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    // Monitor job completion
    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ContainedLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                    if (streaming) {
                        CompressDraftBox(draft)
                    }
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    // Token size selector
                    Text(
                        text = stringResource(R.string.chat_page_compress_target_tokens),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tokenOptions.forEachIndexed { index, tokens ->
                            SegmentedButton(
                                selected = !customMode && selectedTokens == tokens,
                                onClick = {
                                    customMode = false
                                    selectedTokens = tokens
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = tokenOptions.size + 1
                                )
                            ) {
                                Text("$tokens")
                            }
                        }
                        SegmentedButton(
                            selected = customMode,
                            onClick = { customMode = true },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = tokenOptions.size,
                                count = tokenOptions.size + 1
                            )
                        ) {
                            Text(stringResource(R.string.chat_page_compress_custom_tokens))
                        }
                    }
                    if (customMode) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it.filter(Char::isDigit).take(7) },
                            label = {
                                Text(stringResource(R.string.chat_page_compress_target_tokens))
                            },
                            isError = customTokens == null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    OutlinedTextField(
                        value = segmentTurnsText,
                        onValueChange = { segmentTurnsText = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.chat_page_compress_segment_size)) },
                        supportingText = { Text(stringResource(R.string.chat_page_compress_segment_size_desc)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_history_compat),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = historyAsMessages,
                                onCheckedChange = {
                                    historyAsMessages = it
                                    if (it) bufferedText = false
                                },
                            )
                        }
                        Text(
                            text = stringResource(R.string.chat_page_compress_history_compat_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_buffered_text),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = bufferedText,
                                onCheckedChange = {
                                    bufferedText = it
                                    if (it) historyAsMessages = false
                                },
                            )
                        }
                        Text(
                            text = stringResource(R.string.chat_page_compress_buffered_text_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 原文保留在聊天窗口，后续请求使用摘要与保留消息。
                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 条数只有 0~999 几种取值，用步进器比弹数字键盘更好按
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_page_compress_keep_recent),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalIconButton(
                                onClick = { keepRecentMessages-- },
                                enabled = keepRecentMessages > 0,
                            ) {
                                Icon(HugeIcons.Remove01, contentDescription = stringResource(R.string.chat_page_compress_keep_recent_minus))
                            }
                            Text(
                                text = keepRecentMessages.toString(),
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 40.dp),
                            )
                            FilledTonalIconButton(
                                onClick = { keepRecentMessages++ },
                                enabled = keepRecentMessages < 999,
                            ) {
                                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.chat_page_compress_keep_recent_plus))
                            }
                        }
                        Text(
                            text = stringResource(R.string.chat_page_compress_keep_recent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(
                    enabled = !customMode || customTokens != null,
                    onClick = {
                        currentJob = onConfirm(additionalPrompt, effectiveTokens, keepRecentMessages, historyAsMessages, bufferedText, turnsPerSegment)
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

/**
 * 流式压缩的实时草稿。只是进度展示：内容可能还没写完，或因截断/取消而作废，
 * 真正的摘要仍以提交完成后的 checkpoint 为准，所以这里明确标注「生成中，未定稿」。
 */
@Composable
private fun CompressDraftBox(draft: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.chat_page_compress_draft_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.orEmpty(),
            onValueChange = {},
            readOnly = true,
            placeholder = { Text(stringResource(R.string.chat_page_compress_draft_waiting)) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
        )
    }
}
