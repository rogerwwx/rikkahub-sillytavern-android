package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.Settings
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val speakerMap: Map<Uuid, Uuid> = emptyMap(),  // messageNodeId → assistantId（群聊发言人）
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // 会话开始后固定的配置，null 表示尚未开始、跟随助手
    val config: ConversationConfig? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹 ID（null = 未归类）
    val folderId: Uuid? = null,
    // 上下文滚动压缩摘要（覆盖 sourceMessageIds 对应的前缀，生成请求时替换前缀发送）
    val rollingContextSummary: me.rerere.rikkahub.data.ai.context.RollingContextSummary? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            // 被压缩摘要覆盖的节点已标记 hidden，不再进入请求；节点本身仍留在对话里可回溯
            return messageNodes.map { node -> node.messages[node.selectIndex] }
                .filterNot { message -> message.isContextHidden }
        }

    fun messagesBeforeNode(nodeId: Uuid): List<UIMessage> {
        val index = messageNodes.indexOfFirst { it.id == nodeId }
        require(index >= 0) { "生成边界节点已不存在" }
        return messageNodes.take(index).map { it.currentMessage }.filterNot { it.isContextHidden }
    }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()
        val nodeIndexes = mutableMapOf<Uuid, Int>()
        newNodes.forEachIndexed { index, node -> node.messages.forEach { nodeIndexes[it.id] = index } }
        val visibleNodeIndexes = newNodes.indices.filter { !newNodes[it].currentMessage.isContextHidden }

        messages.forEachIndexed { index, message ->
            // 生成输入只含可见消息；已有消息按稳定ID定位，新分支才沿可见节点对齐。
            val nodeIndex = nodeIndexes[message.id] ?: visibleNodeIndexes.getOrNull(index) ?: newNodes.size
            val node = newNodes
                .getOrElse(nodeIndex) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (nodeIndex > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[nodeIndex] = newNode
            }
            nodeIndexes[message.id] = nodeIndex
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    /**
     * 写入一条消息：[nodeId] 节点里已有同 id 的消息就原地替换（不改变选中的分支），否则作为新分支加入并选中；
     * 节点不存在时追加到末尾。按节点 id 而不是下标定位，生成期间别的节点被增删、分支被切换，回复也不会写错位置。
     */
    fun upsertMessage(nodeId: Uuid, message: UIMessage): Conversation {
        // 生成中的回复几乎总在最后一个节点
        val index = messageNodes.indexOfLast { it.id == nodeId }
        if (index == -1) {
            return copy(messageNodes = messageNodes + MessageNode(id = nodeId, messages = listOf(message)))
        }
        val node = messageNodes[index]
        val existing = node.messages.indexOfFirst { it.id == message.id }
        val updated = if (existing == -1) {
            node.copy(messages = node.messages + message, selectIndex = node.messages.size)
        } else {
            node.copy(messages = node.messages.toMutableList().also { it[existing] = message })
        }
        return copy(messageNodes = messageNodes.toMutableList().also { it[index] = updated })
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException(
            "MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex"
        )
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun List<UIMessage>.toMergedMessageNode(): MessageNode {
    return MessageNode(messages = this, selectIndex = 0)
}

/**
 * 最近一次回复结束时的上下文占用（token），取不到时返回 null。
 * 之后又插入了压缩检查点的话，记录的占用已经过时，同样返回 null。
 */
fun Conversation.lastContextTokens(): Int? {
    val messages = currentMessages
    val message = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
    val checkpoint = messages.lastOrNull { it.isContextCheckpoint }
    if (checkpoint != null && checkpoint.createdAt > message.createdAt) return null
    return message.contextTokens()
}

/**
 * 把 [throughNodeId] 及其之前的节点整段换成摘要消息，[throughNodeId] 已不存在时返回 null。
 * 压缩后第一条消息就是摘要，被压缩的历史不再保留（这是早期版本的行为）。
 */
internal fun Conversation.replacePrefixWithSummaries(
    throughNodeId: Uuid,
    summaries: List<String>,
): Conversation? {
    val index = messageNodes.indexOfFirst { it.id == throughNodeId }
    if (index == -1) return null
    val summaryNodes = summaries.map { UIMessage.user(it).copy(isContextCheckpoint = true).toMessageNode() }
    return copy(messageNodes = summaryNodes + messageNodes.subList(index + 1, messageNodes.size))
}

/**
 * 把 [throughNodeId] 及其之前的节点**标记**为已压缩，并在其后插入摘要节点。
 *
 * 与 [replacePrefixWithSummaries] 的差别是历史不再被删掉：节点仍留在对话里（可回溯、
 * 可导出），只是 [Conversation.currentMessages] 会过滤掉它们，不再发给模型。
 * [throughNodeId] 已不存在时返回 null。
 */
internal fun Conversation.hidePrefixWithSummaries(
    throughNodeId: Uuid,
    summaries: List<String>,
): Conversation? {
    val index = messageNodes.indexOfFirst { it.id == throughNodeId }
    if (index == -1) return null
    val hiddenPrefix = messageNodes.take(index + 1).map { node ->
        node.copy(
            messages = node.messages.map { message ->
                if (message.isContextHidden) message else message.copy(isContextHidden = true)
            },
        )
    }
    val summaryNodes = summaries.map { UIMessage.user(it).copy(isContextCheckpoint = true).toMessageNode() }
    return copy(messageNodes = hiddenPrefix + summaryNodes + messageNodes.drop(index + 1))
}

/** 有工具调用在等待用户审批：这一轮还没结束，排队的消息要等审批完成后再发 */
internal val Conversation.isAwaitingToolApproval: Boolean
    get() = currentMessages.any { message -> message.getTools().any { it.isPending } }

/**
 * 去掉还有未执行、又没法继续执行的工具调用的消息（比如还在等待审批），这样的消息发给模型会被拒绝。
 * 已经批准、拒绝或回答过的工具调用之后会继续执行，保留。只处理下标小于 [untilNode] 的节点。
 */
internal fun Conversation.withoutUnresumableToolCalls(untilNode: Int = messageNodes.size): Conversation {
    val nodes = messageNodes.mapIndexedNotNull { index, node ->
        if (index >= untilNode) return@mapIndexedNotNull node
        val unexecuted = node.currentMessage.getTools().filter { !it.isExecuted }
        if (unexecuted.isEmpty() || unexecuted.any { it.canResumeExecution }) return@mapIndexedNotNull node
        val messages = node.messages.filter { it.id != node.currentMessage.id }
        if (messages.isEmpty()) return@mapIndexedNotNull null
        node.copy(messages = messages, selectIndex = (node.selectIndex - 1).coerceIn(messages.indices))
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

/** 对话楼层与语义原文共用候选顺序，压缩原文保留，摘要和注入不占楼层。 */
internal fun List<MessageNode>.dialogueMessageNodes(): List<MessageNode> = filter { node ->
    val message = node.currentMessage
    !message.isContextCheckpoint && !message.isSynthetic &&
        message.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
        !(message.role == MessageRole.USER && message.parts.isEmpty() && message.toText().isBlank())
}

internal fun List<MessageNode>.messageFloorNumbers(): Map<Uuid, Int> =
    dialogueMessageNodes().mapIndexed { index, node -> node.id to index + 1 }.toMap()

internal fun Conversation.messageFloorNumbers(): Map<Uuid, Int> = messageNodes.messageFloorNumbers()

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 给还没有模型快照的消息补上快照，没有可补的消息时原样返回。
 *
 * 在保存会话时调用：新生成的消息由此带上快照，引入快照之前的旧消息也在会话下次保存时补齐。
 * 模型已经被删除的旧消息无从补起，保持原样。
 */
fun Conversation.fillModelSnapshots(settings: Settings): Conversation {
    val models = settings.providers.flatMap { it.models }.associateBy { it.id }
    val nodes = messageNodes.map { node ->
        val messages = node.messages.map { it.fillModelSnapshot(models) }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

private fun UIMessage.fillModelSnapshot(models: Map<Uuid, Model>): UIMessage {
    if (modelSnapshot != null) return this
    val model = modelId?.let(models::get) ?: return this
    return copy(modelSnapshot = ModelSnapshot(modelId = model.modelId, displayName = model.displayName))
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}

/** 替换本地附件的地址，覆盖范围与 [localFileUrls] 一致，包含工具结果中的嵌套附件。 */
internal fun UIMessagePart.mapLocalFileUrls(transform: (String) -> String): UIMessagePart {
    fun String.mapped() = if (startsWith("file://")) transform(this) else this
    return when (this) {
        is UIMessagePart.Image -> copy(url = url.mapped())
        is UIMessagePart.Document -> copy(url = url.mapped())
        is UIMessagePart.Video -> copy(url = url.mapped())
        is UIMessagePart.Audio -> copy(url = url.mapped())
        is UIMessagePart.Tool -> copy(output = output.map { it.mapLocalFileUrls(transform) })
        else -> this
    }
}
