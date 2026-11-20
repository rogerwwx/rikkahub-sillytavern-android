package me.rerere.rikkahub.data.memory.semantic

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import org.junit.Assert.*
import org.junit.Test

class SemanticNarrativeStateInjectionTest {
    private val current = UIMessage.user("左腕已经痊愈，明天再去码头。")
    private val old = DreamMessage("old-node", "old", "assistant", "左腕受伤", 0, true)
    private val tail = DreamMessage("current-node", current.id.toString(), "user", current.toText(), 1, true)
    private val view = SemanticStateView(
        SemanticStateCheckpoint(assistantId = "a", conversationId = "c", coveredCount = 1,
            prefixHash = "prefix", entries = emptyList(), revision = "state-v1"),
        listOf(old, tail), 1,
    )
    private val settings = SemanticMemorySettings(enabled = true, narrativeStateEnabled = true)
    private val state = SemanticStateInjection("已确认状态：左腕受伤。\n", 1, listOf("wrist"), 0, 1)
    private val recalled = SemanticRecallResult(emptyList(), 0, 0, emptyMap(), emptyList(), 0, "event")

    @Test fun `空RAG仍独立发送状态并重算实际消耗`() {
        val plan = planSemanticNarrativeRequest(state, recalled, settings, "a", "c")
        assertEquals(state.prompt, plan.prompt)
        assertEquals(estimateTextTokens(state.prompt), plan.estimatedTokens)
        assertTrue(plan.source.prompt.isEmpty())
    }

    @Test fun `关闭状态开关完全沿用原来源planner`() {
        val config = settings.copy(narrativeStateEnabled = false)
        val plan = planSemanticNarrativeRequest(state, recalled, config, "a", "c")
        assertNull(plan.state)
        assertEquals(planSemanticSourceRecall(recalled, config, "a", "c"), plan.source)
    }

    @Test fun `最小64总预算不会因剩余normalized64越界`() {
        val memory = SemanticMemoryEntity(id = "m", assistantId = "a", conversationId = "c",
            content = "旧日码头承诺", type = "event")
        val candidates = recalled.copy(hits = listOf(SemanticRecallHit(memory,
            SemanticRankHit(memory.id, 1.0, null, 1.0))))
        val config = settings.copy(dream = settings.dream.copy(injectionTokenBudget = 64))
        val tiny = state.copy(prompt = "已确认。\n")
        val plan = planSemanticNarrativeRequest(tiny, candidates, config, "a", "c")
        assertNotNull(plan.state)
        assertTrue(plan.estimatedTokens <= 64)
        assertTrue(estimateTextTokens(plan.state!!.prompt) <= 64 * 40 / 100)
    }

    @Test fun `不信任状态planner低报估算且超40百分比撤下`() {
        val oversized = state.copy(prompt = "伤势".repeat(1000), estimatedTokens = 1)
        val plan = planSemanticNarrativeRequest(oversized, recalled, settings, "a", "c")
        assertNull(plan.state)
        assertTrue(plan.estimatedTokens <= settings.dream.normalized().injectionTokenBudget)
    }

    @Test fun `尾部完整正文和正确角色才允许状态`() {
        assertTrue(semanticStateTailVisible(view, listOf(current)))
        assertFalse(semanticStateTailVisible(view, listOf(current.copy(role = MessageRole.ASSISTANT))))
        assertFalse(semanticStateTailVisible(view, listOf(current.copy(parts = listOf(UIMessagePart.Text("左腕已经"))))))
        assertFalse(semanticStateTailVisible(view, emptyList()))
    }

    @Test fun `重复ID不能拼接或冒充完整尾部`() {
        assertFalse(semanticStateTailVisible(view, listOf(current, current)))
        val duplicateSource = view.copy(messages = view.messages + tail)
        assertFalse(semanticStateTailVisible(duplicateSource, listOf(current)))
    }

    @Test fun `隐藏合成和摘要消息不能证明真实原文可见`() {
        assertFalse(semanticStateTailVisible(view, listOf(current.copy(isContextHidden = true))))
        assertFalse(semanticStateTailVisible(view, listOf(current.copy(isSynthetic = true))))
        assertFalse(semanticStateTailVisible(view, listOf(current.copy(isContextCheckpoint = true))))
    }

