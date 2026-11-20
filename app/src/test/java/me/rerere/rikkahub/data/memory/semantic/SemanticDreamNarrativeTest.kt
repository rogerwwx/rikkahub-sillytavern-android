package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class SemanticDreamNarrativeTest {
    @Test fun reflectionPreservesStateAlongsideMemory() {
        val parsed = parseDreamDraft("""{"changes":[],"state":{"baseRevision":"revision","changes":[]}}""", "revision")
        check(Json.encodeToString(parsed).contains("\"baseRevision\":\"revision\""))
    }

    @Test fun disabledReflectionStillAcceptsOriginalDraft() {
        check(parseDreamDraft("""{"changes":[],"continuity":"场景未变"}""").continuity == "场景未变")
        check(runCatching { parseDreamDraft("""{"changes":[],"unexpected":true}""") }.isFailure)
    }

    @Test fun requiredStateCannotBeMissingOrUseAnotherRevision() {
        check(runCatching { parseDreamDraft("""{"changes":[]}""", "revision") }.isFailure)
        check(runCatching {
            parseDreamDraft("""{"changes":[],"state":{"baseRevision":"other","changes":[]}}""", "revision")
        }.isFailure)
        check(parseDreamDraft("""{"changes":[],"state":{"baseRevision":"revision","changes":[]}}""", "revision").state != null)
    }

    @Test fun stateOnlyCannotSmuggleMemoryChanges() {
        val text = """{"changes":[{"operation":"add","evidence":["source"],"content":"重复的旧回忆"}],"state":{"baseRevision":"revision","changes":[]}}"""
        check(runCatching { parseDreamDraft(text, "revision", stateOnly = true) }.isFailure)
        check(runCatching {
            parseDreamDraft("""{"changes":[],"continuity":"后来关系已改变","state":{"baseRevision":"revision","changes":[]}}""",
                "revision", stateOnly = true)
        }.isFailure)
    }

    @Test fun legacyParserStillRejectsUnrequestedState() {
        check(runCatching {
            parseDreamDraft("""{"changes":[],"state":{"baseRevision":"revision","changes":[]}}""", allowState = false)
        }.isFailure)
    }

    @Test fun sameWindowProducesJointExtraction() {
        val messages = turns(3)
        val before = emptySemanticState("assistant", "conversation")
        val plan = planSemanticDreamState("conversation", messages, DreamCursor(), before)!!
        check(plan.mode == "joint")
        check(plan.window.evidence.size == 6 && plan.window.completeTurns == 3)
        check(plan.updateState && plan.checkpoint == before)
    }

    @Test fun oldMemoryCoverageOnlyBackfillsStateBeforeNewMemory() {
        val messages = turns(3)
        val old = dreamWindow("conversation", messages, DreamCursor(), maxTurns = 2)!!.nextCursor
        val before = emptySemanticState("assistant", "conversation")
        val plan = planSemanticDreamState("conversation", messages, old, before)!!
        check(plan.mode == "state_only")
        check(plan.window.evidence.map { it.position } == listOf(0, 1, 2, 3))
        val after = planSemanticStateChanges(before, SemanticStateDraft(before.revision, emptyList()), plan.window, messages)
        check(planSemanticDreamState("conversation", messages, old, after)!!.mode == "joint")
        check(old.processed.size == 4)
    }

    @Test fun arbitraryManualRangeNeverAdvancesGlobalState() {
        val messages = turns(3)
        val before = emptySemanticState("assistant", "conversation")
        val plan = planSemanticDreamState("conversation", messages, DreamCursor(), before,
            positions = 2..3, includeIncomplete = true)!!
        check(plan.mode == "reflection" && !plan.updateState && plan.checkpoint == null)
        check(plan.window.evidence.map { it.position } == listOf(2, 3))
    }

    @Test fun openingAndIncompleteMiddleKeepStatePrefixContiguous() {
        val opening = DreamMessage("opening", "opening", "assistant", "林夏左腕受伤", 0, true)
        val messages = listOf(opening) + turns(3)
        val before = emptySemanticState("assistant", "conversation")
        val plan = planSemanticDreamState("conversation", messages, DreamCursor(), before)!!
        check(plan.mode == "state_only" && plan.window.evidence.single().position == 0)
        val incomplete = turns(3).mapIndexed { index, message -> if (index == 1) message.copy(finished = false) else message }
        val blocked = planSemanticDreamState("conversation", incomplete, DreamCursor(), before)!!
        check(blocked.mode == "reflection" && !blocked.updateState)
        check(blocked.checkpoint == before)
    }

    @Test fun enabledPromptAppendsProtocolEvenToCustomReflection() {
        val settings = SemanticDreamSettings(reflectionPrompt = "自定义第一人称反思")
        val legacy = settings.reflectionSystemPrompt()
        check(settings.reflectionSystemPrompt(narrativeStateEnabled = false) == legacy)
        val enabled = settings.reflectionSystemPrompt(narrativeStateEnabled = true)
        check(enabled.startsWith(legacy) && enabled.contains("baseRevision"))
        check(enabled.contains("最终变化") && enabled.contains("重复"))
        check(settings.reflectionSystemPrompt(narrativeStateEnabled = true, stateOnly = true).contains("changes 必须为空"))
    }

    private fun turns(count: Int): List<DreamMessage> = (0 until count).flatMap { index ->
        listOf(
            DreamMessage("u$index", "u$index", "user", "林夏的左腕伤势$index", index.toLong(), true),
            DreamMessage("a$index", "a$index", "assistant", "林夏处理伤势$index", index.toLong(), true),
        )
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            SemanticDreamNarrativeTest().apply {
                reflectionPreservesStateAlongsideMemory()
                disabledReflectionStillAcceptsOriginalDraft()
                requiredStateCannotBeMissingOrUseAnotherRevision()
                stateOnlyCannotSmuggleMemoryChanges()
                legacyParserStillRejectsUnrequestedState()
                sameWindowProducesJointExtraction()
                oldMemoryCoverageOnlyBackfillsStateBeforeNewMemory()
                arbitraryManualRangeNeverAdvancesGlobalState()
                openingAndIncompleteMiddleKeepStatePrefixContiguous()
                enabledPromptAppendsProtocolEvenToCustomReflection()
            }
            println("SemanticDreamNarrativeTest: PASS")
        }
    }
}
