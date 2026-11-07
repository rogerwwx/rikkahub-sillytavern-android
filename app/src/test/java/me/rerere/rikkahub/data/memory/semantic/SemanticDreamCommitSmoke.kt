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
        println("SemanticDreamCommitSmoke passed")
    }
}
