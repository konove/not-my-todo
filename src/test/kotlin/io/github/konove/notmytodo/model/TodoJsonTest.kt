package io.github.konove.notmytodo.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoJsonTest {
    private fun item(n: Int, anchor: Anchor? = null) = TodoItem(
        id = ItemId.of(n), title = "title $n", details = "d", priority = Priority.P1,
        tags = listOf("perf"), status = Status.IN_PROGRESS, author = Author.AGENT,
        created = "2026-10-05T14:02:11Z", updated = "2026-10-05T15:00:00Z", anchor = anchor,
    )

    @Test
    fun `round trip keeps every field`() {
        val anchor = Anchor("src/a.cpp", 3, 4, "x\ny", listOf("b"), listOf("a1", "a2"), lost = true)
        val file = TodoFile(1, 8, listOf(item(1), item(7, anchor)))
        assertEquals(file, TodoJson.decode(TodoJson.encode(file)))
    }

    @Test
    fun `ids sort by number not by text`() {
        val file = TodoFile(1, 101, listOf(item(100), item(9), item(10)))
        val ids = TodoJson.decode(TodoJson.encode(file)).items.map { it.id }
        assertEquals(listOf("T-9", "T-10", "T-100"), ids)
    }

    @Test
    fun `id parsing accepts only unpadded positive numbers`() {
        assertEquals(12, ItemId.parse("T-12"))
        assertNull(ItemId.parse("T-012"))
        assertNull(ItemId.parse("T-0"))
        assertNull(ItemId.parse("t-1"))
        assertNull(ItemId.parse("T-"))
    }

    @Test
    fun `keys are written in a fixed order`() {
        val text = TodoJson.encode(TodoFile(1, 2, listOf(item(1))))
        val order = listOf("\"version\"", "\"nextId\"", "\"items\"", "\"id\"", "\"title\"", "\"details\"",
            "\"priority\"", "\"tags\"", "\"status\"", "\"author\"", "\"created\"", "\"updated\"")
        val positions = order.map { text.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertTrue(text.endsWith("\n"))
        assertTrue(text.contains("\"priority\": \"p1\""))
        assertTrue(text.contains("\"status\": \"in_progress\""))
    }

    @Test
    fun `blank text decodes to an empty file`() {
        assertEquals(TodoFile(), TodoJson.decode(""))
        assertEquals(TodoFile(), TodoJson.decode("  \n"))
    }

    @Test
    fun `missing optional fields get defaults`() {
        val file = TodoJson.decode("""{"items":[{"id":"T-4","title":"x"}]}""")
        val it = file.items.single()
        assertEquals(Priority.P2, it.priority)
        assertEquals(Status.OPEN, it.status)
        assertEquals(Author.USER, it.author)
        assertEquals(emptyList<String>(), it.tags)
        assertNull(it.anchor)
        assertEquals(5, file.nextId)
    }

    @Test
    fun `nextId is raised above the highest id`() {
        val file = TodoJson.decode("""{"version":1,"nextId":2,"items":[{"id":"T-9","title":"x"}]}""")
        assertEquals(10, file.nextId)
    }

    @Test
    fun `invalid input is rejected with a reason`() {
        fun fails(text: String, part: String) {
            val e = assertThrows(TodoFormatException::class.java) { TodoJson.decode(text) }
            assertTrue("${e.message} should mention $part", e.message!!.contains(part))
        }
        fails("{", "JSON")
        fails("[]", "object")
        fails("""{"version":2}""", "version")
        fails("""{"items":[{"id":"T-01","title":"x"}]}""", "id")
        fails("""{"items":[{"id":"T-1","title":" "}]}""", "title")
        fails("""{"items":[{"id":"T-1","title":"x"},{"id":"T-1","title":"y"}]}""", "T-1")
        fails("""{"items":[{"id":"T-1","title":"x","priority":"p9"}]}""", "priority")
        fails("""{"items":[{"id":"T-1","title":"x","status":"later"}]}""", "status")
        fails("""{"items":[{"id":"T-1","title":"x","anchor":{"path":"a","startLine":3,"endLine":2}}]}""", "line")
    }

    @Test
    fun `tags are normalised`() {
        assertEquals(listOf("perf", "bug"), Tags.normalizeAll(listOf("#Perf", " bug ", "perf", "", "two words")))
        assertEquals(listOf("a", "b"), Tags.parseList("#a, b  #A"))
    }
}
