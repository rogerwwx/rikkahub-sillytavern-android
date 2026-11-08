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
        val manualHistory = listOf(message("opening", "assistant", "故事从京都车站开始"),
            message("mu1", "user", "牵住你的手"), message("ma1", "assistant", "一起走进旅馆", finished = false),
            message("mu2", "user", "次日清晨"), message("ma2", "assistant", "一起吃早餐"),
            message("tail", "user", "先拿好行李"))
        check(dreamHistory(manualHistory, DreamCursor()).messages.map { it.messageId } ==
            manualHistory.map { it.messageId }) { "范围历史不能因助手状态过滤掉相邻用户、开场或尾消息" }
        val manual = dreamWindow("manual", manualHistory, DreamCursor(), 1000, 0, includeIncomplete = true)!!
        check(manual.evidence.map { it.messageId } == manualHistory.map { it.messageId })
        check(manual.evidence.filter { it.isIncomplete }.map { it.messageId } == listOf("ma1"))
        check(validDreamSources(manual.evidence, manualHistory))
        check(dreamWindow("manual", manualHistory, manual.nextCursor, 1000, 0, includeIncomplete = true) == null) {
            "手动不完整来源必须能推进并保留游标，不能每批反复发送同一片"
        }
        check(!validDreamSources(manual.evidence, manualHistory.map {
            if (it.messageId == "ma1") it.copy(text = "后来改成不同剧情") else it
        }))
        check(dreamWindow("manual", manualHistory, DreamCursor(), 1000, 0)!!.evidence.map { it.messageId } ==
            listOf("mu2", "ma2")) { "自动处理仍只消费完整轮次" }
        val assistantOnly = dreamWindow("manual", manualHistory, DreamCursor(), 1000, 0,
            firstPosition = 2, lastPosition = 2, includeIncomplete = true)!!
        check(assistantOnly.evidence.single().messageId == "ma1") { "手动起止范围应精确截取原文，不能被轮次边界吞掉" }
        check(SemanticDreamRange("single", "ma1", "ma1").positions(manualHistory) == 2..2)
        var manualCursor = DreamCursor()
        val consumedManual = mutableMapOf<String, Int>()
        var slices = 0
        while (true) {
            val part = dreamWindow("manual", manualHistory, manualCursor, 9, 0, includeIncomplete = true) ?: break
            part.evidence.forEach { consumedManual[it.messageId] = consumedManual.getOrDefault(it.messageId, 0) + it.end - it.start }
            manualCursor = part.nextCursor
            check(++slices < 30)
        }
        check(manualHistory.all { consumedManual[it.messageId] == it.text.length }) { "手动原文切片丢字或重复发送" }
        check(dreamHistory(manualHistory, manualCursor).messages.all { it.processed })
        println("SemanticDreamCoreSmoke passed")
    }
}
