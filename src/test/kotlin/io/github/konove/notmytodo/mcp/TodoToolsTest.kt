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
        failsWith("startLine") { tools.create("x", null, null, null, path, null, 2) }
        failsWith("path") { tools.create("x", null, null, null, null, 2, null) }
        failsWith("$path:two") { tools.create("x", null, null, null, null, null, null, "$path:two") }
        failsWith("no/such.txt") { tools.create("x", null, null, null, path, 1, 1, "no/such.txt") }
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
        assertFalse(all[0].asJsonObject.getAsJsonArray("anchors")[0].asJsonObject.has("text"))
        UsefulTestCase.assertSize(1, JsonParser.parseString(tools.list("done", null, null, null)).asJsonArray.toList())
        UsefulTestCase.assertSize(1, JsonParser.parseString(tools.list(null, "#perf", null, null)).asJsonArray.toList())
        UsefulTestCase.assertSize(1, JsonParser.parseString(tools.list(null, null, "p1", null)).asJsonArray.toList())
        UsefulTestCase.assertSize(1, JsonParser.parseString(tools.list(null, null, null, path)).asJsonArray.toList())
        failsWith("status") { tools.list("later", null, null, null) }
    }

    fun `test list combines tags, directory, text and statuses`() {
        val path = service.relativePath(myFixture.addFileToProject("foo/bar/c.txt", text).virtualFile)!!
        val other = service.relativePath(myFixture.addFileToProject("foobar/c.txt", text).virtualFile)!!
        val dir = path.removeSuffix("/bar/c.txt")
        tools.create("Cache the path", "squad wide", null, "bugs td", path, 1, 2)
        tools.create("Drop the CACHE", null, null, "bugs td needs-decision", path, 3, 3)
        tools.create("Cache elsewhere", null, null, "bugs td", other, 1, 1)
        tools.create("Rename it", "the cache key", null, "bugs", path, 4, 4)
        tools.create("A note about the cache", null, null, "bugs td", null, null, null)
        tools.update("T-4", null, null, null, null, "in_progress")
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }

        assertEquals(listOf("T-1"), ids(tools.list("open", "bugs, td", null, "$dir/", "needs-decision", "cache")))
        assertEquals(listOf("T-1", "T-2", "T-4"), ids(tools.list(path = dir)))
        assertEquals(listOf("T-1", "T-2", "T-4"), ids(tools.list(path = path)))
        assertEquals(listOf("T-1", "T-4"), ids(tools.list(path = dir, withoutTags = "#needs-decision")))
        assertEquals(listOf("T-1", "T-2", "T-4", "T-5"), ids(tools.list(text = "CACHE the")))
        assertEquals(listOf("T-1"), ids(tools.list(text = "squad cache")))
        assertEquals(listOf("T-4"), ids(tools.list(status = "in_progress done")))
        assertEquals(5, ids(tools.list(status = "", tags = " ", path = "", text = "")).size)
        failsWith("status") { tools.list(status = "open later") }
    }

    fun `test list can return only items whose anchor is lost`() {
        val path = service.relativePath(myFixture.configureByText("c.txt", text).virtualFile)!!
        tools.create("found", null, null, "bugs", path, 1, 1)
        tools.create("gone", null, null, "bugs", path, 3, 3)
        tools.create("gone too", null, null, null, path, 4, 4)
        tools.create("note", null, null, "bugs", null, null, null)
        store.update("T-2") { it.copy(anchors = listOf(it.anchor!!.copy(lost = true))) }
        store.update("T-3") { it.copy(anchors = listOf(it.anchor!!.copy(lost = true))) }
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }

        assertEquals(listOf("T-2", "T-3"), ids(tools.list(lost = true)))
        assertEquals(listOf("T-2"), ids(tools.list(tags = "bugs", lost = true)))
        assertEquals(listOf("T-2", "T-3"), ids(tools.list(lost = true, compact = true)))
        assertEquals(4, ids(tools.list()).size)
    }

    fun `test compact list has one short line per item`() {
        val path = service.relativePath(myFixture.configureByText("c.txt", text).virtualFile)!!
        tools.create("a", "long details", "p1", "perf", path, 2, 3)
        tools.create("b", "more details", null, null, path, 4, 4)
        tools.create("c", null, null, null, null, null, null)
        val out = tools.list(compact = true)
        assertEquals(5, out.lines().size)
        val rows = JsonParser.parseString(out).asJsonArray.map { it.asJsonObject }
        assertEquals(setOf("id", "title", "priority", "status", "tags", "at"), rows[0].keySet())
        assertEquals("$path:2-3", rows[0].get("at").asString)
        assertEquals("$path:4", rows[1].get("at").asString)
        assertFalse(rows[2].has("at"))
        assertEquals("[]", tools.list(status = "done", compact = true))
    }

    fun `test get returns live lines and code after unsaved edits`() {
        val psi = myFixture.configureByText("d.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        tools.create("a", null, null, null, path, 2, 2)
        project.service<AnchorTracker>().syncFile(psi.virtualFile)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "zero\n") }
        val json = JsonParser.parseString(tools.get("T-1")).asJsonObject
        assertEquals(3, json.getAsJsonArray("anchors")[0].asJsonObject.get("startLine").asInt)
        assertEquals("two", json.getAsJsonArray("anchors")[0].asJsonObject.get("code").asString)
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
        assertEquals(3, json.getAsJsonArray("anchors")[0].asJsonObject.get("startLine").asInt)
        assertEquals("three\nfour", json.getAsJsonArray("anchors")[0].asJsonObject.get("code").asString)

        store.update("T-1") { it.copy(anchors = listOf(it.anchor!!.copy(lost = true))) }
        tools.update("T-1", null, null, null, null, null, path, 1, null)
        val anchor = store.find("T-1")!!.anchor!!
        assertFalse(anchor.lost)
        assertEquals("one", anchor.text)

        failsWith("5 lines") { tools.update("T-1", null, null, null, null, null, null, 9, null) }
        tools.update("T-1", null, null, null, null, null, path, null, null)
        assertEquals(listOf(path), store.find("T-1")!!.anchors.map { it.place })
        failsWith("startLine") { tools.update("T-1", null, null, null, null, null, path, null, 2) }
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

    fun `test comment adds a note and does not touch the details`() {
        store.create(Draft("a", details = "keep"))
        tools.comment("T-1", "found the cause")
        store.update("T-1") { it.copy(details = "edited in the IDE") }
        val json = JsonParser.parseString(tools.comment("T-1", "and a fix")).asJsonObject
        assertEquals("edited in the IDE", json.get("details").asString)
        val item = store.find("T-1")!!
        assertEquals("edited in the IDE", item.details)
        assertEquals(listOf("found the cause", "and a fix"), item.comments.map { it.text })
        assertTrue(item.comments.all { it.author == Author.AGENT })
        val got = JsonParser.parseString(tools.get("T-1")).asJsonObject.getAsJsonArray("comments")
        assertEquals("and a fix", got[1].asJsonObject.get("text").asString)
        failsWith("text") { tools.comment("T-1", "  ") }
        failsWith("T-9") { tools.comment("T-9", "x") }
    }

    fun `test create attaches to a whole file and to several places`() {
        val a = service.relativePath(myFixture.configureByText("m1.txt", text).virtualFile)!!
        val b = service.relativePath(myFixture.configureByText("m2.txt", text).virtualFile)!!
        tools.create("whole", null, null, null, a, null, null)
        assertEquals(listOf(a), store.find("T-1")!!.anchors.map { it.place })
        assertTrue(store.find("T-1")!!.anchor!!.isFile)

        tools.create("several", null, null, null, a, 2, 3, "$b:4, $b\n./$a:2-3")
        assertEquals(listOf("$a:2-3", "$b:4", b), store.find("T-2")!!.anchors.map { it.place })
        assertEquals("four", store.find("T-2")!!.anchors[1].text)

        val got = JsonParser.parseString(tools.get("T-2")).asJsonObject.getAsJsonArray("anchors").map { it.asJsonObject }
        assertEquals("two\nthree", got[0].get("code").asString)
        assertEquals("four", got[1].get("code").asString)
        assertFalse(got[2].has("code"))
        assertFalse(got[2].has("startLine"))

        val rows = tools.list(compact = true).lines().filter { it.startsWith("{") }.map { JsonParser.parseString(it.trimEnd(',')).asJsonObject }
        assertEquals("$a:2-3, $b:4, $b", rows[1].get("at").asString)
        assertEquals(listOf("T-2"), JsonParser.parseString(tools.list(path = b)).asJsonArray.map { it.asJsonObject.get("id").asString })
        val listed = JsonParser.parseString(tools.list(path = b)).asJsonArray[0].asJsonObject.getAsJsonArray("anchors")
        assertEquals(3, listed.size())
        assertFalse(listed[1].asJsonObject.has("text"))
    }

    fun `test update sets the whole list of places or moves the one in a file`() {
        val a = service.relativePath(myFixture.configureByText("n1.txt", text).virtualFile)!!
        val b = service.relativePath(myFixture.configureByText("n2.txt", text).virtualFile)!!
        tools.create("x", null, null, null, a, 2, 2, "$b:1")
        val kept = store.find("T-1")!!.anchors[1]

        tools.update("T-1", null, null, null, null, null, a, 3, 4)
        assertEquals(listOf("$a:3-4", "$b:1"), store.find("T-1")!!.anchors.map { it.place })
        failsWith("2 places") { tools.update("T-1", null, null, null, null, null, null, 1, null) }
        failsWith("places") { tools.update("T-1", null, null, null, null, null, "other.txt", 1, null) }
        failsWith("not both") { tools.update("T-1", null, null, null, null, null, a, 1, null, "$a:1") }

        tools.update("T-1", null, null, null, null, null, places = "$b:1, $a")
        assertEquals(listOf("$b:1", a), store.find("T-1")!!.anchors.map { it.place })
        assertEquals(kept, store.find("T-1")!!.anchors[0])

        tools.update("T-1", null, null, null, null, null, places = "")
        assertTrue(store.find("T-1")!!.anchors.isEmpty())
        tools.update("T-1", null, null, null, null, null, null, null, null)
        assertTrue(store.find("T-1")!!.anchors.isEmpty())
    }
}
