package me.rerere.rikkahub.data.memory.semantic

import me.rerere.rikkahub.data.memory.CrossWindowMemoryCore

object CrossWindowCompressionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val core = CrossWindowMemoryCore()
        repeat(10) { index -> core.append("assistant", "conversation", "m$index",
            if (index % 2 == 0) "user" else "assistant", "内容 $index") }
        val work = core.claimCompression("assistant", 1, 3)!!
        check(work.entries.size == 6 && work.entries.last().role == "assistant")
        core.completeCompression(work, "完整前缀摘要")
        check(core.peekRecent("assistant").size == 4)
        val interleaved = CrossWindowMemoryCore()
        interleaved.append("assistant", "a", "a-u", "user", "用户A")
        interleaved.append("assistant", "b", "b-u", "user", "用户B")
        interleaved.append("assistant", "a", "a-a", "assistant", "回复A")
        interleaved.append("assistant", "b", "b-a", "assistant", "回复B")
        check(interleaved.claimCompression("assistant", 1, 1) == null)
        interleaved.append("assistant", "a", "next-u", "user", "下一轮")
        val full = interleaved.claimCompression("assistant", 1, 1)!!
        check(full.entries.size == 4 && full.entries.last().messageId == "b-a")
        val unfinished = CrossWindowMemoryCore()
        repeat(6) { unfinished.append("assistant", "conversation", "$it", "user", "尚无完整回复") }
        check(unfinished.claimCompression("assistant", 1, 1) == null)
        println("CrossWindowCompressionSmoke: PASS（租约按完整轮次、多窗口交错与未完成轮次）")
    }
}
