package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListNavigationTest {
    @Test
    fun `top jump first stops at the compression boundary`() {
        assertEquals(40, topJumpTarget(72, 0, 40))
    }

    @Test
    fun `next top jump continues to the beginning`() {
        assertEquals(0, topJumpTarget(40, 0, 40))
    }

    @Test
    fun `partially scrolled summary returns to the divider`() {
        assertEquals(40, topJumpTarget(40, 120, 40))
    }

    @Test
    fun `browsing hidden history jumps directly to the beginning`() {
        assertEquals(0, topJumpTarget(12, 80, 40))
    }

    @Test
    fun `conversation without hidden history jumps to the beginning`() {
        assertEquals(0, topJumpTarget(72, 0, 0))
    }
}
