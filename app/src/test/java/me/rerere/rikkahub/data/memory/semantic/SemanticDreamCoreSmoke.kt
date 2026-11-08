package me.rerere.rikkahub.data.memory.semantic

object SemanticDreamCoreSmoke {
    @JvmStatic fun main(args: Array<String>) {
        fun message(id: String, role: String, text: String, finished: Boolean = true) = DreamMessage(id, id, role, text, 1000, finished)
        val turns = listOf(message("u1", "user", "晚饭吃了面"), message("a1", "assistant", "好"),
            message("u2", "user", "以后别放香菜"), message("a2", "assistant", "记住了"))
        val window = dreamWindow("c", turns, DreamCursor(), 1000, 1)!!
        check(window.evidence.map { it.messageId } == listOf("u1", "a1", "u2", "a2"))
        check(dreamWindow("c", turns, window.nextCursor, 1000, 1) == null)
        check(dreamWindow("c", turns + message("u3", "user", "等等"), window.nextCursor, 1000, 1) == null)
        val unfinished = turns.map { if (it.messageId == "a1") it.copy(finished = false) else it }
        val later = dreamWindow("c", unfinished, DreamCursor(), 1000, 0)!!
        check(later.evidence.map { it.messageId } == listOf("u2", "a2"))
        val completedEarlier = dreamWindow("c", turns, later.nextCursor, 1000, 0)!!
        check(completedEarlier.evidence.first().messageId == "u1")
        check(dreamWindow("c", turns, completedEarlier.nextCursor, 1000, 0) == null) {
            "补齐较早轮次后不能丢掉已经成功处理的后续轮次"
        }
        val selected = dreamWindow("c", turns, DreamCursor(), 1000, 0, firstPosition = 2, lastPosition = 3)!!
        check(selected.evidence.map { it.messageId } == listOf("u2", "a2"))
        val history = dreamHistory(turns, selected.nextCursor)
        check(history.completedTurns == 2 && history.processedTurns == 1 && history.pendingTurns == 1)
        check(history.messages.map { it.processed } == listOf(false, false, true, true))
        check(dreamWindow("c", turns, DreamCursor(), 1000, 0, firstPosition = 1, lastPosition = 2) == null)
        check(!validDreamSources(window.evidence, turns.map { if (it.messageId == "u1") it.copy(text = "改了") else it }))
        check(validDreamSources(window.evidence, turns + message("u3", "user", "新增")))
        val localTime = turns.map { it.copy(createdAtText = "2026-11-07T10:00:00") }
        val localWindow = dreamWindow("c", localTime, DreamCursor(), 1000, 0)!!
        check(validDreamSources(localWindow.evidence, localTime.map { it.copy(createdAt = it.createdAt + 3600000) }))
        val split = listOf(message("u", "user", "甲".repeat(100)), message("a", "assistant", "乙".repeat(100)))
        var cursor = DreamCursor()
        var total = 0
        while (true) {
            val next = dreamWindow("c", split, cursor, 31, 0) ?: break
            total += next.evidence.sumOf { it.end - it.start }
            check(next.evidence.sumOf { it.end - it.start } <= 31)
            cursor = next.nextCursor
        }
        check(total == 200)
        val changed = split.map { if (it.messageId == "u") it.copy(text = "丙".repeat(100)) else it }
        check(dreamWindow("c", changed, cursor, 31, 0)!!.evidence.first().messageId == "u")
        val now = 1000L
        check(dreamExpiry("factual", false, now, mapOf("factual" to 180)) == now + 180L * 86400000)
        check(dreamExpiry("factual", true, now, mapOf("factual" to 180)) == null)
        check(parseDreamDraft("```json\n{\"changes\":[]}\n```").changes.isEmpty())
        check(runCatching { parseDreamDraft("{\"changes\":[") }.isFailure)
        println("SemanticDreamCoreSmoke passed")
    }
}