    @Test fun `扣除注入自身避免状态和历史来源冒充尾部`() {
        val contaminated = current.copy(parts = listOf(UIMessagePart.Text(state.prompt + current.toText())))
        assertFalse(semanticStateTailVisible(view, listOf(contaminated), listOf(state.prompt + current.toText())))
        assertTrue(semanticStateTailVisible(view, listOf(contaminated), listOf(state.prompt)))
    }

    @Test fun `无覆盖与越界检查点不能发送状态`() {
        assertFalse(semanticStateTailVisible(view.copy(checkpoint = view.checkpoint.copy(coveredCount = 0)), listOf(current)))
        assertFalse(semanticStateTailVisible(view.copy(checkpoint = view.checkpoint.copy(coveredCount = 3)), listOf(current)))
    }

    @Test fun `转换删除或改写状态要求从基底重组`() {
        val plan = planSemanticNarrativeRequest(state, recalled, settings, "a", "c")
        assertTrue(finalizeSemanticNarrativeRequest(plan, view, listOf(current),
            setOf(current.id.toString())).requiresRebuild)
        val changed = listOf(current, UIMessage.system(state.prompt.replace("受伤", "痊愈")))
        assertTrue(finalizeSemanticNarrativeRequest(plan, view, changed,
            setOf(current.id.toString())).requiresRebuild)
    }

    @Test fun `完整状态但尾正文被裁剪也要求基底重组`() {
        val plan = planSemanticNarrativeRequest(state, recalled, settings, "a", "c")
        val request = listOf(UIMessage.system(plan.prompt))
        assertTrue(finalizeSemanticNarrativeRequest(plan, view, request,
            setOf(current.id.toString())).requiresRebuild)
    }

    @Test fun `来源finalizer删节选时保留独立状态`() {
        val excerpt = SemanticSourceExcerpt("c", "current-node", current.id.toString(), "hash",
            "user", 0, current.toText().length, current.toText(), listOf("m"))
        val source = SemanticSourceRecallPlan(recalled, semanticSourceExcerptBlock(excerpt) + "\n",
            listOf(excerpt), emptyMap(), emptyMap(), 1)
        val plan = SemanticNarrativeRequestPlan(state, source)
        val request = listOf(current, UIMessage.system(plan.prompt))
        val final = finalizeSemanticNarrativeRequest(plan, view, request, setOf(current.id.toString()))
        assertFalse(final.requiresRebuild)
        assertTrue(final.plan.source.excerpts.isEmpty())
        assertEquals(state.prompt, final.plan.prompt)
        assertTrue(final.messages.any { it.toText().contains(state.prompt) })
    }

    @Test fun `注入正文里的来源节选不冒充真实可见证据`() {
        val excerpt = SemanticSourceExcerpt("c", "old-node", "old", "hash", "assistant",
            0, old.text.length, old.text, listOf("m"))
        val source = SemanticSourceRecallPlan(recalled, semanticSourceExcerptBlock(excerpt) + "\n",
            listOf(excerpt), emptyMap(), emptyMap(), 1)
        val plan = SemanticNarrativeRequestPlan(state.copy(prompt = old.text + "\n"), source)
        val final = finalizeSemanticNarrativeRequest(plan, view,
            listOf(current, UIMessage.system(plan.prompt)), setOf(current.id.toString(), "old"))
        assertEquals(listOf(excerpt), final.plan.source.excerpts)
    }

    @Test fun `最终状态审计按转义后的实际发送文本计数`() {
        val xml = state.copy(prompt = "<state>左腕受伤</state>")
        val plan = SemanticNarrativeRequestPlan(xml, planSemanticSourceRecall(recalled, settings, "a", "c"))
        val sent = xml.prompt.replace("<", "&lt;").replace(">", "&gt;")
        assertEquals(1, auditSemanticNarrativeState(plan, listOf(sent), true)["state_confirmed"])
        assertEquals(1, auditSemanticNarrativeState(plan, listOf(sent), true)["state_entries_confirmed"])
        assertEquals(0, auditSemanticNarrativeState(plan, emptyList(), true)["state_confirmed"])
    }

