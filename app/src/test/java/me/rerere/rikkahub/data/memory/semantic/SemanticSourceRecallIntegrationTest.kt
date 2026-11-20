package me.rerere.rikkahub.data.memory.semantic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class SemanticSourceRecallIntegrationTest {
    private val settings = SemanticMemorySettings(enabled = true, sourceExpansionEnabled = true)
    private val memory = SemanticMemoryEntity(
        id = "memory-1", assistantId = "assistant", conversationId = "conversation", content = "约定在周五见面",
    )
    private val hit = SemanticRecallHit(memory, SemanticRankHit(memory.id, 1.0, null, 1.0))
    private val result = SemanticRecallResult(listOf(hit), 1, 0, emptyMap(), emptyList(), 0, "event")
    private val source = UIMessage.user("见面时间是周五下午三点。")
    private val excerpt = SemanticSourceExcerpt("conversation", "node", source.id.toString(), "hash",
        "user", 0, source.toText().length, source.toText(), listOf(memory.id))
    private val line = "[memory-1 / unknown / v1] 约定在周五见面"
    private val plan = SemanticSourceRecallPlan(result,
        "$line\n${semanticSourceExcerptBlock(excerpt)}", listOf(excerpt), mapOf(memory.id to line),
        mapOf("source_expanded" to 1, "injection_budget" to 0), 120)

    @Test fun `完整提示词保留时才按最终历史正文剔除重复来源`() {
        val injected = UIMessage.system(plan.prompt)
        val pruned = finalizeSemanticSourceRecall(plan, listOf(source, injected), setOf(source.id.toString()))
        assertTrue(pruned.plan.excerpts.isEmpty())
        assertTrue(pruned.messages.last().toText().contains(line))
        assertFalse(pruned.messages.last().toText().contains(semanticSourceExcerptBlock(excerpt)))
        assertEquals(1, pruned.plan.filters["source_visible"])
    }

    @Test fun `转换移除正文后不能依据转换前历史剪枝`() {
        val hidden = source.copy(parts = listOf(UIMessagePart.Text("压缩摘要")))
        val final = finalizeSemanticSourceRecall(plan, listOf(hidden, UIMessage.system(plan.prompt)), setOf(source.id.toString()))
        assertEquals(listOf(excerpt), final.plan.excerpts)
        assertTrue(final.messages.last().toText().contains(excerpt.text))
    }

    @Test fun `转换改写提示词时保守保留并逐字审计`() {
        val transformed = plan.prompt.replace("下午三点", "下午四点").replace("约定在周五见面", "摘要被改写")
        val final = finalizeSemanticSourceRecall(plan, listOf(source, UIMessage.system(transformed)), setOf(source.id.toString()))
        assertEquals(plan, final.plan)
        val audit = auditSemanticSourceRecall(listOf("memory-1", "budget-skipped"), final.plan,
            final.messages.map { it.toText() })
        assertEquals(listOf("memory-1", "budget-skipped"), audit.selectedIds)
        assertEquals(listOf(memory.id), audit.budgetAcceptedIds)
        assertTrue(audit.confirmedInjectedIds.isEmpty())
        assertEquals(0, audit.sourceConfirmed)
        assertEquals(2, audit.injectionTransformedOrMissing)
    }

    @Test fun `注入合并到真实消息中不会把自身原文误判为已可见`() {
        val merged = source.copy(parts = listOf(UIMessagePart.Text("原文已隐藏\n${plan.prompt}")))
        val final = finalizeSemanticSourceRecall(plan, listOf(merged), setOf(source.id.toString()))
        assertEquals(listOf(excerpt), final.plan.excerpts)
    }

    @Test fun `只有真实历史身份的最终正文参与可见性判断`() {
        val fake = source.copy(isSynthetic = true)
        val final = finalizeSemanticSourceRecall(plan, listOf(fake, UIMessage.system(plan.prompt)), setOf(source.id.toString()))
        assertEquals(listOf(excerpt), final.plan.excerpts)
    }

    @Test fun `XML转义后按相同转义规则确认正文与原文块`() {
        val xml = plan.copy(prompt = "<memory>$line</memory>", memoryLines = mapOf(memory.id to "<memory>$line</memory>"))
        val texts = listOf(xml.prompt, semanticSourceExcerptBlock(excerpt))
            .map { it.replace("<", "&lt;").replace(">", "&gt;") }
        val audit = auditSemanticSourceRecall(listOf(memory.id), xml, texts, escapeXml = true)
        assertEquals(listOf(memory.id), audit.confirmedInjectedIds)
        assertEquals(1, audit.sourceConfirmed)
        assertEquals(0, audit.injectionTransformedOrMissing)
    }

    @Test fun `来源上界停在冻结请求最后真实消息且不猜配缺失上界`() {
        val before = DreamMessage("before", "before", "user", "隐藏的过去", 0, true)
        val boundary = DreamMessage("current", source.id.toString(), "user", source.toText(), 1, true)
        val future = DreamMessage("future", "future", "assistant", "本轮之后的消息", 2, true)
        assertEquals(listOf(before, boundary),
            semanticSourceRequestWindow(listOf(before, boundary, future), boundary.messageId, "user", source.toText()).messages)
        val missing = semanticSourceRequestWindow(listOf(before, future), boundary.messageId, "user", source.toText())
        assertTrue(missing.messages.isEmpty())
        assertEquals(1, missing.filters["source_request_boundary_missing"])
        val checkpoint = UIMessage.system("摘要").copy(isContextCheckpoint = true)
        val synthetic = UIMessage.user("临时提示").copy(isSynthetic = true)
        assertEquals(source.id.toString(), semanticSourceRequestBoundary(listOf(source, checkpoint, synthetic)))
    }

    @Test fun `同ID边界正文或角色改变也不展开`() {
        val boundary = DreamMessage("node", source.id.toString(), "user", source.toText(), 1, true)
        for (changed in listOf(boundary.copy(text = "已编辑"), boundary.copy(role = "assistant"))) {
            val window = semanticSourceRequestWindow(listOf(changed), boundary.messageId, "user", source.toText())
            assertTrue(window.messages.isEmpty())
            assertEquals(1, window.filters["source_request_boundary_changed"])
        }
    }

    @Test fun `召回等待期间来源修订变更时不读取新正文`() = runBlocking {
        var reads = 0
        val window = readSemanticSourceRequestWindow(1L, source.id.toString(), "user", source.toText(),
            { 2L }, { reads++; emptyList() })
        assertEquals(0, reads)
        assertTrue(window.messages.isEmpty())
        assertEquals(1, window.filters["source_request_stale"])
    }

    @Test fun `来源读取期间变更修订时丢弃快照`() = runBlocking {
        var revision = 1L
        val window = readSemanticSourceRequestWindow(1L, source.id.toString(), "user", source.toText(),
            { revision }, {
                revision = 2L
                listOf(DreamMessage("node", source.id.toString(), "user", source.toText(), 1, true))
            })
        assertTrue(window.messages.isEmpty())
        assertEquals(1, window.filters["source_request_stale"])
    }

    @Test fun `诊断写入期间来源改变后发送前检查拒绝旧证据`() = runBlocking {
        var revision = 1L
        recordSemanticInjectionSafely { revision = 2L }
        assertFalse(inspectSemanticSourceRequest(settings, "same", 1L,
            { "same" }, { settings }, { revision }).sourceCurrent)
    }

    @Test fun `诊断故障不阻断生成但取消必须传播`() = runBlocking {
        recordSemanticInjectionSafely { error("存储失败") }
        try {
            recordSemanticInjectionSafely { throw CancellationException("cancel") }
            fail("取消必须传播")
        } catch (_: CancellationException) {
            // 保留正常取消语义。
        }
    }

    @Test fun `仅来源过时保留相同预算摘要且移除证据`() = runBlocking {
        val validity = inspectSemanticSourceRequest(settings, "same", 1L,
            { "same" }, { settings }, { 2L })
        assertTrue(validity.summaryCurrent)
        assertFalse(validity.sourceCurrent)
        val observed = plan.copy(filters = plan.filters + mapOf("source_invalid" to 2, "source_no_anchor" to 3))
        val fallback = semanticSourceSummaryFallback(observed, result, settings, "assistant", "conversation")
        assertEquals(budgetSemanticRecall(result, settings), fallback.result)
        assertEquals(semanticRecallPrompt(fallback.result), fallback.prompt)
        assertTrue(fallback.excerpts.isEmpty())
        assertEquals(1, fallback.filters["source_request_stale"])
        assertEquals(2, fallback.filters["source_invalid"])
        assertEquals(3, fallback.filters["source_no_anchor"])
    }

    @Test fun `记忆版本或配置改变使整个语义块失效`() = runBlocking {
        assertFalse(inspectSemanticSourceRequest(settings, "old", 1L,
            { "new" }, { settings }, { 1L }).summaryCurrent)
        assertFalse(inspectSemanticSourceRequest(settings, "same", 1L,
            { "same" }, { settings.copy(enabled = false) }, { 1L }).summaryCurrent)
    }

    @Test fun `持久诊断只写身份计数并保留候选明细`() {
        val audit = auditSemanticSourceRecall(listOf(memory.id), plan, emptyList())
        val original = buildJsonObject { put("candidateDetails", buildJsonArray { add("candidate") }) }
        val details = semanticInjectionDetails(original, plan, audit)
        assertEquals(original["candidateDetails"], details["candidateDetails"])
        assertEquals(0, details["injected"]!!.jsonPrimitive.int)
        assertEquals(1, details["sourceExpanded"]!!.jsonPrimitive.int)
        assertEquals(0, details["sourceConfirmed"]!!.jsonPrimitive.int)
        assertEquals(2, details["transformed_or_missing"]!!.jsonPrimitive.int)
        assertFalse(details.toString().contains(memory.content))
        assertFalse(details.toString().contains(excerpt.text))
    }

    @Test fun `发送前最后核对实时配置及来源修订`() = runBlocking {
        val calls = mutableListOf<String>()
        var revision = 1L
        val valid = inspectSemanticSourceRequest(settings, "memory-revision", 1L,
            recallRevision = { calls += "memory"; "memory-revision" },
            currentSettings = { calls += "config"; revision = 2; settings },
            currentSourceRevision = { calls += "source"; revision })
        assertTrue(valid.summaryCurrent)
        assertFalse(valid.sourceCurrent)
        assertEquals(listOf("memory", "config", "source"), calls)
    }

    @Test fun `配置与记忆版本改变均拒绝旧请求并正常传播取消`() = runBlocking {
        assertFalse(inspectSemanticSourceRequest(settings, "old", null,
            { "new" }, { settings }, { error("关闭展开不读取来源修订") }).summaryCurrent)
        assertFalse(inspectSemanticSourceRequest(settings, "same", null,
            { "same" }, { settings.copy(enabled = false) }, { error("关闭展开不读取来源修订") }).summaryCurrent)
        try {
            inspectSemanticSourceRequest(settings, "same", null,
                { throw CancellationException("cancel") }, { settings }, { 0 })
            fail("取消必须传播")
        } catch (_: CancellationException) {
            // 正常取消不被转换成旧请求继续发送。
        }
    }
}
