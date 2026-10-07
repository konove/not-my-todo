package io.github.konove.notmytodo.mcp

import com.google.gson.JsonParser
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.konove.notmytodo.ide.AnchorTracker
import io.github.konove.notmytodo.ide.TodoService
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.store.Draft
import io.github.konove.notmytodo.store.ItemStore
import java.nio.file.Files

class TodoToolsTest : BasePlatformTestCase() {
    private lateinit var service: TodoService
    private lateinit var store: ItemStore
    private lateinit var tools: TodoTools
    private val text = "one\ntwo\nthree\nfour\n"

    override fun setUp() {
        super.setUp()
        service = TodoService.getInstance(project)
        store = service.store
        Files.deleteIfExists(store.file)
        store.reload()
        project.service<AnchorTracker>().start()
        tools = TodoTools(project)
    }

    private fun failsWith(part: String, call: () -> Unit) {
        try {
            call()
            fail("expected a TodoToolError mentioning $part")
        } catch (e: TodoToolError) {
            assertTrue("${e.message} should mention $part", e.message!!.contains(part))
        }
    }

    fun `test create makes an agent item anchored to current code`() {
        val psi = myFixture.configureByText("a.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        val json = JsonParser.parseString(tools.create("from agent", "why", "p1", "perf, #Bug", path, 2, 3)).asJsonObject
        assertEquals("T-1", json.get("id").asString)
        val item = store.find("T-1")!!
        assertEquals(Author.AGENT, item.author)
        assertEquals(listOf("perf", "bug"), item.tags)
        assertEquals("two\nthree", item.anchor!!.text)
    }

    fun `test create without a path makes a plain note with defaults`() {
        tools.create("note", null, null, null, null, null, null)
        val item = store.find("T-1")!!
        assertNull(item.anchor)
        assertEquals("p2", item.priority.json)
    }

    fun `test create rejects bad input`() {
        val psi = myFixture.configureByText("b.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        failsWith("title") { tools.create("  ", null, null, null, null, null, null) }
        failsWith("priority") { tools.create("x", null, "urgent", null, null, null, null) }
        failsWith("no/such.txt") { tools.create("x", null, null, null, "no/such.txt", 1, 1) }
        failsWith("startLine") { tools.create("x", null, null, null, path, null, null) }
        failsWith("5 lines") { tools.create("x", null, null, null, path, 4, 9) }
        assertEquals(0, store.items.size)
    }

    fun `test list filters and leaves out anchor text`() {
        val psi = myFixture.configureByText("c.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        tools.create("a", null, "p1", "perf", path, 1, 1)
        tools.create("b", null, "p2", "bug", null, null, null)
        tools.update("T-2", null, null, null, null, "done")
        val all = JsonParser.parseString(tools.list(null, null, null, null)).asJsonArray
        assertEquals(2, all.size())
        assertFalse(all[0].asJsonObject.getAsJsonObject("anchor").has("text"))
        UsefulTestCase.assertSize(1, JsonParser.parseString(tools.list("done", null, null, null)).asJsonArray.toList())
        UsefulTestCase.assertSize(1, JsonParser.parseString(tools.list(null, "#perf", null, null)).asJsonArray.toList())
        UsefulTestCase.assertSize(1, JsonParser.parseString(tools.list(null, null, "p1", null)).asJsonArray.toList())
        UsefulTestCase.assertSize(1, JsonParser.parseString(tools.list(null, null, null, path)).asJsonArray.toList())
        failsWith("status") { tools.list("later", null, null, null) }
    }

    fun `test get returns live lines and code after unsaved edits`() {
        val psi = myFixture.configureByText("d.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        tools.create("a", null, null, null, path, 2, 2)
        project.service<AnchorTracker>().syncFile(psi.virtualFile)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "zero\n") }
        val json = JsonParser.parseString(tools.get("T-1")).asJsonObject
        assertEquals(3, json.getAsJsonObject("anchor").get("startLine").asInt)
        assertEquals("two", json.get("code").asString)
        failsWith("T-9") { tools.get("T-9") }
    }

    fun `test update changes only the given fields`() {
        store.create(Draft("a", details = "keep", tags = listOf("x")))
        val json = JsonParser.parseString(tools.update("T-1", "b", null, null, null, "fixed")).asJsonObject
        assertEquals("b", json.get("title").asString)
        val item = store.find("T-1")!!
        assertEquals("keep", item.details)
        assertEquals(listOf("x"), item.tags)
        assertEquals(Status.FIXED, item.status)
        failsWith("status") { tools.update("T-1", null, null, null, null, "finished") }
        failsWith("T-9") { tools.update("T-9", "x", null, null, null, null) }
    }

    fun `test update re-attaches an item and the old marker does not win`() {
        val psi = myFixture.configureByText("e.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        tools.create("a", null, null, null, path, 2, 2)
        project.service<AnchorTracker>().syncFile(psi.virtualFile)
        tools.update("T-1", null, null, null, null, null, null, 3, 4)
        assertEquals("three\nfour", store.find("T-1")!!.anchor!!.text)
        val json = JsonParser.parseString(tools.get("T-1")).asJsonObject
        assertEquals(3, json.getAsJsonObject("anchor").get("startLine").asInt)
        assertEquals("three\nfour", json.get("code").asString)

        store.update("T-1") { it.copy(anchor = it.anchor!!.copy(lost = true)) }
        tools.update("T-1", null, null, null, null, null, path, 1, null)
        val anchor = store.find("T-1")!!.anchor!!
        assertFalse(anchor.lost)
        assertEquals("one", anchor.text)

        failsWith("5 lines") { tools.update("T-1", null, null, null, null, null, null, 9, null) }
        failsWith("startLine") { tools.update("T-1", null, null, null, null, null, path, null, null) }
        store.create(Draft("note"))
        failsWith("path") { tools.update("T-2", null, null, null, null, null, null, 1, null) }
    }

    fun `test broken file is reported as a tool error`() {
        Files.createDirectories(store.file.parent)
        Files.writeString(store.file, "{ broken")
        store.reload()
        failsWith("items.json") { tools.list(null, null, null, null) }
        failsWith("items.json") { tools.create("x", null, null, null, null, null, null) }
    }


    fun `test create sees files as they are on disk right now`() {
        val base = java.nio.file.Path.of(project.basePath!!)
        Files.createDirectories(base)
        val disk = base.resolve("fresh-on-disk.txt")
        try {
            Files.writeString(disk, "a\nb\n")
            tools.create("first", null, null, null, "fresh-on-disk.txt", 2, 2)
            assertEquals("b", store.find("T-1")!!.anchor!!.text)
            Files.writeString(disk, "a\nc\n")
            tools.create("second", null, null, null, "fresh-on-disk.txt", 2, 2)
            assertEquals("c", store.find("T-2")!!.anchor!!.text)
        } finally {
            Files.deleteIfExists(disk)
        }
    }
}
