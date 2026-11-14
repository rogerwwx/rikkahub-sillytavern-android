package me.rerere.rikkahub.data.memory.semantic

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.jev.*
import me.rerere.rikkahub.ui.pages.assistant.detail.semanticRerankDiagnosticText
import org.junit.Assert.*
import org.junit.Test

class SemanticJevRerankTest {
    @Test fun recentSceneAndUppercaseRolesSurviveLongHistory() {
        val context = semanticRerankContext("继续",
            "USER: OLD_SCENE " + "旧".repeat(1800) + "\nASSISTANT: 最新的约定\nUSER: LATEST_SCENE")
        assertTrue(context.contains("LATEST_SCENE"))
        assertFalse(context.contains("OLD_SCENE"))
        assertTrue(context.contains("用户") && context.contains("助手"))
        assertFalse(context.contains("按最近上下文推断"))
    }

    @Test fun tailDoesNotStartWithHalfOfAnEmoji() {
        val context = semanticRerankContext("继续", "旧".repeat(1500) + "😀" + "新".repeat(1299))
        assertFalse(context.any { it.isLowSurrogate() })
    }

    @Test fun candidateKeepsCompleteBodyAndScopeWithoutStorageMetadata() {
        val body = "  首行\n" + "完整记忆😀".repeat(1000) + "\n末行  "
        val memory = SemanticMemoryEntity(id = "m", assistantId = "a", conversationId = "c",
            content = body, type = "relational", importance = 0.8,
            metadataJson = """{"scope":"role"}""", sourceJson = """[{"id":"s"}]""", accessCount = 99)
        val text = semanticRerankItemText(memory)
        assertTrue(text.contains(body))
        assertTrue(text.contains("角色"))
        assertFalse(text.contains("[时间]") || text.contains("访问 99") || text.contains("重要度"))
        assertFalse(text.contains("[关联]") || text.contains("[状态]"))
    }

    @Test fun rulesAreSharedButEveryQuestionRetainsItsOwnBodyAndFiveDescriptiveLevels() {
        val state = JevPrompts.memoryRerankState("当前场景：雨夜重逢").jsonObject
        assertTrue(state.getValue("retrieval_context").jsonPrimitive.content.contains("雨夜重逢"))
        val rules = state.getValue("evaluation_rules").jsonPrimitive.content
        assertTrue(rules.contains("未直接提及") && rules.contains("不判断") && rules.contains("指令"))
        val question = JevPrompts.memoryRerank("完整候选")
        assertTrue(question.instructions!!.jsonPrimitive.content.contains("完整候选"))
        assertTrue(question.instructions!!.jsonPrimitive.content.contains("evaluation_rules"))
        assertTrue(question.instructions!!.jsonPrimitive.content.length < 100)
        val levels = question.criteria!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(5, levels.size)
        assertTrue(levels.all { it.length >= 10 && !it.contains("上一") && !it.contains("其他档") })
    }

    @Test fun absentAndPartialUsageAreNotFabricatedAsZero() {
        assertNull(Json.decodeFromString<JevResponse>("""{"answers":{}}""").usage)
        val partial = Json.decodeFromString<JevResponse>("""{"answers":{},"usage":{"input_tokens":17}}""")
        assertEquals(17, partial.usage?.inputTokens)
        assertNull(partial.usage?.outputTokens)
        assertNull(Json.decodeFromString<JevResponse>("""{"answers":{},"usage":{}}""").usage?.inputTokens)
    }

    @Test fun malformedUsageDoesNotBecomeABillOrInvalidateAnswers() {
        val partial = jevResponseUsage(Json.parseToJsonElement(
            """{"input_tokens":-4,"output_tokens":12}"""))
        assertNull(partial?.inputTokens)
        assertEquals(12, partial?.outputTokens)
        assertNull(jevResponseUsage(Json.parseToJsonElement("""{"input_tokens":"42","output_tokens":0.5}""")))
        assertNull(jevResponseUsage(JsonPrimitive("unexpected")))
    }

    @Test fun diagnosticShowsMissingAndPartialBatchUsageHonestly() {
        val noUsage = semanticRerankDiagnosticText("""{"items":[],"batches":1,"batchDetails":[
            {"questions":2,"attempts":1,"requestBytes":900,"estimatedTextTokens":200,"requestedModel":"clef"}
        ]}""")
        assertTrue(noUsage.contains("未提供") && noUsage.contains("本地估算"))
        assertFalse(noUsage.contains("输入 0"))
        val mixed = semanticRerankDiagnosticText("""{"items":[],"batches":2,"batchDetails":[
            {"questions":32,"attempts":1,"requestBytes":900,"estimatedTextTokens":200,
             "responseModel":"clef-fixture","usage":{"input_tokens":17,"output_tokens":2}},
            {"questions":8,"attempts":2,"requestBytes":500,"estimatedTextTokens":100}
        ]}""")
        assertTrue(mixed.contains("clef-fixture") && mixed.contains("输入 17"))
        assertTrue(mixed.contains("未提供") && mixed.contains("不完整"))
        assertTrue(mixed.contains("2 次") && mixed.contains("重试"))
        assertFalse(mixed.contains("总计 17"))
    }

    @Test fun inputOnlyUsageIsStillIncompleteForDecisionModels() {
        val text = semanticRerankDiagnosticText("""{"items":[],"batchDetails":[
            {"attempts":1,"responseModel":"clef-fixture","usage":{"input_tokens":17}}
        ]}""")
        assertTrue(text.contains("输入 17") && text.contains("输出 未提供"))
        assertTrue(text.contains("成本记录不完整"))
    }
}
