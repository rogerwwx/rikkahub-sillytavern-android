package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.ui.StreamCompletion
import me.rerere.ai.ui.UIMessage
import org.junit.Test

class GenerationResponseBaseTest {
    private val model = Model(modelId = "fixture-chat")

    @Test fun newResponseIsUnknownBeforeAnyChunkArrives() {
        val user = UIMessage.user("question")
        val prepared = listOf(user).beginAssistantResponse(model)
        check(prepared.first() === user)
        check(prepared.last().generationCompletion == StreamCompletion.UNKNOWN)
        check(prepared.last().finishedAt == null && prepared.last().modelId == model.id)
    }

    @Test fun anotherToolStepDoesNotInheritCompletedState() {
        val old = UIMessage.assistant("tool step").copy(
            generationCompletion = StreamCompletion.NORMAL,
            finishedAt = UIMessage.user("clock").createdAt,
        )
        val prepared = listOf(UIMessage.user("question"), old).beginAssistantResponse(model).last()
        check(prepared.id == old.id && prepared.parts == old.parts)
        check(prepared.generationCompletion == StreamCompletion.UNKNOWN && prepared.finishedAt == null)
        check(old.generationCompletion == StreamCompletion.NORMAL && old.finishedAt != null)
    }

    @Test fun retryKeepsTheOriginalResponseIdAndUnchangedHistory() {
        val user = UIMessage.user("question")
        val base = listOf(user).beginAssistantResponse(model)
        val retried = base.beginAssistantResponse(model)
        check(retried.last().id == base.last().id)
        check(retried.first() === user && retried.last().parts.isEmpty())
        check(retried.last().generationCompletion == StreamCompletion.UNKNOWN)
    }
}
