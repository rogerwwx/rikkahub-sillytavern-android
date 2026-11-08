package me.rerere.rikkahub.data.memory.semantic

object SemanticDreamScheduleSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val config = SemanticDreamSettings(retryLimit = 2, retryBackoffSeconds = 10)
        var state = SemanticDreamScheduleState("assistant", "config", "turn1", dueAt = 100)
        state = state.afterRun("failed", 1000, config, "网络失败")
        check(state.status == "pending" && state.attempts == 1 && state.dueAt == 11000L)
        state = state.afterRun("failed", 11000, config, "网络失败")
        check(state.status == "pending" && state.attempts == 2 && state.dueAt == 31000L)
        state = state.afterRun("failed", 31000, config, "网络失败")
        check(state.status == "failed" && state.attempts == 3)
        check(state.rescheduled("config", "turn1", 50000, 0).status == "failed")
        check(state.rescheduled("config", "turn1", 50000, 0).attempts == 3)
        check(state.rescheduled("config", "turn2", 50000, 20).attempts == 0)
        check(state.rescheduled("new-config", "turn1", 50000, 20).status == "pending")
        val completed = state.afterRun("completed", 60000, config, null)
        check(completed.status == "completed" && completed.lastCompletedAt == 60000L)
        check(completed.rescheduled("config", "turn1", 90000, 0).status == "completed")
        check(completed.rescheduled("config", "turn1", 90000, 0, manual = true).status == "pending")
        val range = SemanticDreamRange("request", "start", "end")
        val manual = completed.copy(manualRange = range, status = "running")
        check(manual.afterRun("pending", 90000, config, null).manualRange == range)
        check(manual.afterRun("completed", 90000, config, null).manualRange == range)
        check(manual.rescheduled("config", "next-turn", 90000, 0).manualRange == null)
        val encoded = kotlinx.serialization.json.Json.encodeToString(SemanticDreamScheduleState.serializer(), manual)
        check(kotlinx.serialization.json.Json.decodeFromString<SemanticDreamScheduleState>(encoded).manualRange == range)
        println("SemanticDreamScheduleSmoke: PASS（持久失败、退避、来源变化与手动恢复）")
    }
}
