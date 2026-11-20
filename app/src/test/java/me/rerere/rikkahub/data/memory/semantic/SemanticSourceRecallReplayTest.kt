package me.rerere.rikkahub.data.memory.semantic

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.context.estimateTextTokens
import org.junit.Assert.*
import org.junit.Test

/** 固定合成回放只比较工程行为和证据包含性，不调用模型，不衡量问答准确率。 */
class SemanticSourceRecallReplayTest {
    private val assistantId = "replay-assistant"
    private val conversationId = "replay-conversation"
    private val evidence = "周六下午三点"
    private val original = DreamMessage(
        "replay-node", "replay-message", "assistant",
        "码头约定在周六下午三点，迟到就取消。", 1, true,
    )
    private val settings = SemanticMemorySettings(
        enabled = true, sourceExpansionEnabled = true,
        dream = SemanticDreamSettings(injectionTokenBudget = 1000),
    )

    private fun source(message: DreamMessage = original) = DreamSource(
        conversationId, message.nodeId, message.messageId, 0, message.snapshotHash,
        message.role, message.createdAt, 0, message.text.length,
    )

    private fun hit(
        id: String = "replay-memory", content: String = "曾约定在码头见面。",
        sources: List<DreamSource> = listOf(source()),
    ): SemanticRecallHit {
        val memory = SemanticMemoryEntity(
            id = id, assistantId = assistantId, conversationId = conversationId,
            content = content, sourceJson = Json.encodeToString(sources), createdAt = 1,
        )
        return SemanticRecallHit(memory, SemanticRankHit(id, 1.0, null, 1.0))
    }

    private fun result(hits: List<SemanticRecallHit>) = SemanticRecallResult(
        hits, hits.size, 0, emptyMap(), emptyList(), 0, "replay-event", candidates = hits,
    )

    private data class Case(
        val name: String,
        val result: SemanticRecallResult,
        val messages: List<DreamMessage> = emptyList(),
        val settings: SemanticMemorySettings,
        val visible: Map<String, String> = emptyMap(),
        val containsEvidence: Boolean,
        val excerptCount: Int,
    )

    @Test
    fun `固定预算回放覆盖细节恢复与不可修复边界`() {
        val noisy = original.copy(text = "😀无关背景。".repeat(500) + original.text + "😀无关背景。".repeat(500))
        val crowded = result(listOf(hit(content = "字".repeat(60))))
        val cases = listOf(
            Case("命中摘要缺时间", result(listOf(hit())), listOf(original), settings,
                containsEvidence = true, excerptCount = 1),
            Case("没有召回候选", result(emptyList()), listOf(original), settings,
                containsEvidence = false, excerptCount = 0),
            Case("候选未入选", result(emptyList()).copy(candidates = listOf(hit())), listOf(original), settings,
                containsEvidence = false, excerptCount = 0),
            Case("重复来源保留两份摘要", result(listOf(hit("first"), hit("second", "码头另有条件。"))),
                listOf(original), settings, containsEvidence = true, excerptCount = 1),
            Case("错分支原文不发送", result(listOf(hit())),
                listOf(original.copy(messageId = "different-branch", text = "另一分支在山顶见面。")),
                settings, containsEvidence = false, excerptCount = 0),
            Case("编辑后旧哈希不发送", result(listOf(hit())),
                listOf(original.copy(text = "码头约定已改到下周。")), settings,
                containsEvidence = false, excerptCount = 0),
            Case("剩余预算不足只保留摘要", crowded, listOf(original),
                settings.copy(dream = settings.dream.copy(injectionTokenBudget = 200)),
                containsEvidence = false, excerptCount = 0),
            Case("已发送原文不重复展开", result(listOf(hit())), listOf(original), settings,
                visible = mapOf(original.messageId to original.text),
                containsEvidence = false, excerptCount = 0),
            Case("长中文与emoji连续节选", result(listOf(hit(sources = listOf(source(noisy))))),
                listOf(noisy), settings, containsEvidence = true, excerptCount = 1),
        )
        val rows = cases.map { case ->
            val baseline = planSemanticSourceRecall(
                case.result, case.settings.copy(sourceExpansionEnabled = false),
                assistantId, conversationId, case.messages, case.visible, "码头约定何时？",
            )
            val expanded = planSemanticSourceRecall(
                case.result, case.settings, assistantId, conversationId,
                case.messages, case.visible, "码头约定何时？",
            )
            val budget = case.settings.normalized().dream.injectionTokenBudget
            assertEquals(case.name, baseline.result, expanded.result)
            assertEquals(case.name, semanticRecallPrompt(baseline.result), baseline.prompt)
            assertFalse(case.name, baseline.prompt.contains(evidence))
            assertEquals(case.name, case.containsEvidence, expanded.prompt.contains(evidence))
            assertEquals(case.name, case.excerptCount, expanded.excerpts.size)
            assertTrue(case.name, estimateTextTokens(baseline.prompt) <= budget)
            assertTrue(case.name, expanded.estimatedTokens <= budget)
            assertTrue(case.name, estimateTextTokens(expanded.prompt.removePrefix(baseline.prompt)) <= budget * 40 / 100)
            expanded.memoryLines.values.forEach { assertTrue(case.name, expanded.prompt.contains(it)) }
            expanded.excerpts.forEach { excerpt ->
                val message = case.messages.single { it.messageId == excerpt.messageId && it.nodeId == excerpt.nodeId }
                assertEquals(case.name, message.text.substring(excerpt.start, excerpt.end), excerpt.text)
                assertFalse(case.name, excerpt.text.first().isLowSurrogate())
                assertFalse(case.name, excerpt.text.last().isHighSurrogate())
                assertEquals(case.name, 1, Regex(Regex.escape(semanticSourceExcerptBlock(excerpt))).findAll(expanded.prompt).count())
            }
            if (case.name == "重复来源保留两份摘要") {
                assertEquals(listOf("first", "second"), expanded.excerpts.single().memoryIds)
                assertEquals(2, expanded.result.hits.size)
            }
            println("回放：${case.name}；总预算=$budget；摘要证据=false；展开证据=${case.containsEvidence}；片段=${expanded.excerpts.size}")
            buildJsonObject {
                put("case", case.name)
                put("budget", budget)
                put("baselineEstimatedTokens", estimateTextTokens(baseline.prompt))
                put("expandedEstimatedTokens", expanded.estimatedTokens)
                put("baselineContainsEvidence", baseline.prompt.contains(evidence))
                put("expandedContainsEvidence", expanded.prompt.contains(evidence))
                put("excerpts", expanded.excerpts.size)
                put("acceptedMemoryIds", JsonArray(expanded.result.hits.map { JsonPrimitive(it.memory.id) }))
                put("filters", JsonObject(expanded.filters.mapValues { JsonPrimitive(it.value) }))
                put("alreadyVisibleContainsEvidence", case.visible.values.any { it.contains(evidence) })
            }
        }
        System.getProperty("source.recall.replay.dir")?.let { directory ->
            Files.writeString(
                Path.of(directory, "replay.json"),
                buildJsonObject {
                    put("scope", "固定合成回放，仅证明工程行为与证据包含性；不是模型问答准确率")
                    put("cases", JsonArray(rows))
                }.toString(),
                StandardCharsets.UTF_8,
            )
        }
    }
}
