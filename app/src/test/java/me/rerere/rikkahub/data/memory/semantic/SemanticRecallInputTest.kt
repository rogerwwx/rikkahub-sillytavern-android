package me.rerere.rikkahub.data.memory.semantic

import me.rerere.ai.ui.StreamCompletion
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticRecallInputTest {
    private fun completed(text: String): UIMessage = UIMessage.assistant(text).let {
        it.copy(finishedAt = it.createdAt, generationCompletion = StreamCompletion.NORMAL)
    }

    @Test
    fun shortFollowUpUsesTheLatestCompleteRound() {
        val input = buildSemanticRecallInput(listOf(
            UIMessage.user("OLD_USER"), completed("OLD_SCENE".repeat(1000)),
            UIMessage.user("我们已经来到码头了吗？"), completed("LATEST_SCENE 她在码头等那艘渡船。"),
            UIMessage.user("嗯。"),
        ), 0)
        assertEquals("嗯。", input.currentUserMessage)
        assertTrue(input.retrievalQuery.contains("码头"))
        assertTrue(input.recentContext.contains("LATEST_SCENE"))
        assertTrue(input.recentContext.contains("我们已经来到码头了吗"))
        assertFalse(input.recentContext.contains("OLD_SCENE"))
        assertFalse(input.recentContext.contains("嗯。"))
    }

    @Test
    fun contextUsesVisibleSelectedHistoryAndNeverCrossesTheLatestCheckpoint() {
        val input = buildSemanticRecallInput(listOf(
            UIMessage.user("隐藏的原文").copy(isContextHidden = true),
            completed("隐藏的回答").copy(isContextHidden = true),
            UIMessage.user("更早的完整轮次"), completed("已被摘要覆盖"),
            UIMessage.user("CHECKPOINT").copy(isContextCheckpoint = true),
            UIMessage.user("VISIBLE_USER"), completed("VISIBLE_SCENE"),
            UIMessage.user("SYNTHETIC").copy(isSynthetic = true),
            UIMessage.user("继续"),
        ), 0)
        assertTrue(input.recentContext.contains("VISIBLE_USER"))
        assertTrue(input.recentContext.contains("VISIBLE_SCENE"))
        for (hidden in listOf("隐藏", "CHECKPOINT", "已被摘要覆盖", "SYNTHETIC")) {
            assertFalse(input.retrievalQuery.contains(hidden))
            assertFalse(input.recentContext.contains(hidden))
        }
        val limited = buildSemanticRecallInput(listOf(
            UIMessage.user("OLD_USER"), completed("OLD_SCENE"), UIMessage.user("继续"),
        ), 1)
        assertEquals("继续", limited.retrievalQuery)
        assertEquals("", limited.recentContext)
    }

    @Test
    fun aCheckpointDoesNotBecomeTheCurrentUserMessage() {
        val input = buildSemanticRecallInput(listOf(
            UIMessage.user("旧用户"), completed("旧回答"),
            UIMessage.user("压缩摘要").copy(isContextCheckpoint = true),
        ), 0)
        assertEquals("", input.currentUserMessage)
        assertEquals("", input.retrievalQuery)
    }

    @Test
    fun explicitShortTopicAndLongCurrentIntentAreNotRewritten() {
        for (query in listOf("去灯塔。", "别再谈渡船，谈她的父亲。",
            "BEGIN_INTENT" + "很长的当前输入".repeat(1500) + "MIDDLE_INTENT" +
                "另一段当前输入".repeat(1500) + "END_INTENT")) {
            val input = buildSemanticRecallInput(listOf(
                UIMessage.user("背景问题"), completed("过去的场景"), UIMessage.user(query),
            ), 0)
            assertEquals(query, input.currentUserMessage)
            assertEquals(query, input.retrievalQuery)
        }
    }

    @Test
    fun longPriorRoundKeepsBothRolesAndTheLatestSceneWithoutUnboundedExpansion() {
        val input = buildSemanticRecallInput(listOf(
            UIMessage.user("USER_START" + "旧说明".repeat(3000) + "USER_END"),
            completed("ASSISTANT_START" + "中间场景".repeat(3000) + "LATEST_SCENE"),
            UIMessage.user("你还记得吗？"),
        ), 0)
        assertTrue(input.recentContext.contains("USER_START"))
        assertTrue(input.recentContext.contains("USER_END"))
        assertTrue(input.recentContext.contains("ASSISTANT_START"))
        assertTrue(input.recentContext.contains("LATEST_SCENE"))
        assertTrue(input.recentContext.length <= 1300)
        assertTrue(input.retrievalQuery.length <= 1300)
        assertTrue(input.retrievalQuery.contains("LATEST_SCENE"))
        assertTrue(input.retrievalQuery.contains("你还记得吗？"))
        val rerankContext = semanticRerankContext(input.currentUserMessage, input.recentContext)
        assertTrue(rerankContext.contains("USER_START"))
        assertTrue(rerankContext.contains("USER_END"))
        assertTrue(rerankContext.contains("ASSISTANT_START"))
        assertTrue(rerankContext.contains("LATEST_SCENE"))
    }

    @Test
    fun incompleteAssistantOutputIsNotACompleteContextRound() {
        val input = buildSemanticRecallInput(listOf(
            UIMessage.user("未完成轮次"),
            completed("TRUNCATED_SCENE").copy(generationCompletion = StreamCompletion.TRUNCATED),
            UIMessage.user("继续"),
        ), 0)
        assertEquals("", input.recentContext)
        assertEquals("继续", input.retrievalQuery)
    }

    @Test
    fun cacheKeyIncludesTheFrozenQueryContextAndBothRevisions() {
        val history = listOf(UIMessage.user("到哪里去？"), completed("去码头。"), UIMessage.user("嗯"))
        val input = buildSemanticRecallInput(history, 0)
        val key = input.cacheKey("memory-v1", "jev-v1")
        assertEquals(key, input.cacheKey("memory-v1", "jev-v1"))
        assertNotEquals(key, input.cacheKey("memory-v2", "jev-v1"))
        assertNotEquals(key, input.cacheKey("memory-v1", "jev-v2"))
        val anotherScene = buildSemanticRecallInput(
            listOf(history[0], completed("去灯塔。"), history[2]), 0)
        assertNotEquals(key, anotherScene.cacheKey("memory-v1", "jev-v1"))
        val anotherQuery = buildSemanticRecallInput(history.dropLast(1) + UIMessage.user("去书店。"), 0)
        assertNotEquals(key, anotherQuery.cacheKey("memory-v1", "jev-v1"))
    }

    @Test
    fun continuationKeepsTheAssistantPrefillAlreadyInTheFrozenRequest() {
        val input = buildSemanticRecallInput(listOf(
            UIMessage.user("早先谈起花园"), completed("花园的旧场景"),
            UIMessage.user("现在沿着河走。"),
            // ChatService.generateForAssistant(history + promptRole=ASSISTANT) 的实际输入形状。
            UIMessage.assistant("PREFILL_START 她已经抵达渡口，正要登船。PREFILL_END"),
        ), 0)
        assertEquals("现在沿着河走。", input.currentUserMessage)
        assertTrue(input.recentContext.contains("PREFILL_START"))
        assertTrue(input.recentContext.contains("PREFILL_END"))
        assertTrue(input.retrievalQuery.contains("渡口"))
        assertFalse(input.recentContext.contains("花园的旧场景"))
    }

    @Test
    fun generationWithoutANewUserRetainsTheLatestExistingReply() {
        val history = listOf(
            UIMessage.user("去河边。"), completed("LATEST_REPLY 已经上船。"),
            UIMessage.system("代写或安静生成的附加指令"),
        )
        val input = buildSemanticRecallInput(history, 0)
        assertTrue(input.recentContext.contains("LATEST_REPLY"))
        assertTrue(input.retrievalQuery.contains("已经上船"))
        assertFalse(input.retrievalQuery.contains("附加指令"))
        val rerank = semanticRerankContext(input.currentUserMessage, input.recentContext)
        assertTrue(rerank.contains("LATEST_REPLY"))
    }

    @Test
    fun manyExistingAssistantMessagesCannotExceedTheDownstreamContextBudget() {
        val history = listOf(UIMessage.user("CURRENT_TURN")) +
            (0 until 160).map { completed("场景片段 $it") } +
            completed("LATEST_SCENE") + UIMessage.user("嗯")
        val input = buildSemanticRecallInput(history, 0)
        assertTrue(input.recentContext.length <= 1200)
        assertTrue(input.recentContext.contains("CURRENT_TURN"))
        assertTrue(input.recentContext.contains("LATEST_SCENE"))
        val rerankContext = semanticRerankContext(input.currentUserMessage, input.recentContext)
        assertTrue(rerankContext.contains("CURRENT_TURN"))
        assertTrue(rerankContext.contains("LATEST_SCENE"))
    }

    @Test
    fun assistantOnlyVisibleTailStillProvidesARecallScene() {
        val inputs = listOf(
            buildSemanticRecallInput(listOf(
                UIMessage.user("沿着河走。"), UIMessage.assistant("PREFILL 她已经抵达渡口。"),
            ), 1),
            buildSemanticRecallInput(listOf(
                UIMessage.user("旧的隐藏场景"), completed("旧回答"),
                UIMessage.user("CHECKPOINT").copy(isContextCheckpoint = true),
                completed("LATEST_SCENE 她已经上船。"),
            ), 0),
        )
        for (input in inputs) {
            assertEquals("", input.currentUserMessage)
            assertTrue(input.retrievalQuery.isNotBlank())
            assertTrue(input.recentContext.startsWith("assistant: "))
            assertFalse(input.retrievalQuery.contains("隐藏"))
            assertFalse(input.retrievalQuery.contains("CHECKPOINT"))
            assertTrue(input.recentContext.length <= 1200)
            val rerankContext = semanticRerankContext(input.currentUserMessage, input.recentContext)
            assertTrue(rerankContext.contains(input.recentContext))
        }
    }

    @Test
    fun assistantOnlyFallbackCannotUseHiddenTruncatedOrPendingOutput() {
        val pending = completed("PENDING_SCENE").let {
            it.copy(parts = it.parts + UIMessagePart.Tool(toolCallId = "pending", toolName = "tool", input = "{}"))
        }
        for (reply in listOf(
            completed("HIDDEN_SCENE").copy(isContextHidden = true),
            completed("TRUNCATED_SCENE").copy(generationCompletion = StreamCompletion.TRUNCATED),
            pending,
        )) {
            val input = buildSemanticRecallInput(listOf(reply), 0)
            assertEquals("", input.currentUserMessage)
            assertEquals("", input.retrievalQuery)
            assertEquals("", input.recentContext)
        }
    }
}
