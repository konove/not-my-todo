package io.github.konove.notmytodo.store

import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Author
import io.github.konove.notmytodo.model.Status
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
        assertEquals("2026-10-05T11:00:00Z", s.update("T-1", touch = false) { it.copy(anchor = anchor) }.updated)
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
}
