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

    fun `test links are set, replaced and taken away`() {
        tools.create("whole", null, null, null, null, null, null)
        tools.create("first", null, null, null, null, null, null, parent = "T-1")
        tools.create("second", null, null, null, null, null, null, blockedBy = "T-2", parent = "t-1")
        tools.create("again", null, null, null, null, null, null, duplicateOf = "2")
        assertEquals(listOf("T-2"), store.find("T-3")!!.blockedBy)
        assertEquals("T-1", store.find("T-3")!!.parent)
        assertEquals("T-2", store.find("T-4")!!.duplicateOf)

        tools.update("T-3", "renamed", null, null, null, null)
        assertEquals(listOf("T-2"), store.find("T-3")!!.blockedBy)
        tools.update("T-3", null, null, null, null, null, blockedBy = "T-2, T-4")
        assertEquals(listOf("T-2", "T-4"), store.find("T-3")!!.blockedBy)
        assertEquals("T-1", store.find("T-3")!!.parent)
        tools.update("T-3", null, null, null, null, null, blockedBy = "", parent = "")
        assertTrue(store.find("T-3")!!.blockedBy.isEmpty())
        assertNull(store.find("T-3")!!.parent)
        tools.update("T-4", null, null, null, null, null, duplicateOf = " ")
        assertNull(store.find("T-4")!!.duplicateOf)

        failsWith("not an item id") { tools.update("T-3", null, null, null, null, null, blockedBy = "second") }
        failsWith("T-9") { tools.update("T-3", null, null, null, null, null, parent = "T-9") }
        failsWith("itself") { tools.update("T-3", null, null, null, null, null, blockedBy = "T-3") }
        failsWith("one level") { tools.create("deep", null, null, null, null, null, null, parent = "T-2") }
    }

    fun `test list and get say what is blocked and what an item is made of`() {
        tools.create("whole", null, null, null, null, null, null)
        tools.create("first", null, null, null, null, null, null, parent = "T-1")
        tools.create("second", null, null, null, null, null, null, blockedBy = "T-2", parent = "T-1")
        fun ids(json: String) = JsonParser.parseString(json).asJsonArray.map { it.asJsonObject.get("id").asString }

        assertEquals(listOf("T-3"), ids(tools.list(blocked = true)))
        assertEquals(listOf("T-1", "T-2"), ids(tools.list(blocked = false)))
        assertEquals(listOf("T-2", "T-3"), ids(tools.list(parent = "T-1")))
        val row = JsonParser.parseString(tools.list(compact = true)).asJsonArray[2].asJsonObject
        assertEquals("T-2", row.get("blockedBy").asString)
        assertEquals("T-1", row.get("parent").asString)
        assertTrue(JsonParser.parseString(tools.list()).asJsonArray[2].asJsonObject.get("blocked").asBoolean)
        assertTrue(JsonParser.parseString(tools.get("T-3")).asJsonObject.get("blocked").asBoolean)
        assertEquals("[\"T-2\",\"T-3\"]", JsonParser.parseString(tools.get("T-1")).asJsonObject.get("children").toString())

        tools.update("T-2", null, null, null, null, "done")
        assertEquals(emptyList<String>(), ids(tools.list(blocked = true)))
        assertFalse(JsonParser.parseString(tools.get("T-3")).asJsonObject.has("blocked"))
        assertFalse(JsonParser.parseString(tools.list(compact = true)).asJsonArray[2].asJsonObject.has("blockedBy"))
        assertEquals(listOf("T-2"), store.find("T-3")!!.blockedBy)
    }

    fun `test source, commit and resolution are set, kept and taken away`() {
        tools.create("leftover", null, null, null, null, null, null, source = "30c6f6ae..e9ac6245")
        assertEquals("30c6f6ae..e9ac6245", store.find("T-1")!!.source)
        val json = JsonParser.parseString(
            tools.update("T-1", null, null, null, null, "fixed", fixedIn = "93e7970", resolution = "Cached the path.")
        ).asJsonObject
        assertEquals("93e7970", json.get("fixedIn").asString)
        assertEquals("Cached the path.", json.get("resolution").asString)
        tools.update("T-1", "renamed", null, null, null, null)
        assertEquals("Cached the path.", store.find("T-1")!!.resolution)
        assertEquals("30c6f6ae..e9ac6245", store.find("T-1")!!.source)
        assertEquals("93e7970", JsonParser.parseString(tools.list()).asJsonArray[0].asJsonObject.get("fixedIn").asString)
        tools.update("T-1", null, null, null, null, "open", source = "", fixedIn = "", resolution = "")
        val item = store.find("T-1")!!
        assertNull(item.source)
        assertNull(item.fixedIn)
        assertNull(item.resolution)
    }

    fun `test batch files ten items in one write`() {
        val psi = myFixture.configureByText("batch.txt", text)
        val path = service.relativePath(psi.virtualFile)!!
        var writes = 0
        val listener = { writes++; Unit }
        store.addListener(listener)
        val entries = (1..10).joinToString(",", "[", "]") {
            if (it == 1) """{"title": "item 1", "priority": "p1", "tags": ["perf", "#Bug"], "path": "$path", "startLine": 2, "endLine": 3}"""
            else """{"title": "item $it", "details": "why", "tags": "batch"}"""
        }
        val rows = JsonParser.parseString(tools.batch(entries)).asJsonArray
        store.removeListener(listener)
        assertEquals(1, writes)
        assertEquals((1..10).map { "T-$it" }, rows.map { it.asJsonObject.get("id").asString })
        assertEquals((1..10).map { "T-$it" }, store.items.map { it.id })
        val first = store.find("T-1")!!
        assertEquals(Author.AGENT, first.author)
        assertEquals(listOf("perf", "bug"), first.tags)
        assertEquals("two\nthree", first.anchor!!.text)
        assertEquals("why", store.find("T-10")!!.details)
        assertEquals("T-11", JsonParser.parseString(tools.create("next", null, null, null, null, null, null)).asJsonObject.get("id").asString)
    }

    fun `test batch re-tags twenty items in one write`() {
        repeat(20) { store.create(Draft("item $it", tags = listOf("old"))) }
        var writes = 0
        val listener = { writes++; Unit }
        store.addListener(listener)
        tools.batch((1..20).joinToString(",", "[", "]") { """{"id": "T-$it", "tags": "new mcp"}""" })
        store.removeListener(listener)
        assertEquals(1, writes)
        assertTrue(store.items.all { it.tags == listOf("new", "mcp") })
        assertEquals("item 0", store.find("T-1")!!.title)
    }

    fun `test batch creates and updates together, each as its own tool would`() {
        store.create(Draft("a", tags = listOf("keep")))
        store.create(Draft("b", duplicateOf = "T-1"))
        tools.batch(
            """[{"id": "t-1", "status": "fixed", "resolution": "did it", "fixedIn": "abc123"},
                {"id": "T-2", "duplicateOf": "", "blockedBy": "T-1"},
                {"title": "part", "parent": "T-1", "source": "run 7"}]"""
        )
        val a = store.find("T-1")!!
        assertEquals(Status.FIXED, a.status)
        assertEquals("did it", a.resolution)
        assertEquals("abc123", a.fixedIn)
        assertEquals(listOf("keep"), a.tags)
        val b = store.find("T-2")!!
        assertNull(b.duplicateOf)
        assertEquals(listOf("T-1"), b.blockedBy)
        val part = store.find("T-3")!!
        assertEquals("T-1", part.parent)
        assertEquals("run 7", part.source)
    }

    fun `test batch hands items back with the tag and the question in one write`() {
        store.create(Draft("a"))
        store.create(Draft("b"))
        var fired = 0
        val listener = { fired++; Unit }
        store.addListener(listener)
        try {
            tools.batch(
                """[{"id": "T-1", "tags": "needs-decision", "comment": "per unit or per cell?"},
                    {"id": "T-2", "comment": "half way"}]"""
            )
        } finally {
            store.removeListener(listener)
        }
        assertEquals(1, fired)
        val a = store.find("T-1")!!
        assertTrue(a.needsDecision)
        assertEquals(listOf(Author.AGENT to "per unit or per cell?"), a.comments.map { it.author to it.text })
        assertEquals(listOf("half way"), store.find("T-2")!!.comments.map { it.text })
        failsWith("entry 1: comment") { tools.batch("""[{"id": "T-1", "comment": " "}]""") }
        failsWith("comment cannot be set on a new item") { tools.batch("""[{"title": "new", "comment": "x"}]""") }
    }

    fun `test batch with a bad entry names it and changes nothing`() {
        store.create(Draft("a"))
        val before = Files.readString(store.file)
        failsWith("entry 2") { tools.batch("""[{"title": "fine"}, {"id": "T-1", "priority": "urgent"}]""") }
        failsWith("entry 3") { tools.batch("""[{"title": "fine"}, {"id": "T-1", "title": "new"}, {"id": "T-9", "tags": "x"}]""") }
        failsWith("entry 2") { tools.batch("""[{"title": "fine"}, {"title": "part", "parent": "T-9"}]""") }
        failsWith("entry 1") { tools.batch("""[{"details": "no title"}]""") }
        failsWith("titel") { tools.batch("""[{"titel": "typo"}]""") }
        failsWith("status") { tools.batch("""[{"title": "new", "status": "fixed"}]""") }
        failsWith("todo_update") { tools.batch("""[{"id": "T-1", "places": "a.txt"}]""") }
        failsWith("entry 1") { tools.batch("""[{"title": 5}]""") }
        failsWith("JSON array") { tools.batch("""{"title": "not in an array"}""") }
        failsWith("JSON array") { tools.batch("not json") }
        failsWith("empty") { tools.batch("[]") }
        assertEquals(before, Files.readString(store.file))
    }

    fun `test what is to be decided is set with the tag, kept and taken away`() {
        tools.create("pick a license", null, null, "legal", null, null, null, toDecide = "MIT or Apache?")
        val made = store.find("T-1")!!
        assertEquals("MIT or Apache?", made.toDecide)
        assertEquals(listOf("legal", "needs-decision"), made.tags)
        store.create(Draft("b", tags = listOf("x")))
        val json = JsonParser.parseString(tools.update("T-2", null, null, null, null, null, toDecide = "Which of the two?")).asJsonObject
        assertEquals("Which of the two?", json.get("toDecide").asString)
        assertEquals(listOf("x", "needs-decision"), store.find("T-2")!!.tags)
        // The tag goes when the work is done; what was to be decided stays on the item.
        tools.update("T-2", null, null, null, "x", "fixed")
        assertEquals("Which of the two?", store.find("T-2")!!.toDecide)
        assertFalse(store.find("T-2")!!.needsDecision)
        tools.update("T-2", null, null, null, null, null, toDecide = "")
        assertNull(store.find("T-2")!!.toDecide)
        assertFalse(store.find("T-2")!!.needsDecision)
        tools.batch("""[{"id": "T-2", "toDecide": "Again?"}, {"title": "new", "toDecide": "What?"}]""")
        assertTrue(store.find("T-2")!!.needsDecision)
        assertEquals("What?", store.find("T-3")!!.toDecide)
        assertTrue(store.find("T-3")!!.needsDecision)
    }

    fun `test decided records the questions, the options and the answers`() {
        store.create(Draft("pick a license", tags = listOf("needs-decision")))
        val json = JsonParser.parseString(
            tools.decided(
                "T-1",
                """[{"question": "Which license?", "options": ["MIT", "Apache 2.0"], "answer": "MIT"},
                    {"question": "Year?", "answer": "2026"}]""",
            )
        ).asJsonObject
        assertEquals(2, json.getAsJsonArray("decisions").size())
        tools.decided("t-1", """[{"question": "A header in every file?", "options": ["Yes", "No"], "answer": "Only in new ones"}]""")
        val item = store.find("T-1")!!
        assertEquals(
            listOf("Which license?" to "MIT", "Year?" to "2026", "A header in every file?" to "Only in new ones"),
            item.decisions.map { it.question to it.answer },
        )
        assertEquals(listOf("MIT", "Apache 2.0"), item.decisions[0].options)
        assertTrue(item.decisions.all { it.time.isNotEmpty() })
        assertTrue(item.needsDecision)
        assertEquals(Status.OPEN, item.status)
        failsWith("JSON array") { tools.decided("T-1", "not json") }
        failsWith("empty") { tools.decided("T-1", "[]") }
        failsWith("entry 2: answer") { tools.decided("T-1", """[{"question": "q", "answer": "a"}, {"question": "q"}]""") }
        failsWith("question") { tools.decided("T-1", """[{"answer": "a"}]""") }
        failsWith("options") { tools.decided("T-1", """[{"question": "q", "options": "MIT", "answer": "a"}]""") }
        failsWith("chosen") { tools.decided("T-1", """[{"question": "q", "chosen": "a"}]""") }
        failsWith("T-9") { tools.decided("T-9", """[{"question": "q", "answer": "a"}]""") }
        assertEquals(3, store.find("T-1")!!.decisions.size)
    }
}
