package me.rerere.ai.ui

/** 后台消费者必须区分协议完成与连接结束。 */
object CompleteStreamSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        fun rejected(chunks: List<StreamChunk>) = check(runCatching {
            val guard = CompleteStreamGuard()
            chunks.forEach(guard::accept)
            guard.requireComplete()
        }.isFailure)
        val text = listOf(StreamChunk.TextDelta("t", "{}"))
        rejected(text)
        rejected(text + StreamChunk.Finish("stop"))
        rejected(text + StreamChunk.Finish("length", completion = StreamCompletion.TRUNCATED))
        rejected(text + StreamChunk.Finish("stop", completion = StreamCompletion.NORMAL) + StreamChunk.TextDelta("t", "extra"))
        rejected(listOf(StreamChunk.Finish("stop", completion = StreamCompletion.NORMAL)))
        rejected(listOf(StreamChunk.ReasoningDelta("r", "reasoning"), StreamChunk.Finish("stop", completion = StreamCompletion.NORMAL)))
        rejected(listOf(StreamChunk.ToolCallStart("x", "save"), StreamChunk.ToolCallDelta("x", inputDelta = "{"),
            StreamChunk.ToolCallEnd("x"), StreamChunk.Finish("tool_calls", completion = StreamCompletion.NORMAL)))
        val guard = CompleteStreamGuard()
        (text + StreamChunk.Finish("stop", completion = StreamCompletion.NORMAL)).forEach(guard::accept)
        check(guard.requireComplete().completion == StreamCompletion.NORMAL)
        val tools = CompleteStreamGuard()
        listOf(StreamChunk.ToolCallStart("x", "save"), StreamChunk.ToolCallDelta("x", inputDelta = "{}"),
            StreamChunk.ToolCallEnd("x"), StreamChunk.Finish("tool_calls", completion = StreamCompletion.NORMAL)).forEach(tools::accept)
        tools.requireComplete()
        check(protocolCompletion("MAX_TOKENS") == StreamCompletion.TRUNCATED)
        check(protocolCompletion("pause_turn") == StreamCompletion.UNKNOWN)
        println("CompleteStreamSmoke passed")
    }
}
