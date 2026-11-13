package me.rerere.rikkahub.data.memory.semantic

object SemanticDreamCoreSmoke {
    @JvmStatic fun main(args: Array<String>) {
        fun message(id: String, role: String, text: String, finished: Boolean = true) = DreamMessage(id, id, role, text, 1000, finished)
        val turns = listOf(message("u1", "user", "晚饭吃了面"), message("a1", "assistant", "好"),
            message("u2", "user", "以后别放香菜"), message("a2", "assistant", "记住了"))
        // 不分块：一次取走全部待处理轮次，正文完整不切片。
        val window = dreamWindow("c", turns, DreamCursor(), maxTurns = 0)!!
        check(window.evidence.map { it.messageId } == listOf("u1", "a1", "u2", "a2"))
        check(window.evidence.all { it.start == 0 && it.end == turns.first { m -> m.messageId == it.messageId }.text.length })
        check(dreamWindow("c", turns, window.nextCursor) == null)
        check(dreamWindow("c", turns + message("u3", "user", "等等"), window.nextCursor) == null)
        // 手动分块：用户消息加模型回复算一轮。
        val chunked = dreamWindow("c", turns, DreamCursor(), maxTurns = 1)!!
        check(chunked.evidence.map { it.messageId } == listOf("u1", "a1") && chunked.completeTurns == 1)
        val second = dreamWindow("c", turns, chunked.nextCursor, maxTurns = 1)!!
        check(second.evidence.map { it.messageId } == listOf("u2", "a2"))
        check(dreamWindow("c", turns, second.nextCursor, maxTurns = 1) == null)
        val unfinished = turns.map { if (it.messageId == "a1") it.copy(finished = false) else it }
        val later = dreamWindow("c", unfinished, DreamCursor())!!
        check(later.evidence.map { it.messageId } == listOf("u2", "a2"))
        val completedEarlier = dreamWindow("c", turns, later.nextCursor)!!
        check(completedEarlier.evidence.first().messageId == "u1")
        check(dreamWindow("c", turns, completedEarlier.nextCursor) == null) {
            "补齐较早轮次后不能丢掉已经成功处理的后续轮次"
        }
        val selected = dreamWindow("c", turns, DreamCursor(), firstPosition = 2, lastPosition = 3)!!
        check(selected.evidence.map { it.messageId } == listOf("u2", "a2"))
        val history = dreamHistory(turns, selected.nextCursor)
        check(history.completedTurns == 2 && history.processedTurns == 1 && history.pendingTurns == 1)
        check(history.messages.map { it.processed } == listOf(false, false, true, true))
        check(dreamWindow("c", turns, DreamCursor(), firstPosition = 1, lastPosition = 2) == null)
        check(!validDreamSources(window.evidence, turns.map { if (it.messageId == "u1") it.copy(text = "改了") else it }))
        check(validDreamSources(window.evidence, turns + message("u3", "user", "新增")))
        val localTime = turns.map { it.copy(createdAtText = "2026-11-07T10:00:00") }
        val localWindow = dreamWindow("c", localTime, DreamCursor())!!
        check(validDreamSources(localWindow.evidence, localTime.map { it.copy(createdAt = it.createdAt + 3600000) }))
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
        val manual = dreamWindow("manual", manualHistory, DreamCursor(), includeIncomplete = true)!!
        check(manual.evidence.map { it.messageId } == manualHistory.map { it.messageId })
        check(manual.evidence.filter { it.isIncomplete }.map { it.messageId } == listOf("ma1"))
        check(validDreamSources(manual.evidence, manualHistory))
        check(dreamWindow("manual", manualHistory, manual.nextCursor, includeIncomplete = true) == null) {
            "手动不完整来源必须能推进并保留游标，不能每批反复发送同一片"
        }
        check(!validDreamSources(manual.evidence, manualHistory.map {
            if (it.messageId == "ma1") it.copy(text = "后来改成不同剧情") else it
        }))
        check(dreamWindow("manual", manualHistory, DreamCursor())!!.evidence.map { it.messageId } ==
            listOf("mu2", "ma2")) { "自动处理仍只消费完整轮次" }
        val assistantOnly = dreamWindow("manual", manualHistory, DreamCursor(),
            firstPosition = 2, lastPosition = 2, includeIncomplete = true)!!
        check(assistantOnly.evidence.single().messageId == "ma1") { "手动起止范围应精确截取原文，不能被轮次边界吞掉" }
        check(SemanticDreamRange("single", "ma1", "ma1").positions(manualHistory) == 2..2)
        println("SemanticDreamCoreSmoke passed")
    }
}
