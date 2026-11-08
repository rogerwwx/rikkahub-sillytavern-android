package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LazyToolGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationLazyToolsTest {
    private fun tool(name: String, result: String = name) = Tool(
        name = name,
        description = name,
        execute = { listOf(UIMessagePart.Text(result)) },
    )

    @Test
    fun `disabled tools stay empty even when lazy groups are registered`() {
        LazyToolGroups.register(mapOf("file" to listOf(tool("file_read"))))
        assertTrue(lazyToolsFor(Uuid.random(), emptyList()).isEmpty())
    }

    @Test
    fun `loading a group works on the returned tools and stays in its conversation`() = runBlocking {
        val first = Uuid.random()
        val second = Uuid.random()
        val file = tool("file_read")
        val memory = tool("memory_semantic_search", "first-window")
        LazyToolGroups.register(mapOf("file" to listOf(file)))
        val before = lazyToolsFor(first, listOf(memory, file))
        assertFalse(before.any { it.name == file.name })
        assertEquals(listOf(UIMessagePart.Text("first-window")), before.single { it.name == memory.name }
            .execute(buildJsonObject {}))
        before.single { it.name == "load_tools" }.execute(buildJsonObject { put("group", "file") })
        assertTrue(lazyToolsFor(first, listOf(memory, file)).any { it.name == file.name })
        assertFalse(lazyToolsFor(second, listOf(tool(memory.name, "second-window"), file))
            .any { it.name == file.name })
        val refreshed = lazyToolsFor(first, listOf(tool(memory.name, "updated-setting"), file))
        assertEquals(listOf(UIMessagePart.Text("updated-setting")), refreshed.single { it.name == memory.name }
            .execute(buildJsonObject {}))
        assertEquals(1, lazyToolsFor(first, refreshed).count { it.name == "load_tools" })
    }
}
