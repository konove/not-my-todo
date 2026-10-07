package io.github.konove.notmytodo.store

import com.google.gson.JsonParser
import io.github.konove.notmytodo.model.Decision
import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ItemStoreTest {
    private val dir: Path = Files.createTempDirectory("nmt-store")
    private val file: Path = dir.resolve(".todos").resolve("items.json")
    private var now: Instant = Instant.parse("2026-10-05T10:00:00Z")
    private fun store() = ItemStore(file) { now }

    @Test
    fun `missing file is an empty list and is created on first write`() {
        val s = store()
        assertEquals(emptyList<Any>(), s.items)
        assertNull(s.error)
        assertFalse(Files.exists(file))
        val item = s.create(Draft("first", tags = listOf("#Perf")))
        assertEquals("T-1", item.id)
        assertEquals(listOf("perf"), item.tags)
        assertEquals("2026-10-05T10:00:00Z", item.created)
        assertTrue(Files.exists(file))
        assertEquals(listOf("T-1"), store().items.map { it.id })
    }

    @Test
    fun `ids are never reused`() {
        val s = store()
        s.create(Draft("a"))
        s.create(Draft("b"))
        s.delete("T-2")
        assertEquals("T-3", s.create(Draft("c")).id)
    }

    @Test
    fun `update sets the updated time unless touch is false`() {
        val s = store()
        s.create(Draft("a"))
        now = Instant.parse("2026-10-05T11:00:00Z")
        assertEquals("2026-10-05T11:00:00Z", s.update("T-1") { it.copy(status = Status.DONE) }.updated)
        now = Instant.parse("2026-10-05T12:00:00Z")
        val anchor = Anchor("a.txt", 1, 1, "x", emptyList(), emptyList())
        assertEquals("2026-10-05T11:00:00Z", s.update("T-1", touch = false) { it.copy(anchors = listOf(anchor)) }.updated)
    }

    @Test
    fun `update cannot change id, author or created`() {
        val s = store()
        s.create(Draft("a", author = Author.AGENT))
        val saved = s.update("T-1") { it.copy(id = "T-9", author = Author.USER, created = "x", title = "b") }
        assertEquals("T-1", saved.id)
        assertEquals(Author.AGENT, saved.author)
        assertEquals("2026-10-05T10:00:00Z", saved.created)
        assertEquals("b", saved.title)
    }

    @Test
    fun `changes keep what a newer plugin wrote`() {
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """{"version":1,"nextId":3,"schema":"x","items":[
                {"id":"T-1","title":"a","links":["T-2"],"anchor":{"path":"a.txt","startLine":1,"column":4}},
                {"id":"T-2","title":"b","links":["T-1"]}]}""",
        )
        val s = store()
        val anchor = Anchor("b.txt", 2, 2, "y", emptyList(), emptyList())
        s.update("T-1") { TodoItem(it.id, "renamed", anchors = listOf(anchor)) }
        s.create(Draft("c"))
        s.delete("T-2")
        val root = JsonParser.parseString(Files.readString(file)).asJsonObject
        assertEquals(2, root.get("version").asInt)
        assertEquals("x", root.get("schema").asString)
        val first = root.getAsJsonArray("items").first().asJsonObject
        assertEquals("renamed", first.get("title").asString)
        assertEquals("T-2", first.getAsJsonArray("links").single().asString)
        assertEquals("b.txt", first.getAsJsonArray("anchors")[0].asJsonObject.get("path").asString)
        assertEquals(4, first.getAsJsonArray("anchors")[0].asJsonObject.get("column").asInt)
    }

    @Test
    fun `bad requests are refused`() {
        val s = store()
        s.create(Draft("a"))
        assertThrows(StoreException::class.java) { s.create(Draft("   ")) }
        assertThrows(StoreException::class.java) { s.update("T-1") { it.copy(title = "") } }
        assertThrows(StoreException::class.java) { s.update("T-7") { it } }
        assertThrows(StoreException::class.java) { s.delete("T-7") }
    }

    @Test
    fun `listeners fire on change and not on a no-op update`() {
        val s = store()
        var calls = 0
        s.addListener { calls++ }
        s.create(Draft("a"))
        assertEquals(1, calls)
        s.update("T-1") { it }
        assertEquals(1, calls)
        s.delete("T-1")
        assertEquals(2, calls)
    }

    @Test
    fun `malformed file is reported and left untouched`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ not json")
        val s = store()
        assertNotNull(s.error)
        assertEquals(emptyList<Any>(), s.items)
        val e = assertThrows(StoreException::class.java) { s.create(Draft("a")) }
        assertTrue(e.message!!.contains("items.json"))
        assertEquals("{ not json", Files.readString(file))
    }

    @Test
    fun `store recovers when the file is repaired`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ not json")
        val s = store()
        var calls = 0
        s.addListener { calls++ }
        Files.writeString(file, """{"items":[{"id":"T-4","title":"x"}]}""")
        assertTrue(s.reload())
        assertNull(s.error)
        assertEquals(listOf("T-4"), s.items.map { it.id })
        assertEquals(1, calls)
        assertFalse(s.reload())
    }

    @Test
    fun `blank file is an empty list`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, "\n")
        val s = store()
        assertNull(s.error)
        assertEquals("T-1", s.create(Draft("a")).id)
    }

    @Test
    fun `outside edit is picked up before a change`() {
        val s = store()
        s.create(Draft("mine"))
        Files.writeString(file, """{"nextId":6,"items":[{"id":"T-1","title":"mine"},{"id":"T-5","title":"from agent"}]}""")
        val created = s.create(Draft("another"))
        assertEquals("T-6", created.id)
        assertEquals(listOf("T-1", "T-5", "T-6"), store().items.map { it.id })
    }

    @Test
    fun `no temporary file is left behind`() {
        val s = store()
        s.create(Draft("a"))
        assertEquals(listOf("items.json"), Files.list(file.parent).use { p -> p.map { it.fileName.toString() }.toList() })
    }


    @Test
    fun `a throwing listener does not break the change or other listeners`() {
        val s = store()
        var later = 0
        s.addListener { throw IllegalStateException("boom") }
        s.addListener { later++ }
        assertEquals("T-1", s.create(Draft("a")).id)
        assertEquals(1, later)
    }

    @Test
    fun `comment appends a timed note and leaves the other fields alone`() {
        val s = store()
        s.create(Draft("a", details = "keep"))
        now = Instant.parse("2026-10-05T11:00:00Z")
        s.comment("T-1", Author.AGENT, "  first\n")
        now = Instant.parse("2026-10-05T12:00:00Z")
        val saved = s.comment("T-1", Author.USER, "second")
        assertEquals(listOf("first", "second"), saved.comments.map { it.text })
        assertEquals(listOf(Author.AGENT, Author.USER), saved.comments.map { it.author })
        assertEquals("2026-10-05T11:00:00Z", saved.comments[0].time)
        assertEquals("2026-10-05T12:00:00Z", saved.updated)
        assertEquals("keep", saved.details)
        assertEquals(saved, store().find("T-1"))
        assertEquals(2, s.update("T-1") { it.copy(title = "b") }.comments.size)
    }

    @Test
    fun `comment rejects empty text and an unknown id`() {
        val s = store()
        s.create(Draft("a"))
        assertThrows(StoreException::class.java) { s.comment("T-1", Author.AGENT, " \n") }
        assertThrows(StoreException::class.java) { s.comment("T-9", Author.AGENT, "x") }
        assertEquals(emptyList<Any>(), s.find("T-1")!!.comments)
    }

    @Test
    fun `links must lead to other items that are there`() {
        val s = store()
        s.create(Draft("a"))
        assertEquals(listOf("T-1"), s.create(Draft("b", blockedBy = listOf("T-1", "T-1"), duplicateOf = "T-1", parent = "T-1")).blockedBy)
        assertThrows(StoreException::class.java) { s.create(Draft("c", blockedBy = listOf("T-9"))) }
        assertThrows(StoreException::class.java) { s.create(Draft("c", parent = "T-9")) }
        assertThrows(StoreException::class.java) { s.update("T-1") { it.copy(blockedBy = listOf("T-1")) } }
        assertThrows(StoreException::class.java) { s.update("T-1") { it.copy(duplicateOf = "T-1") } }
        assertThrows(StoreException::class.java) { s.update("T-1") { it.copy(parent = "T-1") } }
        assertEquals(listOf("T-1", "T-2"), s.items.map { it.id })
        assertEquals("T-3", s.create(Draft("c")).id)
    }

    @Test
    fun `links must not go round in a circle`() {
        val s = store()
        s.create(Draft("a"))
        s.create(Draft("b", blockedBy = listOf("T-1"), duplicateOf = "T-1"))
        s.create(Draft("c", blockedBy = listOf("T-2"), duplicateOf = "T-2"))
        assertThrows(StoreException::class.java) { s.update("T-1") { it.copy(blockedBy = listOf("T-3")) } }
        assertThrows(StoreException::class.java) { s.update("T-1") { it.copy(duplicateOf = "T-3") } }
        assertEquals(listOf("T-2"), s.update("T-1") { it.copy(blockedBy = emptyList()) }.let { s.find("T-3")!!.blockedBy })
    }

    @Test
    fun `parts go one level deep`() {
        val s = store()
        s.create(Draft("whole"))
        s.create(Draft("part", parent = "T-1"))
        s.create(Draft("other"))
        assertThrows(StoreException::class.java) { s.create(Draft("deeper", parent = "T-2")) }
        assertThrows(StoreException::class.java) { s.update("T-1") { it.copy(parent = "T-3") } }
        assertEquals("T-3", s.update("T-2") { it.copy(parent = "T-3") }.parent)
        assertEquals("T-3", s.update("T-1") { it.copy(parent = "T-3") }.parent)
    }

    @Test
    fun `a bad link already in the file does not stop other changes`() {
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"version":2,"nextId":2,"items":[{"id":"T-1","title":"a","blockedBy":["T-9"]}]}""")
        val s = store()
        assertEquals("b", s.update("T-1") { it.copy(title = "b") }.title)
        assertEquals(listOf("T-9"), s.find("T-1")!!.blockedBy)
    }

    @Test
    fun `deleting an item takes away the links to it`() {
        val s = store()
        s.create(Draft("a"))
        s.create(Draft("b"))
        s.create(Draft("c", blockedBy = listOf("T-1", "T-2"), duplicateOf = "T-1", parent = "T-1"))
        s.delete("T-1")
        val left = store().find("T-3")!!
        assertEquals(listOf("T-2"), left.blockedBy)
        assertNull(left.duplicateOf)
        assertNull(left.parent)
    }

    @Test
    fun `a blank source, commit or resolution is none`() {
        val s = store()
        assertNull(s.create(Draft("a", source = " ")).source)
        assertEquals("abc..def", s.create(Draft("b", source = " abc..def ")).source)
        val fixed = s.update("T-1") { it.copy(status = Status.FIXED, fixedIn = " 93e7970 ", resolution = "Done.\n") }
        assertEquals("93e7970", fixed.fixedIn)
        assertEquals("Done.", fixed.resolution)
        assertNull(s.update("T-1") { it.copy(fixedIn = "", resolution = " ") }.resolution)
        assertNull(store().find("T-1")!!.fixedIn)
    }

    @Test
    fun `a batch of changes is written as one`() {
        val s = store()
        s.create(Draft("a"))
        var fired = 0
        s.addListener { fired++ }
        val saved = s.batch { b ->
            listOf(b.create(Draft("b")), b.create(Draft("c", parent = "T-2")), b.update("T-1") { it.copy(tags = listOf("x")) })
        }
        assertEquals(listOf("T-2", "T-3", "T-1"), saved.map { it.id })
        assertEquals(1, fired)
        val read = store()
        assertEquals(listOf("T-1", "T-2", "T-3"), read.items.map { it.id })
        assertEquals(listOf("x"), read.find("T-1")!!.tags)
        assertEquals("T-4", read.create(Draft("d")).id)
    }

    @Test
    fun `a batch can comment on an item it also changes`() {
        val s = store()
        s.create(Draft("a"))
        s.batch { b ->
            b.update("T-1") { it.copy(tags = listOf("needs-decision")) }
            b.comment("T-1", Author.AGENT, " which one? ")
        }
        val read = store().find("T-1")!!
        assertEquals(listOf("needs-decision"), read.tags)
        assertEquals(listOf(Author.AGENT to "which one?"), read.comments.map { it.author to it.text })
        assertThrows(StoreException::class.java) { s.batch { b -> b.comment("T-1", Author.AGENT, "  ") } }
    }

    @Test
    fun `a batch with a change that fails changes nothing`() {
        val s = store()
        s.create(Draft("a"))
        val before = Files.readString(file)
        var fired = 0
        s.addListener { fired++ }
        assertThrows(StoreException::class.java) {
            s.batch { b ->
                b.create(Draft("b"))
                b.update("T-1") { it.copy(title = "changed") }
                b.update("T-9") { it }
            }
        }
        assertEquals(before, Files.readString(file))
        assertEquals(0, fired)
        assertEquals(listOf("a"), s.items.map { it.title })
    }

    @Test
    fun `decisions are added to the record with the time, and nothing else changes`() {
        val s = store()
        s.create(Draft("pick a license", tags = listOf("needs-decision"), toDecide = " MIT or Apache? "))
        assertNull(s.create(Draft("b", toDecide = " ")).toDecide)
        now = Instant.parse("2026-10-05T11:00:00Z")
        s.decide("T-1", listOf(Decision(" Which license? ", listOf("MIT", " Apache 2.0 ", " "), " MIT ", "")))
        s.decide("T-1", listOf(Decision("Year?", emptyList(), "2026", "")))
        val item = store().find("T-1")!!
        assertEquals(
            listOf(
                Decision("Which license?", listOf("MIT", "Apache 2.0"), "MIT", "2026-10-05T11:00:00Z"),
                Decision("Year?", emptyList(), "2026", "2026-10-05T11:00:00Z"),
            ),
            item.decisions,
        )
        assertEquals("MIT or Apache?", item.toDecide)
        assertTrue(item.needsDecision)
        assertEquals(Status.OPEN, item.status)
        assertThrows(StoreException::class.java) { s.decide("T-1", emptyList()) }
        assertThrows(StoreException::class.java) { s.decide("T-1", listOf(Decision(" ", emptyList(), "a", ""))) }
        assertThrows(StoreException::class.java) { s.decide("T-1", listOf(Decision("q", emptyList(), " ", ""))) }
        assertThrows(StoreException::class.java) { s.decide("T-9", listOf(Decision("q", emptyList(), "a", ""))) }
        assertEquals(2, s.find("T-1")!!.decisions.size)
    }
}
