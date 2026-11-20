package me.rerere.rikkahub.data.memory.semantic

internal data class SemanticDreamStatePlan(
    val mode: String, val window: DreamWindow,
    val checkpoint: SemanticStateCheckpoint? = null, val updateState: Boolean = false,
) {
    val stateOnly: Boolean get() = mode == "state_only"
}

/** 仅连续且证据完全相同的窗口联合提交；旧记忆领先时先补其前面的状态。 */
internal fun planSemanticDreamState(
    conversationId: String, messages: List<DreamMessage>, cursor: DreamCursor,
    checkpoint: SemanticStateCheckpoint? = null, maxTurns: Int = 0,
    positions: IntRange = 0..messages.lastIndex, includeIncomplete: Boolean = false,
): SemanticDreamStatePlan? {
    val memoryWindow = dreamWindow(conversationId, messages, cursor, maxTurns,
        firstPosition = positions.first, lastPosition = positions.last, includeIncomplete = includeIncomplete)
    // 手动范围有独立进度，不能借它覆盖或修改全局状态。
    if (checkpoint == null || includeIncomplete)
        return memoryWindow?.let { SemanticDreamStatePlan("reflection", it) }
    val stateWindow = semanticStateWindow(conversationId, messages, checkpoint.coveredCount, maxTurns)
    if (memoryWindow == null)
        return stateWindow?.let { SemanticDreamStatePlan("state_only", it, checkpoint, updateState = true) }
    if (stateWindow?.evidence == memoryWindow.evidence)
        return SemanticDreamStatePlan("joint", memoryWindow, checkpoint, updateState = true)
    // 开场与已总结前缀先回填，绝不把已经消费的记忆来源再交给记忆变更规划器。
    val prefixEnd = memoryWindow.evidence.first().position - 1
    val prefixWindow = if (prefixEnd < checkpoint.coveredCount) null else
        semanticStateWindow(conversationId, messages, checkpoint.coveredCount, maxTurns, lastPosition = prefixEnd)
    if (prefixWindow != null)
        return SemanticDreamStatePlan("state_only", prefixWindow, checkpoint, updateState = true)
    // 旧手动范围可能留下局部偏移、非连续覆盖或未完成前缀。先完成原记忆窗口，
    // state 只确认基础版本并返回空变更；后续正常做梦再从完整前缀回填状态。
    return SemanticDreamStatePlan("reflection", memoryWindow, checkpoint)
}
