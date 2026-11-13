package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.Serializable

/** 调度状态只保存触发器，不复制做梦来源/草稿；dream_run 是实际任务进度。 */
@Serializable
data class SemanticDreamScheduleState(
    val assistantId: String,
    val configurationHash: String,
    val turnKey: String,
    val dueAt: Long,
    val attempts: Int = 0,
    val status: String = "pending",
    val lastCompletedAt: Long = 0,
    val error: String? = null,
    val conversationId: String? = null,
    val manualRange: SemanticDreamRange? = null,
) {
    fun rescheduled(hash: String, newTurnKey: String, now: Long, delayMillis: Long, manual: Boolean = false): SemanticDreamScheduleState {
        if (!manual && hash == configurationHash && newTurnKey == turnKey) return this
        return copy(configurationHash = hash, turnKey = newTurnKey, dueAt = now + delayMillis.coerceAtLeast(0),
            attempts = 0, status = "pending", error = null, manualRange = null)
    }

    /** 失败只更新退避时间，不写死成终止状态：同一片窗口会继续自动重试。 */
    fun afterRun(runStatus: String, now: Long, failure: String?): SemanticDreamScheduleState = when (runStatus) {
        "completed" -> copy(status = "completed", attempts = 0, lastCompletedAt = now, error = null)
        "cancelled" -> copy(status = "cancelled", error = "做梦已取消")
        "pending" -> copy(status = "pending", dueAt = now + 30_000L, error = null)
        else -> {
            val failures = attempts + 1
            copy(status = "pending", attempts = failures,
                dueAt = now + (30_000L shl (failures - 1).coerceIn(0, 6)).coerceAtMost(30 * 60_000L),
                error = failure ?: "做梦失败，稍后自动重试")
        }
    }
}
