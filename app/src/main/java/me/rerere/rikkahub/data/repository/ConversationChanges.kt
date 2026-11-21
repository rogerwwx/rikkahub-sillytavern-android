package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.fts.ftsText
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

internal data class MessageNodePosition(val id: Uuid, val index: Int, val selectIndex: Int)

internal data class ConversationNodeChanges(
    val written: List<Pair<Int, MessageNode>>,
    val positioned: List<MessageNodePosition>,
    val deleted: List<Uuid>,
    val reindexed: List<MessageNode>,
)

/** 只比较落库字段；收藏独立存储，分支选择与顺序不改变全文索引。 */
internal fun conversationNodeChanges(
    previous: List<MessageNode>,
    current: List<MessageNode>,
): ConversationNodeChanges {
    require(previous.map { it.id }.distinct().size == previous.size) { "已保存的消息节点 ID 重复" }
    require(current.map { it.id }.distinct().size == current.size) { "消息节点 ID 重复" }
    require(current.all { it.selectIndex in it.messages.indices }) { "消息节点的选中分支无效" }
    val oldNodes = previous.withIndex().associateBy { it.value.id }
    val currentIds = current.map { it.id }.toSet()
    val written = mutableListOf<Pair<Int, MessageNode>>()
    val positioned = mutableListOf<MessageNodePosition>()
    val reindexed = mutableListOf<MessageNode>()
    current.forEachIndexed { index, node ->
        val old = oldNodes[node.id]
        if (old == null || old.value.messages != node.messages) {
            written += index to node
            if (old?.value?.ftsContents().orEmpty() != node.ftsContents()) reindexed += node
        } else if (old.index != index || old.value.selectIndex != node.selectIndex) {
            positioned += MessageNodePosition(node.id, index, node.selectIndex)
        }
    }
    return ConversationNodeChanges(written, positioned, previous.map { it.id }.filter { it !in currentIds }, reindexed)
}

private fun MessageNode.ftsContents(): Map<Uuid, String> = messages.mapNotNull { message ->
    message.ftsText().takeIf { it.isNotBlank() }?.let { message.id to it }
}.toMap()
