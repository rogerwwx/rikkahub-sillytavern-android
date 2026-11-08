package me.rerere.rikkahub.data.memory.semantic

object SemanticDreamCommitSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val messages = listOf(DreamMessage("u", "u", "user", "以后别放香菜", 1000, true), DreamMessage("a", "a", "assistant", "好", 1000, true))
        val window = dreamWindow("c", messages, DreamCursor(), 1000, 0)!!
        val evidence = listOf(window.evidence.first().evidenceId)
        val settings = SemanticDreamSettings()
        val add = SemanticDreamChange("add", evidence, content = "用户不吃香菜", type = "preference")
        val mutation = planDreamChanges("assistant", "run", SemanticDreamDraft(listOf(add)), window, emptyList(), settings, 1000).single()
        check(mutation.after.content == "用户不吃香菜")
        val old = mutation.after
        val otherWindow = window.copy(evidence = window.evidence.map { it.copy(conversationId = "other-window") })
        val otherEvidence = listOf(otherWindow.evidence.first().evidenceId)
        val independent = planDreamChanges("assistant", "other-window-run",
            SemanticDreamDraft(listOf(add.copy(evidence = otherEvidence))), otherWindow, listOf(old), settings, 2000).single()
        check(independent.before == null) { "同一助手的不同窗口不能把相同事实合并成一次强化" }
        check(independent.after.conversationId == "other-window" && old.conversationId == window.evidence.first().conversationId)
        check(runCatching {
            planDreamChanges("assistant", "foreign-target",
                SemanticDreamDraft(listOf(add.copy(operation = "replace", evidence = otherEvidence,
                    targetId = old.id, expectedVersion = old.contentVersion))),
                otherWindow, listOf(old), settings, 2000)
        }.isFailure) { "做梦不能替代其他窗口的记忆" }
        val empty = planDreamChanges("assistant", "next", SemanticDreamDraft(emptyList()), window, listOf(old), settings, 2000)
        check(empty.isEmpty())
        val reinforce = SemanticDreamChange("reinforce", evidence, old.id, old.contentVersion)
        check(planDreamChanges("assistant", "same", SemanticDreamDraft(listOf(reinforce)), window, listOf(old), settings, 2000).isEmpty())
        val sameMessageWindow = window.copy(evidence = window.evidence.map { it.copy(start = 1) })
        check(planDreamChanges("assistant", "same-message", SemanticDreamDraft(listOf(reinforce.copy(evidence = listOf(sameMessageWindow.evidence.first().evidenceId)))),
            sameMessageWindow, listOf(old), settings, 2000).isEmpty())
        val changedWindow = window.copy(evidence = window.evidence.map { it.copy(messageId = "new", createdAt = 3000) })
        val confirmed = reinforce.copy(evidence = listOf(changedWindow.evidence.first().evidenceId))
        val strengthened = planDreamChanges("assistant", "different", SemanticDreamDraft(listOf(confirmed)), changedWindow, listOf(old), settings, 3000).single().after
        check(strengthened.content == old.content && strengthened.expiresAt!! > old.expiresAt!!)
        val replace = confirmed.copy(operation = "replace", content = "用户现在可以吃香菜", type = "preference")
        val olderWindow = changedWindow.copy(evidence = changedWindow.evidence.map { it.copy(createdAt = 500) })
        check(runCatching { planDreamChanges("assistant", "older", SemanticDreamDraft(listOf(replace.copy(evidence = listOf(olderWindow.evidence.first().evidenceId)))),
            olderWindow, listOf(old), settings, 3000) }.isFailure)
        val updates = planDreamChanges("assistant", "replace", SemanticDreamDraft(listOf(replace)), changedWindow, listOf(old), settings, 3000)
        check(updates.size == 2 && updates.any { it.after.id == old.id && it.after.status == "superseded" })
        check(updates.any { it.after.content == "用户现在可以吃香菜" && it.after.status == "active" })
        check(runCatching { planDreamChanges("assistant", "core", SemanticDreamDraft(listOf(replace)), changedWindow, listOf(old.copy(isCore = true)), settings, 3000) }.isFailure)
        val coreReplacement = planDreamChanges("assistant", "core-manual", SemanticDreamDraft(listOf(replace)), changedWindow,
            listOf(old.copy(isCore = true)), settings.copy(protectCore = false), 3000).first { it.before == null }.after
        check(coreReplacement.isCore && coreReplacement.expiresAt == null)
        check(runCatching { planDreamChanges("assistant", "bad", SemanticDreamDraft(listOf(add.copy(evidence = listOf("old-context")))), window, emptyList(), settings, 1000) }.isFailure)
        check(runCatching { planDreamChanges("assistant", "bad", SemanticDreamDraft(listOf(add.copy(evidence = listOf(window.evidence.last().evidenceId)))), window, emptyList(), settings, 1000) }.isFailure)
        val withdrawn = withdrawDreamSources(old, evidence.toSet(), 4000)
        check(withdrawn.status == "dormant" && dreamSources(withdrawn).none { it.valid })
        val restored = restoreDreamSources(withdrawn, evidence.toSet(), 5000)
        check(restored.status == "active" && restored.expiresAt == old.expiresAt)
        check(dreamSources(restored).all { it.valid })
        check(restoreDreamSources(withdrawn.copy(metadataJson = "{\"sourceWithdrawn\":true,\"manualLifecycle\":true}"), evidence.toSet(), 5000).status == "dormant")
        check(withdrawDreamSources(old.copy(isCore = true), evidence.toSet(), 4000).status == "active")
        check(withdrawDreamSources(old.copy(metadataJson = "{\"origin\":\"dream\",\"manualEdited\":true}"), evidence.toSet(), 4000).status == "active")
        check(withdrawDreamSources(strengthened, evidence.toSet(), 4000).status == "active")
        val consolidated = strengthened.copy(content = "用户不吃香菜，也喜欢清淡饭菜", metadataJson = "{\"origin\":\"consolidation\"}")
        val partlyWithdrawn = withdrawDreamSources(consolidated, evidence.toSet(), 4000)
        check(partlyWithdrawn.status == "dormant" && partlyWithdrawn.content == consolidated.content) {
            "归纳记忆的一部分来源失效后，不能继续召回完整旧正文"
        }
        val remainingEvidence = dreamSources(partlyWithdrawn).filter { it.valid }.map { it.evidenceId }.toSet()
        val fullyWithdrawn = withdrawDreamSources(partlyWithdrawn, remainingEvidence, 4500)
        val partlyRestored = restoreDreamSources(fullyWithdrawn, evidence.toSet(), 5000)
        check(partlyRestored.status == "dormant") { "归纳记忆只恢复部分来源时不能自动启用" }
        val fullyRestored = restoreDreamSources(partlyRestored, remainingEvidence, 5500)
        check(fullyRestored.status == "active" && fullyRestored.expiresAt == consolidated.expiresAt)
        check(withdrawDreamSources(consolidated.copy(metadataJson = "{\"origin\":\"consolidation\",\"manualEdited\":true}"),
            evidence.toSet(), 4000).status == "active")
        println("SemanticDreamCommitSmoke passed")
    }
}