    @Test fun `末尾用户完整给planner时无需状态全覆盖`() {
        assertTrue(semanticStateQueryTailVisible(view, current.toText(), ""))
    }

    @Test fun `给planner最近完整轮与续写尾部可证明缺口`() {
        val reply = tail.copy(messageId = "reply", role = "assistant", text = "带上绷带。")
        val withReply = view.copy(messages = view.messages + reply)
        assertTrue(semanticStateQueryTailVisible(withReply, current.toText() + "\n" + reply.text, ""))
        assertTrue(semanticStateQueryTailVisible(withReply, current.toText(), "assistant: ${reply.text}"))
    }

    @Test fun `planner没拿到更早缺口或只有history摘要就不给状态`() {
        val missing = tail.copy(messageId = "missing", text = "旧轮改变了右腿伤势。")
        val gap = view.copy(messages = listOf(old, missing, tail))
        assertFalse(semanticStateQueryTailVisible(gap, current.toText(), ""))
    }

    @Test fun `按planner限长文本核验超长尾部不能假装完整`() {
        val longTail = tail.copy(text = "完整的伤势变化".repeat(1500))
        assertFalse(semanticStateQueryTailVisible(view.copy(messages = listOf(old, longTail)), longTail.text, ""))
    }

    @Test fun `baseline读取期间改变来源时最终检查拒绝无节选状态`() = runBlocking {
        var revision = 1L
        assertFalse(inspectSemanticNarrativeAttempt(1L, baselineCurrent = {
            revision = 2L
            true
        }, dependenciesCurrent = { true }, currentSourceRevision = { revision }))
    }

    @Test fun `settings读取期间状态修订改变也在最终检查拒绝`() = runBlocking {
        var stateRevision = "state-v1"
        assertFalse(inspectSemanticNarrativeAttempt(1L, baselineCurrent = {
            stateRevision = "state-v2"
            true
        }, dependenciesCurrent = { stateRevision == "state-v1" }, currentSourceRevision = { 1L }))
    }

    @Test fun `状态读取期间来源变化由最后同步来源检查拒绝`() = runBlocking {
        var revision = 1L
        val calls = mutableListOf<String>()
        assertFalse(inspectSemanticNarrativeAttempt(1L, baselineCurrent = {
            calls += "baseline"
            true
        }, dependenciesCurrent = {
            calls += "dependencies"
            revision = 2L
            true
        }, currentSourceRevision = { calls += "source"; revision }))
        assertEquals(listOf("baseline", "dependencies", "source"), calls)
    }

    @Test fun `状态审计单列预算跳过数且合并预算反映完整提示词`() {
        val plan = SemanticNarrativeRequestPlan(state.copy(skipped = 3),
            planSemanticSourceRecall(recalled, settings, "a", "c"))
        assertEquals(3, auditSemanticNarrativeState(plan, listOf(plan.prompt))["state_budget_skipped"])
        assertEquals(estimateTextTokens(plan.prompt), plan.estimatedTokens)
    }

    @Test fun `回退召回保存开始版本且前后改变时撤回旧结果`() = runBlocking {
        var revision = "old"
        val prepared = prepareSemanticBaselineRecall(revision = { revision }, recall = {
            revision = "new"
            recalled
        })
        assertEquals("old", prepared.revision)
        assertNull(prepared.result)
    }

    @Test fun `caller传参期间repo改变不能给旧结果盖现在版本`() = runBlocking {
        var revision = "old"
        val prepared = prepareSemanticBaselineRecall(revision = { revision }, recall = { recalled })
        revision = "new"
        assertEquals("old", prepared.revision)
        assertFalse(inspectSemanticSourceRequest(settings, prepared.revision, null,
            { revision }, { settings }, { 1L }).summaryCurrent)
    }

    @Test fun `稳定回退召回将结果与冻结版本一同保存`() = runBlocking {
        val prepared = prepareSemanticBaselineRecall(revision = { "same" }, recall = { recalled })
        assertEquals("same", prepared.revision)
        assertEquals(recalled, prepared.result)
    }
}
