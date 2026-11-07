package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.StreamCompletion
import me.rerere.ai.ui.UIMessage
import org.junit.Test
import kotlin.uuid.Uuid

class GeneratedResponseTargetTest {
    @Test fun partialResponseKeepsUnknownAndAllMetadataOnTheStableTarget() {
        val target = UIMessage.assistant("")
        val generated = UIMessage.assistant("partial").copy(
            generationCompletion = StreamCompletion.UNKNOWN,
            modelId = Uuid.random(), modelSnapshot = ModelSnapshot("fixture", "Fixture"),
            usage = TokenUsage(promptTokens = 11, completionTokens = 7), stepCount = 2,
        )
        val bound = generated.bindResponseTo(target)
        check(bound == generated.copy(id = target.id, createdAt = target.createdAt))
        check(bound.generationCompletion == StreamCompletion.UNKNOWN && bound.finishedAt == null)
    }

    @Test fun everyCompletedOrFailedProtocolStateSurvivesTargetBinding() {
        val target = UIMessage.assistant("")
        StreamCompletion.entries.forEach { completion ->
            val generated = UIMessage.assistant("response").copy(
                generationCompletion = completion, finishedAt = target.createdAt,
            )
            val bound = generated.bindResponseTo(target)
            check(bound.id == target.id && bound.createdAt == target.createdAt)
            check(bound.parts == generated.parts && bound.finishedAt == generated.finishedAt)
            check(bound.generationCompletion == completion)
        }
    }
}
