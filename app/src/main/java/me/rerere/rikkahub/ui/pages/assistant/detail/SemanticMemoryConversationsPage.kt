package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticMemoryConversationsPage(id: String, previousConversationId: String? = null) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val repository: ConversationRepository = koinInject()
    // 该列表查询不加载消息节点，长对话也只读取窗口信息。
    val conversations by remember(id) { repository.getConversationsOfAssistant(Uuid.parse(id)) }
        .collectAsStateWithLifecycle(initialValue = null)
    val lastConversation = if (previousConversationId == null) {
        context.lastSemanticMemoryConversation(id, conversations.orEmpty())
    } else null
    LaunchedEffect(lastConversation?.id) {
        lastConversation?.let { nav.openSemanticMemoryConversation(id, it.id.toString(), null) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("选择记忆窗口") }, navigationIcon = { BackButton() })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("每个对话窗口独立保存语义记忆。模型、召回和做梦设置由同一助手的窗口共用。",
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { nav.navigate(Screen.SemanticDreamSettings(id)) }) { Text("助手做梦设置") }
            }
            items(conversations.orEmpty(), key = { it.id }) { conversation ->
                Card(onClick = {
                    nav.openSemanticMemoryConversation(id, conversation.id.toString(), previousConversationId)
                },
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(conversation.title.ifBlank { "未命名对话" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("管理此窗口的记忆", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (conversations == null) item { Text("正在加载对话…") }
            if (conversations?.isEmpty() == true) item { Text("此助手还没有对话。新建对话后，可在这里管理记忆。") }
        }
    }
}
