package io.github.konove.notmytodo.mcp

import com.intellij.mcpserver.impl.util.asTools
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.ide.TodoService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

class TodoToolsetTest : BasePlatformTestCase() {
    private fun properties(tool: String): JsonObject =
        TodoToolset().asTools().single { it.descriptor.name == tool }.descriptor.inputSchema.propertiesSchema

    fun `test items and decisions are arrays of objects in the schema`() {
        for ((tool, parameter) in listOf("todo_batch" to "items", "todo_decided" to "decisions")) {
            val schema = properties(tool).getValue(parameter).jsonObject
            assertEquals("array", schema.getValue("type").jsonPrimitive.content)
            assertEquals("object", schema.getValue("items").jsonObject.getValue("type").jsonPrimitive.content)
            assertTrue(schema.getValue("description").jsonPrimitive.content.isNotBlank())
        }
    }

    fun `test the tools that change one item take full`() {
        for (tool in listOf("todo_create", "todo_update", "todo_comment", "todo_decided")) {
            assertEquals(tool, "boolean", properties(tool).getValue("full").jsonObject.getValue("type").jsonPrimitive.content)
        }
        assertFalse(properties("todo_get").containsKey("full"))
    }

    fun `test an array is read as it is and so is a text that holds one`() {
        val store = TodoService.getInstance(project).store
        Files.deleteIfExists(store.file)
        store.reload()
        val tools = TodoTools(project)
        fun entries(json: String) = Json.decodeFromString(JsonEntries.serializer(), json).text

        tools.batch(entries("""[{"title": "as an array", "tags": ["a", "b"]}, {"title": "second"}]"""))
        tools.batch(entries(""""[{\"title\": \"as a text\"}]""""))
        assertEquals(listOf("as an array", "second", "as a text"), store.items.map { it.title })
        assertEquals(listOf("a", "b"), store.find("T-1")!!.tags)

        tools.decided("T-1", entries("""[{"question": "q", "options": ["x", "y"], "answer": "x"}]"""))
        tools.decided("T-1", entries(""""[{\"question\": \"again\", \"answer\": \"y\"}]""""))
        assertEquals(listOf("x", "y"), store.find("T-1")!!.decisions.map { it.answer })
        // What is neither is turned down by the tool, in its own words.
        try {
            tools.batch(entries("""{"title": "not in an array"}"""))
            fail("expected a TodoToolError")
        } catch (e: TodoToolError) {
            assertTrue(e.message, e.message!!.contains("JSON array"))
        }
    }
}
