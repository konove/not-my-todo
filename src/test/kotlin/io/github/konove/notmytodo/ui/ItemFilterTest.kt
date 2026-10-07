package io.github.konove.notmytodo.ui

import io.github.konove.notmytodo.model.Anchor
import io.github.konove.notmytodo.model.Priority
import io.github.konove.notmytodo.model.Status
import io.github.konove.notmytodo.model.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemFilterTest {
    private fun anchor(path: String, lost: Boolean = false) = Anchor(path, 1, 1, "x", emptyList(), emptyList(), lost)
    private val items = listOf(
        TodoItem("T-1", "Cache findPath", "squad wide", Priority.P2, listOf("perf"), anchors = listOf(anchor("src/unit.cpp"))),
        TodoItem("T-2", "Decide save versioning", priority = Priority.P1, tags = listOf("design")),
        TodoItem("T-10", "Rename costAt", priority = Priority.P1, tags = listOf("cleanup"), anchors = listOf(anchor("src/grid.h", lost = true))),
        TodoItem("T-9", "Old bug", priority = Priority.P1, status = Status.DONE, anchors = listOf(anchor("src/unit.cpp"))),
        TodoItem("T-3", "Skipped", priority = Priority.P3, status = Status.WONT_FIX),
    )
    private fun ids(q: ItemQuery) = ItemFilter.apply(items, q).map { it.id }

    @Test
    fun `sorted by priority then by number and closed items hidden`() {
        assertEquals(listOf("T-2", "T-10", "T-1"), ids(ItemQuery()))
    }

    @Test
    fun `search finds an item by its id, closed or not`() {
        assertEquals(listOf("T-10", "T-1"), ids(ItemQuery("t-1")))
        assertEquals(listOf("T-10"), ids(ItemQuery("T-10")))
        assertEquals(listOf("T-9"), ids(ItemQuery("t-9")))
        assertEquals(listOf("T-2"), ids(ItemQuery("2")))
    }

    @Test
    fun `show closed includes done and wont fix`() {
        assertEquals(listOf("T-2", "T-9", "T-10", "T-1", "T-3"), ids(ItemQuery(showClosed = true)))
    }

    @Test
    fun `scopes`() {
        assertEquals(listOf("T-1"), ids(ItemQuery(scope = Scope.THIS_FILE, currentPath = "src/unit.cpp")))
        assertEquals(emptyList<String>(), ids(ItemQuery(scope = Scope.THIS_FILE, currentPath = null)))
        assertEquals(listOf("T-2"), ids(ItemQuery(scope = Scope.NOTES)))
        assertEquals(listOf("T-10"), ids(ItemQuery(scope = Scope.ANCHOR_LOST)))
    }

    @Test
    fun `search words match title, details and tags, ignoring case`() {
        assertEquals(listOf("T-1"), ids(ItemQuery(text = "FINDPATH")))
        assertEquals(listOf("T-1"), ids(ItemQuery(text = "squad")))
        assertEquals(listOf("T-10"), ids(ItemQuery(text = "clean")))
        assertEquals(emptyList<String>(), ids(ItemQuery(text = "cache versioning")))
    }

    @Test
    fun `tag and priority tokens filter exactly`() {
        assertEquals(listOf("T-1"), ids(ItemQuery(text = "#perf")))
        assertEquals(emptyList<String>(), ids(ItemQuery(text = "#per")))
        assertEquals(listOf("T-2", "T-10"), ids(ItemQuery(text = "!p1")))
        assertEquals(listOf("T-10"), ids(ItemQuery(text = "!P1 rename")))
    }

    @Test
    fun `tag of the query narrows the scope`() {
        assertEquals(listOf("T-10"), ids(ItemQuery(tag = "cleanup")))
        assertEquals(emptyList<String>(), ids(ItemQuery(tag = "cleanup", scope = Scope.NOTES)))
    }

    @Test
    fun `priority, status and author filters`() {
        assertEquals(listOf("T-1"), ids(ItemQuery(priorities = setOf(Priority.P2, Priority.P3))))
        assertEquals(listOf("T-9"), ids(ItemQuery(statuses = setOf(Status.DONE))))
        assertEquals(emptyList<String>(), ids(ItemQuery(authors = setOf(io.github.konove.notmytodo.model.Author.AGENT))))
    }

    @Test
    fun `sort by file puts notes last`() {
        assertEquals(listOf("T-10", "T-1", "T-2"), ids(ItemQuery(sort = ItemSort.FILE)))
    }

    @Test
    fun `rows are grouped under headings and collapsed groups keep only the heading`() {
        val shown = ItemFilter.apply(items, ItemQuery())
        assertEquals(shown.map { ItemRow(it) }, ItemGroups.rows(shown, GroupBy.NONE, emptySet()))
        assertEquals(
            listOf<Any>(
                GroupHeader("src/grid.h", "src/grid.h", 1, false, 0, GroupKind.FILE), ItemRow(shown[1], 1),
                GroupHeader("src/unit.cpp", "src/unit.cpp", 1, true, 0, GroupKind.FILE),
                GroupHeader("Notes", "Notes", 1, false), ItemRow(shown[0], 1),
            ),
            ItemGroups.rows(shown, GroupBy.FILE, setOf("src/unit.cpp")),
        )
    }

    @Test
    fun `directory tree nests files in directories and joins single-child directories`() {
        fun at(path: String, id: String) = TodoItem(id, id, anchors = listOf(anchor(path)))
        val a = at("lib/core/io/read.c", "T-1")
        val b = at("lib/core/io/write.c", "T-2")
        val c = at("top.c", "T-3")
        val note = TodoItem("T-4", "note")
        assertEquals(
            listOf<Any>(
                GroupHeader("lib/core/io", "lib/core/io", 2, false, 0, GroupKind.DIRECTORY),
                GroupHeader("lib/core/io/read.c", "read.c", 1, false, 1, GroupKind.FILE), ItemRow(a, 2),
                GroupHeader("lib/core/io/write.c", "write.c", 1, true, 1, GroupKind.FILE),
                GroupHeader("top.c", "top.c", 1, false, 0, GroupKind.FILE), ItemRow(c, 1),
                GroupHeader("Notes", "Notes", 1, false), ItemRow(note, 1),
            ),
            ItemGroups.rows(listOf(a, b, c, note), GroupBy.DIRECTORY, setOf("lib/core/io/write.c")),
        )
        assertEquals(
            listOf<Any>(GroupHeader("lib/core/io", "lib/core/io", 2, true, 0, GroupKind.DIRECTORY)),
            ItemGroups.rows(listOf(a, b), GroupBy.DIRECTORY, ItemGroups.allKeys(listOf(a, b), GroupBy.DIRECTORY)),
        )
    }

    @Test
    fun `needs-decision tag splits what an agent can fix from what waits for me`() {
        val waiting = TodoItem("T-20", "Pick a format", tags = listOf("needs-decision"))
        val all = items + waiting
        assertEquals(listOf("T-20"), ItemFilter.apply(all, ItemQuery(scope = Scope.NEEDS_DECISION)).map { it.id })
        assertEquals(
            listOf("T-2", "T-10", "T-1"),
            ItemFilter.apply(all, ItemQuery(scope = Scope.AGENT_READY, showClosed = true)).map { it.id },
        )
    }

    @Test
    fun `details render as markdown and keep their line breaks`() {
        val html = Markdown.html("**Needs** `code`\n1. one\n   a. first\n   b. second\n```\nx\ny\n```")
        assertTrue(html, "<strong>Needs</strong>" in html && "<code>code</code>" in html && "<ol>" in html)
        assertTrue(html, Regex("a\\. first\\s*<br").containsMatchIn(html))
        assertFalse(html, Regex("x\\s*<br").containsMatchIn(html))
    }

    @Test
    fun `an item is in a file, or lost, by any of its anchors`() {
        val two = TodoItem("T-20", "two places", anchors = listOf(anchor("src/a.cpp"), anchor("src/b.cpp", lost = true)))
        val whole = TodoItem("T-21", "whole file", anchors = listOf(Anchor.file("src/b.cpp")))
        val all = listOf(two, whole, TodoItem("T-22", "note"))
        fun ids(scope: Scope, path: String? = null) = ItemFilter.apply(all, ItemQuery(scope = scope, currentPath = path)).map { it.id }
        assertEquals(listOf("T-20", "T-21"), ids(Scope.THIS_FILE, "src/b.cpp"))
        assertEquals(listOf("T-20"), ids(Scope.THIS_FILE, "src/a.cpp"))
        assertEquals(listOf("T-20"), ids(Scope.ANCHOR_LOST))
        assertEquals(listOf("T-22"), ids(Scope.NOTES))
    }
}
